package de.seism0saurus.glacier.webservice.messaging;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;

import java.security.Principal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Integration test for BOLA-protection in {@link PrincipalHandler}.
 *
 * <p>FIX B (F-03, D-09, OWASP API1 BOLA): Two parallel STOMP handshakes with identical
 * short or empty {@code wallId} cookies must NOT receive the same principal. When the
 * cookie is absent, empty, or below the minimum length, {@link PrincipalHandler} must
 * generate a fresh random UUID so that cross-principal collision is statistically
 * impossible while the STOMP handshake still succeeds.
 *
 * <p>Named {@code *IT.java} for Failsafe inclusion; the test itself runs purely in-process
 * (no container needed) because {@link PrincipalHandler#determineUser} is a simple method.
 */
class WebSocketPrincipalValidationIT {

    /**
     * Two sessions with an empty {@code wallId} cookie must receive different principals
     * (fresh-UUID branch — collision probability ~ 1/2^122).
     *
     * <p>This test closes the BOLA-asymmetry gap: an attacker sending
     * {@code Cookie: wallId=} can no longer force both sessions to share
     * a single subscription namespace.
     */
    @Test
    void determineUser_emptyWallId_twoSessions_receiveDifferentPrincipals() {
        PrincipalHandler handler = new PrincipalHandler();

        Principal p1 = invokeHandlerWithCookie("", "session-1");
        Principal p2 = invokeHandlerWithCookie("", "session-2");

        assertThat(p1).isNotNull();
        assertThat(p2).isNotNull();
        // Fresh UUIDs — must differ to prevent cross-principal collision
        assertThat(p1.getName()).isNotEqualTo(p2.getName());
    }

    /**
     * Two sessions with a short (< 32 char) {@code wallId} cookie must receive different
     * principals.
     */
    @Test
    void determineUser_shortWallId_twoSessions_receiveDifferentPrincipals() {
        PrincipalHandler handler = new PrincipalHandler();

        Principal p1 = invokeHandlerWithCookie("abc", "session-1");
        Principal p2 = invokeHandlerWithCookie("abc", "session-2");

        assertThat(p1).isNotNull();
        assertThat(p2).isNotNull();
        assertThat(p1.getName()).isNotEqualTo(p2.getName());
    }

    /**
     * Two sessions with no {@code wallId} cookie at all must receive different principals.
     */
    @Test
    void determineUser_noCookie_twoSessions_receiveDifferentPrincipals() {
        PrincipalHandler handler = new PrincipalHandler();

        Principal p1 = invokeHandlerWithNoCookies("session-1");
        Principal p2 = invokeHandlerWithNoCookies("session-2");

        assertThat(p1).isNotNull();
        assertThat(p2).isNotNull();
        assertThat(p1.getName()).isNotEqualTo(p2.getName());
    }

    /**
     * A valid (≥32 char) {@code wallId} cookie must pass through unchanged.
     */
    @Test
    void determineUser_validWallId_returnsCookieValueAsIs() {
        PrincipalHandler handler = new PrincipalHandler();
        String wallId = UUID.randomUUID().toString(); // 36 chars — satisfies ≥32

        Principal p = invokeHandlerWithCookie(wallId, "session-x");

        assertThat(p).isNotNull();
        assertThat(p.getName()).isEqualTo(wallId);
    }

    /**
     * Fresh-UUID generated for an invalid wallId must itself be UUID-format (valid principal).
     */
    @Test
    void determineUser_invalidWallId_freshUuidIsParseable() {
        PrincipalHandler handler = new PrincipalHandler();

        Principal p = invokeHandlerWithCookie("short", "session-y");

        assertThat(p).isNotNull();
        // Should not throw
        assertThat(UUID.fromString(p.getName())).isNotNull();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Principal invokeHandlerWithCookie(String wallIdValue, String sessionId) {
        PrincipalHandler handler = new PrincipalHandler();
        ServletServerHttpRequest serverRequest = mock(ServletServerHttpRequest.class);
        HttpServletRequest servletRequest = mock(HttpServletRequest.class);
        HttpSession session = mock(HttpSession.class);
        WebSocketHandler wsHandler = mock(WebSocketHandler.class);
        Map<String, Object> attributes = new HashMap<>();

        when(serverRequest.getServletRequest()).thenReturn(servletRequest);
        when(servletRequest.getSession()).thenReturn(session);
        when(session.getId()).thenReturn(sessionId);
        Cookie cookie = new Cookie("wallId", wallIdValue);
        when(servletRequest.getCookies()).thenReturn(new Cookie[]{cookie});

        return handler.determineUser(serverRequest, wsHandler, attributes);
    }

    private Principal invokeHandlerWithNoCookies(String sessionId) {
        PrincipalHandler handler = new PrincipalHandler();
        ServletServerHttpRequest serverRequest = mock(ServletServerHttpRequest.class);
        HttpServletRequest servletRequest = mock(HttpServletRequest.class);
        HttpSession session = mock(HttpSession.class);
        WebSocketHandler wsHandler = mock(WebSocketHandler.class);
        Map<String, Object> attributes = new HashMap<>();

        when(serverRequest.getServletRequest()).thenReturn(servletRequest);
        when(servletRequest.getSession()).thenReturn(session);
        when(session.getId()).thenReturn(sessionId);
        when(servletRequest.getCookies()).thenReturn(null);

        return handler.determineUser(serverRequest, wsHandler, attributes);
    }
}
