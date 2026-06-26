package de.seism0saurus.glacier.security;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.seism0saurus.glacier.webservice.security.SubscribeRateLimitInterceptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.MessageBuilder;

import java.security.Principal;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SubscribeRateLimitInterceptor} — OWASP API6:2023 / CWE-400
 * STOMP SUBSCRIBE rate limiting to prevent subscription enumeration attacks.
 *
 * <p>Tests run in isolation: no Spring context, synthetic STOMP message headers.
 *
 * <p><b>Mode applicability</b>: live mode only. Killswitch mode: N/A (no WebSocket).
 * Fallback mode: N/A (no STOMP in fallback). Insecure mode: same interceptor applies.
 *
 * <p>Security: SR-WS-02, SR-LOG-WS-01 (ADR-PT-G7-01).
 */
class SubscribeRateLimitInterceptorTest {

    private static final String TEST_IP = "10.0.0.42";
    private static final String MASKED_IP_PREFIX = "10.0.0";
    private static final String TEST_PRINCIPAL = "aaaabbbb-aaaa-bbbb-cccc-000000000001";

    private MessageChannel mockChannel;

    @BeforeEach
    void setUp() {
        mockChannel = mock(MessageChannel.class);
    }

    // -------------------------------------------------------------------------
    // Helper: build a STOMP SUBSCRIBE message with optional principal + IP
    // -------------------------------------------------------------------------

    private Message<?> buildSubscribeMessage(String principalName, String ip) {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create(SimpMessageType.SUBSCRIBE);
        accessor.setDestination("/topic/hashtags/some-wall-id/foo/creation");
        accessor.setLeaveMutable(true);

        if (principalName != null) {
            Principal principal = () -> principalName;
            accessor.setUser(principal);
        }

        if (ip != null) {
            Map<String, Object> sessionAttrs = new HashMap<>();
            sessionAttrs.put(SubscribeRateLimitInterceptor.REMOTE_ADDR, ip);
            accessor.setSessionAttributes(sessionAttrs);
        }

        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private Message<?> buildNonSubscribeMessage() {
        // Use MESSAGE type (equivalent to STOMP SEND) — SimpMessageType.SEND does not exist
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        accessor.setDestination("/glacier/subscription");
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    // -------------------------------------------------------------------------
    // UT-SUBRL-01: below limit — message passes through
    // -------------------------------------------------------------------------

    /**
     * UT-SUBRL-01: the first SUBSCRIBE frame from an IP+principal within the rate limit
     * must pass through unchanged (non-null return).
     *
     * <p>Security: SR-WS-02 — within-limit subscriptions must not be dropped.
     */
    @Test
    void belowLimit_preSendReturnsMessage() {
        SubscribeRateLimitInterceptor interceptor =
                new SubscribeRateLimitInterceptor(30, Clock.systemUTC());

        Message<?> msg = buildSubscribeMessage(TEST_PRINCIPAL, TEST_IP);
        Message<?> result = interceptor.preSend(msg, mockChannel);

        assertThat(result)
                .as("UT-SUBRL-01: message within rate limit must be returned unchanged")
                .isNotNull()
                .isSameAs(msg);
    }

    // -------------------------------------------------------------------------
    // UT-SUBRL-02: at limit — silent drop (null return)
    // -------------------------------------------------------------------------

    /**
     * UT-SUBRL-02: after the bucket is exhausted, the next SUBSCRIBE frame must be
     * silently dropped (return {@code null}).
     *
     * <p>Security: SR-WS-02 — silent drop preserves indistinguishability; an attacker
     * cannot distinguish a dropped subscription from a slow server by observing the response.
     * OWASP API6 — enumeration prevention.
     */
    @Test
    void atLimit_preSendReturnsNull_silentDrop() {
        // Capacity = 1: second SUBSCRIBE is rejected
        SubscribeRateLimitInterceptor interceptor =
                new SubscribeRateLimitInterceptor(1, Clock.systemUTC());

        Message<?> msg = buildSubscribeMessage(TEST_PRINCIPAL, TEST_IP);

        // First — consumes the token
        Message<?> first = interceptor.preSend(msg, mockChannel);
        assertThat(first).isNotNull();

        // Second — bucket empty, must be silently dropped
        Message<?> second = interceptor.preSend(msg, mockChannel);
        assertThat(second)
                .as("UT-SUBRL-02: SUBSCRIBE above rate limit must be silently dropped (null return)")
                .isNull();
    }

    // -------------------------------------------------------------------------
    // UT-SUBRL-03: non-SUBSCRIBE command passes through
    // -------------------------------------------------------------------------

    /**
     * UT-SUBRL-03: non-SUBSCRIBE frames (SEND, CONNECT, etc.) must pass through
     * unmodified — the interceptor must not apply rate limiting to them.
     *
     * <p>Security: only SUBSCRIBE frames are relevant for enumeration attacks.
     */
    @Test
    void nonSubscribeCommand_preSendPassesThrough() {
        SubscribeRateLimitInterceptor interceptor =
                new SubscribeRateLimitInterceptor(30, Clock.systemUTC());

        Message<?> msg = buildNonSubscribeMessage();
        Message<?> result = interceptor.preSend(msg, mockChannel);

        assertThat(result)
                .as("UT-SUBRL-03: non-SUBSCRIBE frames must pass through unmodified")
                .isNotNull()
                .isSameAs(msg);
    }

    // -------------------------------------------------------------------------
    // UT-SUBRL-04: fail-open on Throwable
    // -------------------------------------------------------------------------

    /**
     * UT-SUBRL-04: when the interceptor throws unexpectedly, it must fail-open
     * (return the original message, allow the frame) and log a WARN.
     *
     * <p>Security: SR-WS-04 — fail-open consistent with {@code FallbackRateLimiter} pattern.
     * A broken rate-limit guard must not cause a denial-of-service on legitimate users.
     */
    @Test
    void onThrowable_failsOpen_returnsMessage() {
        // A broken message that throws when headers are accessed
        @SuppressWarnings("unchecked")
        Message<byte[]> brokenMessage = mock(Message.class);
        when(brokenMessage.getHeaders()).thenThrow(new RuntimeException("simulated header failure"));

        SubscribeRateLimitInterceptor interceptor =
                new SubscribeRateLimitInterceptor(30, Clock.systemUTC());

        Message<?> result = interceptor.preSend(brokenMessage, mockChannel);

        assertThat(result)
                .as("UT-SUBRL-04: fail-open — broken interceptor must not drop the message")
                .isSameAs(brokenMessage);
    }

    // -------------------------------------------------------------------------
    // UT-SUBRL-05-ISO: different (IP, principal) pairs have independent buckets
    // -------------------------------------------------------------------------

    /**
     * UT-SUBRL-05-ISO: exhausting the bucket for (IP_A, PRINCIPAL_A) must not affect
     * (IP_B, PRINCIPAL_A) or (IP_A, PRINCIPAL_B).
     *
     * <p>This is the key-isolation mutation guard for the composite bucket key
     * {@code ip + ":" + principal}. If the key computation were reduced to a constant
     * (e.g., {@code "default"}), the second and third assertions below would fail because
     * both alternative pairs would share the already-exhausted bucket.
     *
     * <p>Security: SR-WS-02 — per-(IP + principal) rate limiting must not bleed across
     * identities. OWASP API6:2023 — each unique combination must be independently throttled.
     */
    @Test
    void differentIpOrPrincipal_haveIndependentBuckets() {
        // Capacity = 1: the first SUBSCRIBE from any (IP, principal) pair exhausts its bucket
        SubscribeRateLimitInterceptor interceptor =
                new SubscribeRateLimitInterceptor(1, Clock.systemUTC());

        String ipA = "10.1.0.10";
        String ipB = "10.1.0.20";
        String principalA = "aaaabbbb-aaaa-bbbb-cccc-000000000011";
        String principalB = "aaaabbbb-aaaa-bbbb-cccc-000000000022";

        // (IP_A, PRINCIPAL_A): consumes the only token — bucket exhausted
        Message<?> firstMsg = buildSubscribeMessage(principalA, ipA);
        Message<?> firstResult = interceptor.preSend(firstMsg, mockChannel);
        assertThat(firstResult)
                .as("UT-SUBRL-05-ISO: (IP_A, PRINCIPAL_A) first SUBSCRIBE must be allowed")
                .isNotNull();

        // (IP_A, PRINCIPAL_A): second attempt — bucket empty, must be silently dropped
        Message<?> secondResult = interceptor.preSend(firstMsg, mockChannel);
        assertThat(secondResult)
                .as("UT-SUBRL-05-ISO: (IP_A, PRINCIPAL_A) second SUBSCRIBE must be silently dropped")
                .isNull();

        // (IP_B, PRINCIPAL_A): different IP, same principal — independent bucket, must be allowed
        Message<?> ipBMsg = buildSubscribeMessage(principalA, ipB);
        Message<?> ipBResult = interceptor.preSend(ipBMsg, mockChannel);
        assertThat(ipBResult)
                .as("UT-SUBRL-05-ISO: (IP_B, PRINCIPAL_A) must be allowed — different IP means different bucket key")
                .isNotNull();

        // (IP_A, PRINCIPAL_B): same IP, different principal — independent bucket, must be allowed
        Message<?> principalBMsg = buildSubscribeMessage(principalB, ipA);
        Message<?> principalBResult = interceptor.preSend(principalBMsg, mockChannel);
        assertThat(principalBResult)
                .as("UT-SUBRL-05-ISO: (IP_A, PRINCIPAL_B) must be allowed — different principal means different bucket key")
                .isNotNull();
    }

    // -------------------------------------------------------------------------
    // UT-SUBRL-05: AUDIT log contains masked IP and hashed wallId — SR-LOG-WS-01
    // -------------------------------------------------------------------------

    /**
     * UT-SUBRL-05: when a rate-limit breach emits an AUDIT event, the log line
     * must contain {@code ip-hash=} (masked IP) and {@code wallid-hash=} (hashed principal),
     * and must NOT contain the raw IP address string or the raw principal UUID.
     *
     * <p>Security: SR-LOG-WS-01 — D-13/SR-8: raw IP and wallId must not appear in logs.
     * Uses {@code LogScrubber.maskIp(ip)} and {@code LogScrubber.hash8(principalName)}.
     */
    @Test
    void auditLog_containsMaskedIpAndHashedWallId_notRawValues() {
        // Capacity = 1 to trigger rejection on second call
        SubscribeRateLimitInterceptor interceptor =
                new SubscribeRateLimitInterceptor(1, Clock.systemUTC());

        Logger auditLogger = (Logger) LoggerFactory.getLogger("AUDIT");
        ListAppender<ILoggingEvent> listAppender = new ListAppender<>();
        listAppender.start();
        auditLogger.addAppender(listAppender);

        try {
            Message<?> msg = buildSubscribeMessage(TEST_PRINCIPAL, TEST_IP);

            // Exhaust the bucket
            interceptor.preSend(msg, mockChannel);

            // Trigger the rate-limited path
            interceptor.preSend(msg, mockChannel);

            assertThat(listAppender.list)
                    .as("UT-SUBRL-05: AUDIT log must have at least one entry for the rate-limited event")
                    .isNotEmpty();

            // Must contain the literal event token (SR-F7-02, OWASP A09:2021)
            boolean hasEventToken = listAppender.list.stream()
                    .anyMatch(e -> e.getFormattedMessage().contains("ws.subscribe.rate_limited"));
            assertThat(hasEventToken)
                    .as("UT-SUBRL-05: AUDIT log must contain literal token 'ws.subscribe.rate_limited' (SR-F7-02)")
                    .isTrue();

            // Must contain ip-hash= prefix
            boolean hasIpHash = listAppender.list.stream()
                    .anyMatch(e -> e.getFormattedMessage().contains("ip-hash="));
            assertThat(hasIpHash)
                    .as("UT-SUBRL-05: AUDIT log must contain ip-hash= (masked IP form)")
                    .isTrue();

            // Must contain wallid-hash= prefix
            boolean hasWallidHash = listAppender.list.stream()
                    .anyMatch(e -> e.getFormattedMessage().contains("wallid-hash="));
            assertThat(hasWallidHash)
                    .as("UT-SUBRL-05: AUDIT log must contain wallid-hash= (hashed wallId form)")
                    .isTrue();

            // Must NOT contain raw IP
            boolean hasRawIp = listAppender.list.stream()
                    .anyMatch(e -> e.getFormattedMessage().contains(TEST_IP));
            assertThat(hasRawIp)
                    .as("UT-SUBRL-05: AUDIT log must NOT contain the raw IP address (SR-LOG-WS-01, D-13)")
                    .isFalse();

            // Must NOT contain raw principal UUID
            boolean hasRawPrincipal = listAppender.list.stream()
                    .anyMatch(e -> e.getFormattedMessage().contains(TEST_PRINCIPAL));
            assertThat(hasRawPrincipal)
                    .as("UT-SUBRL-05: AUDIT log must NOT contain the raw wallId UUID (SR-LOG-WS-01, D-13)")
                    .isFalse();

        } finally {
            auditLogger.detachAppender(listAppender);
        }
    }

    // -------------------------------------------------------------------------
    // Bucket-keying fallbacks + token-bucket refill (DoS-defence branches)
    // -------------------------------------------------------------------------

    @Test
    void missingRemoteAddr_fallsBackToPerPrincipalBucket_stillAllowsBelowLimit() {
        // No REMOTE_ADDR session attribute (regression in the handshake handler) → the interceptor
        // keys the bucket on "unknown:<principal>" instead of NPEing or failing open silently.
        SubscribeRateLimitInterceptor interceptor =
                new SubscribeRateLimitInterceptor(30, Clock.systemUTC());

        Message<?> msg = buildSubscribeMessage(TEST_PRINCIPAL, null);
        Message<?> result = interceptor.preSend(msg, mockChannel);

        assertThat(result).as("below limit, must pass through even without a REMOTE_ADDR").isNotNull();
    }

    @Test
    void missingRemoteAddr_stillEnforcesLimitOnUnknownBucket() {
        // The "unknown:<principal>" fallback bucket must still rate-limit (not be a bypass).
        SubscribeRateLimitInterceptor interceptor =
                new SubscribeRateLimitInterceptor(1, Clock.systemUTC());
        Message<?> msg = buildSubscribeMessage(TEST_PRINCIPAL, null);

        assertThat(interceptor.preSend(msg, mockChannel)).isNotNull(); // 1st: allowed
        assertThat(interceptor.preSend(msg, mockChannel)).isNull();     // 2nd: dropped
    }

    @Test
    void anonymousPrincipal_whenNoUser_isBucketedAndLimited() {
        // No Principal on the message → keyed under "anonymous" rather than throwing.
        SubscribeRateLimitInterceptor interceptor =
                new SubscribeRateLimitInterceptor(1, Clock.systemUTC());
        Message<?> msg = buildSubscribeMessage(null, TEST_IP);

        assertThat(interceptor.preSend(msg, mockChannel)).isNotNull(); // 1st: allowed
        assertThat(interceptor.preSend(msg, mockChannel)).isNull();     // 2nd: dropped
    }

    @Test
    void tokenBucket_refillsAfterWindowElapses() {
        // After the 60s window passes, the bucket refills — a previously rate-limited client is
        // allowed again (the limiter is not a permanent lock-out).
        MutableClock clock = new MutableClock(java.time.Instant.parse("2026-01-01T00:00:00Z"));
        SubscribeRateLimitInterceptor interceptor = new SubscribeRateLimitInterceptor(1, clock);
        Message<?> msg = buildSubscribeMessage(TEST_PRINCIPAL, TEST_IP);

        assertThat(interceptor.preSend(msg, mockChannel)).isNotNull(); // token consumed
        assertThat(interceptor.preSend(msg, mockChannel)).isNull();     // dropped — window not elapsed
        clock.advance(java.time.Duration.ofSeconds(61));
        assertThat(interceptor.preSend(msg, mockChannel))
                .as("after the window elapses the bucket refills")
                .isNotNull();
    }

    /** Minimal advanceable clock for exercising the token-bucket refill window. */
    private static final class MutableClock extends Clock {
        private java.time.Instant now;
        private MutableClock(java.time.Instant start) { this.now = start; }
        void advance(java.time.Duration d) { now = now.plus(d); }
        @Override public java.time.Instant instant() { return now; }
        @Override public java.time.ZoneId getZone() { return java.time.ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
    }
}
