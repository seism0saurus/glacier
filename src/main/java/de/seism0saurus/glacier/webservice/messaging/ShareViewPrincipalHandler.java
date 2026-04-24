package de.seism0saurus.glacier.webservice.messaging;

import de.seism0saurus.glacier.share.application.ShareLinkViewerCounter;
import de.seism0saurus.glacier.share.domain.ShareLinkCapPolicy;
import de.seism0saurus.glacier.share.domain.ShareLinkId;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpSession;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

import java.net.URI;
import java.security.Principal;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

import static de.seism0saurus.glacier.util.LogScrubber.hash8;

/**
 * Handshake handler for the viewer-only {@code /share-view-ws} STOMP endpoint.
 *
 * <p>Reads the {@code __Host-shareViewerId} cookie and uses it as the viewer identity.
 * Mints a new viewer ID if the cookie is absent, blank, too short, or missing the
 * mandatory {@value #SV_PREFIX} prefix.
 *
 * <p>Extracts the {@code shareLinkId} query parameter from the WebSocket upgrade URI
 * and binds it to the returned {@link ShareViewerPrincipal}. The interceptor
 * ({@link de.seism0saurus.glacier.share.web.ShareViewTopicAuthInterceptor}) uses this
 * bound link ID to enforce that the viewer may only subscribe to their share's topics.
 *
 * <p>Enforces the per-link viewer cap (SR-SHARE-05, OWASP API4 — Unrestricted Resource
 * Consumption): at handshake time, the counter for the bound share link is incremented
 * and checked against {@link ShareLinkCapPolicy#getMaxViewersPerLink()}. If the cap is
 * exceeded the counter is refunded and the handshake is rejected (fail-closed).
 * On session disconnect the counter is decremented so the slot is freed for new viewers.
 *
 * <p>Security controls (ADR-SHARE-04, ADR-SHARE-05 revised, SR-SHARE-05, SR-SHARE-06, SR-SHARE-07):
 * <ul>
 *   <li>Returns {@link ShareViewerPrincipal} — typed principal that structurally cannot
 *       collide with {@link WallPrincipal} in cache or rate-limit bucket maps (ADR-SHARE-05).</li>
 *   <li>Only reads {@code __Host-shareViewerId} — ignores {@code wallId} even if present.</li>
 *   <li>{@code sv_} prefix on cookie VALUES retained as log/observability aid only;
 *       authorization is enforced by the type, not the prefix (ADR-SHARE-05 revised).</li>
 *   <li>Minimum token length {@value #MIN_VIEWER_ID_LENGTH} chars to prevent brute-force.</li>
 *   <li>New IDs generated via {@link SecureRandom} (256 bits entropy, URL-safe base64).</li>
 *   <li>Only {@link ShareViewerPrincipal} disconnects decrement the counter — {@link WallPrincipal}
 *       sessions are never counted (glacier-fallback-mode-discipline: namespace isolation).</li>
 * </ul>
 */
public class ShareViewPrincipalHandler extends DefaultHandshakeHandler {

    private static final Logger log = LoggerFactory.getLogger(ShareViewPrincipalHandler.class);
    private static final Logger AUDIT = LoggerFactory.getLogger("AUDIT");

    /** Cookie name for share viewer identity. Uses __Host- prefix in secure mode (OWASP A05). */
    public static final String COOKIE_NAME_SECURE = "__Host-shareViewerId";
    public static final String COOKIE_NAME_INSECURE = "shareViewerId";

    /**
     * Prefix on shareViewerId cookie VALUES — retained as log/observability aid.
     * Authorization is enforced by the {@link ShareViewerPrincipal} type, not this prefix.
     * (ADR-SHARE-05 revised)
     */
    public static final String SV_PREFIX = "sv_";

    /** Minimum total length of a valid shareViewerId (sv_ + 43 base64url chars = 46). */
    public static final int MIN_VIEWER_ID_LENGTH = 46;

    /** Token entropy: 32 bytes = 256 bits. */
    private static final int TOKEN_BYTES = 32;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Base64.Encoder BASE64_URL = Base64.getUrlEncoder().withoutPadding();

    private final boolean secureCookies;
    private final ShareLinkViewerCounter viewerCounter;
    private final ShareLinkCapPolicy capPolicy;

    /**
     * Constructs the handler with viewer-cap enforcement.
     *
     * @param secureCookies  whether to use the {@code __Host-} cookie prefix (production mode)
     * @param viewerCounter  per-link viewer session counter (SR-SHARE-05)
     * @param capPolicy      cap configuration; provides {@link ShareLinkCapPolicy#getMaxViewersPerLink()}
     */
    public ShareViewPrincipalHandler(
            final boolean secureCookies,
            final ShareLinkViewerCounter viewerCounter,
            final ShareLinkCapPolicy capPolicy) {
        this.secureCookies = secureCookies;
        this.viewerCounter = viewerCounter;
        this.capPolicy = capPolicy;
    }

    @Override
    protected Principal determineUser(
            @NotNull ServerHttpRequest request,
            @NotNull WebSocketHandler wsHandler,
            @NotNull Map<String, Object> attributes) {

        String cookieName = secureCookies ? COOKIE_NAME_SECURE : COOKIE_NAME_INSECURE;
        String viewerId = null;

        // Extract shareLinkId from query parameter (e.g. /share-view-ws?shareLinkId=sv_xxx)
        ShareLinkId boundShareLinkId = extractShareLinkId(request.getURI());

        if (request instanceof ServletServerHttpRequest servletRequest) {
            HttpSession session = servletRequest.getServletRequest().getSession();
            attributes.put(PrincipalHandler.SESSION_ID, session.getId());

            Cookie[] cookies = servletRequest.getServletRequest().getCookies();
            if (cookies != null) {
                Optional<Cookie> viewerCookieOpt = Arrays.stream(cookies)
                        .filter(c -> cookieName.equals(c.getName()))
                        .findFirst();
                if (viewerCookieOpt.isPresent()) {
                    String raw = viewerCookieOpt.get().getValue();
                    if (isValidShareViewerId(raw)) {
                        viewerId = raw;
                    } else {
                        String reason = raw == null ? "null"
                                : raw.isBlank() ? "blank"
                                : !raw.startsWith(SV_PREFIX) ? "missing_sv_prefix"
                                : "too_short(" + raw.length() + ")";
                        AUDIT.info("viewer.handshake_rejected reason={} viewerId-hash={}", reason, hash8(raw));
                    }
                }
            }

            if (viewerId == null) {
                viewerId = mintNewShareViewerId();
                AUDIT.info("viewer.handshake new_viewer_id viewerId-hash={}", hash8(viewerId));
            }
        } else {
            viewerId = mintNewShareViewerId();
        }

        // SR-SHARE-05 / OWASP API4: enforce the per-link viewer cap at handshake time.
        // Increment first; if the new count exceeds the cap, refund and reject (fail-closed).
        int newCount = viewerCounter.increment(boundShareLinkId);
        int maxViewers = capPolicy.getMaxViewersPerLink();
        if (newCount > maxViewers) {
            // Refund the slot so the count remains accurate for future requests.
            viewerCounter.decrement(boundShareLinkId);
            AUDIT.info("viewer.handshake_rejected reason=cap_exceeded shareId-hash={} viewerId-hash={} cap={} count={}",
                    boundShareLinkId.hash8(), hash8(viewerId), maxViewers, newCount);
            // Returning null causes DefaultHandshakeHandler to reject the WebSocket upgrade
            // with HTTP 403, preventing the STOMP session from being established (fail-closed).
            return null;
        }

        // ADR-SHARE-05 revised: return typed ShareViewerPrincipal bound to the share link.
        // The ShareViewTopicAuthInterceptor validates the shareLinkId is ACTIVE on SUBSCRIBE.
        return new ShareViewerPrincipal(viewerId, boundShareLinkId);
    }

    /**
     * Decrements the viewer counter for a share link when a viewer STOMP session ends.
     *
     * <p>glacier-fallback-mode-discipline: only {@link ShareViewerPrincipal} disconnects
     * trigger a decrement — {@link WallPrincipal} sessions are fully isolated and never
     * tracked by this counter.
     *
     * @param event the Spring WebSocket disconnect event
     */
    @EventListener
    public void onDisconnect(final SessionDisconnectEvent event) {
        Principal user = event.getUser();
        if (!(user instanceof ShareViewerPrincipal viewerPrincipal)) {
            // WallPrincipal or null — not our concern; namespace isolation preserved.
            return;
        }
        ShareLinkId shareLinkId = viewerPrincipal.boundShareLinkId();
        viewerCounter.decrement(shareLinkId);
        AUDIT.info("viewer.disconnect viewerId-hash={} shareId-hash={}",
                hash8(viewerPrincipal.getName()), shareLinkId.hash8());
    }

    /**
     * Validates a shareViewerId value.
     *
     * @return true if the value is non-null, starts with {@value #SV_PREFIX},
     *         is at least {@value #MIN_VIEWER_ID_LENGTH} chars long, and
     *         contains only URL-safe base64 characters after the prefix.
     */
    public static boolean isValidShareViewerId(final String value) {
        if (value == null || value.isBlank()) return false;
        if (!value.startsWith(SV_PREFIX)) return false;
        if (value.length() < MIN_VIEWER_ID_LENGTH) return false;
        String token = value.substring(SV_PREFIX.length());
        return token.matches("[A-Za-z0-9_-]+");
    }

    /** Generates a new secure shareViewerId: {@value #SV_PREFIX} + 256-bit random base64url. */
    public static String mintNewShareViewerId() {
        byte[] bytes = new byte[TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return SV_PREFIX + BASE64_URL.encodeToString(bytes);
    }

    /** Returns the appropriate cookie name based on transport security mode. */
    public String cookieName() {
        return secureCookies ? COOKIE_NAME_SECURE : COOKIE_NAME_INSECURE;
    }

    /**
     * Extracts the {@code shareLinkId} query parameter from the WebSocket upgrade URI and
     * constructs a {@link ShareLinkId}. Returns a sentinel "unbound" share link ID if the
     * parameter is absent or invalid — the interceptor will reject SUBSCRIBE attempts for
     * an unbound viewer.
     */
    private static ShareLinkId extractShareLinkId(final URI uri) {
        if (uri == null) return unboundShareLinkId();
        String query = uri.getQuery();
        if (query == null || query.isBlank()) return unboundShareLinkId();
        for (String param : query.split("&")) {
            if (param.startsWith("shareLinkId=")) {
                String raw = param.substring("shareLinkId=".length());
                try {
                    return ShareLinkId.fromUrlPath(raw);
                } catch (IllegalArgumentException e) {
                    // Invalid format — return unbound; interceptor will reject SUBSCRIBE
                    return unboundShareLinkId();
                }
            }
        }
        return unboundShareLinkId();
    }

    /**
     * Sentinel share link ID used when no valid {@code shareLinkId} is present in the
     * upgrade request. The interceptor rejects any SUBSCRIBE from a principal bound to
     * this value because it cannot match any real share link.
     */
    private static ShareLinkId unboundShareLinkId() {
        // A well-formed but semantically empty ID that no real link can match.
        // 43 URL-safe base64 chars of zeros — cannot be a real share link.
        return ShareLinkId.fromUrlPath("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
    }
}
