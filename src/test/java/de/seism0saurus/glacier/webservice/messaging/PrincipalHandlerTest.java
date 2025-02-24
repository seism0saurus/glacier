package de.seism0saurus.glacier.webservice.messaging;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;

import java.security.Principal;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class PrincipalHandlerTest {

    @Test
    void testDetermineUserWithWallIdCookie() {
        // Arrange
        PrincipalHandler principalHandler = new PrincipalHandler();
        HttpServletRequest httpServletRequest = mock(HttpServletRequest.class);
        HttpSession httpSession = mock(HttpSession.class);
        ServletServerHttpRequest serverRequest = mock(ServletServerHttpRequest.class);
        WebSocketHandler wsHandler = mock(WebSocketHandler.class);

        Cookie[] cookies = {new Cookie("wallId", "testWallId")};
        Map<String, Object> attributes = new HashMap<>();

        when(serverRequest.getServletRequest()).thenReturn(httpServletRequest);
        when(httpServletRequest.getSession()).thenReturn(httpSession);
        when(httpSession.getId()).thenReturn("testSessionId");
        when(httpServletRequest.getCookies()).thenReturn(cookies);

        // Act
        Principal result = principalHandler.determineUser(serverRequest, wsHandler, attributes);

        // Assert
        assert result != null;
        assertEquals("testWallId", result.getName());
        assertEquals("testWallId", attributes.get("principal"));
        assertEquals("testSessionId", attributes.get("sessionId"));
    }

    @Test
    void testDetermineUserWithoutWallIdCookie() {
        // Arrange
        PrincipalHandler principalHandler = new PrincipalHandler();
        HttpServletRequest httpServletRequest = mock(HttpServletRequest.class);
        HttpSession httpSession = mock(HttpSession.class);
        ServletServerHttpRequest serverRequest = mock(ServletServerHttpRequest.class);
        WebSocketHandler wsHandler = mock(WebSocketHandler.class);

        Cookie[] cookies = {};
        Map<String, Object> attributes = new HashMap<>();

        when(serverRequest.getServletRequest()).thenReturn(httpServletRequest);
        when(httpServletRequest.getSession()).thenReturn(httpSession);
        when(httpSession.getId()).thenReturn("testSessionId");
        when(httpServletRequest.getCookies()).thenReturn(cookies);

        // Act
        Principal result = principalHandler.determineUser(serverRequest, wsHandler, attributes);

        // Assert
        assert result != null;
        assertEquals("", result.getName());
        assertEquals("", attributes.get("principal"));
        assertEquals("testSessionId", attributes.get("sessionId"));
    }

    @Test
    void testDetermineUserWithNoCookiesInRequest() {
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

        // Assert
        assert result != null;
        assertEquals("", result.getName());
        assertEquals("", attributes.get("principal"));
        assertEquals("testSessionId", attributes.get("sessionId"));
    }
}