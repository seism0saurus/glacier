package de.seism0saurus.glacier.share.web;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Factory for the share CSRF token cookie.
 *
 * <p>The CSRF token is NOT HttpOnly (JS must be able to read it for the double-submit
 * pattern). It is Secure per transport mode, SameSite=Strict, Path=/share.
 *
 * <p>Security: SR-SHARE-05, SR-SHARE-12.
 * References: OWASP CSRF Prevention Cheat Sheet.
 */
@Component
public class CsrfTokenCookieFactory {

    private static final int TOKEN_BYTES = 32; // 256 bits
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final boolean secureCookies;

    public CsrfTokenCookieFactory(
            @Value("${glacier.cookie.secure:true}") final boolean secureCookies) {
        this.secureCookies = secureCookies;
    }

    /**
     * Generates a new CSRF token and adds the cookie to the response.
     *
     * @param response the HTTP response to add the cookie to
     * @return the generated token string (must be stored in response for the client to echo back)
     */
    public String issueCsrfToken(final HttpServletResponse response) {
        byte[] bytes = new byte[TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        String token = ENCODER.encodeToString(bytes);

        String cookieName = secureCookies ? ShareCsrfGuard.CSRF_COOKIE_SECURE : ShareCsrfGuard.CSRF_COOKIE_INSECURE;
        Cookie cookie = new Cookie(cookieName, token);
        cookie.setPath("/share");
        cookie.setMaxAge(3600); // 1 hour — short-lived CSRF token
        cookie.setHttpOnly(false); // must be readable by JS for double-submit pattern
        // SameSite=Strict is the strongest — prevents cross-site requests entirely
        // This is set via response header since Cookie API doesn't support SameSite directly
        if (secureCookies) {
            cookie.setSecure(true);
        }

        response.addCookie(cookie);
        // Set SameSite=Strict via Set-Cookie header manipulation
        response.addHeader("Set-Cookie",
                cookieName + "=" + token
                        + "; Path=/share"
                        + "; SameSite=Strict"
                        + "; Max-Age=3600"
                        + (secureCookies ? "; Secure" : ""));

        return token;
    }
}
