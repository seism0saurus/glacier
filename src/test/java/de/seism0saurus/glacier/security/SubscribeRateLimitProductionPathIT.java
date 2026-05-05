package de.seism0saurus.glacier.security;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.seism0saurus.glacier.webservice.security.SubscribeRateLimitInterceptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.annotation.DirtiesContext.ClassMode;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import social.bigbone.MastodonClient;

import java.lang.reflect.Type;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test that verifies the per-IP rate-limit axis is populated through the real
 * WebSocket handshake path (IT-sec-RL-01).
 *
 * <p>The test connects via a real {@link WebSocketStompClient} — without manually injecting
 * session attributes — and sends enough STOMP SUBSCRIBE frames to exceed the configured test
 * threshold ({@code glacier.security.ws.subscribe.max-per-minute=3}). It then asserts that:
 * <ol>
 *   <li>At least one AUDIT event is emitted for the rate-limited frame.</li>
 *   <li>The AUDIT log line contains {@code ip-hash=} with a real IP representation (not null
 *       and not the literal string {@code "null"}).</li>
 *   <li>The bucket key does NOT start with {@code "unknown:"} — confirming the REMOTE_ADDR
 *       was correctly populated by {@link de.seism0saurus.glacier.webservice.messaging.PrincipalHandler#determineUser}
 *       during the WebSocket handshake.</li>
 * </ol>
 *
 * <p>Without the F-1 fix, every bucket key starts with {@code "unknown:"} and every AUDIT
 * event logs {@code ip-hash=null}, making per-IP isolation completely inert.
 *
 * <p><b>Mode applicability</b>: <strong>live</strong> mode (real STOMP WebSocket endpoint).
 * Killswitch mode: N/A (no WebSocket). Fallback mode: N/A (no STOMP in fallback).
 * Insecure mode: same PrincipalHandler applies.
 *
 * <p>This is a Failsafe IT ({@code *IT.java}) — it runs during {@code mvn verify}.
 *
 * <p>Standard: OWASP API6:2023 / API4:2023 / SR-WS-02 / SR-WS-03.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        // Very low threshold to trigger rate limiting in the test quickly
        "glacier.security.ws.subscribe.max-per-minute=3"
})
@DirtiesContext(classMode = ClassMode.AFTER_EACH_TEST_METHOD)
class SubscribeRateLimitProductionPathIT {

    @LocalServerPort
    private int port;

    @MockitoBean
    @SuppressWarnings("unused")
    private MastodonClient mastodonClient;

    private WebSocketStompClient stompClient;

    /** AUDIT logger to capture rate-limit events */
    private Logger auditLogger;
    private ListAppender<ILoggingEvent> auditAppender;

    @BeforeEach
    void setUp() {
        stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        stompClient.setMessageConverter(new StringMessageConverter());

        auditLogger = (Logger) LoggerFactory.getLogger("AUDIT");
        auditAppender = new ListAppender<>();
        auditAppender.start();
        auditLogger.addAppender(auditAppender);
    }

    @AfterEach
    void tearDown() {
        auditLogger.detachAppender(auditAppender);
        auditAppender.stop();
        stompClient.stop();
    }

    /**
     * IT-sec-RL-01: the per-IP rate-limit axis is live through the real WebSocket handshake.
     *
     * <p>Connects WITHOUT manually injecting REMOTE_ADDR session attributes — the
     * {@link de.seism0saurus.glacier.webservice.messaging.PrincipalHandler} must populate the
     * attribute itself during the WebSocket handshake.
     *
     * <p>Sends 5 SUBSCRIBE frames (threshold is 3). Asserts:
     * <ul>
     *   <li>At least one AUDIT event is emitted.</li>
     *   <li>The AUDIT log line contains {@code ip-hash=} and the value is NOT the literal
     *       string {@code "null"} (raw {@code null} is rendered as the string "null" by SLF4J
     *       when passed as an argument to a parameterised format string).</li>
     *   <li>The AUDIT log line does NOT contain {@code ip-hash=null} which would indicate
     *       the REMOTE_ADDR was not written to session attributes during handshake.</li>
     * </ul>
     *
     * <p>Standard: OWASP API6:2023, SR-WS-02, SR-WS-03.
     */
    @Test
    void subscribeRateLimit_throughRealHandshake_populatesIpInAuditLog() throws Exception {
        String wallId = "eeeeeeee-eeee-eeee-eeee-000000000099";

        AtomicBoolean connected = new AtomicBoolean(false);
        WebSocketHttpHeaders handshakeHeaders = new WebSocketHttpHeaders();
        handshakeHeaders.add("Cookie", "wallId=" + wallId);

        StompSession session;
        try {
            session = stompClient.connectAsync(
                    "ws://localhost:" + port + "/websocket",
                    handshakeHeaders,
                    new StompHeaders(),
                    new StompSessionHandlerAdapter() {
                        @Override
                        public void afterConnected(StompSession sess, StompHeaders connectedHeaders) {
                            connected.set(true);
                        }
                    }
            ).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            // Connection failure is unexpected in the live-mode IT environment
            throw new AssertionError("WebSocket connection failed: " + e.getMessage(), e);
        }

        // Wait for session to be established
        long deadline = System.currentTimeMillis() + 3_000;
        while (!connected.get() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertThat(connected.get())
                .as("IT-sec-RL-01 precondition: STOMP session must connect")
                .isTrue();

        // Send 5 SUBSCRIBE frames — threshold is 3, so frames 4 and 5 should be rate-limited
        for (int i = 0; i < 5; i++) {
            try {
                StompHeaders subHeaders = new StompHeaders();
                subHeaders.setDestination("/topic/hashtags/" + wallId + "/test" + i + "/creation");
                session.subscribe(subHeaders, new StompFrameHandler() {
                    @Override
                    public Type getPayloadType(StompHeaders headers) {
                        return String.class;
                    }
                    @Override
                    public void handleFrame(StompHeaders headers, Object payload) {
                        // no-op — we only care about rate-limit events
                    }
                });
            } catch (Exception ignored) {
                // A rate-limited SUBSCRIBE may cause an exception on the client side
            }
        }

        // Allow time for async AUDIT events to be written
        Thread.sleep(300);

        // Collect all AUDIT events for ws.subscribe.rate_limited
        List<ILoggingEvent> rateLimitEvents = auditAppender.list.stream()
                .filter(e -> e.getFormattedMessage().contains("ws.subscribe.rate_limited"))
                .toList();

        assertThat(rateLimitEvents)
                .as("IT-sec-RL-01: at least one ws.subscribe.rate_limited AUDIT event must be emitted "
                        + "after exceeding the threshold of 3 per minute. "
                        + "All AUDIT events: %s",
                        auditAppender.list.stream()
                                .map(ILoggingEvent::getFormattedMessage).toList())
                .isNotEmpty();

        // All emitted rate-limit AUDIT events must have a real ip-hash= value (not null)
        for (ILoggingEvent event : rateLimitEvents) {
            String msg = event.getFormattedMessage();

            assertThat(msg)
                    .as("IT-sec-RL-01: AUDIT event must contain 'ip-hash=' prefix (SR-WS-02, SR-LOG-WS-01)")
                    .contains("ip-hash=");

            // ip-hash=null means REMOTE_ADDR was not written during handshake — F-1 is not fixed
            assertThat(msg)
                    .as("IT-sec-RL-01: AUDIT event must NOT contain 'ip-hash=null' — "
                            + "that would mean PrincipalHandler did not populate REMOTE_ADDR "
                            + "during the WebSocket handshake (F-1, SR-WS-02)")
                    .doesNotContain("ip-hash=null");
        }

        // Cleanup
        try {
            if (session.isConnected()) {
                session.disconnect();
            }
        } catch (Exception ignored) {
            // best-effort
        }
    }
}
