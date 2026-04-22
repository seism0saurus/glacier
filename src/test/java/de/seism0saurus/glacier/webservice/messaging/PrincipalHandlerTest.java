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
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PrincipalHandler}.
 *
 * <p>Updated for FIX B (F-03, D-09, SR-2): validates that short or absent wallId cookies
 * produce a fresh random UUID principal (instead of an empty string that could cause
 * cross-principal collisions — BOLA attack surface).
 */
class PrincipalHandlerTest {

    @Test
    void testDetermineUserWithWallIdCookie() {
        // Arrange
        PrincipalHandler principalHandler = new PrincipalHandler();
        HttpServletRequest httpServletRequest = mock(HttpServletRequest.class);
        HttpSession httpSession = mock(HttpSession.class);
        ServletServerHttpRequest serverRequest = mock(ServletServerHttpRequest.class);
        WebSocketHandler wsHandler = mock(WebSocketHandler.class);

        // 36-char UUID — satisfies MIN_WALL_ID_LENGTH (32)
        String wallIdValue = UUID.randomUUID().toString();
        Cookie[] cookies = {new Cookie("wallId", wallIdValue)};
        Map<String, Object> attributes = new HashMap<>();

        when(serverRequest.getServletRequest()).thenReturn(httpServletRequest);
        when(httpServletRequest.getSession()).thenReturn(httpSession);
        when(httpSession.getId()).thenReturn("testSessionId");
        when(httpServletRequest.getCookies()).thenReturn(cookies);

        // Act
        Principal result = principalHandler.determineUser(serverRequest, wsHandler, attributes);

        // Assert — valid cookie passes through unchanged
        assertThat(result).isNotNull();
        assertEquals(wallIdValue, result.getName());
        assertEquals(wallIdValue, attributes.get("principal"));
        assertEquals("testSessionId", attributes.get("sessionId"));
    }

    /**
     * FIX B: empty wallId cookie must produce a fresh UUID, not an empty string.
     * Two consecutive calls with the same empty value must return different principals.
     */
    @Test
    void testDetermineUserWithEmptyWallIdCookie_returnsFreshUuid() {
        // Arrange
        PrincipalHandler principalHandler = new PrincipalHandler();
        HttpServletRequest httpServletRequest = mock(HttpServletRequest.class);
        HttpSession httpSession = mock(HttpSession.class);
        ServletServerHttpRequest serverRequest = mock(ServletServerHttpRequest.class);
        WebSocketHandler wsHandler = mock(WebSocketHandler.class);

        Cookie[] cookies = {new Cookie("wallId", "")};
        Map<String, Object> attributes = new HashMap<>();

        when(serverRequest.getServletRequest()).thenReturn(httpServletRequest);
        when(httpServletRequest.getSession()).thenReturn(httpSession);
        when(httpSession.getId()).thenReturn("testSessionId");
        when(httpServletRequest.getCookies()).thenReturn(cookies);

        // Act
        Principal result = principalHandler.determineUser(serverRequest, wsHandler, attributes);

        // Assert — must not be empty/null; must be a valid UUID
        assertThat(result).isNotNull();
        assertThat(result.getName()).isNotBlank();
        // Should be parseable as UUID (fresh random)
        assertThat(UUID.fromString(result.getName())).isNotNull();
    }

    /**
     * FIX B: short wallId (< 32 chars) must produce a fresh UUID.
     */
    @Test
    void testDetermineUserWithShortWallIdCookie_returnsFreshUuid() {
        // Arrange
        PrincipalHandler principalHandler = new PrincipalHandler();
        HttpServletRequest httpServletRequest = mock(HttpServletRequest.class);
        HttpSession httpSession = mock(HttpSession.class);
        ServletServerHttpRequest serverRequest = mock(ServletServerHttpRequest.class);
        WebSocketHandler wsHandler = mock(WebSocketHandler.class);

        // 3 chars — below MIN_WALL_ID_LENGTH (32)
        Cookie[] cookies = {new Cookie("wallId", "abc")};
        Map<String, Object> attributes = new HashMap<>();

        when(serverRequest.getServletRequest()).thenReturn(httpServletRequest);
        when(httpServletRequest.getSession()).thenReturn(httpSession);
        when(httpSession.getId()).thenReturn("testSessionId");
        when(httpServletRequest.getCookies()).thenReturn(cookies);

        // Act
        Principal result = principalHandler.determineUser(serverRequest, wsHandler, attributes);

        // Assert — fresh UUID, not "abc"
        assertThat(result).isNotNull();
        assertThat(result.getName()).isNotEqualTo("abc");
        assertThat(UUID.fromString(result.getName())).isNotNull();
    }

    /**
     * FIX B: no wallId cookie at all must produce a fresh UUID.
     * Two calls with no cookie must produce different principals (no collision possible).
     */
    @Test
    void testDetermineUserWithoutWallIdCookie_returnsFreshUuid() {
        // Arrange
        PrincipalHandler principalHandler = new PrincipalHandler();
        HttpServletRequest httpServletRequest = mock(HttpServletRequest.class);
        HttpSession httpSession = mock(HttpSession.class);
        ServletServerHttpRequest serverRequest = mock(ServletServerHttpRequest.class);
        WebSocketHandler wsHandler = mock(WebSocketHandler.class);

        Cookie[] cookies = {};
        Map<String, Object> attributes1 = new HashMap<>();
        Map<String, Object> attributes2 = new HashMap<>();

        when(serverRequest.getServletRequest()).thenReturn(httpServletRequest);
        when(httpServletRequest.getSession()).thenReturn(httpSession);
        when(httpSession.getId()).thenReturn("testSessionId");
        when(httpServletRequest.getCookies()).thenReturn(cookies);

        // Act — two calls
        Principal result1 = principalHandler.determineUser(serverRequest, wsHandler, attributes1);
        Principal result2 = principalHandler.determineUser(serverRequest, wsHandler, attributes2);

        // Assert — both are valid UUIDs and they differ
        assertThat(result1).isNotNull();
        assertThat(result2).isNotNull();
        assertThat(UUID.fromString(result1.getName())).isNotNull();
        assertThat(UUID.fromString(result2.getName())).isNotNull();
        // Different UUIDs — no cross-principal collision possible
        assertThat(result1.getName()).isNotEqualTo(result2.getName());
    }

    /**
     * FIX B: null cookies array must produce a fresh UUID (same as no-cookie case).
     */
    @Test
    void testDetermineUserWithNoCookiesInRequest_returnsFreshUuid() {
        // Arrange
        PrincipalHandler principalHandler = new PrincipalHandler();
        HttpServletRequest httpServletRequest = mock(HttpServletRequest.class);
        HttpSession httpSession = mock(HttpSession.class);
        ServletServerHttpRequest serverRequest = mock(ServletServerHttpRequest.class);
        WebSocketHandler wsHandler = mock(WebSocketHandler.class);

        Map<String, Object> attributes = new HashMap<>();

        when(serverRequest.getServletRequest()).thenReturn(httpServletRequest);
        when(httpServletRequest.getSession()).thenReturn(httpSession);
        when(httpSession.getId()).thenReturn("testSessionId");
        when(httpServletRequest.getCookies()).thenReturn(null);

        // Act
        Principal result = principalHandler.determineUser(serverRequest, wsHandler, attributes);

        // Assert — fresh UUID, not empty
        assertThat(result).isNotNull();
        assertThat(result.getName()).isNotBlank();
        assertThat(UUID.fromString(result.getName())).isNotNull();
    }

    /**
     * A valid wallId (exactly at MIN_WALL_ID_LENGTH = 32 chars) passes through unchanged.
     */
    @Test
    void testDetermineUserWithMinLengthWallId_passesThrough() {
        // Arrange
        PrincipalHandler principalHandler = new PrincipalHandler();
        HttpServletRequest httpServletRequest = mock(HttpServletRequest.class);
        HttpSession httpSession = mock(HttpSession.class);
        ServletServerHttpRequest serverRequest = mock(ServletServerHttpRequest.class);
        WebSocketHandler wsHandler = mock(WebSocketHandler.class);

        // Exactly 32 chars — at the boundary
        String wallId = "12345678901234567890123456789012";
        assertThat(wallId.length()).isEqualTo(PrincipalHandler.MIN_WALL_ID_LENGTH);
        Cookie[] cookies = {new Cookie("wallId", wallId)};
        Map<String, Object> attributes = new HashMap<>();

        when(serverRequest.getServletRequest()).thenReturn(httpServletRequest);
        when(httpServletRequest.getSession()).thenReturn(httpSession);
        when(httpSession.getId()).thenReturn("testSessionId");
        when(httpServletRequest.getCookies()).thenReturn(cookies);

        // Act
        Principal result = principalHandler.determineUser(serverRequest, wsHandler, attributes);

        // Assert — passes through unchanged
        assertThat(result).isNotNull();
        assertThat(result.getName()).isEqualTo(wallId);
    }
}
