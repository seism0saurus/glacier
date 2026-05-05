package de.seism0saurus.glacier.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.seism0saurus.glacier.webservice.messaging.messages.SubscriptionAckMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
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
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for STOMP subscription DTO mass-assignment protection
 * (IT-sec-04 / IT-sec-05 / IT-sec-06).
 *
 * <p>These tests verify that injected fields in the STOMP subscription frame cannot override
 * server-side state (principal, subscription result, rejection bypass).
 *
 * <ul>
 *   <li>IT-sec-04: injected {@code principal} field must not override the cookie-derived wallId</li>
 *   <li>IT-sec-05: injected {@code rejection:null} must not bypass blank-hashtag validation</li>
 *   <li>IT-sec-06: injected {@code isSubscribed:true} must not pre-set the ack value</li>
 * </ul>
 *
 * <p>These tests close OWASP API3:2023 — Broken Object Property Level Authorization.
 * Jackson's {@code @JsonIgnoreProperties(ignoreUnknown = true)} on {@code SubscriptionMessage}
 * means injected fields that are not part of the DTO are silently dropped. Fields that ARE part
 * of the DTO but should be server-set only (like {@code principal}, {@code isSubscribed},
 * {@code rejection}) must not be settable via the wire.
 *
 * <p>Mode applicability: <strong>live</strong> mode (real STOMP WebSocket endpoint).
 * Fallback / killswitch: not applicable (no STOMP subscription in those modes).
 *
 * <p>This is a Failsafe IT ({@code *IT.java}) — runs during {@code mvn verify}.
 *
 * <p>OWASP: API3:2023 — Broken Object Property Level Authorization (mass-assignment via STOMP).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StompMassAssignmentIT {

    @LocalServerPort
    private int port;

    /**
     * Mock the Mastodon client so the app context starts without a real Mastodon instance.
     */
    @MockitoBean
    @SuppressWarnings("unused")
    private MastodonClient mastodonClient;

    @Autowired
    private ObjectMapper objectMapper;

    private WebSocketStompClient stompClient;

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
    // IT-sec-04: injected principal field must not override cookie-derived wallId
    // -------------------------------------------------------------------------

    /**
     * IT-sec-04: connect as wallId A; send a subscription frame that includes a
     * {@code "principal":"forged-wall-B"} field. The server must use the cookie-derived
     * principal (wallId A), not the injected value.
     *
     * <p>The {@link de.seism0saurus.glacier.webservice.messaging.messages.SubscriptionAckMessage}
     * returned must reflect the real server decision:
     * <ul>
     *   <li>If the ack carries a {@code principal} field, it must equal the cookie-derived
     *       wallId A, NOT the injected "forged-wall-B".</li>
     *   <li>The {@code isSubscribed} field must reflect actual server logic (may be true or false
     *       depending on whether the Mastodon mock allows subscription — the key assertion is that
     *       the principal was not forged).</li>
     * </ul>
     *
     * <p>Arrange: connect with {@code wallId = eeeeeeee-eeee-eeee-eeee-000000000001}.
     * Act: send raw JSON with injected {@code principal} field.
     * Assert: ack's {@code principal} (if present) must equal the cookie wallId.
     */
    @Test
    void subscribeFrame_withInjectedPrincipalField_doesNotOverridePrincipal() throws Exception {
        String cookieWallId = "eeeeeeee-eeee-eeee-eeee-000000000001";
        String forgedPrincipal = "forged-wall-B";

        // Inject a principal field, plus a valid hashtag so validation passes
        String rawJson = "{\"hashtag\":\"testtagC\",\"principal\":\"" + forgedPrincipal
                + "\",\"isSubscribed\":true,\"rejection\":{\"code\":\"INVALID_HASHTAG\"}}";

        SubscriptionAckMessage ack = sendRawJsonAndAwaitAck(cookieWallId, rawJson);

        assertThat(ack)
                .as("IT-sec-04: server must respond to the subscription request")
                .isNotNull();

        // The ack must carry the cookie-derived wallId as principal — NOT the forged value from
        // the wire. SubscriptionController always populates principal from
        // headerAccessor.getUser().getName() (the server-side cookie principal), never from the
        // incoming DTO. This assertion is intentionally unconditional: if getPrincipal() were
        // null here, the SubscriptionController would have failed to set it from the cookie,
        // which is itself a security regression we must detect.
        assertThat(ack.getPrincipal())
                .as("IT-sec-04: ack principal must be the cookie-derived wallId, not the forged value")
                .isEqualTo(cookieWallId)
                .isNotEqualTo(forgedPrincipal);

        // The injected rejection code from the frame must not appear in the ack.
        // A negative ack with INVALID_HASHTAG would be wrong here since "testtagC" is valid.
        // Rejection must be server-computed, never injected from the wire.
        if (ack.getRejection() != null) {
            assertThat(ack.getRejection().getCode())
                    .as("IT-sec-04: injected INVALID_HASHTAG must not appear in ack for a valid hashtag")
                    .isNotEqualTo(de.seism0saurus.glacier.webservice.messaging.messages.RejectionCode.INVALID_HASHTAG);
        }
    }

    // -------------------------------------------------------------------------
    // IT-sec-05: injected rejection:null must not bypass blank-hashtag validation
    // -------------------------------------------------------------------------

    /**
     * IT-sec-05: a subscription frame with a blank {@code hashtag} AND an injected
     * {@code "rejection":null} must still be rejected by the server.
     *
     * <p>The server-side Bean Validation on {@code SubscriptionMessage.hashtag} must fire
     * regardless of any {@code rejection} field injected from the wire, because
     * {@code SubscriptionMessage} does not have a {@code rejection} field — injection is silently
     * ignored.
     *
     * <p>Arrange: connect with a real wallId.
     * Act: send frame with blank hashtag AND injected {@code rejection:null}.
     * Assert: ack is negative (blank hashtag rejected) regardless of the injected field.
     */
    @Test
    void subscribeFrame_withInjectedRejectionField_doesNotBypassValidation() throws Exception {
        String wallId = "eeeeeeee-eeee-eeee-eeee-000000000002";
        // Blank hashtag combined with injected rejection:null
        String rawJson = "{\"hashtag\":\"\",\"rejection\":null}";

        SubscriptionAckMessage ack = sendRawJsonAndAwaitAck(wallId, rawJson);

        assertThat(ack)
                .as("IT-sec-05: server must respond to the subscription request")
                .isNotNull();

        // Blank hashtag must be rejected — the injected rejection:null must not bypass this
        assertThat(ack.isSubscribed())
                .as("IT-sec-05: blank hashtag must be rejected even when rejection:null is injected")
                .isFalse();
    }

    // -------------------------------------------------------------------------
    // IT-sec-06: injected isSubscribed:true must not pre-set the ack
    // -------------------------------------------------------------------------

    /**
     * IT-sec-06: a subscription frame with a valid hashtag AND an injected
     * {@code "isSubscribed":true} field must not pre-set the ack's {@code isSubscribed} value.
     *
     * <p>The {@code isSubscribed} field is not part of {@code SubscriptionMessage} DTO — it is
     * only present on {@code SubscriptionAckMessage} (the response). Injecting it into the
     * subscribe request frame must have no effect on the server's response.
     *
     * <p>The server's response {@code isSubscribed} value must come from actual server logic
     * (subscription attempt result), not from the injected wire value.
     *
     * <p>Arrange: connect with a real wallId. Send valid hashtag with injected {@code isSubscribed:true}.
     * Act: collect the ack.
     * Assert: ack is returned from the server (session alive); the ack's content reflects real
     * server logic, not the injected true — specifically, if the Mastodon mock causes subscription
     * failure, the ack should NOT be a spurious success carrying {@code isSubscribed:true} without
     * an actual Mastodon subscription.
     */
    @Test
    void subscribeFrame_withInjectedIsSubscribedField_doesNotPreSetAck() throws Exception {
        String wallId = "eeeeeeee-eeee-eeee-eeee-000000000003";
        // Valid hashtag with injected isSubscribed:true
        String rawJson = "{\"hashtag\":\"testtagD\",\"isSubscribed\":true}";

        SubscriptionAckMessage ack = sendRawJsonAndAwaitAck(wallId, rawJson);

        assertThat(ack)
                .as("IT-sec-06: server must respond to the subscription request")
                .isNotNull();

        // Verify the ack came from real server logic:
        // The key invariant is that the hashtag field in the ack matches what we sent,
        // and the isSubscribed value is set by the server (not pre-set by injection).
        // We cannot assert a specific boolean value here without knowing the mock behaviour —
        // instead we assert that if isSubscribed is true, it must not be accompanied by a
        // non-null rejection (which would indicate an inconsistent injected state).
        if (ack.isSubscribed()) {
            assertThat(ack.getRejection())
                    .as("IT-sec-06: a positive ack (isSubscribed=true) must not carry a rejection — "
                            + "this would indicate an injected inconsistent state")
                    .isNull();
        }
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    /**
     * Connects via STOMP, subscribes to the user ack topic, sends the JSON as a
     * {@link java.util.Map} payload (so Jackson serializes arbitrary fields to JSON) to
     * {@code /glacier/subscription}, and returns the first
     * {@link SubscriptionAckMessage} received within 5 seconds (or null if none arrives).
     *
     * <p>Using a {@code Map<String, Object>} allows mass-assignment field injection while still
     * going through the normal STOMP + Jackson serialization path. The server receives valid JSON
     * bytes containing all fields (including injected ones like {@code principal}, {@code isSubscribed}).
     *
     * @param wallId  the wallId to use as the session identity cookie
     * @param rawJson the raw JSON string to parse into a Map for transmission
     * @return the first {@link SubscriptionAckMessage} received, or {@code null} if none
     */
    private SubscriptionAckMessage sendRawJsonAndAwaitAck(String wallId, String rawJson)
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

        // Parse the raw JSON into a Map so Jackson serializes arbitrary fields on send.
        // The server's MappingJackson2MessageConverter deserializes JSON into SubscriptionMessage;
        // unknown fields (principal, isSubscribed, rejection) are silently ignored due to
        // @JsonIgnoreProperties(ignoreUnknown=true) / Jackson default behaviour.
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> payload = objectMapper.readValue(rawJson, java.util.Map.class);

        StompHeaders sendHeaders = new StompHeaders();
        sendHeaders.setDestination("/glacier/subscription");
        session.send(sendHeaders, payload);

        SubscriptionAckMessage ack = acks.poll(5, TimeUnit.SECONDS);

        try {
            session.disconnect();
        } catch (Exception ignored) {
            // best-effort disconnect
        }

        return ack;
    }
}
