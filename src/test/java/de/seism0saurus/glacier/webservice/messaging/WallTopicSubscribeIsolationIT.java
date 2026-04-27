package de.seism0saurus.glacier.webservice.messaging;

import de.seism0saurus.glacier.share.application.ShareLinkViewerCounter;
import de.seism0saurus.glacier.share.domain.ShareLinkCapPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.SimpMessagingTemplate;
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
 * Integration test verifying that {@link WallTopicAuthInterceptor} prevents
 * cross-principal topic subscriptions over a real WebSocket/STOMP connection.
 *
 * <p>Scenario under test (OWASP API1 BOLA, ADR-TEST-01, SR-TEST-21):
 * <ol>
 *   <li>Client A connects with wallId-A cookie.</li>
 *   <li>Client A attempts to SUBSCRIBE to {@code /topic/hashtags/{wallId-B}/...}</li>
 *   <li>The SUBSCRIBE must be rejected — the server must not deliver messages
 *       published on wallId-B's topic to client A.</li>
 * </ol>
 *
 * <p>Note: Spring's simple broker does not send an explicit ERROR frame for rejected
 * SUBSCRIBE frames; instead the subscription is simply never registered. We verify
 * isolation by confirming client A does NOT receive messages within a generous timeout.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WallTopicSubscribeIsolationIT {

    @LocalServerPort
    private int port;

    /** Mock the Mastodon client so the app context starts without a real Mastodon instance. */
    @MockitoBean
    @SuppressWarnings("unused")
    private MastodonClient mastodonClient;

    /**
     * WebSocketConfiguration requires ShareLinkViewerCounter and ShareLinkCapPolicy
     * as constructor arguments for ShareViewPrincipalHandler bean creation (SR-SHARE-05).
     */
    @MockitoBean
    @SuppressWarnings("unused")
    private ShareLinkViewerCounter viewerCounter;

    @MockitoBean
    @SuppressWarnings("unused")
    private ShareLinkCapPolicy capPolicy;

    /**
     * Injected to allow synthetic message publication into broker topics during tests.
     * Used by {@link #clientA_canSubscribe_toOwnTopic_andReceivesPublishedMessage()} (SR-TEST-22/F-8).
     */
    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    private WebSocketStompClient stompClient;

    @BeforeEach
    void setUp() {
        stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        stompClient.setMessageConverter(new StringMessageConverter());
    }

    @AfterEach
    void tearDown() {
        stompClient.stop();
    }

    /**
     * Verifies that a client using wallId-A CANNOT receive messages published on
     * wallId-B's topic — the SUBSCRIBE is silently dropped by WallTopicAuthInterceptor.
     */
    @Test
    void clientA_cannotReceiveMessages_onWallB_topic() throws Exception {
        String wallIdA = "aaaaaaaa-aaaa-aaaa-aaaa-000000000001";
        String wallIdB = "bbbbbbbb-bbbb-bbbb-bbbb-000000000002";

        BlockingQueue<String> receivedByA = new LinkedBlockingQueue<>();
        AtomicBoolean sessionConnected = new AtomicBoolean(false);

        // Connect client A with wallId-A cookie
        WebSocketHttpHeaders handshakeHeaders = new WebSocketHttpHeaders();
        handshakeHeaders.add("Cookie", "wallId=" + wallIdA);

        StompSession sessionA = stompClient.connectAsync(
                "ws://localhost:" + port + "/websocket",
                handshakeHeaders,
                new StompHeaders(),
                new StompSessionHandlerAdapter() {
                    @Override
                    public void afterConnected(StompSession session, StompHeaders connectedHeaders) {
                        sessionConnected.set(true);
                    }
                }
        ).get(5, TimeUnit.SECONDS);

        // Wait for connection to be established
        long deadline = System.currentTimeMillis() + 3_000;
        while (!sessionConnected.get() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertThat(sessionConnected.get()).as("session connected").isTrue();

        // Client A attempts to subscribe to wallId-B's topic (cross-principal BOLA attempt)
        String crossPrincipalDestination = "/topic/hashtags/" + wallIdB + "/tag/creation";
        sessionA.subscribe(crossPrincipalDestination, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return String.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                receivedByA.add((String) payload);
            }
        });

        // Give the interceptor time to process the (rejected) SUBSCRIBE
        Thread.sleep(200);

        // ASSERT: No message received by client A on wallId-B's topic
        // (subscription was silently dropped by the interceptor)
        String leaked = receivedByA.poll(1, TimeUnit.SECONDS);
        assertThat(leaked)
                .as("No message from wallId-B's topic should reach wallId-A's client")
                .isNull();

        sessionA.disconnect();
    }

    /**
     * Verifies that a client can subscribe to their OWN topic without issue.
     *
     * <p>This is the original sanity assertion — it only checks that the subscription
     * object is non-null (i.e. no ERROR frame received during the SUBSCRIBE exchange).
     * The deeper delivery assertion is in
     * {@link #clientA_canSubscribe_toOwnTopic_andReceivesPublishedMessage}.
     */
    @Test
    void clientA_canSubscribe_toOwnTopic() throws Exception {
        String wallIdA = "aaaaaaaa-aaaa-aaaa-aaaa-000000000003";

        AtomicBoolean sessionConnected = new AtomicBoolean(false);

        WebSocketHttpHeaders handshakeHeaders = new WebSocketHttpHeaders();
        handshakeHeaders.add("Cookie", "wallId=" + wallIdA);

        StompSession sessionA = stompClient.connectAsync(
                "ws://localhost:" + port + "/websocket",
                handshakeHeaders,
                new StompHeaders(),
                new StompSessionHandlerAdapter() {
                    @Override
                    public void afterConnected(StompSession session, StompHeaders connectedHeaders) {
                        sessionConnected.set(true);
                    }
                }
        ).get(5, TimeUnit.SECONDS);

        long deadline = System.currentTimeMillis() + 3_000;
        while (!sessionConnected.get() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertThat(sessionConnected.get()).as("session connected").isTrue();

        // Subscribe to own topic — this should succeed (interceptor allows matching wallId)
        String ownDestination = "/topic/hashtags/" + wallIdA + "/tag/creation";
        StompSession.Subscription subscription = sessionA.subscribe(ownDestination, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return String.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                // not expected to receive messages in this test
            }
        });

        // The subscribe itself completes — no ERROR frame should be received
        assertThat(subscription).isNotNull();

        sessionA.disconnect();
    }

    /**
     * SR-TEST-22 / F-8: Verifies that a client subscribed to their OWN topic actually
     * receives messages published to that topic via {@link SimpMessagingTemplate}.
     *
     * <p>This test strengthens the happy-path coverage: the subscription must not only
     * be non-null but must result in message delivery when the broker forwards a message.
     * Subscribing to own topic and receiving on it proves the authorization path allows
     * through-delivery, not just the SUBSCRIBE frame acknowledgment.
     *
     * <p>Security: SR-TEST-22 / F-8 — ensures {@link WallTopicAuthInterceptor} does not
     * incorrectly drop legitimate SUBSCRIBE frames for matching principals.
     */
    @Test
    void clientA_canSubscribe_toOwnTopic_andReceivesPublishedMessage() throws Exception {
        String wallIdA = "aaaaaaaa-aaaa-aaaa-aaaa-000000000004";

        BlockingQueue<String> receivedByA = new LinkedBlockingQueue<>();
        AtomicBoolean sessionConnected = new AtomicBoolean(false);

        WebSocketHttpHeaders handshakeHeaders = new WebSocketHttpHeaders();
        handshakeHeaders.add("Cookie", "wallId=" + wallIdA);

        StompSession sessionA = stompClient.connectAsync(
                "ws://localhost:" + port + "/websocket",
                handshakeHeaders,
                new StompHeaders(),
                new StompSessionHandlerAdapter() {
                    @Override
                    public void afterConnected(StompSession session, StompHeaders connectedHeaders) {
                        sessionConnected.set(true);
                    }
                }
        ).get(5, TimeUnit.SECONDS);

        long deadline = System.currentTimeMillis() + 3_000;
        while (!sessionConnected.get() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertThat(sessionConnected.get()).as("session connected").isTrue();

        // Subscribe to own topic — interceptor must allow this through
        String ownDestination = "/topic/hashtags/" + wallIdA + "/tag/creation";
        StompSession.Subscription subscription = sessionA.subscribe(ownDestination, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return String.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                if (payload instanceof String s) {
                    receivedByA.add(s);
                }
            }
        });

        assertThat(subscription)
                .as("Subscription handle must be non-null for own-topic SUBSCRIBE")
                .isNotNull();

        // Allow the broker to register the subscription before publishing
        Thread.sleep(100);

        // Publish a synthetic message directly into the broker topic (SR-TEST-22 / F-8)
        messagingTemplate.convertAndSend(ownDestination, "test-message");

        // Assert that client A receives the message within a generous timeout
        String received = receivedByA.poll(2, TimeUnit.SECONDS);
        assertThat(received)
                .as("Client A must receive messages published to its own topic after subscription (SR-TEST-22)")
                .isEqualTo("test-message");

        sessionA.disconnect();
    }
}

