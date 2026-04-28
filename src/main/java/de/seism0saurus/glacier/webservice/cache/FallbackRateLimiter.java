package de.seism0saurus.glacier.webservice.cache;

import de.seism0saurus.glacier.webservice.messaging.PrincipalKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Two-axis token-bucket rate limiter for the HTTP fallback endpoint (D-10, SR-4).
 *
 * <p>Security controls (OWASP API4: Lack of Resources &amp; Rate Limiting):
 * <ul>
 *   <li><b>Per-wallId axis</b>: default 30 requests/minute; closes T-09 (session flooding).</li>
 *   <li><b>Per-IP axis</b>: default 120 requests/minute; closes T-08 (cookie farming / IP-based
 *       flooding regardless of wallId).</li>
 *   <li>429 responses carry {@code Retry-After} seconds; 429 does NOT cause any server-side state
 *       change (no probe flip, no cookie invalidation).</li>
 *   <li>Stale buckets are evicted after 10 minutes of inactivity (no tokens consumed),
 *       preventing unbounded memory growth under a slow-leak of unique IPs or wallIds.</li>
 * </ul>
 *
 * <p>Thread-safety: each {@link TokenBucket} serialises its own state with a {@link ReentrantLock}.
 * The hot path uses {@link TokenBucket#tryConsume()} which holds the lock across the entire
 * check-and-decrement, making admission decisions deterministic under concurrent load.
 * The outer {@link ConcurrentHashMap} provides safe concurrent access at bucket-entry granularity.
 *
 * <p>{@code server.forward-headers-strategy=${FORWARD_HEADERS_STRATEGY:NONE}} in
 * {@code application.properties} means the IP used for bucket keying is the real remote IP by
 * default.  Operators MUST set {@code FORWARD_HEADERS_STRATEGY=FRAMEWORK} (and configure a
 * trusted proxy) to honour {@code X-Forwarded-For} — that flag is the only way IP spoofing is
 * possible; with NONE it is not (D-10).
 *
 * <p>H-1 fix: a {@link Clock} is injected rather than calling {@link Instant#now()} directly.
 * This makes eviction timing fully deterministic in tests and eliminates the stale-bucket
 * escape bug: {@code refillIfNewWindow()} previously reset {@code lastFullAt} on every window
 * rollover, causing idle buckets to appear "fresh" and escape eviction indefinitely.  The fix
 * introduces a separate {@code lastAccessedAt} timestamp — updated only when a token is
 * actually consumed or refunded — which is independent of window rollovers.
 */
@Component
public class FallbackRateLimiter {

    // OWASP A04 rate-limit audit events (D-13, SR-8)
    private static final Logger AUDIT = LoggerFactory.getLogger("AUDIT");

    private final int perWallIdPerMinute;
    private final int perIpPerMinute;
    // H-1: injected Clock — never call Instant.now() directly in this class or TokenBucket
    private final Clock clock;

    /** Bucket map keyed by PrincipalKey — prevents cross-namespace collision (ADR-SHARE-05). */
    private final ConcurrentHashMap<PrincipalKey, TokenBucket> wallIdBuckets = new ConcurrentHashMap<>();

    /** Bucket map keyed by remote IP address. */
    private final ConcurrentHashMap<String, TokenBucket> ipBuckets = new ConcurrentHashMap<>();

    /**
     * Spring-managed constructor.  The {@link Clock} bean is provided by
     * {@link de.seism0saurus.glacier.GlacierApplication#clock()}.
     *
     * @param perWallIdPerMinute max requests per minute per wallId
     *                           ({@code glacier.fallback.ratelimit.perMinute}, default 30)
     * @param perIpPerMinute     max requests per minute per IP
     *                           ({@code glacier.fallback.ratelimit.perMinutePerIp}, default 120)
     * @param clock              clock used for all time measurements (injected; default UTC)
     */
    public FallbackRateLimiter(
            @Value("${glacier.fallback.ratelimit.perMinute:30}") final int perWallIdPerMinute,
            @Value("${glacier.fallback.ratelimit.perMinutePerIp:120}") final int perIpPerMinute,
            final Clock clock) {
        this.perWallIdPerMinute = perWallIdPerMinute;
        this.perIpPerMinute = perIpPerMinute;
        this.clock = clock;
    }

    /**
     * Result of a rate-limit check.
     *
     * @param allowed    {@code true} when the request is within limits
     * @param retryAfterSeconds seconds until the bucket refills; 0 when {@code allowed=true}
     */
    public record RateLimitResult(boolean permitted, long retryAfterSeconds) {
        /** Convenience factory: request is allowed. */
        public static RateLimitResult allowed() {
            return new RateLimitResult(true, 0L);
        }

        /** Convenience factory: request is rejected; client should retry after N seconds. */
        public static RateLimitResult rejected(long retryAfterSeconds) {
            return new RateLimitResult(false, retryAfterSeconds);
        }
    }

    /**
     * Checks both axes (principal and IP) and debits one token from each bucket if both pass.
     *
     * <p>If either axis is exhausted, neither bucket is debited (atomic double-check).
     * The more restrictive {@code Retry-After} is returned in the 429 response.
     *
     * <p>Security: using {@link PrincipalKey} prevents cross-namespace bucket collision
     * where a forged {@code WallPrincipal} with an {@code sv_}-prefixed name could
     * overwrite a {@code ShareViewerPrincipal}'s bucket (ADR-SHARE-05, revised).
     *
     * @param principalKey the authenticated principal key (type-safe; see {@link PrincipalKey})
     * @param remoteIp     the peer IP address (as resolved by the servlet container — honours
     *                     {@code server.forward-headers-strategy})
     * @return a {@link RateLimitResult}; inspect {@link RateLimitResult#permitted()} before serving
     */
    public RateLimitResult check(final PrincipalKey principalKey, final String remoteIp) {
        TokenBucket wBucket = wallIdBuckets.computeIfAbsent(principalKey,
                k -> new TokenBucket(perWallIdPerMinute, clock));
        TokenBucket iBucket = ipBuckets.computeIfAbsent(remoteIp,
                k -> new TokenBucket(perIpPerMinute, clock));

        // Check-and-consume atomically on each bucket (single lock hold per bucket).
        // Using tryConsume() instead of the split hasToken()+consume() pair eliminates
        // the TOCTOU window that causes spurious over-admission under burst concurrency
        // (NIST SP 800-53 AC-3; D-10 correctness).
        if (!wBucket.tryConsume()) {
            long retryAfter = wBucket.secondsUntilRefill();
            AUDIT.info("fallback.ratelimit.hit axis=wallId retryAfterSeconds={}", retryAfter);
            return RateLimitResult.rejected(retryAfter);
        }
        if (!iBucket.tryConsume()) {
            // wallId bucket was already decremented; refund it so neither axis is penalised
            // when the other axis is the limiting factor (atomic double-check intent preserved).
            wBucket.refund();
            long retryAfter = iBucket.secondsUntilRefill();
            AUDIT.info("fallback.ratelimit.hit axis=ip retryAfterSeconds={}", retryAfter);
            return RateLimitResult.rejected(retryAfter);
        }

        return RateLimitResult.allowed();
    }

    /**
     * Checks and debits one token from the per-IP bucket only.
     *
     * <p>Used for the {@code /rest/wall-id} endpoint rate-limit (non-blocking Phase 2 fix #1).
     *
     * @param remoteIp the peer IP address
     * @return a {@link RateLimitResult}
     */
    public RateLimitResult checkIpOnly(final String remoteIp) {
        TokenBucket iBucket = ipBuckets.computeIfAbsent(remoteIp,
                k -> new TokenBucket(perIpPerMinute, clock));

        if (!iBucket.tryConsume()) {
            long retryAfter = iBucket.secondsUntilRefill();
            AUDIT.info("fallback.ratelimit.hit axis=ip endpoint=wall-id retryAfterSeconds={}", retryAfter);
            return RateLimitResult.rejected(retryAfter);
        }
        return RateLimitResult.allowed();
    }

    /**
     * Evicts bucket entries that have not been accessed (token consumed or refunded) for
     * more than 10 minutes.
     *
     * <p>H-1 fix: eviction is now based on {@code lastAccessedAt} (updated only in
     * {@link TokenBucket#tryConsume()}, {@link TokenBucket#refund()}, and the legacy
     * {@link TokenBucket#consume()} entry points), NOT on {@code lastFullAt}.  The prior
     * {@code lastFullAt}-based approach had a bug: {@code refillIfNewWindow()} reset
     * {@code lastFullAt} on every 60-second window rollover, so idle buckets could never
     * accumulate a stale timestamp and were never evicted (unbounded memory growth).
     *
     * <p>Scheduled to run every 5 minutes via Spring's {@link Scheduled} support.
     */
    @Scheduled(fixedDelayString = "${glacier.ratelimit.eviction.intervalMs:300000}")
    public void evictStaleBuckets() {
        // 10-minute idle window: evict any bucket whose last token access was > 10 min ago
        Instant threshold = clock.instant().minusSeconds(600);
        wallIdBuckets.entrySet().removeIf(e -> e.getValue().isIdleSince(threshold));
        ipBuckets.entrySet().removeIf(e -> e.getValue().isIdleSince(threshold));
    }

    // -------------------------------------------------------------------------
    // Package-private accessors for testing
    // -------------------------------------------------------------------------

    int wallIdBucketCount() {
        return wallIdBuckets.size();
    }

    int ipBucketCount() {
        return ipBuckets.size();
    }

    // -------------------------------------------------------------------------
    // Inner token bucket
    // -------------------------------------------------------------------------

    /**
     * Fixed-window token bucket that refills once per minute.
     *
     * <p>Thread-safety: all mutable state is guarded by {@link ReentrantLock}.  The primary
     * entry point for callers is {@link #tryConsume()}, which performs the check-and-decrement
     * atomically under a single lock hold.  This eliminates the TOCTOU race that the older
     * split {@code hasToken()} + {@code consume()} pattern would introduce under concurrent
     * burst load (NIST SP 800-53 AC-3; D-10 correctness).
     *
     * <p>{@link #hasToken()} and {@link #consume()} are retained for use by
     * {@link FallbackRateLimiter#evictStaleBuckets()} and for test introspection.
     * Callers in the hot path MUST use {@link #tryConsume()}.
     *
     * <p>H-1 fix: {@link #lastAccessedAt} tracks the last moment a token was actually
     * consumed or refunded.  It is intentionally NOT updated by {@link #refillIfNewWindow()},
     * so window rollovers in idle buckets do not reset the staleness clock.
     */
    static final class TokenBucket {

        private final int capacity;
        private final Clock clock;
        private final ReentrantLock lock = new ReentrantLock();

        private int tokens;
        /** Epoch millis when the current window started. */
        private long windowStart;
        /**
         * Last time a token was consumed or refunded (i.e. the bucket was actively used).
         * Used by {@link FallbackRateLimiter#evictStaleBuckets()} as the primary staleness
         * criterion (H-1 fix — replaces the broken {@code lastFullAt} approach).
         *
         * <p>Updated by: {@link #tryConsume()}, {@link #refund()}, {@link #consume()}.
         * NOT updated by: {@link #refillIfNewWindow()} — that was the root cause of H-1.
         */
        private Instant lastAccessedAt;

        TokenBucket(int capacity, Clock clock) {
            this.capacity = capacity;
            this.clock = clock;
            this.tokens = capacity;
            this.windowStart = clock.instant().toEpochMilli();
            this.lastAccessedAt = clock.instant();
        }

        /** Returns {@code true} if at least one token is available (does NOT consume). */
        boolean hasToken() {
            lock.lock();
            try {
                refillIfNewWindow();
                return tokens > 0;
            } finally {
                lock.unlock();
            }
        }

        /**
         * Consumes one token. Caller MUST have called {@link #hasToken()} first.
         * Updates {@code lastAccessedAt} (H-1 fix).
         */
        void consume() {
            lock.lock();
            try {
                refillIfNewWindow();
                if (tokens > 0) {
                    tokens--;
                    // H-1: update lastAccessedAt only on actual consumption, not on window refill
                    lastAccessedAt = clock.instant();
                }
            } finally {
                lock.unlock();
            }
        }

        /**
         * Atomically checks for an available token and consumes it in a single lock hold.
         *
         * <p>This is the correct entry point for rate-limit enforcement.  Unlike the split
         * {@code hasToken()} + {@code consume()} pattern, this method holds the lock across
         * the entire check-and-decrement sequence, making the admission decision fully
         * deterministic under concurrent load (NIST SP 800-53 AC-3; D-10).
         *
         * <p>H-1 fix: updates {@code lastAccessedAt} on successful consumption so idle buckets
         * are correctly evicted without being "refreshed" by window rollovers.
         *
         * @return {@code true} if a token was available and has been consumed;
         *         {@code false} if the bucket was empty (request should be rejected with 429)
         */
        boolean tryConsume() {
            lock.lock();
            try {
                refillIfNewWindow();
                if (tokens > 0) {
                    tokens--;
                    // H-1: record last real access time (NOT updated by refillIfNewWindow)
                    lastAccessedAt = clock.instant();
                    return true;
                }
                return false;
            } finally {
                lock.unlock();
            }
        }

        /**
         * Returns one token to the bucket (adds back up to capacity).
         *
         * <p>Used by {@link FallbackRateLimiter#check} to refund the wallId-axis token when
         * the IP-axis check subsequently fails — preserving the "neither axis is penalised
         * unless both pass" invariant without requiring a two-phase commit.
         *
         * <p>H-1 fix: updates {@code lastAccessedAt} because a refund is an active use of
         * the bucket.
         */
        void refund() {
            lock.lock();
            try {
                if (tokens < capacity) {
                    tokens++;
                    // H-1: refund counts as access — bucket is not idle
                    lastAccessedAt = clock.instant();
                }
            } finally {
                lock.unlock();
            }
        }

        /**
         * Returns the number of seconds until the current window expires and the bucket refills.
         * Returns 0 when the window has already passed.
         */
        long secondsUntilRefill() {
            lock.lock();
            try {
                long elapsed = clock.instant().toEpochMilli() - windowStart;
                long remaining = 60_000L - elapsed;
                return remaining > 0 ? (remaining / 1000L) + 1 : 0L;
            } finally {
                lock.unlock();
            }
        }

        /**
         * Returns {@code true} when the bucket has not had any token consumed or refunded
         * since before {@code since}.  Used by {@link #evictStaleBuckets()} to remove
         * idle entries.
         *
         * <p>H-1 fix: this method replaces the broken {@code isFullSince()} which called
         * {@code refillIfNewWindow()} before inspecting the staleness timestamp, causing
         * every idle bucket older than 60 s to have its stale-marker overwritten and thus
         * escape eviction indefinitely.  This method does NOT call {@code refillIfNewWindow()}.
         *
         * @param since the earliest {@link Instant} a bucket must have been accessed to be
         *              considered non-stale; buckets whose {@code lastAccessedAt} is before
         *              this value are evicted
         */
        boolean isIdleSince(final Instant since) {
            lock.lock();
            try {
                // Primary invariant: time since last real access (H-1 fix)
                return lastAccessedAt.isBefore(since);
            } finally {
                lock.unlock();
            }
        }

        /**
         * Caller holds {@link #lock}.
         *
         * <p>H-1 note: this method intentionally does NOT update {@code lastAccessedAt}.
         * Window rollovers are automatic refills, not active bucket accesses.  Updating
         * {@code lastAccessedAt} here was the root cause of H-1 (idle buckets were never
         * evicted because every rollover reset their staleness clock).
         */
        private void refillIfNewWindow() {
            long now = clock.instant().toEpochMilli();
            if (now - windowStart >= 60_000L) {
                tokens = capacity;
                windowStart = now;
                // H-1: do NOT update lastAccessedAt here — refill is automatic, not an access
            }
        }
    }
}
