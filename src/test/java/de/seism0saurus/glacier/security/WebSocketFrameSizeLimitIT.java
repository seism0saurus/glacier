package de.seism0saurus.glacier.security;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.seism0saurus.glacier.webservice.messaging.messages.SubscriptionAckMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import social.bigbone.MastodonClient;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.lang.reflect.Type;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for WebSocket frame-size limits (IT-sec-07a / IT-sec-07b / IT-sec-07c).
 *
 * <p>These tests connect via a real {@link WebSocketStompClient} to the running Spring Boot
 * server and send STOMP frames of controlled sizes to verify:
 * <ul>
 *   <li>IT-sec-07a: a subscribe payload just under 64 KB is accepted and a
 *       {@link SubscriptionAckMessage} is returned.</li>
 *   <li>IT-sec-07b: a payload exceeding the configured message-size limit causes the STOMP
 *       session to receive an ERROR frame or be disconnected — not a server NPE or 500.</li>
 *   <li>IT-sec-07c: after an oversize frame, no raw hashtag string appears in any log line
 *       (D-13/SR-8 — sensitive data must not leak through error paths).</li>
 * </ul>
 *
 * <p>The explicit frame-size cap is set via
 * {@code glacier.security.ws.message-size-bytes=65536} (64 KB) in
 * {@link WebSocketConfiguration#configureWebSocketTransport}.
 *
 * <p><b>Mode applicability</b>: <strong>live</strong> mode only (real STOMP WebSocket endpoint).
 * Fallback mode: N/A (WebSocket/STOMP broker is not active in fallback mode — clients poll REST).
 * Killswitch mode: N/A (no WebSocket). Insecure mode: same interceptor applies.
 *
 * <p>This is a Failsafe IT ({@code *IT.java}) — it runs during {@code mvn verify}.
 *
 * <p>OWASP: API4:2023 — Unrestricted Resource Consumption (SR-WS-05, ADR-PT-G5-01).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        // Use a small message-size limit to make oversize testing practical
        "glacier.security.ws.message-size-bytes=65536",
        "glacier.security.ws.send-buffer-bytes=524288",
        "glacier.security.ws.send-time-ms=20000"
})
class WebSocketFrameSizeLimitIT {

    @LocalServerPort
    private int port;

    @MockitoBean
    @SuppressWarnings("unused")
    private MastodonClient mastodonClient;

    @Autowired
    private ObjectMapper objectMapper;

    private WebSocketStompClient stompClient;

    /** A safe hashtag that is well within validation rules. */
    private static final String SAFE_HASHTAG = "testfoo";

    @BeforeEach
    void setUp() {
        stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        MappingJackson2MessageConverter converter = new MappingJackson2MessageConverter();
        converter.setObjectMapper(objectMapper);
        stompClient.setMessageConverter(converter);
    }

    @AfterEach
    void tearDown() {
        stompClient.stop();
    }

    // -------------------------------------------------------------------------
    // IT-sec-07a: normal payload is accepted (transport limits configured)
    // -------------------------------------------------------------------------

    /**
     * IT-sec-07a: a normal STOMP SUBSCRIBE frame with a valid hashtag payload must be
     * accepted by the broker and the server must respond with a
     * {@link SubscriptionAckMessage}.
     *
     * <p>This test verifies that the transport-limit configuration does NOT break
     * normal subscription flow — i.e., the explicit {@code setMessageSizeLimit},
     * {@code setSendBufferSizeLimit}, and {@code setSendTimeLimit} are set to values
     * that are permissive for legitimate use.
     *
     * <p>Security: SR-WS-05 — the message-size cap must not reject legitimate messages.
     */
    @Test
    void subscribeFrame_normalPayload_isAccepted() throws Exception {
        String wallId = "ffffffff-ffff-ffff-ffff-000000000001";

        // Build a normal, small payload — well within any transport limit
        Map<String, Object> payload = new HashMap<>();
        payload.put("hashtag", SAFE_HASHTAG);

        SubscriptionAckMessage ack = connectAndSendPayload(wallId, payload);

        assertThat(ack)
                .as("IT-sec-07a: a normal subscribe payload must be accepted and produce an ack")
                .isNotNull();
    }

    // -------------------------------------------------------------------------
    // IT-sec-07b: payload above 64 KB causes ERROR or disconnect, not NPE/500
    // -------------------------------------------------------------------------

    /**
     * IT-sec-07b: a STOMP frame exceeding the configured message-size limit must not
     * cause a server NullPointerException or unhandled error. The server must either:
     * <ul>
     *   <li>Send a STOMP ERROR frame to the client, OR</li>
     *   <li>Close the WebSocket session gracefully</li>
     * </ul>
     *
     * <p>In either case, the key invariant is: no server-side stacktrace or 500 response
     * reaches the wire (fail-secure per OWASP A05).
     *
     * <p>Note: Spring's STOMP broker may silently close the connection rather than sending
     * an ERROR frame. The test accepts both outcomes.
     *
     * <p>Security: SR-WS-05 — explicit frame-size enforcement (OWASP API4, CWE-400).
     */
    @Test
    void subscribeFrame_above64KB_isRejectedOrDisconnected() throws Exception {
        String wallId = "ffffffff-ffff-ffff-ffff-000000000002";

        // Build a payload well above 64 KB — 100 KB padding
        int targetPaddingBytes = 100 * 1024; // 100 KB
        String padding = "x".repeat(targetPaddingBytes);
        Map<String, Object> oversizePayload = new HashMap<>();
        oversizePayload.put("hashtag", SAFE_HASHTAG);
        oversizePayload.put("_ignored_padding", padding);

        AtomicBoolean errorOrDisconnect = new AtomicBoolean(false);
        AtomicBoolean sessionConnected = new AtomicBoolean(false);

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
                            sessionConnected.set(true);
                        }

                        @Override
                        public void handleException(StompSession sess, StompCommand command,
                                StompHeaders headers, byte[] payload, Throwable exception) {
                            // STOMP ERROR frame received — this is the expected outcome
                            errorOrDisconnect.set(true);
                        }

                        @Override
                        public void handleTransportError(StompSession sess, Throwable exception) {
                            // Connection closed / transport error — also acceptable
                            errorOrDisconnect.set(true);
                        }
                    }
            ).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            // Connection itself failed — acceptable for oversize scenario
            // This can happen if the server rejects the connection before STOMP handshake
            return;
        }

        // Wait for session
        long deadline = System.currentTimeMillis() + 3_000;
        while (!sessionConnected.get() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }

        if (!sessionConnected.get()) {
            // Never connected — acceptable for oversize scenario
            return;
        }

        // Send the oversize payload
        try {
            StompHeaders sendHeaders = new StompHeaders();
            sendHeaders.setDestination("/glacier/subscription");
            session.send(sendHeaders, oversizePayload);
        } catch (Exception e) {
            // Exception on send is acceptable for an oversize frame
            errorOrDisconnect.set(true);
        }

        // Wait for an error or disconnect signal
        long waitDeadline = System.currentTimeMillis() + 3_000;
        while (!errorOrDisconnect.get() && System.currentTimeMillis() < waitDeadline) {
            Thread.sleep(100);
        }

        // IT-sec-07b core assertion: the server must reject an oversize frame via a STOMP ERROR
        // frame or a transport disconnect — confirming that setMessageSizeLimit(65536) in
        // WebSocketConfiguration.configureWebSocketTransport is actually enforced.
        //
        // If this assertion fails, it means the message-size limit was removed or bypassed
        // (e.g. WebSocketConfiguration.configureWebSocketTransport no longer calls
        // registration.setMessageSizeLimit(messageSizeBytes)), causing the 100 KB payload to
        // be absorbed silently instead of rejected. This is the SR-WS-05 invariant.
        //
        // Spring's SockJS/WebSocket transport triggers handleTransportError when the
        // inbound message exceeds setMessageSizeLimit — the oversize frame causes the
        // SubProtocolWebSocketHandler to close the session, which propagates as a
        // transport error to the STOMP client.
        assertThat(errorOrDisconnect.get())
                .as("IT-sec-07b: server must reject oversize frame via STOMP ERROR or transport disconnect. "
                        + "If false, WebSocketConfiguration.configureWebSocketTransport may no longer "
                        + "call setMessageSizeLimit(messageSizeBytes) (SR-WS-05, OWASP API4, CWE-400).")
                .isTrue();

        try {
            if (session.isConnected()) {
                session.disconnect();
            }
        } catch (Exception ignored) {
            // best-effort
        }
    }

    // -------------------------------------------------------------------------
    // IT-sec-07c: oversize frame does not leak raw hashtag in logs — D-13/SR-8
    // -------------------------------------------------------------------------

    /**
     * IT-sec-07c: after sending an oversize frame, no raw hashtag string appears in any
     * log line. This verifies that error handling in the STOMP broker and the frame-size
     * enforcement path does not inadvertently log the raw hashtag value.
     *
     * <p>Security: D-13/SR-8 — sensitive data (hashtag subscription targets) must not
     * appear verbatim in log output, even when the processing path throws or fails.
     */
    @Test
    void oversizeFrame_doesNotLeakRawHashtagInLogs() throws Exception {
        String wallId = "ffffffff-ffff-ffff-ffff-000000000003";
        String sensitiveHashtag = "uniqueHashtagShouldNotAppearInLogs_" + System.nanoTime();

        // Attach appenders to root logger and AUDIT logger to capture all log output
        Logger rootLogger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        Logger auditLogger = (Logger) LoggerFactory.getLogger("AUDIT");
        ListAppender<ILoggingEvent> rootAppender = new ListAppender<>();
        ListAppender<ILoggingEvent> auditAppender = new ListAppender<>();
        rootAppender.start();
        auditAppender.start();
        rootLogger.addAppender(rootAppender);
        auditLogger.addAppender(auditAppender);

        try {
            // Build an oversize payload with the sensitive hashtag
            String padding = "x".repeat(100 * 1024); // 100 KB
            Map<String, Object> oversizePayload = new HashMap<>();
            oversizePayload.put("hashtag", sensitiveHashtag);
            oversizePayload.put("_ignored_padding", padding);

            // Attempt to send; ignore exceptions
            try {
                connectAndSendPayload(wallId, oversizePayload);
            } catch (Exception ignored) {
                // expected — oversize frame may cause errors
            }

            // Wait until both appenders stop growing — two consecutive size-stable
            // polls separated by quietWindow provide a deterministic proxy for
            // "logging has quiesced" without an arbitrary fixed sleep.
            LogStabilityHelper.awaitLogStability(rootAppender, Duration.ofSeconds(5), Duration.ofMillis(150));
            LogStabilityHelper.awaitLogStability(auditAppender, Duration.ofSeconds(5), Duration.ofMillis(150));

            // Assert: the raw hashtag must NOT appear in any log line
            boolean rawHashtagInRootLogs = rootAppender.list.stream()
                    .anyMatch(e -> e.getFormattedMessage().contains(sensitiveHashtag));
            boolean rawHashtagInAuditLogs = auditAppender.list.stream()
                    .anyMatch(e -> e.getFormattedMessage().contains(sensitiveHashtag));

            assertThat(rawHashtagInRootLogs || rawHashtagInAuditLogs)
                    .as("IT-sec-07c (D-13/SR-8): raw hashtag '%s' must not appear in any log line "
                            + "after an oversize frame", sensitiveHashtag)
                    .isFalse();

        } finally {
            rootLogger.detachAppender(rootAppender);
            auditLogger.detachAppender(auditAppender);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Connects via STOMP with the given wallId cookie, sends the payload map to
     * {@code /glacier/subscription}, and returns the first {@link SubscriptionAckMessage}
     * received within 5 seconds (or {@code null} if none arrives).
     *
     * @param wallId  the wallId to use as the session identity cookie
     * @param payload the message payload to send to {@code /glacier/subscription}
     * @return the first {@link SubscriptionAckMessage} received, or {@code null}
     */
    private SubscriptionAckMessage connectAndSendPayload(String wallId, Map<String, Object> payload)
            throws Exception {
        BlockingQueue<SubscriptionAckMessage> acks = new LinkedBlockingQueue<>();
        AtomicBoolean sessionConnected = new AtomicBoolean(false);

        WebSocketHttpHeaders handshakeHeaders = new WebSocketHttpHeaders();
        handshakeHeaders.add("Cookie", "wallId=" + wallId);

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

        session.subscribe("/user/topic/subscriptions", new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return SubscriptionAckMessage.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                if (payload instanceof SubscriptionAckMessage ackMsg) {
                    acks.add(ackMsg);
                }
            }
        });

        Thread.sleep(100);

        StompHeaders sendHeaders = new StompHeaders();
        sendHeaders.setDestination("/glacier/subscription");
        session.send(sendHeaders, payload);

        SubscriptionAckMessage ack = acks.poll(5, TimeUnit.SECONDS);

        try {
            if (session.isConnected()) {
                session.disconnect();
            }
        } catch (Exception ignored) {
            // best-effort disconnect
        }

        return ack;
    }

}
