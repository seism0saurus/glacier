package de.seism0saurus.glacier.webservice.messaging;

import de.seism0saurus.glacier.share.application.ShareLinkViewerCounter;
import de.seism0saurus.glacier.share.domain.ShareLinkCapPolicy;
import de.seism0saurus.glacier.share.domain.ShareLinkId;
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
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.net.URI;
import java.security.Principal;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ShareViewPrincipalHandler}.
 *
 * <p>Security requirements: SR-SHARE-05 (viewer cap enforcement at handshake),
 * SR-SHARE-06 (viewer cookie isolation),
 * SR-SHARE-07 (__Host-shareViewerId correct flags),
 * ADR-SHARE-04 (dedicated WS endpoint),
 * ADR-SHARE-05 revised (typed ShareViewerPrincipal, not sv_ prefix convention),
 * glacier-fallback-mode-discipline (WallPrincipal disconnects never decrement the counter).
 */
@ExtendWith(MockitoExtension.class)
class ShareViewPrincipalHandlerTest {

    /** A cap policy permitting 2 concurrent viewers — low enough to test cap rejection cheaply. */
    private static final int TEST_CAP = 2;

    private ShareLinkViewerCounter viewerCounter;
    private ShareLinkCapPolicy capPolicy;
    private ShareViewPrincipalHandler handler;

    @Mock
    private WebSocketHandler wsHandler;

    @BeforeEach
    void setUp() {
        viewerCounter = new ShareLinkViewerCounter();
        capPolicy = new ShareLinkCapPolicy();
        capPolicy.setMaxViewersPerLink(TEST_CAP);
        handler = new ShareViewPrincipalHandler(true, viewerCounter, capPolicy);
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
    // SR-SHARE-05: viewer cap enforcement at handshake
    // -----------------------------------------------------------------------

    @Test
    void handshakeAcceptedWhenUnderCap() {
        // First viewer within cap=2 → accepted
        Principal p = handler.determineUser(requestWithNoCookies(), wsHandler, new HashMap<>());
        assertThat(p).isNotNull();
    }

    @Test
    void handshakeAcceptedAtExactlyCapViewers() {
        // Two viewers for cap=2 → both accepted
        Principal p1 = handler.determineUser(requestWithNoCookies(), wsHandler, new HashMap<>());
        Principal p2 = handler.determineUser(requestWithNoCookies(), wsHandler, new HashMap<>());
        assertThat(p1).isNotNull();
        assertThat(p2).isNotNull();
    }

    @Test
    void handshakeRejectedWhenCapExceeded() {
        // Fill the cap
        handler.determineUser(requestWithNoCookies(), wsHandler, new HashMap<>());
        handler.determineUser(requestWithNoCookies(), wsHandler, new HashMap<>());
        // Third viewer must be rejected (null = WebSocket upgrade refused)
        Principal p3 = handler.determineUser(requestWithNoCookies(), wsHandler, new HashMap<>());
        assertThat(p3).isNull();
    }

    @Test
    void counterIsRefundedOnCapRejection() {
        // Fill the cap then try a third — counter must stay at cap, not cap+1
        handler.determineUser(requestWithNoCookies(), wsHandler, new HashMap<>());
        handler.determineUser(requestWithNoCookies(), wsHandler, new HashMap<>());
        // third attempt — rejected
        handler.determineUser(requestWithNoCookies(), wsHandler, new HashMap<>());
        // The unbound sentinel link ID used by requestWithNoCookies() is always the same;
        // counter for it must equal the cap (2), not 3
        ShareLinkId sentinelId = ShareLinkId.fromUrlPath("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
        assertThat(viewerCounter.get(sentinelId)).isEqualTo(TEST_CAP);
    }

    @Test
    void slotIsFreedAfterDisconnectAllowingNewViewer() {
        // Fill cap
        Principal p1 = handler.determineUser(requestWithNoCookies(), wsHandler, new HashMap<>());
        handler.determineUser(requestWithNoCookies(), wsHandler, new HashMap<>());
        // Third attempt should be rejected now
        assertThat(handler.determineUser(requestWithNoCookies(), wsHandler, new HashMap<>())).isNull();

        // Simulate p1 disconnect → frees one slot
        assertThat(p1).isInstanceOf(ShareViewerPrincipal.class);
        handler.onDisconnect(disconnectEventFor(p1));

        // New viewer should now be accepted
        Principal p3 = handler.determineUser(requestWithNoCookies(), wsHandler, new HashMap<>());
        assertThat(p3).isNotNull();
    }

    // -----------------------------------------------------------------------
    // glacier-fallback-mode-discipline: WallPrincipal disconnect must not decrement
    // -----------------------------------------------------------------------

    @Test
    void wallPrincipalDisconnectDoesNotDecrementViewerCounter() {
        // One viewer connected
        handler.determineUser(requestWithNoCookies(), wsHandler, new HashMap<>());
        ShareLinkId sentinelId = ShareLinkId.fromUrlPath("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
        int countBefore = viewerCounter.get(sentinelId);

        // A WallPrincipal disconnects — must NOT affect the viewer counter
        WallPrincipal wallPrincipal = new WallPrincipal("some-wall-id-uuid-1234567890abcdef");
        handler.onDisconnect(disconnectEventFor(wallPrincipal));

        assertThat(viewerCounter.get(sentinelId)).isEqualTo(countBefore);
    }

    @Test
    void nullPrincipalDisconnectDoesNotThrow() {
        // Null principal in the event must be handled gracefully (defensive)
        handler.onDisconnect(disconnectEventFor(null));
        // No exception thrown — counter unchanged
        ShareLinkId sentinelId = ShareLinkId.fromUrlPath("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
        assertThat(viewerCounter.get(sentinelId)).isZero();
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

    /**
     * Builds a minimal {@link SessionDisconnectEvent} carrying the given {@code principal}.
     * Uses Spring's internal STOMP message infrastructure borrowed from
     * {@link de.seism0saurus.glacier.webservice.cache.MessageCacheDisconnectTimerIT}.
     */
    private static SessionDisconnectEvent disconnectEventFor(final Principal principal) {
        org.springframework.messaging.support.MessageBuilder<byte[]> builder =
                org.springframework.messaging.support.MessageBuilder.withPayload(new byte[0]);
        builder.setHeader(org.springframework.messaging.simp.SimpMessageHeaderAccessor.SESSION_ID_HEADER, "test-session");
        org.springframework.messaging.Message<byte[]> msg = builder.build();
        return new SessionDisconnectEvent(new Object(), msg, "test-session", null, principal);
    }
}
