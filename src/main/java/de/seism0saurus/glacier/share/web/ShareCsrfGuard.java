package de.seism0saurus.glacier.share.web;

import de.seism0saurus.glacier.util.LogScrubber;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Optional;

/**
 * CSRF guard for share-link state-changing endpoints.
 *
 * <p>Implements the double-submit cookie pattern:
 * <ol>
 *   <li>Validates the {@code Origin} header matches the expected domain (scheme+host+port exact match).</li>
 *   <li>Compares the {@code X-Share-Csrf-Token} request header against the
 *       {@code __Host-shareCsrf} cookie using constant-time compare
 *       ({@link MessageDigest#isEqual}) to prevent timing side-channels.</li>
 * </ol>
 *
 * <p>The CSRF token is issued by {@link CsrfTokenCookieFactory} and stored in
 * the {@code __Host-shareCsrf} cookie (SameSite=Strict, HttpOnly=false so JS can read it,
 * Secure per transport mode, Path=/share).
 *
 * <p>Security: SR-SHARE-05, SR-SHARE-08, SR-SHARE-12.
 * References: OWASP CSRF Prevention Cheat Sheet, NIST SP 800-53 IA-8.
 */
@Component
public class ShareCsrfGuard {

    private static final Logger AUDIT = LoggerFactory.getLogger("AUDIT");
    private static final Logger log = LoggerFactory.getLogger(ShareCsrfGuard.class);

    /** The CSRF token header name that clients must include. */
    public static final String CSRF_HEADER = "X-Share-CSRF";

    /** The CSRF cookie name. Uses __Host- prefix in secure mode (OWASP A05). */
    public static final String CSRF_COOKIE_SECURE = "__Host-shareCsrf";
    public static final String CSRF_COOKIE_INSECURE = "shareCsrf";

    /** Minimum CSRF token length to ensure it cannot be brute-forced. */
    static final int MIN_TOKEN_LENGTH = 32;

    private final String glacierDomain;
    private final boolean secureCookies;

    public ShareCsrfGuard(
            @Value("${glacier.domain}") final String glacierDomain,
            @Value("${glacier.cookie.secure:true}") final boolean secureCookies) {
        this.glacierDomain = glacierDomain;
        this.secureCookies = secureCookies;
    }

    /**
     * Verifies the CSRF controls for the incoming request.
     *
     * @param request the HTTP request to inspect
     * @return {@code true} if CSRF verification passes; {@code false} otherwise
     */
    public boolean verify(final HttpServletRequest request) {
        // Step 1: Origin check — exact scheme+host+port match (SR-SHARE-08)
        String origin = request.getHeader("Origin");
        if (origin == null || origin.isBlank()) {
            AUDIT.info("share.csrf.fail reason=missing_origin");
            return false;
        }

        String expectedOrigin = (secureCookies ? "https" : "http") + "://" + glacierDomain;
        if (!origin.equals(expectedOrigin)) {
            AUDIT.info("share.csrf.fail reason=origin_mismatch");
            return false;
        }

        // Step 2: Extract header token
        String headerToken = request.getHeader(CSRF_HEADER);
        if (headerToken == null || headerToken.isBlank() || headerToken.length() < MIN_TOKEN_LENGTH) {
            AUDIT.info("share.csrf.fail reason=missing_or_short_header_token");
            return false;
        }

        // Step 3: Extract cookie token
        String cookieName = secureCookies ? CSRF_COOKIE_SECURE : CSRF_COOKIE_INSECURE;
        Optional<String> cookieToken = extractCookieValue(request, cookieName);
        if (cookieToken.isEmpty()) {
            AUDIT.info("share.csrf.fail reason=missing_cookie_token");
            return false;
        }

        // Step 4: Constant-time comparison (SR-SHARE-12, OWASP timing-attack prevention)
        byte[] headerBytes = headerToken.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] cookieBytes = cookieToken.get().getBytes(java.nio.charset.StandardCharsets.UTF_8);

        if (!MessageDigest.isEqual(headerBytes, cookieBytes)) {
            AUDIT.info("share.csrf.fail reason=token_mismatch");
            return false;
        }

        return true;
    }

    private Optional<String> extractCookieValue(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return Optional.empty();
        return Arrays.stream(cookies)
                .filter(c -> name.equals(c.getName()))
                .map(Cookie::getValue)
                .findFirst();
    }
}
