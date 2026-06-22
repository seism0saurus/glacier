package de.seism0saurus.glacier.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.annotation.DirtiesContext.ClassMode;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import social.bigbone.MastodonClient;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test proving NF1 end-to-end: a {@code /share-view-ws} WebSocket upgrade that
 * carries NO {@code shareLinkId} query parameter is rejected at the handshake by
 * {@link de.seism0saurus.glacier.webservice.security.ShareLinkRequiredHandshakeInterceptor},
 * so that no idle WebSocket session is ever established (OWASP API4:2023).
 *
 * <p>The present-{@code shareLinkId} happy path is covered by
 * {@link ShareViewRemoteAddrProductionPathIT}; here we assert only the fail-closed rejection.
 *
 * <p>Mode applicability: live (real {@code /share-view-ws} endpoint). Failsafe IT ({@code *IT.java}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        // Plain cookie name compatible with the non-TLS test server.
        "glacier.cookie.secure=false"
})
@DirtiesContext(classMode = ClassMode.AFTER_EACH_TEST_METHOD)
class ShareViewLinkRequiredHandshakeIT {

    private static final String VIEWER_ID_COOKIE_VALUE = "sv_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

    @LocalServerPort
    private int port;

    @MockitoBean
    @SuppressWarnings("unused")
    private MastodonClient mastodonClient;

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

    @Test
    void handshakeWithoutShareLinkId_isRejected() {
        // No shareLinkId query parameter — the NF1 interceptor must abort the upgrade (HTTP 403),
        // so connectAsync().get() completes exceptionally rather than establishing a session.
        String wsUrl = "ws://localhost:" + port + "/share-view-ws";
        WebSocketHttpHeaders handshakeHeaders = new WebSocketHttpHeaders();
        handshakeHeaders.add("Cookie", "shareViewerId=" + VIEWER_ID_COOKIE_VALUE);

        assertThatThrownBy(() ->
                stompClient.connectAsync(
                        wsUrl,
                        handshakeHeaders,
                        new StompHeaders(),
                        new StompSessionHandlerAdapter() { }
                ).get(5, TimeUnit.SECONDS))
                .as("a /share-view-ws handshake without a shareLinkId must be rejected (no session established)")
                .isInstanceOf(Exception.class);
    }
}
