package de.seism0saurus.glacier.webservice.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;

/**
 * Rejects {@code /share-view-ws} WebSocket upgrades that carry no {@code shareLinkId}
 * query parameter (NF1, OWASP API4:2023 — Unrestricted Resource Consumption).
 *
 * <p>Why a {@link HandshakeInterceptor} and not the handshake handler:
 * {@code ShareViewPrincipalHandler.determineUser()} returning {@code null} does <em>not</em>
 * reject the upgrade — Spring's {@code DefaultHandshakeHandler} then establishes a session
 * with an anonymous principal. Only a {@code HandshakeInterceptor.beforeHandshake()} returning
 * {@code false} actually aborts the upgrade so that <em>no</em> WebSocket session is created.
 *
 * <p>A link-less connection has no legitimate use on this viewer-only endpoint — a real viewer
 * always navigates to {@code /share/{id}} and connects with {@code ?shareLinkId=<id>}. Rejecting
 * at handshake time prevents an attacker from holding idle sockets that would otherwise survive
 * until the read timeout. Connection <em>frequency</em> remains bounded by the
 * {@link HandshakeRateLimitInterceptor} registered ahead of this one, which also caps the volume
 * of the AUDIT line emitted here.
 *
 * <p>Note: this guards the absent/blank case. A syntactically-present id is left to
 * {@code ShareViewPrincipalHandler} (resolve + cap) and {@code ShareViewTopicAuthInterceptor}
 * (per-link SUBSCRIBE authorization) to validate.
 */
@Component
public class ShareLinkRequiredHandshakeInterceptor implements HandshakeInterceptor {

    private static final Logger AUDIT = LoggerFactory.getLogger("AUDIT");

    /** Query parameter carrying the share link id on the upgrade URI. */
    static final String SHARE_LINK_ID_PARAM = "shareLinkId";

    @Override
    public boolean beforeHandshake(final ServerHttpRequest request,
                                   final ServerHttpResponse response,
                                   final WebSocketHandler wsHandler,
                                   final Map<String, Object> attributes) {
        final String shareLinkId = UriComponentsBuilder.fromUri(request.getURI())
                .build()
                .getQueryParams()
                .getFirst(SHARE_LINK_ID_PARAM);

        if (shareLinkId == null || shareLinkId.isBlank()) {
            // Abort the upgrade — no session is established (fail-closed).
            response.setStatusCode(HttpStatus.FORBIDDEN);
            // Bounded by the upstream HandshakeRateLimitInterceptor; no raw values logged (D-13/SR-8).
            AUDIT.info("viewer.handshake_rejected reason=missing_share_link_id_param");
            return false;
        }
        return true;
    }

    @Override
    public void afterHandshake(final ServerHttpRequest request,
                               final ServerHttpResponse response,
                               final WebSocketHandler wsHandler,
                               final Exception exception) {
        // no-op
    }
}
