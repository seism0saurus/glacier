package de.seism0saurus.glacier.share.web;

import de.seism0saurus.glacier.webservice.messaging.ShareViewPrincipalHandler;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

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
 *   <li>Path: /share (scoped — not sent to main wall endpoints)</li>
 *   <li>Max-Age: capped to share link TTL remaining; max 7 days</li>
 * </ul>
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

        Cookie cookie = new Cookie(cookieName, viewerId);
        cookie.setPath("/share");
        cookie.setMaxAge(maxAge);
        cookie.setHttpOnly(true);
        if (secureCookies) {
            cookie.setSecure(true);
        }

        // SameSite=Lax via Set-Cookie header (Cookie API doesn't support SameSite directly)
        response.addHeader("Set-Cookie",
                cookieName + "=" + viewerId
                        + "; Path=/share"
                        + "; HttpOnly"
                        + "; SameSite=Lax"
                        + "; Max-Age=" + maxAge
                        + (secureCookies ? "; Secure" : ""));
    }

    private int computeMaxAge(Instant linkExpiresAt) {
        if (linkExpiresAt == null) return MAX_COOKIE_AGE_SECONDS;
        long remaining = linkExpiresAt.getEpochSecond() - Instant.now().getEpochSecond();
        if (remaining <= 0) return 0;
        return (int) Math.min(remaining, MAX_COOKIE_AGE_SECONDS);
    }
}
