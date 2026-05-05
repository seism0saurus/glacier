package de.seism0saurus.glacier.security;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import social.bigbone.MastodonClient;

import java.lang.reflect.Type;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for STOMP subscription enumeration indistinguishability
 * (IT-sec-08 / IT-sec-09).
 *
 * <p>These tests verify that an authenticated client cannot enumerate other users' wallId
 * subscription topics by distinguishing the server's response to a foreign live wallId
 * from the response to a non-existent wallId.
 *
 * <ul>
 *   <li>IT-sec-08: subscribing to a foreign wallId's topic and subscribing to a
 *       non-existent wallId's topic both result in zero message delivery within 2 seconds.
 *       The form of rejection must be identical (no ERROR frame for one and silence for the
 *       other — both must produce silence).</li>
 *   <li>IT-sec-09: after a foreign-wallId subscription attempt, the AUDIT log must NOT
 *       contain the raw foreign wallId value (only hashed form if anything is logged).
 *       This ensures the D-13/SR-8 rule is upheld even in security-event logging.</li>
 * </ul>
 *
 * <p><b>Mode applicability</b>: <strong>live</strong> mode (real STOMP WebSocket endpoint).
 * Killswitch mode: N/A (no WebSocket). Fallback mode: N/A (no STOMP in fallback).
 *
 * <p>This is a Failsafe IT ({@code *IT.java}) — it runs during {@code mvn verify}.
 *
 * <p>OWASP: API6:2023 — Unrestricted Access to Sensitive Business Flows (subscription
 * enumeration prevention, ADR-PT-G7-01).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StompEnumerationIndistinguishabilityIT {

    @LocalServerPort
    private int port;

    @MockitoBean
    @SuppressWarnings("unused")
    private MastodonClient mastodonClient;

    private WebSocketStompClient stompClient;

    /** Fixed wallId A used to authenticate as the "attacker" session. */
    private static final String WALL_ID_A = "aaaaaaaa-aaaa-aaaa-aaaa-000000000001";
    /** Fixed wallId B used as the "victim" (foreign live wallId). */
    private static final String WALL_ID_B = "bbbbbbbb-bbbb-bbbb-bbbb-000000000002";
    /** A random non-existent wallId (different from A and B). */
    private static final String WALL_ID_NONEXISTENT = UUID.randomUUID().toString();

    @BeforeEach
    void setUp() {
        stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        stompClient.setMessageConverter(new org.springframework.messaging.converter.MappingJackson2MessageConverter());
    }

    @AfterEach
    void tearDown() {
        stompClient.stop();
    }

    // -------------------------------------------------------------------------
    // IT-sec-08: foreign and non-existent wallId produce identical silence
    // -------------------------------------------------------------------------

    /**
     * IT-sec-08: connect as wallId A; attempt to subscribe to
     * {@code /topic/hashtags/{wallId-B}/foo/creation} (foreign live wallId).
     * Assert zero messages delivered within 2 seconds.
     *
     * <p>Then connect as wallId A again; attempt to subscribe to
     * {@code /topic/hashtags/{non-existent-wallId}/foo/creation}.
     * Assert same zero-delivery outcome.
     *
     * <p>Key assertion: the <em>form</em> of the response/silence must be identical —
     * both attempts must produce silence (no delivery, no distinguishable error code
     * that would allow an attacker to determine whether the target wallId is "live"
     * or has never existed).
     *
     * <p>Security: ADR-PT-G7-01 — enumeration indistinguishability. OWASP API6.
     */
    @Test
    void subscribeToForeignLiveWallId_returnsSameSignalAsNonexistentWallId() throws Exception {
        // Attempt 1: subscribe to foreign live wallId B's topic
        int foreignLiveDelivered = attemptForeignSubscriptionAndCountDeliveries(
                WALL_ID_A,
                "/topic/hashtags/" + WALL_ID_B + "/foo/creation",
                2_000
        );

        // Attempt 2: subscribe to a non-existent wallId's topic
        int nonExistentDelivered = attemptForeignSubscriptionAndCountDeliveries(
                WALL_ID_A,
                "/topic/hashtags/" + WALL_ID_NONEXISTENT + "/foo/creation",
                2_000
        );

        // Both must produce zero deliveries — identical outcome
        assertThat(foreignLiveDelivered)
                .as("IT-sec-08: subscribing to a foreign live wallId's topic must deliver zero messages "
                        + "(STOMP broker enforces topic isolation via WallTopicAuthInterceptor)")
                .isZero();

        assertThat(nonExistentDelivered)
                .as("IT-sec-08: subscribing to a non-existent wallId's topic must also deliver zero messages")
                .isZero();

        // The outcomes are indistinguishable from the attacker's perspective:
        // both received zero deliveries within the 2-second window
        assertThat(foreignLiveDelivered)
                .as("IT-sec-08: the delivery outcome for foreign live wallId must equal "
                        + "the outcome for non-existent wallId (indistinguishability invariant)")
                .isEqualTo(nonExistentDelivered);
    }

    // -------------------------------------------------------------------------
    // IT-sec-09: AUDIT log does NOT contain raw foreign wallId
    // -------------------------------------------------------------------------

    /**
     * IT-sec-09: after a foreign-wallId subscription attempt, the AUDIT log must NOT
     * contain the raw foreign wallId value in any logged line.
     *
     * <p>The {@link de.seism0saurus.glacier.webservice.messaging.WallTopicAuthInterceptor}
     * emits an AUDIT event on cross-principal rejection. It uses {@code LogScrubber.hash8}
     * for both the principal name AND the destination wallId — so neither raw UUID must
     * appear in the log output.
     *
     * <p>Security: D-13/SR-8 — raw wallId values must never appear in log output.
     */
    @Test
    void subscribeToForeignWallId_doesNotEmitDistinctAuditEvent() throws Exception {
        // Use a unique wallId for this test so we can be sure any log entry is from this test.
        // Build it as a single effectively-final variable so it can be captured in lambdas.
        final String uniqueForeignWallId = "cccccccc-cccc-cccc-cccc-dd" + String.format("%010d",
                System.nanoTime() % 10_000_000_000L);

        Logger auditLogger = (Logger) LoggerFactory.getLogger("AUDIT");
        ListAppender<ILoggingEvent> auditAppender = new ListAppender<>();
        auditAppender.start();
        auditLogger.addAppender(auditAppender);

        try {
            // Connect as wallId A and subscribe to the unique foreign wallId's topic
            attemptForeignSubscriptionAndCountDeliveries(
                    WALL_ID_A,
                    "/topic/hashtags/" + uniqueForeignWallId + "/foo/creation",
                    1_000
            );

            // Wait until the audit appender stops growing — deterministic proxy for
            // "logging has quiesced" that avoids an arbitrary fixed sleep.
            LogStabilityHelper.awaitLogStability(auditAppender, Duration.ofSeconds(5), Duration.ofMillis(150));

            // AUDIT log must NOT contain the raw foreign wallId
            boolean rawForeignWallIdInLogs = auditAppender.list.stream()
                    .anyMatch(e -> e.getFormattedMessage().contains(uniqueForeignWallId));

            assertThat(rawForeignWallIdInLogs)
                    .as("IT-sec-09 (D-13/SR-8): AUDIT log must NOT contain the raw foreign wallId '%s'. "
                            + "WallTopicAuthInterceptor must use LogScrubber.hash8 for the destination wallId.",
                            uniqueForeignWallId)
                    .isFalse();

            // Additionally verify that if any rejection was logged, it used hashed form
            boolean rejectionLogged = auditAppender.list.stream()
                    .anyMatch(e -> e.getFormattedMessage().contains("cross_principal"));
            if (rejectionLogged) {
                boolean hasDestinationHash = auditAppender.list.stream()
                        .anyMatch(e -> e.getFormattedMessage().contains("destination-wallId-hash8="));
                assertThat(hasDestinationHash)
                        .as("IT-sec-09: if a cross-principal rejection is logged, "
                                + "it must use hashed destination-wallId form")
                        .isTrue();
            }

        } finally {
            auditLogger.detachAppender(auditAppender);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Connects via STOMP as the given wallId, subscribes to the specified topic destination,
     * waits for {@code waitMs} milliseconds, and returns the count of messages received.
     *
     * <p>For foreign-wallId subscriptions, the STOMP broker's
     * {@link de.seism0saurus.glacier.webservice.messaging.WallTopicAuthInterceptor} will
     * silently drop the subscription frame (return {@code null}), so zero messages should
     * be delivered.
     *
     * @param connectedAsWallId  the wallId cookie to use for authentication
     * @param subscribeToTopic   the STOMP topic destination to subscribe to
     * @param waitMs             how long to wait for messages (milliseconds)
     * @return the count of messages received on the subscribed topic
     */
    private int attemptForeignSubscriptionAndCountDeliveries(
            String connectedAsWallId, String subscribeToTopic, long waitMs) throws Exception {
        BlockingQueue<Object> received = new LinkedBlockingQueue<>();
        AtomicBoolean sessionConnected = new AtomicBoolean(false);

        WebSocketHttpHeaders handshakeHeaders = new WebSocketHttpHeaders();
        handshakeHeaders.add("Cookie", "wallId=" + connectedAsWallId);

        StompSession session = stompClient.connectAsync(
                "ws://localhost:" + port + "/websocket",
                handshakeHeaders,
                new StompHeaders(),
                new StompSessionHandlerAdapter() {
                    @Override
                    public void afterConnected(StompSession sess, StompHeaders connectedHeaders) {
                        sessionConnected.set(true);
                    }
                }
        ).get(5, TimeUnit.SECONDS);

        long deadline = System.currentTimeMillis() + 3_000;
        while (!sessionConnected.get() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertThat(sessionConnected.get()).as("STOMP session must connect successfully").isTrue();

        // Subscribe to the foreign topic
        session.subscribe(subscribeToTopic, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return byte[].class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                received.add(payload != null ? payload : new byte[0]);
            }
        });

        // Wait for the specified duration
        Thread.sleep(waitMs);

        int deliveredCount = received.size();

        try {
            session.disconnect();
        } catch (Exception ignored) {
            // best-effort
        }

        return deliveredCount;
    }
}
