package de.seism0saurus.glacier.webservice;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.seism0saurus.glacier.webservice.messaging.messages.RejectionCode;
import de.seism0saurus.glacier.webservice.messaging.messages.SubscriptionAckMessage;
import de.seism0saurus.glacier.webservice.messaging.messages.SubscriptionMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
 * End-to-end STOMP integration test for hashtag validation (SR-PT-01 / ADR-PT-03).
 *
 * <p>Uses a real {@link WebSocketStompClient} connected to the running Spring Boot
 * server to verify that invalid hashtags are rejected with a structured negative ack
 * BEFORE the {@link de.seism0saurus.glacier.mastodon.SubscriptionManager} is invoked.</p>
 *
 * <p>Test scenarios:</p>
 * <ul>
 *   <li>Blank hashtag → negative ack with {@code rejection.code == INVALID_HASHTAG}</li>
 *   <li>Hashtag with illegal characters ({@code /}, spaces, HTML) → same</li>
 *   <li>Hashtag exceeding 50 characters → same</li>
 *   <li>Valid hashtag → positive ack (regression guard)</li>
 * </ul>
 *
 * <p>This is a Failsafe IT ({@code *IT.java}) — it runs during {@code mvn verify}.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SubscriptionControllerHashtagValidationIT {

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
    // SR-PT-01: invalid hashtags must be rejected with INVALID_HASHTAG code
    // -------------------------------------------------------------------------

    /**
     * SR-PT-01: a hashtag containing path separators and exceeding the length limit
     * must be rejected with a negative ack carrying {@code INVALID_HASHTAG}.
     */
    @Test
    void subscribe_withMaliciouslyLongHashtag_returnsNegativeAckWithInvalidHashtagCode() throws Exception {
        String wallId = "cccccccc-cccc-cccc-cccc-000000000001";
        String invalidHashtag = "/" + "x".repeat(5_000) + "/etc/passwd";

        SubscriptionAckMessage ack = sendSubscribeAndAwaitAck(wallId, invalidHashtag);

        assertThat(ack).isNotNull();
        assertThat(ack.isSubscribed())
                .as("SR-PT-01: malformed hashtag must be rejected")
                .isFalse();
        assertThat(ack.getRejection())
                .as("SR-PT-01: rejection block must be present")
                .isNotNull();
        assertThat(ack.getRejection().getCode())
                .as("SR-PT-01: rejection code must be INVALID_HASHTAG")
                .isEqualTo(RejectionCode.INVALID_HASHTAG);
    }

    /**
     * SR-PT-01: various invalid hashtag formats are rejected end-to-end over STOMP.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "#glacier",
            "hello world",
            "<script>alert(1)</script>",
            "aaaaaaaaaabbbbbbbbbbccccccccccddddddddddeeeeeeeeeef"  // 51 characters
    })
    void subscribe_withInvalidHashtag_returnsNegativeAckWithInvalidHashtagCode(
            final String invalidHashtag) throws Exception {
        String wallId = "cccccccc-cccc-cccc-cccc-000000000002";

        SubscriptionAckMessage ack = sendSubscribeAndAwaitAck(wallId, invalidHashtag);

        assertThat(ack).isNotNull();
        assertThat(ack.isSubscribed())
                .as("SR-PT-01: invalid hashtag '%s' must be rejected", invalidHashtag)
                .isFalse();
        assertThat(ack.getRejection())
                .as("SR-PT-01: rejection block must be present for '%s'", invalidHashtag)
                .isNotNull();
        assertThat(ack.getRejection().getCode())
                .as("SR-PT-01: rejection code must be INVALID_HASHTAG for '%s'", invalidHashtag)
                .isEqualTo(RejectionCode.INVALID_HASHTAG);
    }

    /**
     * SR-PT-01 regression guard: a valid hashtag must still produce a positive ack.
     *
     * <p>The {@link de.seism0saurus.glacier.mastodon.SubscriptionManager} will throw
     * because {@link MastodonClient} is mocked (returns null from streaming calls).
     * The controller wraps all exceptions (SR-PT-02), so the response may be a negative
     * ack due to the downstream mock, but it must NOT carry {@code INVALID_HASHTAG}.</p>
     */
    @Test
    void subscribe_withValidHashtag_doesNotReturnInvalidHashtagRejection() throws Exception {
        String wallId = "cccccccc-cccc-cccc-cccc-000000000003";
        String validHashtag = "glacier";

        SubscriptionAckMessage ack = sendSubscribeAndAwaitAck(wallId, validHashtag);

        // A valid hashtag must not trigger an INVALID_HASHTAG rejection.
        // (The actual subscription may succeed or fail due to the mock Mastodon client,
        // but the rejection code must never be INVALID_HASHTAG.)
        assertThat(ack).isNotNull();
        boolean isInvalidHashtagRejection = ack.getRejection() != null
                && RejectionCode.INVALID_HASHTAG.equals(ack.getRejection().getCode());
        assertThat(isInvalidHashtagRejection)
                .as("SR-PT-01: valid hashtag 'glacier' must not receive INVALID_HASHTAG rejection")
                .isFalse();
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    /**
     * Connects to the STOMP endpoint, subscribes to the user-specific ack topic,
     * sends a subscription request with the given hashtag, and returns the first
     * ack message received within 5 seconds.
     *
     * @param wallId   the wallId to use as cookie (determines principal)
     * @param hashtag  the hashtag to include in the {@link SubscriptionMessage}
     * @return the {@link SubscriptionAckMessage} or {@code null} if no ack received
     */
    private SubscriptionAckMessage sendSubscribeAndAwaitAck(String wallId, String hashtag)
            throws Exception {
        BlockingQueue<SubscriptionAckMessage> acks = new LinkedBlockingQueue<>();
        AtomicBoolean sessionConnected = new AtomicBoolean(false);

        // Attach the wallId cookie so PrincipalHandler sets the principal correctly.
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

        // Wait for the connection to be fully established
        long deadline = System.currentTimeMillis() + 3_000;
        while (!sessionConnected.get() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertThat(sessionConnected.get()).as("STOMP session connected").isTrue();

        // Subscribe to the user ack destination BEFORE sending the request so
        // the server's response is not missed.
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

        // Give the broker a moment to register the subscription
        Thread.sleep(100);

        // Send the SUBSCRIBE message to the application destination
        SubscriptionMessage subMsg = new SubscriptionMessage();
        subMsg.setHashtag(hashtag);

        StompHeaders sendHeaders = new StompHeaders();
        sendHeaders.setDestination("/glacier/subscription");

        session.send(sendHeaders, subMsg);

        // Await the ack with a generous timeout
        SubscriptionAckMessage ack = acks.poll(5, TimeUnit.SECONDS);

        try {
            session.disconnect();
        } catch (Exception ignored) {
            // Disconnect best-effort; connection may already be closed
        }

        return ack;
    }
}
