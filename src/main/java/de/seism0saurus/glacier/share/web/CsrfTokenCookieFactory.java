package de.seism0saurus.glacier.share.web;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;

/**
 * Factory for the share CSRF token cookie.
 *
 * <p>The CSRF token is NOT HttpOnly (JS must be able to read it for the double-submit
 * pattern). It is Secure per transport mode, SameSite=Strict, Path=/ (secure) or
 * Path=/share (insecure/dev).
 *
 * <p>I-CSRF-1 (SR-CSRF-13): exactly one Set-Cookie header is emitted per call.
 * Achieved by using {@link ResponseCookie} exclusively — the former dual-emission
 * (addCookie + addHeader) is eliminated. ADR-1, ADR-2.
 *
 * <p>Security: SR-SHARE-05, SR-SHARE-12, SR-CSRF-13.
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
     * <p>Emits exactly one {@code Set-Cookie} header via {@link ResponseCookie} (ADR-1, ADR-2).
     * The former pattern of calling both {@code response.addCookie()} and a manual
     * raw-string {@code addHeader} produced two headers for the same cookie name —
     * a violation of RFC 6265 and invariant I-CSRF-1.
     *
     * @param response the HTTP response to add the cookie to
     * @return the generated token string (must be stored in response for the client to echo back)
     */
    public String issueCsrfToken(final HttpServletResponse response) {
        byte[] bytes = new byte[TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        String token = ENCODER.encodeToString(bytes);

        String cookieName = secureCookies ? ShareCsrfGuard.CSRF_COOKIE_SECURE : ShareCsrfGuard.CSRF_COOKIE_INSECURE;

        // OWASP A05: __Host- prefix requires Path=/ (RFC 6265bis §4.1.3).
        // In secure mode the cookie uses the __Host- prefix so Path MUST be /.
        // In insecure mode (dev/test) we scope to /share.
        // SR-SHARE-07, Finding 8.
        String cookiePath = secureCookies ? "/" : "/share";

        // ADR-1: use ResponseCookie (not jakarta.servlet.http.Cookie) to emit a single
        // canonical Set-Cookie header that includes SameSite=Strict.
        // ADR-2: ResponseCookie.toString() co-emits Expires= alongside Max-Age for
        // HTTP/1.0 proxy compatibility — callers must not assert on the Expires= value.
        // C5: HttpOnly=false is intentional — the double-submit pattern requires JS to read
        // this token and echo it in the X-Share-CSRF request header. (SR-SHARE-05)
        ResponseCookie csrfCookie = ResponseCookie.from(cookieName, token)
                .path(cookiePath)
                .maxAge(Duration.ofSeconds(3600))
                .sameSite("Strict")
                .secure(secureCookies)
                .httpOnly(false)
                .build();

        // I-CSRF-1 (SR-CSRF-13): exactly one Set-Cookie header emitted — no addCookie() call.
        response.addHeader(HttpHeaders.SET_COOKIE, csrfCookie.toString());

        return token;
    }
}
