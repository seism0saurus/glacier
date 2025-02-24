package de.seism0saurus.glacier.webservice.messaging;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpSession;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

import java.security.Principal;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;

/**
 * PrincipalHandler is a custom handshake handler that extends the DefaultHandshakeHandler
 * to determine the user principal for WebSocket connections.
 *
 * During the WebSocket handshake process, this class extracts a specific user identifier
 * (wallId) from HTTP cookies and assigns it as the principal name. Additionally, the HTTP
 * session ID is stored in the attributes for further use.
 *
 * The principal defines the identity of the user for the duration of the session, allowing
 * secure communication and message routing in WebSocket-based applications.
 */
public class PrincipalHandler extends DefaultHandshakeHandler {
    private static final String PRINCIPAL = "principal";
    public static final String SESSION_ID = "sessionId";

    @Override
    protected Principal determineUser(@NotNull ServerHttpRequest request, @NotNull WebSocketHandler wsHandler, @NotNull Map<String, Object> attributes) {
        String wallId;
        Optional<Cookie> wallIdOptional = Optional.empty();

        if (request instanceof ServletServerHttpRequest servletRequest) {
            HttpSession session = servletRequest.getServletRequest().getSession();
            attributes.put(SESSION_ID, session.getId());

            if (servletRequest.getServletRequest().getCookies() != null) {
                wallIdOptional = Arrays.stream(servletRequest
                                .getServletRequest()
                                .getCookies())
                        .filter(cookie -> cookie.getName().equals("wallId"))
                        .findAny();
            }
        }

        if (wallIdOptional.isPresent()) {
            wallId = wallIdOptional.get().getValue();
        } else {
            wallId = "";
        }
        attributes.put(PRINCIPAL, wallId);
        return new Principal() {
            @Override
            public String getName() {
                return wallId;
            }
        };
    }
}