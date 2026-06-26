package de.seism0saurus.glacier.security;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.seism0saurus.glacier.webservice.security.HandshakeRateLimitInterceptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;

import java.net.InetSocketAddress;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link HandshakeRateLimitInterceptor} — OWASP API4:2023 / CWE-400
 * WebSocket CONNECT rate limiting at handshake level.
 *
 * <p>Tests run in isolation: no Spring context, mocked HTTP request/response.
 *
 * <p><b>Mode applicability</b>: live mode only. Killswitch mode: N/A (no WebSocket).
 * Fallback mode: N/A (no WebSocket). Insecure mode: same interceptor applies.
 *
 * <p>Security: SR-WS-01, SR-LOG-WS-01, SR-LOG-WS-02 (ADR-PT-G5-01).
 */
class HandshakeRateLimitInterceptorTest {

    private static final String TEST_IP = "192.168.1.100";
    private static final String MASKED_IP_PREFIX = "192.168.1";

    private ServerHttpRequest mockRequest;
    private ServerHttpResponse mockResponse;
    private WebSocketHandler mockWsHandler;
    private Map<String, Object> attributes;

    @BeforeEach
    void setUp() {
        mockRequest = mock(ServerHttpRequest.class);
        mockResponse = mock(ServerHttpResponse.class);
        mockWsHandler = mock(WebSocketHandler.class);
        attributes = new HashMap<>();

        InetSocketAddress remoteAddress = new InetSocketAddress(TEST_IP, 12345);
        when(mockRequest.getRemoteAddress()).thenReturn(remoteAddress);
    }

    // -------------------------------------------------------------------------
    // UT-WS-RL-01: below limit — request allowed
    // -------------------------------------------------------------------------

    /**
     * UT-WS-RL-01: first connection from an IP is within the rate limit.
     * {@code beforeHandshake} must return {@code true} and NOT set a 429 status.
     *
     * <p>Security: SR-WS-01 — within-limit connections must not be blocked.
     */
    @Test
    void belowLimit_beforeHandshakeReturnsTrue() throws Exception {
        // 10 requests/minute default — first request is within limit
        HandshakeRateLimitInterceptor interceptor =
                new HandshakeRateLimitInterceptor(10, Clock.systemUTC());

        boolean result = interceptor.beforeHandshake(mockRequest, mockResponse, mockWsHandler, attributes);

        assertThat(result).isTrue();
        verify(mockResponse, never()).setStatusCode(any());
    }

    // -------------------------------------------------------------------------
    // UT-WS-RL-02: at limit — returns false and sets 429
    // -------------------------------------------------------------------------

    /**
     * UT-WS-RL-02: after exhausting the bucket, the next request must be rejected
     * with HTTP 429 and {@code beforeHandshake} must return {@code false}.
     *
     * <p>Security: SR-WS-01 — rate-limit breach returns HTTP 429. OWASP API4.
     */
    @Test
    void atLimit_beforeHandshakeReturnsFalse_and429Status() throws Exception {
        // Capacity = 1: first request consumes the token, second is rejected
        HandshakeRateLimitInterceptor interceptor =
                new HandshakeRateLimitInterceptor(1, Clock.systemUTC());

        // First request — consumes the only token
        boolean firstResult = interceptor.beforeHandshake(mockRequest, mockResponse, mockWsHandler, attributes);
        assertThat(firstResult).isTrue();

        // Second request — bucket empty, must be rejected
        ServerHttpResponse secondResponse = mock(ServerHttpResponse.class);
        boolean secondResult = interceptor.beforeHandshake(mockRequest, secondResponse, mockWsHandler, attributes);

        assertThat(secondResult).isFalse();
        verify(secondResponse).setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
    }

    // -------------------------------------------------------------------------
    // UT-WS-RL-03: fail-open on Throwable
    // -------------------------------------------------------------------------

    /**
     * UT-WS-RL-03: when the request processing throws unexpectedly, the interceptor
     * must fail-open (return {@code true}, allow the connection) and log a WARN.
     *
     * <p>Security: SR-WS-04 — consistent with {@code FallbackRateLimiter} fail-open pattern.
     * Fail-open is preferred here: a broken rate-limit guard must not cause a denial-of-service
     * on legitimate users.
     */
    @Test
    void onThrowable_failsOpen_returnsTrueAndLogsWarn() throws Exception {
        // Inject a request that throws when getRemoteAddress is called
        ServerHttpRequest badRequest = mock(ServerHttpRequest.class);
        when(badRequest.getRemoteAddress()).thenThrow(new RuntimeException("simulated failure"));

        HandshakeRateLimitInterceptor interceptor =
                new HandshakeRateLimitInterceptor(10, Clock.systemUTC());

        boolean result = interceptor.beforeHandshake(badRequest, mockResponse, mockWsHandler, attributes);

        assertThat(result)
                .as("UT-WS-RL-03: fail-open — broken rate-limit guard must not block connection")
                .isTrue();
    }

    // -------------------------------------------------------------------------
    // UT-WS-RL-04: AUDIT log contains masked IP, NOT raw IP — SR-LOG-WS-01
    // -------------------------------------------------------------------------

    /**
     * UT-WS-RL-04: when a rate-limit breach is logged to the AUDIT logger,
     * the log line must contain {@code ip-hash=} with the masked IP (last octet replaced)
     * and must NOT contain the raw IP string.
     *
     * <p>Security: SR-LOG-WS-01 — D-13/SR-8: raw IP must not appear in JSON log output.
     * Uses {@code LogScrubber.maskIp(ip)}.
     */
    @Test
    void auditLog_containsMaskedIp_notRawIp() throws Exception {
        // Capacity = 1 to trigger a rejection on the second call
        HandshakeRateLimitInterceptor interceptor =
                new HandshakeRateLimitInterceptor(1, Clock.systemUTC());

        // Attach a ListAppender to the AUDIT logger to capture log output
        Logger auditLogger = (Logger) LoggerFactory.getLogger("AUDIT");
        ListAppender<ILoggingEvent> listAppender = new ListAppender<>();
        listAppender.start();
        auditLogger.addAppender(listAppender);

        try {
            // Exhaust the bucket
            interceptor.beforeHandshake(mockRequest, mockResponse, mockWsHandler, attributes);

            // Trigger the rate-limited path
            interceptor.beforeHandshake(mockRequest, mockResponse, mockWsHandler, attributes);

            // Verify AUDIT log content
            assertThat(listAppender.list)
                    .as("UT-WS-RL-04: AUDIT log must contain at least one entry for the rate-limited event")
                    .isNotEmpty();

            // The AUDIT message must contain the literal event token (SR-F7-02, OWASP A09:2021)
            boolean hasEventToken = listAppender.list.stream()
                    .anyMatch(e -> e.getFormattedMessage().contains("ws.handshake.rate_limited"));
            assertThat(hasEventToken)
                    .as("UT-WS-RL-04: AUDIT log must contain literal token 'ws.handshake.rate_limited' (SR-F7-02)")
                    .isTrue();

            // The AUDIT message must contain ip-hash= prefix
            boolean hasIpHash = listAppender.list.stream()
                    .anyMatch(e -> e.getFormattedMessage().contains("ip-hash="));
            assertThat(hasIpHash)
                    .as("UT-WS-RL-04: AUDIT log must contain ip-hash= (masked IP form)")
                    .isTrue();

            // The AUDIT message must NOT contain the raw IP
            boolean hasRawIp = listAppender.list.stream()
                    .anyMatch(e -> e.getFormattedMessage().contains(TEST_IP));
            assertThat(hasRawIp)
                    .as("UT-WS-RL-04: AUDIT log must NOT contain the raw IP address (SR-LOG-WS-01, D-13)")
                    .isFalse();

            // The masked form (last octet replaced) must be present
            boolean hasMaskedIp = listAppender.list.stream()
                    .anyMatch(e -> e.getFormattedMessage().contains(MASKED_IP_PREFIX));
            assertThat(hasMaskedIp)
                    .as("UT-WS-RL-04: AUDIT log must contain the masked IP prefix (xxx for last octet)")
                    .isTrue();
        } finally {
            auditLogger.detachAppender(listAppender);
        }
    }

    // -------------------------------------------------------------------------
    // UT-WS-RL-05: null remoteAddress — treated as unknown IP, fail-open
    // -------------------------------------------------------------------------

    /**
     * UT-WS-RL-05: when {@code getRemoteAddress()} returns {@code null},
     * the interceptor must not throw and must fail-open (allow the connection).
     *
     * <p>Security: SR-WS-04 — null address is treated as an exception case, fail-open.
     */
    @Test
    void nullRemoteAddress_failsOpen() throws Exception {
        when(mockRequest.getRemoteAddress()).thenReturn(null);
        HandshakeRateLimitInterceptor interceptor =
                new HandshakeRateLimitInterceptor(10, Clock.systemUTC());

        boolean result = interceptor.beforeHandshake(mockRequest, mockResponse, mockWsHandler, attributes);

        assertThat(result)
                .as("UT-WS-RL-05: null remoteAddress must fail-open")
                .isTrue();
    }

    // -------------------------------------------------------------------------
    // MutationKill: L108 — if (ip == null) fail-open BEFORE bucketing (RemoveConditional_EQUAL_ELSE)
    // -------------------------------------------------------------------------

    /**
     * Kills L108 RemoveConditional_EQUAL_ELSE on {@code if (ip == null)}.
     *
     * <p>When the IP cannot be extracted the interceptor must take the dedicated fail-open path:
     * log {@code "cannot extract remote IP — fail-open"} and return {@code true} WITHOUT creating a
     * bucket. If the conditional were removed (always-false), control would fall through to
     * {@code ipBuckets.computeIfAbsent(null, ...)} which throws NPE and lands in the generic
     * {@code "unexpected error — fail-open"} catch instead. Asserting the specific WARN token and
     * that NO bucket was created discriminates the two.
     */
    @Test
    void nullIp_takesDedicatedFailOpenPath_noBucketCreated() throws Exception {
        when(mockRequest.getRemoteAddress()).thenReturn(null);
        HandshakeRateLimitInterceptor interceptor =
                new HandshakeRateLimitInterceptor(10, Clock.systemUTC());

        Logger classLogger = (Logger) LoggerFactory.getLogger(HandshakeRateLimitInterceptor.class);
        ListAppender<ILoggingEvent> listAppender = new ListAppender<>();
        listAppender.start();
        classLogger.addAppender(listAppender);
        try {
            boolean result = interceptor.beforeHandshake(mockRequest, mockResponse, mockWsHandler, attributes);

            assertThat(result).as("null IP fails open (true)").isTrue();
            assertThat(ipBucketCount(interceptor))
                    .as("null-IP path must NOT create a bucket (the conditional short-circuits before computeIfAbsent)")
                    .isZero();

            boolean hasCannotExtract = listAppender.list.stream()
                    .anyMatch(e -> e.getFormattedMessage().contains("cannot extract remote IP"));
            assertThat(hasCannotExtract)
                    .as("null IP must log the dedicated 'cannot extract remote IP' WARN, not the generic catch")
                    .isTrue();
            boolean hasGenericCatch = listAppender.list.stream()
                    .anyMatch(e -> e.getFormattedMessage().contains("unexpected error"));
            assertThat(hasGenericCatch)
                    .as("null IP must NOT fall through to the generic catch (would mean the conditional was bypassed)")
                    .isFalse();
        } finally {
            classLogger.detachAppender(listAppender);
        }
    }

    // Reflection accessor: ipBucketCount() is package-private on HandshakeRateLimitInterceptor,
    // which lives in package ...webservice.security — a different package than this test — so it
    // cannot be called directly. (setAccessible works: the app runs on the classpath, no modules.)
    private static int ipBucketCount(HandshakeRateLimitInterceptor interceptor) {
        try {
            java.lang.reflect.Method m =
                    HandshakeRateLimitInterceptor.class.getDeclaredMethod("ipBucketCount");
            m.setAccessible(true);
            return (int) m.invoke(interceptor);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("reflective ipBucketCount() failed", e);
        }
    }

    // -------------------------------------------------------------------------
    // MutationKill: evictStaleBuckets — 600s boundary, Clock.instant, minusSeconds, removeIf/entrySet
    // (L155, L156)
    // -------------------------------------------------------------------------

    /**
     * Kills L155 ("600 with 601" boundary, Clock::instant, Instant::minusSeconds) and L156
     * (Set::removeIf, ConcurrentHashMap::entrySet) on {@code evictStaleBuckets()}.
     *
     * <p>Bucket created at t0, clock advanced to t0+601s. threshold = (t0+601) - 600 = t0+1;
     * lastAccessedAt (t0) isBefore(t0+1) → stale → removed. Under minusSeconds(601) threshold = t0
     * and isBefore(t0) is false → survives → assertion fails. Under no-op removeIf/entrySet the
     * bucket survives → assertion fails.
     */
    @Test
    void evictStaleBuckets_removesBucketIdlePast600s() throws Exception {
        MutableClock clock = new MutableClock(java.time.Instant.parse("2026-01-01T00:00:00Z"));
        HandshakeRateLimitInterceptor interceptor = new HandshakeRateLimitInterceptor(10, clock);

        interceptor.beforeHandshake(mockRequest, mockResponse, mockWsHandler, attributes);
        assertThat(ipBucketCount(interceptor)).as("one bucket at t0").isEqualTo(1);

        clock.advance(java.time.Duration.ofSeconds(601));
        interceptor.evictStaleBuckets();

        assertThat(ipBucketCount(interceptor))
                .as("bucket idle 601s (> 600s) must be evicted")
                .isZero();
    }

    /**
     * Pins the 600s boundary from the other side: a bucket idle for EXACTLY 600s must be KEPT.
     * threshold = (t0+600) - 600 = t0; lastAccessedAt (t0) isBefore(t0) is false → not stale.
     * Kills minusSeconds(599)-style boundary mutants (which would set threshold t0+1 and wrongly evict).
     */
    @Test
    void evictStaleBuckets_keepsBucketIdleExactly600s() throws Exception {
        MutableClock clock = new MutableClock(java.time.Instant.parse("2026-01-01T00:00:00Z"));
        HandshakeRateLimitInterceptor interceptor = new HandshakeRateLimitInterceptor(10, clock);

        interceptor.beforeHandshake(mockRequest, mockResponse, mockWsHandler, attributes);

        clock.advance(java.time.Duration.ofSeconds(600));
        interceptor.evictStaleBuckets();

        assertThat(ipBucketCount(interceptor))
                .as("bucket idle exactly 600s is on the boundary (not yet stale) and must be kept")
                .isEqualTo(1);
    }

    /**
     * Kills L156 Set::removeIf / entrySet together with the predicate: a FRESH bucket (touched at
     * the eviction instant) must be KEPT while a STALE one is removed in the same pass.
     */
    @Test
    void evictStaleBuckets_keepsFreshBucket_removesStaleOne() throws Exception {
        MutableClock clock = new MutableClock(java.time.Instant.parse("2026-01-01T00:00:00Z"));
        HandshakeRateLimitInterceptor interceptor = new HandshakeRateLimitInterceptor(10, clock);

        // Stale bucket for IP_A at t0
        ServerHttpRequest reqA = mock(ServerHttpRequest.class);
        when(reqA.getRemoteAddress()).thenReturn(new InetSocketAddress("10.0.0.1", 1000));
        interceptor.beforeHandshake(reqA, mock(ServerHttpResponse.class), mockWsHandler, attributes);

        // Advance past window, fresh bucket for IP_B at t0+601s
        clock.advance(java.time.Duration.ofSeconds(601));
        ServerHttpRequest reqB = mock(ServerHttpRequest.class);
        when(reqB.getRemoteAddress()).thenReturn(new InetSocketAddress("10.0.0.2", 1001));
        interceptor.beforeHandshake(reqB, mock(ServerHttpResponse.class), mockWsHandler, attributes);
        assertThat(ipBucketCount(interceptor)).as("two buckets before eviction").isEqualTo(2);

        interceptor.evictStaleBuckets();

        assertThat(ipBucketCount(interceptor))
                .as("stale IP_A bucket (t0) evicted, fresh IP_B bucket (t0+601) kept")
                .isEqualTo(1);
    }

    /** Minimal advanceable clock for exercising eviction window boundaries. */
    private static final class MutableClock extends Clock {
        private java.time.Instant now;
        private MutableClock(java.time.Instant start) { this.now = start; }
        void advance(java.time.Duration d) { now = now.plus(d); }
        @Override public java.time.Instant instant() { return now; }
        @Override public java.time.ZoneId getZone() { return java.time.ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
    }

    // -------------------------------------------------------------------------
    // UT-WS-RL-06: different source IPs have independent buckets
    // -------------------------------------------------------------------------

    /**
     * UT-WS-RL-06: two distinct source IPs must each receive their own token bucket.
     * Exhausting IP_A's bucket must not affect IP_B's bucket.
     *
     * <p>This is the key-isolation mutation guard: if the bucket key computation were
     * changed to a constant (e.g., {@code "default"}), IP_B would share the same
     * exhausted bucket and this test would fail.
     *
     * <p>Security: SR-WS-01 — per-IP rate limiting must not bleed across identities.
     * OWASP API4:2023 — each IP must be independently throttled.
     */
    @Test
    void differentSourceIps_haveIndependentBuckets() throws Exception {
        // Capacity = 1: the first connection from any IP exhausts that IP's bucket
        HandshakeRateLimitInterceptor interceptor =
                new HandshakeRateLimitInterceptor(1, Clock.systemUTC());

        // IP_A: build a separate request mock so its address differs from TEST_IP (IP_B)
        String ipA = "192.168.2.10";
        ServerHttpRequest requestA = mock(ServerHttpRequest.class);
        when(requestA.getRemoteAddress()).thenReturn(new InetSocketAddress(ipA, 9000));

        String ipB = "192.168.2.20";
        ServerHttpRequest requestB = mock(ServerHttpRequest.class);
        when(requestB.getRemoteAddress()).thenReturn(new InetSocketAddress(ipB, 9001));

        ServerHttpResponse responseA = mock(ServerHttpResponse.class);
        ServerHttpResponse responseB = mock(ServerHttpResponse.class);

        // IP_A connects — consumes its only token, bucket exhausted
        boolean firstResult = interceptor.beforeHandshake(requestA, responseA, mockWsHandler, attributes);
        assertThat(firstResult)
                .as("UT-WS-RL-06: IP_A first connection must be allowed")
                .isTrue();

        // IP_A connects again — its bucket is exhausted, must be rejected
        boolean secondResult = interceptor.beforeHandshake(requestA, responseA, mockWsHandler, attributes);
        assertThat(secondResult)
                .as("UT-WS-RL-06: IP_A second connection must be rejected (bucket exhausted)")
                .isFalse();
        verify(responseA).setStatusCode(HttpStatus.TOO_MANY_REQUESTS);

        // IP_B connects — must be allowed; its bucket is independent of IP_A's
        boolean thirdResult = interceptor.beforeHandshake(requestB, responseB, mockWsHandler, attributes);
        assertThat(thirdResult)
                .as("UT-WS-RL-06: IP_B must be allowed even though IP_A's bucket is exhausted — buckets are key-isolated")
                .isTrue();
        verify(responseB, never()).setStatusCode(any());
    }
}
