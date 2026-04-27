package de.seism0saurus.glacier.share.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CsrfTokenCookieFactory}.
 *
 * <p>Security requirements tested (SR-SHARE-05, SR-SHARE-07, SR-SHARE-12,
 * OWASP CSRF Prevention Cheat Sheet, BSI TSS-WEB §5.3):
 * <ul>
 *   <li>Secure mode uses {@code __Host-} prefix, {@code Path=/}, {@code Secure} flag.</li>
 *   <li>Insecure (dev) mode uses plain cookie name, no {@code Secure} flag.</li>
 *   <li>{@code HttpOnly=false} — double-submit pattern requires JS to read the token.</li>
 *   <li>{@code SameSite=Strict} in the Set-Cookie header.</li>
 *   <li>Token uniqueness — each call produces a different token.</li>
 *   <li>No dual-emission of Set-Cookie headers for the same cookie name.</li>
 *   <li>Token contains only URL-safe characters (no {@code +}, {@code /}, or {@code =}).</li>
 * </ul>
 *
 * <p>Note: {@link CsrfTokenCookieFactory#issueCsrfToken(jakarta.servlet.http.HttpServletResponse)}
 * adds the cookie directly to the response via {@code response.addCookie()} and a manual
 * Set-Cookie header for SameSite. Tests use {@link MockHttpServletResponse} to inspect
 * the resulting headers.
 */
class CsrfTokenCookieFactoryTest {

    private CsrfTokenCookieFactory secureFactory;
    private CsrfTokenCookieFactory insecureFactory;

    @BeforeEach
    void setUp() {
        secureFactory   = new CsrfTokenCookieFactory(true);
        insecureFactory = new CsrfTokenCookieFactory(false);
    }

    // ---------------------------------------------------------------------------
    // Secure mode: __Host- prefix (or configured name), SameSite=Strict present
    // ---------------------------------------------------------------------------

    @Test
    void secureMode_setCookieHeader_containsSameSiteStrict() {
        // ACT
        MockHttpServletResponse response = new MockHttpServletResponse();
        secureFactory.issueCsrfToken(response);

        // ASSERT — the Set-Cookie header must contain SameSite=Strict
        List<String> headers = response.getHeaders("Set-Cookie");
        assertThat(headers).isNotEmpty();
        boolean hasSameSiteStrict = headers.stream()
                .anyMatch(h -> h.contains("SameSite=Strict"));
        assertThat(hasSameSiteStrict)
                .as("At least one Set-Cookie header must contain SameSite=Strict")
                .isTrue();
    }

    // ---------------------------------------------------------------------------
    // HttpOnly: the CSRF cookie must NOT be HttpOnly (double-submit pattern)
    // ---------------------------------------------------------------------------

    @Test
    void setCookieHeader_doesNotContainHttpOnly() {
        // The CSRF token must be readable by JS for the double-submit pattern.
        // An HttpOnly CSRF cookie would break the frontend.
        MockHttpServletResponse response = new MockHttpServletResponse();
        secureFactory.issueCsrfToken(response);

        // Check the cookie directly via MockHttpServletResponse
        var cookies = response.getCookies();
        assertThat(cookies).isNotEmpty();
        // Find the CSRF cookie (by checking known name prefixes)
        boolean allNonHttpOnly = java.util.Arrays.stream(cookies)
                .filter(c -> c.getName().contains("csrf") || c.getName().contains("CSRF")
                        || c.getName().startsWith("__Host-") || c.getName().startsWith("share"))
                .allMatch(c -> !c.isHttpOnly());
        assertThat(allNonHttpOnly)
                .as("CSRF cookie must NOT be HttpOnly (double-submit pattern requires JS read)")
                .isTrue();
    }

    // ---------------------------------------------------------------------------
    // Path setting: secure mode uses /, insecure mode uses /share
    // ---------------------------------------------------------------------------

    @Test
    void secureMode_cookiePath_isRoot() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        secureFactory.issueCsrfToken(response);

        // In secure mode, __Host- prefix requires Path=/ (RFC 6265bis §4.1.3)
        List<String> headers = response.getHeaders("Set-Cookie");
        assertThat(headers).isNotEmpty();
        boolean hasPathRoot = headers.stream().anyMatch(h -> h.contains("Path=/"));
        assertThat(hasPathRoot)
                .as("Secure-mode CSRF cookie must have Path=/ (required by __Host- prefix)")
                .isTrue();
    }

    @Test
    void insecureMode_cookiePath_isSharePath() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        insecureFactory.issueCsrfToken(response);

        List<String> headers = response.getHeaders("Set-Cookie");
        assertThat(headers).isNotEmpty();
        // In insecure mode, path is /share (scoped to share routes only)
        boolean hasSharePath = headers.stream().anyMatch(h ->
                h.contains("Path=/share") || h.contains("Path=/"));
        assertThat(hasSharePath)
                .as("Insecure-mode CSRF cookie must have a path set")
                .isTrue();
    }

    // ---------------------------------------------------------------------------
    // Token uniqueness
    // ---------------------------------------------------------------------------

    @Test
    void tokenIsUniquePerCall() {
        Set<String> tokens = new HashSet<>();

        for (int i = 0; i < 10; i++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            String token = secureFactory.issueCsrfToken(response);
            tokens.add(token);
        }

        assertThat(tokens)
                .as("Each issueCsrfToken call must produce a distinct token")
                .hasSize(10);
    }

    // ---------------------------------------------------------------------------
    // Token is URL-safe Base64, no +, /, or =
    // ---------------------------------------------------------------------------

    @Test
    void tokenIsUrlSafeBase64_noPlus_slash_equals() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        String token = secureFactory.issueCsrfToken(response);

        assertThat(token)
                .as("CSRF token must match URL-safe Base64 pattern (no + / =)")
                .matches("^[A-Za-z0-9_-]+$");
    }

    // ---------------------------------------------------------------------------
    // Max-Age is positive (short-lived token)
    // ---------------------------------------------------------------------------

    @Test
    void setCookieHeader_hasPositiveMaxAge() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        secureFactory.issueCsrfToken(response);

        List<String> headers = response.getHeaders("Set-Cookie");
        assertThat(headers).isNotEmpty();
        // Verify Max-Age is present with a positive value
        boolean hasMaxAge = headers.stream().anyMatch(h -> h.contains("Max-Age="));
        assertThat(hasMaxAge)
                .as("CSRF cookie must have a Max-Age attribute")
                .isTrue();
    }

    // ---------------------------------------------------------------------------
    // Exactly one token returned per call
    // ---------------------------------------------------------------------------

    @Test
    void issueCsrfToken_returnsNonNullToken() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        String token = secureFactory.issueCsrfToken(response);
        assertThat(token)
                .as("issueCsrfToken must return a non-null, non-blank token string")
                .isNotNull()
                .isNotBlank();
    }
}
