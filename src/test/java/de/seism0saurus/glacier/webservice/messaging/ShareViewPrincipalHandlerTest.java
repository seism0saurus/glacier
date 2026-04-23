package de.seism0saurus.glacier.webservice.messaging;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;

import java.net.URI;
import java.security.Principal;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ShareViewPrincipalHandler}.
 *
 * <p>Security requirements: SR-SHARE-06 (viewer cookie isolation),
 * SR-SHARE-07 (__Host-shareViewerId correct flags),
 * ADR-SHARE-04 (dedicated WS endpoint),
 * ADR-SHARE-05 revised (typed ShareViewerPrincipal, not sv_ prefix convention).
 */
@ExtendWith(MockitoExtension.class)
class ShareViewPrincipalHandlerTest {

    private ShareViewPrincipalHandler handler;

    @Mock
    private WebSocketHandler wsHandler;

    @BeforeEach
    void setUp() {
        handler = new ShareViewPrincipalHandler(true);
    }

    @Test
    void validShareViewerIdCookieIsUsedAsPrincipal() {
        String viewerId = "sv_" + "A".repeat(43);
        ServerHttpRequest request = requestWithCookie("__Host-shareViewerId", viewerId);
        Map<String, Object> attrs = new HashMap<>();

        Principal principal = handler.determineUser(request, wsHandler, attrs);

        assertThat(principal).isNotNull();
        assertThat(principal.getName()).isEqualTo(viewerId);
        // ADR-SHARE-05 revised: must be typed ShareViewerPrincipal, not raw lambda
        assertThat(principal).isInstanceOf(ShareViewerPrincipal.class);
    }

    @Test
    void missingCookieMintsNewShareViewerId() {
        ServerHttpRequest request = requestWithNoCookies();
        Map<String, Object> attrs = new HashMap<>();

        Principal principal = handler.determineUser(request, wsHandler, attrs);

        assertThat(principal).isNotNull();
        assertThat(principal.getName()).startsWith("sv_");
        assertThat(principal.getName().length()).isGreaterThanOrEqualTo(46);
        assertThat(principal).isInstanceOf(ShareViewerPrincipal.class);
    }

    @Test
    void shortCookieValueMintsNewShareViewerId() {
        ServerHttpRequest request = requestWithCookie("__Host-shareViewerId", "sv_short");
        Map<String, Object> attrs = new HashMap<>();

        Principal principal = handler.determineUser(request, wsHandler, attrs);

        assertThat(principal).isNotNull();
        assertThat(principal.getName()).startsWith("sv_");
        assertThat(principal.getName()).isNotEqualTo("sv_short");
        assertThat(principal).isInstanceOf(ShareViewerPrincipal.class);
    }

    @Test
    void cookieMissingPrefixMintsNewShareViewerId() {
        // wallId-style cookie (no sv_ prefix) must be rejected on this endpoint
        String wallIdStyleCookie = "a1b2c3d4-e5f6-7890-abcd-ef1234567890";
        ServerHttpRequest request = requestWithCookie("__Host-shareViewerId", wallIdStyleCookie);
        Map<String, Object> attrs = new HashMap<>();

        Principal principal = handler.determineUser(request, wsHandler, attrs);

        assertThat(principal).isNotNull();
        assertThat(principal.getName()).startsWith("sv_");
        assertThat(principal.getName()).isNotEqualTo(wallIdStyleCookie);
    }

    @Test
    void wallIdCookiePresentAlongsideShareViewerIdIgnoresWallId() {
        String viewerId = "sv_" + "B".repeat(43);
        ServerHttpRequest request = requestWithTwoCookies(
                "wallId", "regular-wallid-uuid-1234567890abcdef",
                "__Host-shareViewerId", viewerId
        );
        Map<String, Object> attrs = new HashMap<>();

        Principal principal = handler.determineUser(request, wsHandler, attrs);

        assertThat(principal.getName()).isEqualTo(viewerId);
        assertThat(principal.getName()).startsWith("sv_");
        assertThat(principal).isInstanceOf(ShareViewerPrincipal.class);
    }

    @Test
    void mintsNewIdWithCorrectPrefixAndMinimumLength() {
        ServerHttpRequest request = requestWithNoCookies();
        Map<String, Object> attrs = new HashMap<>();

        Principal principal = handler.determineUser(request, wsHandler, attrs);

        String name = principal.getName();
        assertThat(name).startsWith("sv_");
        assertThat(name.length()).isGreaterThanOrEqualTo(46);
        String token = name.substring(3);
        assertThat(token).matches("[A-Za-z0-9_-]+");
    }

    @Test
    void twoSuccessiveMintedIdsAreDistinct() {
        Principal p1 = handler.determineUser(requestWithNoCookies(), wsHandler, new HashMap<>());
        Principal p2 = handler.determineUser(requestWithNoCookies(), wsHandler, new HashMap<>());

        assertThat(p1.getName()).isNotEqualTo(p2.getName());
    }

    // -----------------------------------------------------------------------
    // Helpers — mock requests that also stub getURI() (needed by extractShareLinkId)
    // -----------------------------------------------------------------------

    private static final URI DEFAULT_URI = URI.create("ws://localhost/share-view-ws");

    private ServerHttpRequest requestWithCookie(String name, String value) {
        Cookie cookie = new Cookie(name, value);
        HttpServletRequest servletRequest = mock(HttpServletRequest.class);
        when(servletRequest.getCookies()).thenReturn(new Cookie[]{cookie});
        when(servletRequest.getSession()).thenReturn(mock(jakarta.servlet.http.HttpSession.class));
        ServletServerHttpRequest serverHttpRequest = mock(ServletServerHttpRequest.class);
        when(serverHttpRequest.getServletRequest()).thenReturn(servletRequest);
        when(serverHttpRequest.getURI()).thenReturn(DEFAULT_URI);
        return serverHttpRequest;
    }

    private ServerHttpRequest requestWithTwoCookies(String name1, String value1, String name2, String value2) {
        Cookie c1 = new Cookie(name1, value1);
        Cookie c2 = new Cookie(name2, value2);
        HttpServletRequest servletRequest = mock(HttpServletRequest.class);
        when(servletRequest.getCookies()).thenReturn(new Cookie[]{c1, c2});
        when(servletRequest.getSession()).thenReturn(mock(jakarta.servlet.http.HttpSession.class));
        ServletServerHttpRequest serverHttpRequest = mock(ServletServerHttpRequest.class);
        when(serverHttpRequest.getServletRequest()).thenReturn(servletRequest);
        when(serverHttpRequest.getURI()).thenReturn(DEFAULT_URI);
        return serverHttpRequest;
    }

    private ServerHttpRequest requestWithNoCookies() {
        HttpServletRequest servletRequest = mock(HttpServletRequest.class);
        when(servletRequest.getCookies()).thenReturn(null);
        when(servletRequest.getSession()).thenReturn(mock(jakarta.servlet.http.HttpSession.class));
        ServletServerHttpRequest serverHttpRequest = mock(ServletServerHttpRequest.class);
        when(serverHttpRequest.getServletRequest()).thenReturn(servletRequest);
        when(serverHttpRequest.getURI()).thenReturn(DEFAULT_URI);
        return serverHttpRequest;
    }
}
