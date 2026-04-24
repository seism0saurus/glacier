package de.seism0saurus.glacier.share.web;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ShareCsrfGuard}.
 *
 * <p>Security requirements: SR-SHARE-05 (CSRF double-submit pattern),
 * SR-SHARE-08 (exact-Origin match), SR-SHARE-12 (constant-time compare).
 * References: OWASP CSRF Prevention Cheat Sheet.
 */
class ShareCsrfGuardTest {

    private ShareCsrfGuard guard;
    private HttpServletRequest request;

    private static final String DOMAIN = "glacier.example.com";
    private static final String TOKEN = "test-csrf-token-at-least-32chars!!";

    @BeforeEach
    void setUp() {
        guard = new ShareCsrfGuard(DOMAIN, true);
        request = mock(HttpServletRequest.class);
    }

    @Test
    void allowsRequestWithMatchingOriginAndToken() {
        when(request.getHeader("Origin")).thenReturn("https://glacier.example.com");
        when(request.getHeader("X-Share-CSRF")).thenReturn(TOKEN);
        setupCookieToken(TOKEN);

        assertThat(guard.verify(request)).isTrue();
    }

    @Test
    void rejectsWhenOriginMismatch() {
        when(request.getHeader("Origin")).thenReturn("https://evil.com");
        when(request.getHeader("X-Share-CSRF")).thenReturn(TOKEN);
        setupCookieToken(TOKEN);

        assertThat(guard.verify(request)).isFalse();
    }

    @Test
    void rejectsWhenOriginMissing() {
        when(request.getHeader("Origin")).thenReturn(null);
        when(request.getHeader("X-Share-CSRF")).thenReturn(TOKEN);
        setupCookieToken(TOKEN);

        assertThat(guard.verify(request)).isFalse();
    }

    @Test
    void rejectsWhenHeaderTokenMissing() {
        when(request.getHeader("Origin")).thenReturn("https://glacier.example.com");
        when(request.getHeader("X-Share-CSRF")).thenReturn(null);
        setupCookieToken(TOKEN);

        assertThat(guard.verify(request)).isFalse();
    }

    @Test
    void rejectsWhenCookieTokenMissing() {
        when(request.getHeader("Origin")).thenReturn("https://glacier.example.com");
        when(request.getHeader("X-Share-CSRF")).thenReturn(TOKEN);
        when(request.getCookies()).thenReturn(null);

        assertThat(guard.verify(request)).isFalse();
    }

    @Test
    void rejectsWhenTokensMismatch() {
        when(request.getHeader("Origin")).thenReturn("https://glacier.example.com");
        when(request.getHeader("X-Share-CSRF")).thenReturn(TOKEN);
        setupCookieToken("different-token-32chars-minimum!!");

        assertThat(guard.verify(request)).isFalse();
    }

    @Test
    void rejectsWhenOriginHasDifferentPort() {
        // scheme+host+port must all match
        when(request.getHeader("Origin")).thenReturn("https://glacier.example.com:8443");
        when(request.getHeader("X-Share-CSRF")).thenReturn(TOKEN);
        setupCookieToken(TOKEN);

        assertThat(guard.verify(request)).isFalse();
    }

    @Test
    void rejectsHttpOriginWhenDomainExpectsHttps() {
        when(request.getHeader("Origin")).thenReturn("http://glacier.example.com");
        when(request.getHeader("X-Share-CSRF")).thenReturn(TOKEN);
        setupCookieToken(TOKEN);

        assertThat(guard.verify(request)).isFalse();
    }

    @Test
    void allowsHttpOriginInInsecureMode() {
        ShareCsrfGuard insecureGuard = new ShareCsrfGuard(DOMAIN, false);
        when(request.getHeader("Origin")).thenReturn("http://glacier.example.com");
        when(request.getHeader("X-Share-CSRF")).thenReturn(TOKEN);
        // In insecure mode the cookie name is 'shareCsrf' (no __Host- prefix)
        Cookie cookie = new Cookie("shareCsrf", TOKEN);
        when(request.getCookies()).thenReturn(new Cookie[]{cookie});

        assertThat(insecureGuard.verify(request)).isTrue();
    }

    // -----------------------------------------------------------------------
    // Helper
    // -----------------------------------------------------------------------

    private void setupCookieToken(String token) {
        Cookie cookie = new Cookie("__Host-shareCsrf", token);
        when(request.getCookies()).thenReturn(new Cookie[]{cookie});
    }
}
