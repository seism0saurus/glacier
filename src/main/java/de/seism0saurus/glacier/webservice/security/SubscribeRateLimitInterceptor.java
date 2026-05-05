package de.seism0saurus.glacier.webservice.security;

import de.seism0saurus.glacier.util.LogScrubber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * STOMP SUBSCRIBE rate-limiter channel interceptor (SR-WS-02, ADR-PT-G7-01).
 *
 * <p>Enforces a per-(IP + principal) rate limit on STOMP SUBSCRIBE frames.
 * This closes the subscription-enumeration attack vector: an attacker who learns a
 * victim's wallId can probe subscription topics, but only at the rate-limited rate.
 *
 * <h2>Security controls</h2>
 * <ul>
 *   <li><b>OWASP API6:2023 — Unrestricted Access to Sensitive Business Flows</b>: limits
 *       SUBSCRIBE frequency per (IP + principal) to {@code glacier.security.ws.subscribe.max-per-minute}
 *       (default 30).</li>
 *   <li><b>Silent drop (null return)</b>: preserves indistinguishability — the attacker
 *       cannot distinguish a dropped SUBSCRIBE from a slow server by observing the wire.
 *       This is stricter than returning a STOMP ERROR, which would provide a timing
 *       signal that distinguishes rate-limited from non-existent wallIds.</li>
 *   <li><b>SR-LOG-WS-01 / D-13 / SR-8</b>: AUDIT events log {@code ip-hash=} via
 *       {@link LogScrubber#maskIp(String)} and {@code wallid-hash=} via
 *       {@link LogScrubber#hash8(String)} — raw IP and wallId never appear in log output.</li>
 * </ul>
 *
 * <h2>Fail-open design (SR-WS-04)</h2>
 * <p>On any {@link Throwable} during interceptor logic, the interceptor returns the original
 * message (allow the frame) and logs a WARN. Consistent with {@code FallbackRateLimiter}
 * and {@code HandshakeRateLimitInterceptor} fail-open patterns.
 *
 * <h2>Rate-limit key</h2>
 * <p>The bucket key combines source IP and principal name: {@code ip + ":" + principal}.
 * This means separate buckets exist for each unique (IP, wallId) pair. If IP extraction
 * fails (no session attributes), the principal name alone is used as the key.
 *
 * <h2>Registration</h2>
 * <p>Registered on {@code clientInboundChannel} in
 * {@link de.seism0saurus.glacier.webservice.messaging.WebSocketConfiguration#configureClientInboundChannel}.
 *
 * <h2>Accepted residual risk</h2>
 * <p>Rate-limit buckets are in-memory (single-instance only). Horizontal scaling requires
 * a shared rate-limit store (AR-WS-01).
 */
@Component
public class SubscribeRateLimitInterceptor implements ChannelInterceptor {

    // OWASP API6 rate-limit audit event (SR-LOG-WS-01)
    private static final Logger AUDIT = LoggerFactory.getLogger("AUDIT");
    private static final Logger LOG = LoggerFactory.getLogger(SubscribeRateLimitInterceptor.class);

    private final int maxPerMinute;
    private final Clock clock;

    /** Bucket map keyed by composite "ip:principalName". Thread-safe. */
    private final ConcurrentHashMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    /**
     * Spring-managed constructor.
     *
     * @param maxPerMinute   max SUBSCRIBE frames per minute per (IP + principal)
     *                       ({@code glacier.security.ws.subscribe.max-per-minute}, default 30)
     * @param clock          clock for token-bucket window management (UTC default)
     */
    public SubscribeRateLimitInterceptor(
            @Value("${glacier.security.ws.subscribe.max-per-minute:30}") final int maxPerMinute,
            final Clock clock) {
        this.maxPerMinute = maxPerMinute;
        this.clock = clock;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Intercepts STOMP SUBSCRIBE frames, applies per-(IP + principal) rate limiting,
     * and silently drops frames that exceed the limit.
     *
     * @return the original message when allowed; {@code null} (silent drop) when the bucket
     *         is exhausted; the original message on any error (fail-open).
     */
    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        try {
            SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.wrap(message);

            // Only intercept SUBSCRIBE frames — other frame types are not subject to this limit.
            if (SimpMessageType.SUBSCRIBE != accessor.getMessageType()) {
                return message;
            }

            String ip = extractIp(accessor);
            String principalName = extractPrincipalName(accessor);

            // Composite key: (ip + ":" + principal) — isolates buckets per (IP, wallId) pair.
            // If ip is null the REMOTE_ADDR session attribute was not populated at handshake
            // (regression in PrincipalHandler / ShareViewPrincipalHandler). Emit a one-shot
            // WARN per fresh bucket key so the regression is visible in operator logs (OWASP A09).
            String bucketKey;
            if (ip != null) {
                bucketKey = ip + ":" + principalName;
            } else {
                bucketKey = "unknown:" + principalName;
                if (!buckets.containsKey(bucketKey)) {
                    LOG.warn("ws.subscribe.ratelimit: REMOTE_ADDR missing for principal-hash={} — "
                            + "bucket falling back to per-principal only (check PrincipalHandler / ShareViewPrincipalHandler)",
                            LogScrubber.hash8(principalName));
                }
            }

            TokenBucket bucket = buckets.computeIfAbsent(bucketKey, k -> new TokenBucket(maxPerMinute, clock));

            if (!bucket.tryConsume()) {
                // Rate limit exceeded — silent drop preserves indistinguishability (API6)
                // SR-LOG-WS-01: raw IP and wallId must NOT appear in logs
                AUDIT.info("ws.subscribe.rate_limited ip-hash={} wallid-hash={}",
                        LogScrubber.maskIp(ip),
                        LogScrubber.hash8(principalName));
                return null; // silent drop
            }

            return message;

        } catch (Throwable t) {
            // SR-WS-04: fail-open on any unexpected error
            LOG.warn("ws.subscribe.ratelimit: unexpected error — fail-open", t);
            return message;
        }
    }

    /**
     * Evicts stale bucket entries (inactive for more than 10 minutes) to prevent
     * unbounded memory growth under a slow-drip of unique (IP, wallId) pairs.
     *
     * <p>Scheduled every 5 minutes (matches {@code FallbackRateLimiter} eviction cadence).
     */
    @Scheduled(fixedDelayString = "${glacier.ratelimit.eviction.intervalMs:300000}")
    public void evictStaleBuckets() {
        Instant threshold = clock.instant().minusSeconds(600);
        buckets.entrySet().removeIf(e -> e.getValue().isIdleSince(threshold));
    }

    // -------------------------------------------------------------------------
    // Package-private for testing
    // -------------------------------------------------------------------------

    int bucketCount() {
        return buckets.size();
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Session attribute key written by {@link de.seism0saurus.glacier.webservice.messaging.PrincipalHandler}
     * and {@link de.seism0saurus.glacier.webservice.messaging.ShareViewPrincipalHandler} during the
     * WebSocket handshake to carry the client's remote IP address into the STOMP session.
     *
     * <p>Using a shared constant prevents the magic-string "REMOTE_ADDR" from drifting between
     * writers and this reader (F-1, OWASP API6:2023, SR-WS-02, SR-WS-03).
     */
    public static final String REMOTE_ADDR = "REMOTE_ADDR";

    /**
     * Extracts the source IP from STOMP session attributes.
     *
     * <p>The session attributes map is populated during the WebSocket handshake by
     * {@link de.seism0saurus.glacier.webservice.messaging.PrincipalHandler#determineUser} and
     * {@link de.seism0saurus.glacier.webservice.messaging.ShareViewPrincipalHandler#determineUser}
     * via {@link #REMOTE_ADDR}.
     * Returns {@code null} if IP cannot be determined.
     */
    private String extractIp(SimpMessageHeaderAccessor accessor) {
        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
        if (sessionAttributes == null) {
            return null;
        }
        Object remoteAddr = sessionAttributes.get(REMOTE_ADDR);
        if (remoteAddr instanceof String s) {
            return s;
        }
        return null;
    }

    private String extractPrincipalName(SimpMessageHeaderAccessor accessor) {
        java.security.Principal principal = accessor.getUser();
        if (principal != null) {
            return principal.getName();
        }
        return "anonymous";
    }

    // -------------------------------------------------------------------------
    // Inner token bucket — fixed-window, thread-safe
    // (same design as FallbackRateLimiter.TokenBucket and HandshakeRateLimitInterceptor.TokenBucket)
    // -------------------------------------------------------------------------

    /**
     * Fixed-window token bucket for per-(IP + principal) SUBSCRIBE rate limiting.
     *
     * <p>Uses the same H-1-safe design as {@code FallbackRateLimiter.TokenBucket}:
     * {@code lastAccessedAt} is updated only on real token consumption, NOT on window refills.
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

        /**
         * Atomically checks for an available token and consumes it.
         *
         * @return {@code true} if a token was available and consumed;
         *         {@code false} if the bucket was empty (frame should be dropped)
         */
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

        /**
         * Returns {@code true} if the bucket has been idle since before {@code since}.
         */
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
                // H-1 design: do NOT update lastAccessedAt on window refill
            }
        }
    }
}
