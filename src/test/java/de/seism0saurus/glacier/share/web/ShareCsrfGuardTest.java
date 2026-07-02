package de.seism0saurus.glacier.share.web;

import de.seism0saurus.glacier.GlacierCookieProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

    /** Factory helper: creates {@link GlacierCookieProperties} with the given secure flag. */
    private static GlacierCookieProperties cookieProps(boolean secure) {
        GlacierCookieProperties props = new GlacierCookieProperties();
        props.setSecure(secure);
        return props;
    }

    @BeforeEach
    void setUp() {
        guard = new ShareCsrfGuard(DOMAIN, cookieProps(true));
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
        ShareCsrfGuard insecureGuard = new ShareCsrfGuard(DOMAIN, cookieProps(false));
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

    // -----------------------------------------------------------------------
    // Blank-origin + blank/short header-token rejections (SR-SHARE-08/12)
    // -----------------------------------------------------------------------

    @Test
    void rejectsWhenOriginBlank() {
        when(request.getHeader("Origin")).thenReturn("   ");
        assertThat(guard.verify(request)).isFalse();
    }

    @Test
    void rejectsWhenHeaderTokenBlank() {
        when(request.getHeader("Origin")).thenReturn("https://glacier.example.com");
        when(request.getHeader("X-Share-CSRF")).thenReturn("   ");
        setupCookieToken(TOKEN);
        assertThat(guard.verify(request)).isFalse();
    }

    @Test
    void rejectsWhenHeaderTokenShorterThanMinimum() {
        // < MIN_TOKEN_LENGTH (32) — too short to be a real token.
        when(request.getHeader("Origin")).thenReturn("https://glacier.example.com");
        when(request.getHeader("X-Share-CSRF")).thenReturn("short-token");
        setupCookieToken(TOKEN);
        assertThat(guard.verify(request)).isFalse();
    }

    // -----------------------------------------------------------------------
    // Origin-confusion bypasses (SR-SHARE-08): the guard must use an EXACT
    // scheme+host+port match, not a substring/prefix/suffix test. Each payload
    // shares the expected origin string "https://glacier.example.com" as a
    // prefix, suffix, or substring, so a naive relaxed check would wrongly
    // accept it — but the real host is attacker-controlled. These lock the
    // exact-match invariant against the classic wrong implementations.
    // -----------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            // Attacker registers a subdomain of the real domain. Kills endsWith(host).
            "https://evil.glacier.example.com",
            // Suffix confusion: real host is evil.com. Kills startsWith(expectedOrigin).
            "https://glacier.example.com.evil.com",
            // Userinfo confusion: everything before '@' is credentials; host is evil.com.
            // Kills startsWith(expectedOrigin) and contains(expectedOrigin).
            "https://glacier.example.com@evil.com",
            // Trailing path/slash — Origin has no path per spec; kills startsWith(expectedOrigin).
            "https://glacier.example.com/",
            // Prefix-substring host. Kills contains(domain) where domain="glacier.example.com".
            "https://notglacier.example.com",
            // Wrong scheme with an otherwise-matching host. Kills a host-only compare.
            "http://glacier.example.com.evil.com",
    })
    void rejectsOriginConfusionBypass(String maliciousOrigin) {
        when(request.getHeader("Origin")).thenReturn(maliciousOrigin);
        when(request.getHeader("X-Share-CSRF")).thenReturn(TOKEN);
        setupCookieToken(TOKEN);

        assertThat(guard.verify(request))
                .as("malicious origin must be rejected: %s", maliciousOrigin)
                .isFalse();
    }
}
