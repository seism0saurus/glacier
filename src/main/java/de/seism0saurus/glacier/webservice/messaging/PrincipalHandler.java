package de.seism0saurus.glacier.webservice.messaging;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpSession;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

import java.security.Principal;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * PrincipalHandler is a custom handshake handler that extends the DefaultHandshakeHandler
 * to determine the user principal for WebSocket connections.
 * <p>
 * During the WebSocket handshake process, this class extracts a specific user identifier
 * (wallId) from HTTP cookies and assigns it as the principal name. Additionally, the HTTP
 * session ID is stored in the attributes for further use.
 * <p>
 * The principal defines the identity of the user for the duration of the session, allowing
 * secure communication and message routing in WebSocket-based applications.
 */
public class PrincipalHandler extends DefaultHandshakeHandler {
    private static final String PRINCIPAL = "principal";
    public static final String SESSION_ID = "sessionId";

    /**
     * Dedicated AUDIT logger for security-relevant events (D-13, SR-8, FIX B).
     * Routes {@code websocket.auth.fail} events for invalid/short wallId cookies.
     */
    private static final Logger AUDIT = LoggerFactory.getLogger("AUDIT");

    /**
     * Minimum required length for a {@code wallId} cookie to be accepted as a valid principal.
     * Mirrors {@code CookieBasedFallbackAuthGuard.MIN_WALL_ID_LENGTH} — both enforce the
     * same threshold so the HTTP and WebSocket paths are symmetric (FIX B, D-09, SR-2).
     */
    public static final int MIN_WALL_ID_LENGTH = 32;

    /**
     * Determines the WebSocket principal from the {@code wallId} cookie.
     *
     * <p>Security hardening (FIX B, D-09, OWASP API1 BOLA, SR-2):
     * <ul>
     *   <li>Missing cookie → fresh random UUID (prevents attacker from forcing collisions
     *       by omitting the cookie entirely).</li>
     *   <li>Empty or too-short cookie (< {@link #MIN_WALL_ID_LENGTH} chars) → fresh random
     *       UUID (closes the BOLA-asymmetry gap where HTTP guard rejects but WS accepted
     *       the same invalid cookie).</li>
     *   <li>An AUDIT event is emitted for every validation failure (no raw cookie value
     *       logged — D-13, SR-8).</li>
     *   <li>Valid cookie passes through unchanged so that existing sessions retain their
     *       principal and MessageCache subscriptions.</li>
     * </ul>
     */
    @Override
    protected Principal determineUser(@NotNull ServerHttpRequest request, @NotNull WebSocketHandler wsHandler, @NotNull Map<String, Object> attributes) {
        String sessionId = "unknown";
        Optional<Cookie> wallIdOptional = Optional.empty();

        if (request instanceof ServletServerHttpRequest servletRequest) {
            HttpSession session = servletRequest.getServletRequest().getSession();
            sessionId = session.getId();
            attributes.put(SESSION_ID, sessionId);

            if (servletRequest.getServletRequest().getCookies() != null) {
                wallIdOptional = Arrays.stream(servletRequest
                                .getServletRequest()
                                .getCookies())
                        .filter(cookie -> cookie.getName().equals("wallId"))
                        .findAny();
            }
        }

        final String wallId;
        if (wallIdOptional.isEmpty()) {
            // No wallId cookie — generate a fresh UUID to prevent cross-principal collision
            // (FIX B, D-09, SR-2: symmetric with CookieBasedFallbackAuthGuard rejection)
            AUDIT.info("websocket.auth.fail reason=missing_cookie sessionId={}", sessionId);
            wallId = UUID.randomUUID().toString();
        } else {
            String rawValue = wallIdOptional.get().getValue();
            if (rawValue == null || rawValue.isBlank() || rawValue.length() < MIN_WALL_ID_LENGTH) {
                // Cookie present but invalid — generate fresh UUID to prevent collision
                // Never log the raw cookie value (D-13, SR-8)
                String reason = rawValue == null ? "null_value"
                        : rawValue.isBlank() ? "blank_value"
                        : "short_value(" + rawValue.length() + ")";
                AUDIT.info("websocket.auth.fail reason={} sessionId={}", reason, sessionId);
                wallId = UUID.randomUUID().toString();
            } else {
                // Valid wallId — use as-is so MessageCache lookups succeed
                wallId = rawValue;
            }
        }

        attributes.put(PRINCIPAL, wallId);
        // ADR-SHARE-05 (revised): return typed WallPrincipal rather than a raw lambda.
        // The sealed hierarchy prevents a ShareViewerPrincipal-forged string from
        // polluting the wall's cache/rate-limit namespace.
        return new WallPrincipal(wallId);
    }
}