package de.seism0saurus.glacier.share.web;

import de.seism0saurus.glacier.GlacierCookieProperties;
import de.seism0saurus.glacier.share.application.ShareLinkService;
import de.seism0saurus.glacier.share.application.ShareViewStompRelay;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * IT-CSRF-01: Integration test for CSRF token issuance by {@link ShareViewController}.
 *
 * <p>Verifies the Set-Cookie invariants required by the Phase 1 decision document
 * for the double Set-Cookie emission fix (SR-CSRF-13, SR-CSRF-14):
 *
 * <ul>
 *   <li>I-CSRF-1: exactly one Set-Cookie header per cookie name</li>
 *   <li>I-CSRF-2: the single header contains {@code SameSite=Strict}</li>
 *   <li>I-CSRF-3: the single header does NOT contain {@code HttpOnly}</li>
 *   <li>I-CSRF-4: secure mode → {@code __Host-shareCsrf}, {@code Path=/}, {@code Secure};
 *       insecure mode → {@code shareCsrf}, {@code Path=/share}, no {@code Secure}</li>
 *   <li>I-CSRF-5: {@code Max-Age=3600} present</li>
 *   <li>I-CSRF-6: token returned in JSON body is non-blank and URL-safe Base64</li>
 * </ul>
 *
 * <p>Uses {@code @WebMvcTest} (Failsafe *IT.java naming convention) for a focused
 * Spring MVC slice — only the controller and its real {@link CsrfTokenCookieFactory}
 * bean are loaded; all other collaborators are mocked.
 *
 * <p>ADR-2: assertions use substring containment only — {@code ResponseCookie}
 * co-emits {@code Expires=} alongside {@code Max-Age} for HTTP/1.0 proxy compatibility;
 * never assert on the {@code Expires=} value.
 *
 * <p>ADR-4: all Set-Cookie assertions filter by cookie name prefix first, then assert
 * on the resulting single header. {@code anyMatch} over an unfiltered Set-Cookie stream
 * is banned.
 *
 * <p>Security: SR-SHARE-05, SR-SHARE-07, SR-SHARE-12, SR-CSRF-13, SR-CSRF-14.
 */
class CsrfTokenIssuanceIT {

    // -------------------------------------------------------------------------
    // Secure mode tests (glacier.cookie.secure=true) — __Host-shareCsrf cookie
    // -------------------------------------------------------------------------

    /**
     * Secure-mode CSRF issuance tests.
     *
     * <p>{@code glacier.cookie.secure=true} configures {@link CsrfTokenCookieFactory}
     * to use the {@code __Host-} prefix, {@code Path=/}, and the {@code Secure} attribute.
     */
    @Nested
    @WebMvcTest(controllers = {ShareViewController.class})
    @Import({CsrfTokenCookieFactory.class, GlacierCookieProperties.class})
    @TestPropertySource(properties = {
            "glacier.cookie.secure=true",
            "glacier.domain=example.com",
            "glacier.fallback.enabled=true"
    })
    class SecureMode {

        @Autowired
        private MockMvc mockMvc;

        @MockitoBean
        @SuppressWarnings("unused")
        private ShareLinkService shareLinkService;

        @MockitoBean
        @SuppressWarnings("unused")
        private ShareViewerCookieFactory shareViewerCookieFactory;

        @MockitoBean
        private ShareRateLimiter shareRateLimiter;

        @MockitoBean
        @SuppressWarnings("unused")
        private ShareViewStompRelay shareViewStompRelay;

        /**
         * IT-CSRF-01a: I-CSRF-1 in secure mode — exactly one {@code __Host-shareCsrf=}
         * Set-Cookie header per call to {@code GET /rest/share-csrf}.
         *
         * <p>Arrange: rate limiter allows the request, secure-cookie mode active.
         * Act: GET /rest/share-csrf.
         * Assert: filter Set-Cookie headers by {@code __Host-shareCsrf=} prefix → size == 1.
         *
         * <p>Fails RED on the current production code (addCookie + addHeader → 2 headers).
         * Turns GREEN after Lane B replaces the dual-emission with a single ResponseCookie.
         */
        @Test
        void issueCsrfToken_emitsExactlyOneSetCookieHeader_secureMode() throws Exception {
            when(shareRateLimiter.checkCsrfIssuance(any()))
                    .thenReturn(ShareRateLimiter.RateLimitResult.allowed());

            MvcResult result = mockMvc.perform(get("/rest/share-csrf"))
                    .andExpect(status().isOk())
                    .andReturn();

            // ADR-4: filter by name prefix before asserting count
            List<String> csrfHeaders = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE)
                    .stream()
                    .filter(h -> h.startsWith("__Host-shareCsrf="))
                    .collect(Collectors.toList());

            assertThat(csrfHeaders)
                    .as("IT-CSRF-01a (I-CSRF-1, SR-CSRF-14): GET /rest/share-csrf must emit "
                            + "exactly one Set-Cookie header for '__Host-shareCsrf' in secure mode. "
                            + "Current code produces 2 headers (addCookie + addHeader); "
                            + "fix by using ResponseCookie only (Lane B).")
                    .hasSize(1);
        }

        /**
         * IT-CSRF-01b: I-CSRF-2 + I-CSRF-3 + I-CSRF-4 + I-CSRF-5 attribute matrix on
         * the single secure-mode header.
         *
         * <p>Arrange/Act: same as above.
         * Assert: the single filtered header contains SameSite=Strict (I-CSRF-2),
         * does not contain httponly (I-CSRF-3), contains Path=/ and Secure (I-CSRF-4),
         * contains Max-Age=3600 (I-CSRF-5). ADR-2: Expires= is not asserted.
         *
         * <p>Fails RED on current code because hasSize(1) fails first.
         */
        @Test
        void issueCsrfToken_singleHeader_attributeMatrix_secureMode() throws Exception {
            when(shareRateLimiter.checkCsrfIssuance(any()))
                    .thenReturn(ShareRateLimiter.RateLimitResult.allowed());

            MvcResult result = mockMvc.perform(get("/rest/share-csrf"))
                    .andExpect(status().isOk())
                    .andReturn();

            // ADR-4: filter by name prefix (I-CSRF-1 prerequisite)
            List<String> csrfHeaders = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE)
                    .stream()
                    .filter(h -> h.startsWith("__Host-shareCsrf="))
                    .collect(Collectors.toList());

            assertThat(csrfHeaders)
                    .as("Prerequisite (I-CSRF-1): exactly one Set-Cookie for '__Host-shareCsrf'")
                    .hasSize(1);

            String header = csrfHeaders.get(0);

            // I-CSRF-2: SameSite=Strict (ADR-2: substring only)
            assertThat(header)
                    .as("IT-CSRF-01b I-CSRF-2: Set-Cookie must contain SameSite=Strict")
                    .contains("SameSite=Strict");

            // I-CSRF-3: NOT HttpOnly (double-submit pattern requires JS access)
            assertThat(header.toLowerCase())
                    .as("IT-CSRF-01b I-CSRF-3: CSRF cookie must NOT carry HttpOnly — "
                            + "JS must read the token for the double-submit pattern")
                    .doesNotContain("httponly");

            // I-CSRF-4 secure: __Host- prefix requires Path=/ and Secure
            assertThat(header)
                    .as("IT-CSRF-01b I-CSRF-4 (secure): Path=/ required by __Host- prefix")
                    .contains("Path=/");
            assertThat(header)
                    .as("IT-CSRF-01b I-CSRF-4 (secure): Secure attribute must be present")
                    .contains("Secure");

            // I-CSRF-5: Max-Age=3600 (ADR-2: do not assert Expires=)
            assertThat(header)
                    .as("IT-CSRF-01b I-CSRF-5: Max-Age=3600 must be present "
                            + "(ADR-2: Expires= co-emitted by ResponseCookie — do not assert it)")
                    .contains("Max-Age=3600");
        }

        /**
         * IT-CSRF-01c: I-CSRF-6 — the JSON body {@code token} field must be non-blank
         * and contain only URL-safe Base64 characters (no {@code +}, {@code /}, or {@code =}).
         *
         * <p>The token is issued by {@link CsrfTokenCookieFactory} using 32 bytes of
         * {@code SecureRandom} encoded with {@code Base64.getUrlEncoder().withoutPadding()}.
         */
        @Test
        void issueCsrfToken_jsonBody_containsUrlSafeBase64Token() throws Exception {
            when(shareRateLimiter.checkCsrfIssuance(any()))
                    .thenReturn(ShareRateLimiter.RateLimitResult.allowed());

            MvcResult result = mockMvc.perform(get("/rest/share-csrf"))
                    .andExpect(status().isOk())
                    .andReturn();

            String body = result.getResponse().getContentAsString();
            assertThat(body)
                    .as("IT-CSRF-01c (I-CSRF-6): response body must contain 'token' field")
                    .contains("\"token\"");

            // Extract token value from JSON manually to avoid a Jackson dependency in this slice
            // Body format: {"token":"<value>"}
            String tokenValue = body.replaceAll(".*\"token\"\\s*:\\s*\"([^\"]+)\".*", "$1");
            assertThat(tokenValue)
                    .as("IT-CSRF-01c (I-CSRF-6): token must be non-blank URL-safe Base64 "
                            + "(no +, /, or = — CsrfTokenCookieFactory uses withoutPadding())")
                    .isNotBlank()
                    .matches("^[A-Za-z0-9_-]+$");
        }
    }

    // -------------------------------------------------------------------------
    // Insecure mode tests (glacier.cookie.secure=false) — shareCsrf cookie
    // -------------------------------------------------------------------------

    /**
     * Insecure-mode CSRF issuance tests (dev / non-HTTPS environments).
     *
     * <p>{@code glacier.cookie.secure=false} configures {@link CsrfTokenCookieFactory}
     * to use the plain {@code shareCsrf} name, {@code Path=/share}, and no {@code Secure}.
     */
    @Nested
    @WebMvcTest(controllers = {ShareViewController.class})
    @Import({CsrfTokenCookieFactory.class, GlacierCookieProperties.class})
    @TestPropertySource(properties = {
            "glacier.cookie.secure=false",
            "glacier.domain=example.com",
            "glacier.fallback.enabled=true"
    })
    class InsecureMode {

        @Autowired
        private MockMvc mockMvc;

        @MockitoBean
        @SuppressWarnings("unused")
        private ShareLinkService shareLinkService;

        @MockitoBean
        @SuppressWarnings("unused")
        private ShareViewerCookieFactory shareViewerCookieFactory;

        @MockitoBean
        private ShareRateLimiter shareRateLimiter;

        @MockitoBean
        @SuppressWarnings("unused")
        private ShareViewStompRelay shareViewStompRelay;

        /**
         * IT-CSRF-02a: I-CSRF-1 in insecure mode — exactly one {@code shareCsrf=}
         * Set-Cookie header per call to {@code GET /rest/share-csrf}.
         *
         * <p>Arrange: rate limiter allows, insecure-cookie mode active.
         * Act: GET /rest/share-csrf.
         * Assert: filter Set-Cookie headers by {@code shareCsrf=} prefix → size == 1.
         *
         * <p>Fails RED on current code for the same dual-emission reason.
         */
        @Test
        void issueCsrfToken_emitsExactlyOneSetCookieHeader_insecureMode() throws Exception {
            when(shareRateLimiter.checkCsrfIssuance(any()))
                    .thenReturn(ShareRateLimiter.RateLimitResult.allowed());

            MvcResult result = mockMvc.perform(get("/rest/share-csrf"))
                    .andExpect(status().isOk())
                    .andReturn();

            // ADR-4: filter by name prefix before asserting count
            List<String> csrfHeaders = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE)
                    .stream()
                    .filter(h -> h.startsWith("shareCsrf="))
                    .collect(Collectors.toList());

            assertThat(csrfHeaders)
                    .as("IT-CSRF-02a (I-CSRF-1, SR-CSRF-14): GET /rest/share-csrf must emit "
                            + "exactly one Set-Cookie header for 'shareCsrf' in insecure mode. "
                            + "Current code produces 2 headers (addCookie + addHeader); "
                            + "fix by using ResponseCookie only (Lane B).")
                    .hasSize(1);
        }

        /**
         * IT-CSRF-02b: I-CSRF-2 + I-CSRF-3 + I-CSRF-4 + I-CSRF-5 attribute matrix on
         * the single insecure-mode header.
         *
         * <p>Assert: contains SameSite=Strict (I-CSRF-2), no httponly (I-CSRF-3),
         * Path=/share and no Secure (I-CSRF-4), Max-Age=3600 (I-CSRF-5).
         */
        @Test
        void issueCsrfToken_singleHeader_attributeMatrix_insecureMode() throws Exception {
            when(shareRateLimiter.checkCsrfIssuance(any()))
                    .thenReturn(ShareRateLimiter.RateLimitResult.allowed());

            MvcResult result = mockMvc.perform(get("/rest/share-csrf"))
                    .andExpect(status().isOk())
                    .andReturn();

            // ADR-4: filter by name prefix
            List<String> csrfHeaders = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE)
                    .stream()
                    .filter(h -> h.startsWith("shareCsrf="))
                    .collect(Collectors.toList());

            assertThat(csrfHeaders)
                    .as("Prerequisite (I-CSRF-1): exactly one Set-Cookie for 'shareCsrf'")
                    .hasSize(1);

            String header = csrfHeaders.get(0);

            // I-CSRF-2: SameSite=Strict
            assertThat(header)
                    .as("IT-CSRF-02b I-CSRF-2: Set-Cookie must contain SameSite=Strict")
                    .contains("SameSite=Strict");

            // I-CSRF-3: NOT HttpOnly
            assertThat(header.toLowerCase())
                    .as("IT-CSRF-02b I-CSRF-3: CSRF cookie must NOT carry HttpOnly")
                    .doesNotContain("httponly");

            // I-CSRF-4 insecure: plain name, Path=/share, no Secure
            assertThat(header)
                    .as("IT-CSRF-02b I-CSRF-4 (insecure): path must be /share")
                    .contains("Path=/share");
            assertThat(header)
                    .as("IT-CSRF-02b I-CSRF-4 (insecure): Secure attribute must NOT be present")
                    .doesNotContain("Secure");

            // I-CSRF-5: Max-Age=3600 (ADR-2: do not assert Expires=)
            assertThat(header)
                    .as("IT-CSRF-02b I-CSRF-5: Max-Age=3600 must be present")
                    .contains("Max-Age=3600");
        }
    }
}
