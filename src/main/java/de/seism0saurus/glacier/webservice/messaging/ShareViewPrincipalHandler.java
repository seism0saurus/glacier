package de.seism0saurus.glacier.webservice.messaging;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

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
 * <p>Reads the {@code __Host-shareViewerId} cookie and uses it as the principal.
 * Mints a new viewer ID if the cookie is absent, blank, too short, or missing the
 * mandatory {@value #SV_PREFIX} prefix.
 *
 * <p>Security controls (ADR-SHARE-04, ADR-SHARE-05, SR-SHARE-06, SR-SHARE-07):
 * <ul>
 *   <li>Only reads {@code __Host-shareViewerId} — ignores {@code wallId}
 *       even if both cookies are present (prevents cross-endpoint privilege escalation).</li>
 *   <li>Validates {@code sv_} prefix to prevent namespace collision with wallId principals
 *       in rate-limiters and audit logs (ADR-SHARE-05).</li>
 *   <li>Minimum token length {@value #MIN_VIEWER_ID_LENGTH} chars to prevent brute-force.</li>
 *   <li>New IDs generated via {@link SecureRandom} (256 bits entropy, URL-safe base64).</li>
 * </ul>
 *
 * <p>Caller note: the minted cookie is written to the HTTP response before the WS upgrade;
 * Spring's {@link DefaultHandshakeHandler} executes the handshake within the HTTP request
 * context so we have access to the {@link HttpServletResponse}.
 */
public class ShareViewPrincipalHandler extends DefaultHandshakeHandler {

    private static final Logger log = LoggerFactory.getLogger(ShareViewPrincipalHandler.class);
    private static final Logger AUDIT = LoggerFactory.getLogger("AUDIT");

    /** Cookie name for share viewer identity. Uses __Host- prefix in secure mode (OWASP A05). */
    public static final String COOKIE_NAME_SECURE = "__Host-shareViewerId";
    public static final String COOKIE_NAME_INSECURE = "shareViewerId";

    /** Required prefix on all shareViewerId values — prevents namespace collision with wallId. */
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
                        // Invalid cookie — mint fresh, log at AUDIT with scrubbed info
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
            // Non-servlet request — mint anonymous share viewer ID
            viewerId = mintNewShareViewerId();
        }

        final String finalViewerId = viewerId;
        return () -> finalViewerId;
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
        // Validate the token part after sv_ is URL-safe base64
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
}
