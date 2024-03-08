package de.seism0saurus.glacier.webservice.messaging;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

import java.security.Principal;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;

public class PrincipalHandler extends DefaultHandshakeHandler {
    private static final String PRINCIPAL = "principal";
    public static final String SESSION_ID = "sessionId";

    @Override
    protected Principal determineUser(ServerHttpRequest request, WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String wallId;
        Optional<Cookie> wallIdOptional = Optional.empty();

        if (request instanceof ServletServerHttpRequest) {
            ServletServerHttpRequest servletRequest = (ServletServerHttpRequest) request;
            HttpSession session = servletRequest.getServletRequest().getSession();
            attributes.put(SESSION_ID, session.getId());

            wallIdOptional = Arrays.stream(servletRequest
                            .getServletRequest()
                            .getCookies())
                    .filter(cookie -> cookie.getName().equals("wallId"))
                    .findAny();

        }

        if (wallIdOptional.isPresent()){
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