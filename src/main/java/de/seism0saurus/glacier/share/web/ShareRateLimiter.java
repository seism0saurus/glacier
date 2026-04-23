package de.seism0saurus.glacier.share.web;

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
 * Six-axis token-bucket rate limiter for the share-link endpoints.
 *
 * <p>Each share endpoint has its own rate-limit parameters, reflecting the different
 * risk profiles and abuse potential of each operation:
 *
 * <table>
 *   <caption>Share rate-limit axes (SR-SHARE-12)</caption>
 *   <thead><tr><th>Axis</th><th>Default</th><th>Rationale</th></tr></thead>
 *   <tbody>
 *     <tr><td>share.create per wallId</td><td>5/min</td><td>Prevent link-flooding by a single sharer</td></tr>
 *     <tr><td>share.create per IP</td><td>20/min</td><td>Prevent bulk creation from a single IP</td></tr>
 *     <tr><td>share.csrf per IP</td><td>60/min</td><td>Token refresh — prevent CSRF token farming</td></tr>
 *     <tr><td>share.fallback per viewer</td><td>20/min</td><td>Per-viewer polling limit</td></tr>
 *     <tr><td>share.fallback per IP</td><td>120/min</td><td>Shared cross-viewer IP limit</td></tr>
 *     <tr><td>share.imgproxy per IP</td><td>300/min</td><td>Image proxy scraping prevention</td></tr>
 *   </tbody>
 * </table>
 *
 * <p>Implementation is based on a fixed-window token bucket (same design as
 * {@code FallbackRateLimiter} in the main wall). Stale buckets are evicted after
 * 10 minutes of inactivity to prevent unbounded memory growth.
 *
 * <p>Security: OWASP API4 (Lack of Resources &amp; Rate Limiting), SR-SHARE-12.
 * References: NIST SP 800-53 AC-3, NIST SP 800-204 §4.4.
 */
@Component
public class ShareRateLimiter {

    private static final Logger AUDIT = LoggerFactory.getLogger("AUDIT");

    // Configuration — injected or defaulted
    private final int createPerWallId;
    private final int createPerIp;
    private final int csrfPerIp;
    private final int fallbackPerViewer;
    private final int fallbackPerIp;
    private final int imgProxyPerIp;
    private final Clock clock;

    // Six separate bucket maps — each axis is isolated from the others
    private final ConcurrentHashMap<String, TokenBucket> createWallIdBuckets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, TokenBucket> createIpBuckets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, TokenBucket> csrfIpBuckets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, TokenBucket> fallbackViewerBuckets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, TokenBucket> fallbackIpBuckets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, TokenBucket> imgProxyIpBuckets = new ConcurrentHashMap<>();

    /**
     * Spring-managed constructor.
     *
     * <p>{@code @Autowired} is required because there are two constructors; without it
     * Spring cannot determine which one to use for dependency injection.
     *
     * @param clock injected clock (UTC by default; allows deterministic testing)
     */
    @org.springframework.beans.factory.annotation.Autowired
    public ShareRateLimiter(
            @Value("${glacier.share.ratelimit.create.perMinutePerWallId:5}") final int createPerWallId,
            @Value("${glacier.share.ratelimit.create.perMinutePerIp:20}") final int createPerIp,
            @Value("${glacier.share.ratelimit.csrf.perMinutePerIp:60}") final int csrfPerIp,
            @Value("${glacier.share.ratelimit.fallback.perMinutePerViewer:20}") final int fallbackPerViewer,
            @Value("${glacier.share.ratelimit.fallback.perMinutePerIp:120}") final int fallbackPerIp,
            @Value("${glacier.share.ratelimit.imgproxy.perMinutePerIp:300}") final int imgProxyPerIp,
            final Clock clock) {
        this.createPerWallId = createPerWallId;
        this.createPerIp = createPerIp;
        this.csrfPerIp = csrfPerIp;
        this.fallbackPerViewer = fallbackPerViewer;
        this.fallbackPerIp = fallbackPerIp;
        this.imgProxyPerIp = imgProxyPerIp;
        this.clock = clock;
    }

    /**
     * Constructor for unit tests — uses fixed defaults matching the spec.
     * Package-private to prevent Spring from trying to autowire it.
     */
    ShareRateLimiter(final Clock clock) {
        this(5, 20, 60, 20, 120, 300, clock);
    }


    /**
     * Rate-limit result.
     *
     * @param permitted          {@code true} if the request is within limits
     * @param retryAfterSeconds  seconds until bucket refills; 0 when {@code permitted=true}
     */
    public record RateLimitResult(boolean permitted, long retryAfterSeconds) {
        /** Request is allowed. */
        public static RateLimitResult allowed() { return new RateLimitResult(true, 0L); }
        /** Request is rejected with retry hint. */
        public static RateLimitResult rejected(long retryAfter) { return new RateLimitResult(false, retryAfter); }
    }

    /**
     * Checks share-link creation rate limits (per-wallId AND per-IP).
     *
     * <p>Both axes must pass. If either is exhausted, neither is debited.
     *
     * @param wallId   the authenticated sharer's wallId
     * @param remoteIp the request's remote IP
     * @return the rate-limit result
     */
    public RateLimitResult checkShareCreate(final String wallId, final String remoteIp) {
        TokenBucket wb = createWallIdBuckets.computeIfAbsent(wallId, k -> new TokenBucket(createPerWallId, clock));
        TokenBucket ib = createIpBuckets.computeIfAbsent(remoteIp, k -> new TokenBucket(createPerIp, clock));

        if (!wb.tryConsume()) {
            long retry = wb.secondsUntilRefill();
            AUDIT.info("share.ratelimit.hit endpoint=create axis=wallId retryAfterSeconds={}", retry);
            return RateLimitResult.rejected(retry);
        }
        if (!ib.tryConsume()) {
            wb.refund();
            long retry = ib.secondsUntilRefill();
            AUDIT.info("share.ratelimit.hit endpoint=create axis=ip retryAfterSeconds={}", retry);
            return RateLimitResult.rejected(retry);
        }
        return RateLimitResult.allowed();
    }

    /**
     * Checks CSRF token issuance rate limit (per-IP only).
     *
     * @param remoteIp the request's remote IP
     * @return the rate-limit result
     */
    public RateLimitResult checkCsrfIssuance(final String remoteIp) {
        TokenBucket ib = csrfIpBuckets.computeIfAbsent(remoteIp, k -> new TokenBucket(csrfPerIp, clock));
        if (!ib.tryConsume()) {
            long retry = ib.secondsUntilRefill();
            AUDIT.info("share.ratelimit.hit endpoint=csrf axis=ip retryAfterSeconds={}", retry);
            return RateLimitResult.rejected(retry);
        }
        return RateLimitResult.allowed();
    }

    /**
     * Checks share fallback (catalog polling) rate limits (per-viewer AND per-IP).
     *
     * @param viewerId the authenticated viewer's ID
     * @param remoteIp the request's remote IP
     * @return the rate-limit result
     */
    public RateLimitResult checkShareFallback(final String viewerId, final String remoteIp) {
        TokenBucket vb = fallbackViewerBuckets.computeIfAbsent(viewerId, k -> new TokenBucket(fallbackPerViewer, clock));
        TokenBucket ib = fallbackIpBuckets.computeIfAbsent(remoteIp, k -> new TokenBucket(fallbackPerIp, clock));

        if (!vb.tryConsume()) {
            long retry = vb.secondsUntilRefill();
            AUDIT.info("share.ratelimit.hit endpoint=fallback axis=viewer retryAfterSeconds={}", retry);
            return RateLimitResult.rejected(retry);
        }
        if (!ib.tryConsume()) {
            vb.refund();
            long retry = ib.secondsUntilRefill();
            AUDIT.info("share.ratelimit.hit endpoint=fallback axis=ip retryAfterSeconds={}", retry);
            return RateLimitResult.rejected(retry);
        }
        return RateLimitResult.allowed();
    }

    /**
     * Checks image proxy rate limit (per-IP only).
     *
     * @param remoteIp the request's remote IP
     * @return the rate-limit result
     */
    public RateLimitResult checkImgProxy(final String remoteIp) {
        TokenBucket ib = imgProxyIpBuckets.computeIfAbsent(remoteIp, k -> new TokenBucket(imgProxyPerIp, clock));
        if (!ib.tryConsume()) {
            long retry = ib.secondsUntilRefill();
            AUDIT.info("share.ratelimit.hit endpoint=imgproxy axis=ip retryAfterSeconds={}", retry);
            return RateLimitResult.rejected(retry);
        }
        return RateLimitResult.allowed();
    }

    /**
     * Evicts stale bucket entries (inactive for &gt;10 minutes) to prevent unbounded memory growth.
     * Scheduled every 5 minutes.
     */
    @Scheduled(fixedDelayString = "${glacier.ratelimit.eviction.intervalMs:300000}")
    public void evictStaleBuckets() {
        Instant threshold = clock.instant().minusSeconds(600);
        createWallIdBuckets.entrySet().removeIf(e -> e.getValue().isIdleSince(threshold));
        createIpBuckets.entrySet().removeIf(e -> e.getValue().isIdleSince(threshold));
        csrfIpBuckets.entrySet().removeIf(e -> e.getValue().isIdleSince(threshold));
        fallbackViewerBuckets.entrySet().removeIf(e -> e.getValue().isIdleSince(threshold));
        fallbackIpBuckets.entrySet().removeIf(e -> e.getValue().isIdleSince(threshold));
        imgProxyIpBuckets.entrySet().removeIf(e -> e.getValue().isIdleSince(threshold));
    }

    // -----------------------------------------------------------------------
    // Inner token bucket — fixed-window, thread-safe
    // -----------------------------------------------------------------------

    /**
     * Fixed-window token bucket. All mutable state guarded by {@link ReentrantLock}.
     * Uses the same H-1-safe design as {@code FallbackRateLimiter.TokenBucket}:
     * {@code lastAccessedAt} is updated only on real token consumption/refund,
     * NOT on window refills.
     */
    static final class TokenBucket {

        private final int capacity;
        private final Clock clock;
        private final ReentrantLock lock = new ReentrantLock();

        private int tokens;
        private long windowStart;
        private Instant lastAccessedAt;

        TokenBucket(int capacity, Clock clock) {
            this.capacity = capacity;
            this.clock = clock;
            this.tokens = capacity;
            this.windowStart = clock.instant().toEpochMilli();
            this.lastAccessedAt = clock.instant();
        }

        /** Atomically check and consume one token. Returns {@code false} if empty. */
        boolean tryConsume() {
            lock.lock();
            try {
                refillIfNewWindow();
                if (tokens > 0) {
                    tokens--;
                    lastAccessedAt = clock.instant();
                    return true;
                }
                return false;
            } finally {
                lock.unlock();
            }
        }

        /** Return one token (used when a paired axis rejects after this one accepted). */
        void refund() {
            lock.lock();
            try {
                if (tokens < capacity) {
                    tokens++;
                    lastAccessedAt = clock.instant();
                }
            } finally {
                lock.unlock();
            }
        }

        /** Seconds remaining until the current window resets. */
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

        /** {@code true} if no token was consumed or refunded since before {@code since}. */
        boolean isIdleSince(final Instant since) {
            lock.lock();
            try {
                return lastAccessedAt.isBefore(since);
            } finally {
                lock.unlock();
            }
        }

        private void refillIfNewWindow() {
            long now = clock.instant().toEpochMilli();
            if (now - windowStart >= 60_000L) {
                tokens = capacity;
                windowStart = now;
                // H-1 design: do NOT update lastAccessedAt here
            }
        }
    }
}
