package de.seism0saurus.glacier.share.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

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
 *   <li>Exactly one Set-Cookie header per cookie name (I-CSRF-1: no dual-emission).</li>
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

        // ASSERT — filter by cookie name (ADR-4: no unfiltered anyMatch), then check attribute
        List<String> csrfHeaders = response.getHeaders("Set-Cookie").stream()
                .filter(h -> h.startsWith("__Host-shareCsrf="))
                .collect(Collectors.toList());
        assertThat(csrfHeaders)
                .as("Exactly one Set-Cookie header must be emitted for __Host-shareCsrf (I-CSRF-1)")
                .hasSize(1);
        assertThat(csrfHeaders.get(0))
                .as("The __Host-shareCsrf Set-Cookie header must contain SameSite=Strict")
                .contains("SameSite=Strict");
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
        // ADR-4: filter by cookie name before checking attribute — no unfiltered anyMatch
        List<String> csrfHeaders = response.getHeaders("Set-Cookie").stream()
                .filter(h -> h.startsWith("__Host-shareCsrf="))
                .collect(Collectors.toList());
        assertThat(csrfHeaders)
                .as("Exactly one Set-Cookie header must be emitted for __Host-shareCsrf (I-CSRF-1)")
                .hasSize(1);
        assertThat(csrfHeaders.get(0))
                .as("Secure-mode CSRF cookie must have Path=/ (required by __Host- prefix)")
                .contains("Path=/");
    }

    @Test
    void insecureMode_cookiePath_isSharePath() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        insecureFactory.issueCsrfToken(response);

        // In insecure mode, path is /share (scoped to share routes only)
        // ADR-4: filter by cookie name before checking attribute — no unfiltered anyMatch
        List<String> csrfHeaders = response.getHeaders("Set-Cookie").stream()
                .filter(h -> h.startsWith("shareCsrf="))
                .collect(Collectors.toList());
        assertThat(csrfHeaders)
                .as("Exactly one Set-Cookie header must be emitted for shareCsrf (I-CSRF-1)")
                .hasSize(1);
        assertThat(csrfHeaders.get(0))
                .as("Insecure-mode CSRF cookie must have Path=/share (scoped to share routes only)")
                .contains("Path=/share");
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

        // ADR-4: filter by cookie name before checking attribute — no unfiltered anyMatch
        // ADR-2: assert Max-Age=3600 (not Expires=) — ResponseCookie may co-emit Expires
        List<String> csrfHeaders = response.getHeaders("Set-Cookie").stream()
                .filter(h -> h.startsWith("__Host-shareCsrf="))
                .collect(Collectors.toList());
        assertThat(csrfHeaders)
                .as("Exactly one Set-Cookie header must be emitted for __Host-shareCsrf (I-CSRF-1)")
                .hasSize(1);
        assertThat(csrfHeaders.get(0))
                .as("CSRF cookie must have Max-Age=3600 attribute (I-CSRF-5)")
                .contains("Max-Age=3600");
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

    // ---------------------------------------------------------------------------
    // I-CSRF-1: exactly one Set-Cookie header per cookie name (RED canary)
    //
    // CsrfTokenCookieFactory currently calls response.addCookie() AND
    // response.addHeader("Set-Cookie", ...) for the same cookie name.
    // This produces two Set-Cookie headers — a violation of RFC 6265.
    // These tests MUST FAIL RED on the current production code and turn GREEN
    // only after Lane B replaces the dual-emission with a single ResponseCookie.
    // ---------------------------------------------------------------------------

    /**
     * I-CSRF-1 canary tests — single Set-Cookie emission per cookie name.
     *
     * <p>Invariant I-CSRF-1: {@code response.getHeaders("Set-Cookie")} filtered by the
     * CSRF cookie name prefix must yield exactly one entry per {@code issueCsrfToken} call.
     *
     * <p>ADR-2: assertions use substring containment (not equality) because
     * {@code ResponseCookie.toString()} co-emits {@code Expires=} alongside {@code Max-Age}
     * for HTTP/1.0 proxy compatibility. Never assert on the {@code Expires=} value.
     *
     * <p>ADR-4: filter by cookie name first, then assert size and attribute substrings.
     * {@code anyMatch} over an unfiltered Set-Cookie stream is banned.
     */
    @Nested
    class SingleSetCookieEmission {

        /**
         * I-CSRF-1 secure mode: exactly one Set-Cookie header with name {@code __Host-shareCsrf=}.
         *
         * <p>Arrange: secure factory (secureCookies=true), fresh MockHttpServletResponse.
         * Act: call {@code issueCsrfToken}.
         * Assert: filtering by {@code __Host-shareCsrf=} prefix yields exactly 1 header.
         *
         * <p>FAILS RED on current code because {@code addCookie()} + {@code addHeader("Set-Cookie")}
         * both emit a header for {@code __Host-shareCsrf}, resulting in count==2.
         */
        @Test
        void issueCsrfToken_emitsExactlyOneSetCookieHeader_inSecureMode() {
            // ARRANGE
            MockHttpServletResponse response = new MockHttpServletResponse();

            // ACT
            secureFactory.issueCsrfToken(response);

            // ASSERT — filter by name prefix, then demand exactly one entry (I-CSRF-1, ADR-4)
            List<String> csrfHeaders = response.getHeaders("Set-Cookie").stream()
                    .filter(h -> h.startsWith("__Host-shareCsrf="))
                    .collect(Collectors.toList());

            assertThat(csrfHeaders)
                    .as("I-CSRF-1 (SR-CSRF-13): secure mode must emit exactly one Set-Cookie "
                            + "header for cookie name '__Host-shareCsrf'. "
                            + "Current code calls addCookie() + addHeader() for the same name, "
                            + "producing two headers — fix by using ResponseCookie only (Lane B).")
                    .hasSize(1);
        }

        /**
         * I-CSRF-1 insecure mode: exactly one Set-Cookie header with name {@code shareCsrf=}.
         *
         * <p>Arrange: insecure factory (secureCookies=false), fresh MockHttpServletResponse.
         * Act: call {@code issueCsrfToken}.
         * Assert: filtering by {@code shareCsrf=} prefix yields exactly 1 header.
         *
         * <p>FAILS RED on current code for the same dual-emission reason.
         */
        @Test
        void issueCsrfToken_emitsExactlyOneSetCookieHeader_inInsecureMode() {
            // ARRANGE
            MockHttpServletResponse response = new MockHttpServletResponse();

            // ACT
            insecureFactory.issueCsrfToken(response);

            // ASSERT — filter by name prefix, then demand exactly one entry (I-CSRF-1, ADR-4)
            List<String> csrfHeaders = response.getHeaders("Set-Cookie").stream()
                    .filter(h -> h.startsWith("shareCsrf="))
                    .collect(Collectors.toList());

            assertThat(csrfHeaders)
                    .as("I-CSRF-1 (SR-CSRF-13): insecure mode must emit exactly one Set-Cookie "
                            + "header for cookie name 'shareCsrf'. "
                            + "Current code calls addCookie() + addHeader() for the same name, "
                            + "producing two headers — fix by using ResponseCookie only (Lane B).")
                    .hasSize(1);
        }

        /**
         * I-CSRF-2 + I-CSRF-3 + I-CSRF-4 + I-CSRF-5: attribute matrix on the single header,
         * secure mode.
         *
         * <p>After I-CSRF-1 passes (exactly one header), verify the single header carries
         * the required attribute set using substring containment (ADR-2: Expires= is co-emitted
         * by ResponseCookie and must NOT be asserted).
         *
         * <p>FAILS RED on current code because there are two headers (size==2 not 1),
         * making the prerequisite {@code hasSize(1)} fail first.
         */
        @Test
        void issueCsrfToken_singleHeader_hasCorrectAttributes_inSecureMode() {
            // ARRANGE
            MockHttpServletResponse response = new MockHttpServletResponse();

            // ACT
            secureFactory.issueCsrfToken(response);

            // ASSERT — filter by name prefix (ADR-4)
            List<String> csrfHeaders = response.getHeaders("Set-Cookie").stream()
                    .filter(h -> h.startsWith("__Host-shareCsrf="))
                    .collect(Collectors.toList());

            assertThat(csrfHeaders)
                    .as("Prerequisite: exactly one __Host-shareCsrf= Set-Cookie header (I-CSRF-1)")
                    .hasSize(1);

            String header = csrfHeaders.get(0);

            // I-CSRF-2: SameSite=Strict
            assertThat(header)
                    .as("I-CSRF-2: single CSRF Set-Cookie header must contain SameSite=Strict")
                    .contains("SameSite=Strict");

            // I-CSRF-3: NOT HttpOnly (double-submit JS readability)
            assertThat(header.toLowerCase())
                    .as("I-CSRF-3: CSRF cookie must NOT carry HttpOnly "
                            + "(JS must read it for the double-submit pattern)")
                    .doesNotContain("httponly");

            // I-CSRF-4 secure: Path=/ and Secure present
            assertThat(header)
                    .as("I-CSRF-4 (secure): __Host- cookie requires Path=/")
                    .contains("Path=/");
            assertThat(header)
                    .as("I-CSRF-4 (secure): Secure attribute must be present")
                    .contains("Secure");

            // I-CSRF-5: Max-Age=3600
            assertThat(header)
                    .as("I-CSRF-5: Max-Age=3600 must be present (ADR-2: do not assert Expires=)")
                    .contains("Max-Age=3600");
        }

        /**
         * I-CSRF-2 + I-CSRF-3 + I-CSRF-4 + I-CSRF-5: attribute matrix on the single header,
         * insecure mode.
         *
         * <p>FAILS RED on current code for the same dual-emission reason.
         */
        @Test
        void issueCsrfToken_singleHeader_hasCorrectAttributes_inInsecureMode() {
            // ARRANGE
            MockHttpServletResponse response = new MockHttpServletResponse();

            // ACT
            insecureFactory.issueCsrfToken(response);

            // ASSERT — filter by name prefix (ADR-4)
            List<String> csrfHeaders = response.getHeaders("Set-Cookie").stream()
                    .filter(h -> h.startsWith("shareCsrf="))
                    .collect(Collectors.toList());

            assertThat(csrfHeaders)
                    .as("Prerequisite: exactly one shareCsrf= Set-Cookie header (I-CSRF-1)")
                    .hasSize(1);

            String header = csrfHeaders.get(0);

            // I-CSRF-2: SameSite=Strict
            assertThat(header)
                    .as("I-CSRF-2: single CSRF Set-Cookie header must contain SameSite=Strict")
                    .contains("SameSite=Strict");

            // I-CSRF-3: NOT HttpOnly
            assertThat(header.toLowerCase())
                    .as("I-CSRF-3: CSRF cookie must NOT carry HttpOnly")
                    .doesNotContain("httponly");

            // I-CSRF-4 insecure: name=shareCsrf, Path=/share, no Secure
            assertThat(header)
                    .as("I-CSRF-4 (insecure): path must be /share (not /)")
                    .contains("Path=/share");
            assertThat(header)
                    .as("I-CSRF-4 (insecure): Secure attribute must NOT be present")
                    .doesNotContain("Secure");

            // I-CSRF-5: Max-Age=3600
            assertThat(header)
                    .as("I-CSRF-5: Max-Age=3600 must be present (ADR-2: do not assert Expires=)")
                    .contains("Max-Age=3600");
        }
    }
}
