package de.seism0saurus.glacier.webservice.messaging;

import de.seism0saurus.glacier.share.domain.ShareLinkId;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpSession;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
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
 * <p>Security controls (ADR-SHARE-04, ADR-SHARE-05 revised, SR-SHARE-06, SR-SHARE-07):
 * <ul>
 *   <li>Returns {@link ShareViewerPrincipal} — typed principal that structurally cannot
 *       collide with {@link WallPrincipal} in cache or rate-limit bucket maps (ADR-SHARE-05).</li>
 *   <li>Only reads {@code __Host-shareViewerId} — ignores {@code wallId} even if present.</li>
 *   <li>{@code sv_} prefix on cookie VALUES retained as log/observability aid only;
 *       authorization is enforced by the type, not the prefix (ADR-SHARE-05 revised).</li>
 *   <li>Minimum token length {@value #MIN_VIEWER_ID_LENGTH} chars to prevent brute-force.</li>
 *   <li>New IDs generated via {@link SecureRandom} (256 bits entropy, URL-safe base64).</li>
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

    public ShareViewPrincipalHandler(boolean secureCookies) {
        this.secureCookies = secureCookies;
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

        // ADR-SHARE-05 revised: return typed ShareViewerPrincipal bound to the share link.
        // The ShareViewTopicAuthInterceptor validates the shareLinkId is ACTIVE on SUBSCRIBE.
        return new ShareViewerPrincipal(viewerId, boundShareLinkId);
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
