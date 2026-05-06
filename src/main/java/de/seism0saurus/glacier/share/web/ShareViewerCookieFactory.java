package de.seism0saurus.glacier.share.web;

import de.seism0saurus.glacier.webservice.messaging.ShareViewPrincipalHandler;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Factory for the {@code __Host-shareViewerId} viewer identity cookie.
 *
 * <p>Cookie configuration:
 * <ul>
 *   <li>Name: {@code __Host-shareViewerId} (secure) / {@code shareViewerId} (insecure)</li>
 *   <li>HttpOnly: true (prevents JS access — XSS theft prevention)</li>
 *   <li>Secure: per {@code glacier.cookie.secure}</li>
 *   <li>SameSite: Lax (top-nav friendly while blocking cross-site subrequests)</li>
 *   <li>Path: / (secure, required by __Host- prefix) or /share (insecure/dev)</li>
 *   <li>Max-Age: capped to share link TTL remaining; max 7 days</li>
 * </ul>
 *
 * <p>ADR-1: uses {@link ResponseCookie} exclusively to emit a single canonical
 * Set-Cookie header. The former dual-emission pattern (Cookie API + raw addHeader) is
 * replaced by a single {@code response.addHeader(HttpHeaders.SET_COOKIE, ...)} call.
 *
 * <p>Security: SR-SHARE-07, ADR-SHARE-05.
 * References: OWASP A02 (Cookie security), spring-security-hardening skill.
 */
@Component
public class ShareViewerCookieFactory {

    /** 7 days in seconds — maximum TTL for the viewer cookie. */
    private static final int MAX_COOKIE_AGE_SECONDS = 7 * 24 * 3600;

    private final boolean secureCookies;

    public ShareViewerCookieFactory(
            @Value("${glacier.cookie.secure:true}") final boolean secureCookies) {
        this.secureCookies = secureCookies;
    }

    /**
     * Mints a new shareViewerId and adds the cookie to the response.
     *
     * @param response     the HTTP response
     * @param linkExpiresAt the share link's expiry time — cookie Max-Age is bounded by this
     * @return the minted shareViewerId token
     */
    public String mintAndSet(final HttpServletResponse response, final Instant linkExpiresAt) {
        String viewerId = ShareViewPrincipalHandler.mintNewShareViewerId();
        int maxAge = computeMaxAge(linkExpiresAt);
        addCookie(response, viewerId, maxAge);
        return viewerId;
    }

    /**
     * Refreshes an existing viewer ID cookie (extends Max-Age).
     */
    public void refresh(final HttpServletResponse response, final String viewerId, final Instant linkExpiresAt) {
        int maxAge = computeMaxAge(linkExpiresAt);
        addCookie(response, viewerId, maxAge);
    }

    private void addCookie(HttpServletResponse response, String viewerId, int maxAge) {
        String cookieName = secureCookies
                ? ShareViewPrincipalHandler.COOKIE_NAME_SECURE
                : ShareViewPrincipalHandler.COOKIE_NAME_INSECURE;

        // OWASP A05: __Host- prefix requires Path=/ (RFC 6265bis §4.1.3).
        // In secure mode the cookie uses the __Host- prefix so Path MUST be /.
        // In insecure mode (dev/test) we scope to /share to reduce cookie blast radius.
        // SR-SHARE-07, Finding 8.
        String cookiePath = secureCookies ? "/" : "/share";

        // ADR-1: use ResponseCookie (not jakarta.servlet.http.Cookie) to emit a single
        // canonical Set-Cookie header with SameSite=Lax.
        // SameSite=Lax is appropriate for the viewer identity cookie — it allows top-level
        // navigation (share link clicks) while blocking cross-site subrequests. (ADR-SHARE-05)
        // HttpOnly=true prevents XSS theft of the viewer session identity. (OWASP A02)
        ResponseCookie viewerCookie = ResponseCookie.from(cookieName, viewerId)
                .path(cookiePath)
                .maxAge(Duration.ofSeconds(maxAge))
                .sameSite("Lax")
                .secure(secureCookies)
                .httpOnly(true)
                .build();

        // Single Set-Cookie header — no addCookie() call (ADR-1 / SR-CSRF-13 pattern).
        response.addHeader(HttpHeaders.SET_COOKIE, viewerCookie.toString());
    }

    private int computeMaxAge(Instant linkExpiresAt) {
        if (linkExpiresAt == null) return MAX_COOKIE_AGE_SECONDS;
        long remaining = linkExpiresAt.getEpochSecond() - Instant.now().getEpochSecond();
        if (remaining <= 0) return 0;
        return (int) Math.min(remaining, MAX_COOKIE_AGE_SECONDS);
    }
}
