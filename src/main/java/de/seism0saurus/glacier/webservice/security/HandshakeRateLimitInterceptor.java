package de.seism0saurus.glacier.webservice.security;

import de.seism0saurus.glacier.util.LogScrubber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * WebSocket handshake rate-limiter (SR-WS-01, ADR-PT-G5-01).
 *
 * <p>Implements {@link HandshakeInterceptor} to enforce a per-source-IP rate limit on
 * WebSocket CONNECT attempts. This closes the cookie-rotation DoS vector: an attacker
 * creating fresh wallIds on each connection can still be rate-limited at the IP level.
 *
 * <h2>Security controls</h2>
 * <ul>
 *   <li><b>OWASP API4:2023 — Unrestricted Resource Consumption</b>: limits WebSocket CONNECT
 *       frequency per source IP to {@code glacier.security.ws.handshake.max-per-minute}
 *       (default 10).</li>
 *   <li><b>CWE-400 — Uncontrolled Resource Consumption</b>: each IP has a fixed-window token
 *       bucket; exhausted buckets return HTTP 429 without consuming server-side WS state.</li>
 *   <li><b>SR-LOG-WS-01 / D-13 / SR-8</b>: AUDIT events log {@code ip-hash=}
 *       via {@link LogScrubber#maskIp(String)} — the raw IP never appears in log output.</li>
 * </ul>
 *
 * <h2>Fail-open design (SR-WS-04)</h2>
 * <p>On any {@link Throwable} during interceptor logic, the interceptor returns {@code true}
 * (allow the connection) and logs a WARN. This is consistent with the {@code FallbackRateLimiter}
 * fail-open pattern: a broken rate-limit guard must not cause a denial-of-service on
 * legitimate users.
 *
 * <h2>Source IP extraction</h2>
 * <p>Uses {@code ServerHttpRequest.getRemoteAddress().getAddress().getHostAddress()} after
 * Spring's {@code ForwardedHeaderFilter} has applied (per
 * {@code server.forward-headers-strategy=FRAMEWORK} — set via the
 * {@code FORWARD_HEADERS_STRATEGY} environment variable in production deployments;
 * see {@code infrastructure/docker-compose.yaml}).
 * {@code FRAMEWORK} mode auto-registers the servlet-stack {@code ForwardedHeaderFilter},
 * which strips and processes {@code X-Forwarded-For} before it reaches servlet code.
 * This means IP spoofing via {@code X-Forwarded-For} requires internal cluster access
 * (accepted residual risk AR-WS-02).
 *
 * <h2>Registration</h2>
 * <p>Registered in {@link de.seism0saurus.glacier.webservice.messaging.WebSocketConfiguration}
 * on both the {@code /websocket} and {@code /share-view-ws} endpoints.
 *
 * <h2>Accepted residual risk</h2>
 * <p>Rate-limit buckets are in-memory (single-instance only). Horizontal scaling is not in
 * scope; this must be revisited before HA deployment (AR-WS-01).
 */
@Component
public class HandshakeRateLimitInterceptor implements HandshakeInterceptor {

    // OWASP API4 rate-limit audit event (SR-LOG-WS-01)
    private static final Logger AUDIT = LoggerFactory.getLogger("AUDIT");
    private static final Logger LOG = LoggerFactory.getLogger(HandshakeRateLimitInterceptor.class);

    private final int maxPerMinute;
    private final Clock clock;

    /** Bucket map keyed by source IP address. Thread-safe. */
    private final ConcurrentHashMap<String, TokenBucket> ipBuckets = new ConcurrentHashMap<>();

    /**
     * Spring-managed constructor.
     *
     * @param maxPerMinute  max WebSocket connections per minute per IP
     *                      ({@code glacier.security.ws.handshake.max-per-minute}, default 10)
     * @param clock          clock for token-bucket window management (UTC default)
     */
    public HandshakeRateLimitInterceptor(
            @Value("${glacier.security.ws.handshake.max-per-minute:10}") final int maxPerMinute,
            final Clock clock) {
        this.maxPerMinute = maxPerMinute;
        this.clock = clock;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Checks the per-IP bucket before the WebSocket handshake completes.
     *
     * @return {@code false} (with HTTP 429) when the IP has exhausted its bucket;
     *         {@code true} (allow) in all other cases including error conditions (fail-open).
     */
    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Map<String, Object> attributes) throws Exception {
        try {
            String ip = extractIp(request);
            if (ip == null) {
                // Cannot extract IP — fail-open (SR-WS-04)
                LOG.warn("ws.handshake.ratelimit: cannot extract remote IP — fail-open");
                return true;
            }

            TokenBucket bucket = ipBuckets.computeIfAbsent(ip, k -> new TokenBucket(maxPerMinute, clock));

            if (!bucket.tryConsume()) {
                // Rate limit exceeded — return 429 (SR-WS-01)
                // SR-LOG-WS-01: use LogScrubber.maskIp() — raw IP must NOT appear in logs
                AUDIT.info("ws.handshake.rate_limited ip-hash={}", LogScrubber.maskIp(ip));
                response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
                return false;
            }

            return true;

        } catch (Throwable t) {
            // SR-WS-04: fail-open on any unexpected error
            LOG.warn("ws.handshake.ratelimit: unexpected error — fail-open", t);
            return true;
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>No action needed after the handshake completes.
     */
    @Override
    public void afterHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Exception exception) {
        // No post-handshake action required
    }

    /**
     * Evicts stale bucket entries (inactive for more than 10 minutes) to prevent
     * unbounded memory growth under a slow-drip of unique source IPs.
     *
     * <p>Scheduled every 5 minutes (matches {@code FallbackRateLimiter} eviction cadence).
     */
    @Scheduled(fixedDelayString = "${glacier.ratelimit.eviction.intervalMs:300000}")
    public void evictStaleBuckets() {
        Instant threshold = clock.instant().minusSeconds(600);
        ipBuckets.entrySet().removeIf(e -> e.getValue().isIdleSince(threshold));
    }

    // -------------------------------------------------------------------------
    // Package-private for testing
    // -------------------------------------------------------------------------

    int ipBucketCount() {
        return ipBuckets.size();
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private String extractIp(ServerHttpRequest request) {
        InetSocketAddress remoteAddress = request.getRemoteAddress();
        if (remoteAddress == null) {
            return null;
        }
        return remoteAddress.getAddress().getHostAddress();
    }

    // -------------------------------------------------------------------------
    // Inner token bucket — fixed-window, thread-safe
    // (same design as FallbackRateLimiter.TokenBucket and ShareRateLimiter.TokenBucket)
    // -------------------------------------------------------------------------

    /**
     * Fixed-window token bucket for per-IP handshake rate limiting.
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
         *         {@code false} if the bucket was empty (request should be rejected with 429)
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
         * Used by eviction to remove stale buckets.
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
                // H-1 design: do NOT update lastAccessedAt here — refill is automatic, not an access
            }
        }
    }
}
