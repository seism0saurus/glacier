package de.seism0saurus.glacier.webservice.security;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;

import java.net.URI;
import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ShareLinkRequiredHandshakeInterceptor} (NF1).
 *
 * <p>The {@code /share-view-ws} upgrade must be rejected outright when no {@code shareLinkId}
 * query parameter is present, so that link-less connections never establish an idle WebSocket
 * session (OWASP API4:2023 — Unrestricted Resource Consumption). A legitimate viewer always
 * navigates with {@code ?shareLinkId=<id>}.
 */
class ShareLinkRequiredHandshakeInterceptorTest {

    private final ShareLinkRequiredHandshakeInterceptor interceptor = new ShareLinkRequiredHandshakeInterceptor();
    private final WebSocketHandler wsHandler = mock(WebSocketHandler.class);

    private boolean before(final String uri) {
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        when(request.getURI()).thenReturn(URI.create(uri));
        ServerHttpResponse response = mock(ServerHttpResponse.class);
        return interceptor.beforeHandshake(request, response, wsHandler, new HashMap<>());
    }

    @Test
    void rejectsWhenShareLinkIdParamAbsent() {
        assertThat(before("ws://localhost/share-view-ws")).isFalse();
    }

    @Test
    void rejectsWhenShareLinkIdParamBlank() {
        assertThat(before("ws://localhost/share-view-ws?shareLinkId=")).isFalse();
    }

    @Test
    void allowsWhenShareLinkIdParamPresent() {
        assertThat(before("ws://localhost/share-view-ws?shareLinkId=AbC123_-xyz")).isTrue();
    }

    @Test
    void setsForbiddenStatusOnReject() {
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        when(request.getURI()).thenReturn(URI.create("ws://localhost/share-view-ws"));
        ServerHttpResponse response = mock(ServerHttpResponse.class);

        boolean allowed = interceptor.beforeHandshake(request, response, wsHandler, new HashMap<>());

        assertThat(allowed).isFalse();
        verify(response).setStatusCode(HttpStatus.FORBIDDEN);
    }
}
