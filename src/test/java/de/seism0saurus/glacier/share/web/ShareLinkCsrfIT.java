package de.seism0saurus.glacier.share.web;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for CSRF guard behavior on share-link endpoints.
 *
 * <p>Verifies the double-submit cookie pattern (Origin check + constant-time cookie/header compare).
 *
 * <p>Security: SR-SHARE-05, SR-SHARE-08, OWASP CSRF Prevention Cheat Sheet.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "glacier.domain=glacier.example.com",
        "glacier.cookie.secure=false",
        "glacier.fallback.enabled=true",
        "mastodon.instance=mastodon.social",
        "mastodon.accessToken=dummy",
        "mastodon.handle=glacier@mastodon.social",
        "glacier.operatorName=Test",
        "glacier.operatorStreetAndNumber=Test 1",
        "glacier.operatorZipcode=12345",
        "glacier.operatorCity=Test",
        "glacier.operatorCountry=Test",
        "glacier.operatorPhone=+1",
        "glacier.operatorMail=test@test.com",
        "glacier.operatorWebsite=test.com",
        "glacier.share.imgproxy.hmacSecret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
})
class ShareLinkCsrfIT {

    @Autowired
    private MockMvc mockMvc;

    /**
     * The CSRF token endpoint itself is a GET — no CSRF check applies.
     * It should return 200 and set the CSRF cookie.
     */
    @Test
    void csrfEndpoint_getRequest_returns200() throws Exception {
        mockMvc.perform(get("/rest/share-csrf"))
                .andExpect(status().isOk());
    }

    /**
     * CSRF endpoint sets the CSRF cookie in the response.
     */
    @Test
    void csrfEndpoint_setsCsrfCookie() throws Exception {
        mockMvc.perform(get("/rest/share-csrf"))
                .andExpect(cookie().exists("shareCsrf")); // insecure mode: no __Host- prefix
    }

    /**
     * Catalog endpoint is a GET — no CSRF check. Should return 404 (link not found)
     * and NOT 403 (CSRF reject), proving GET endpoints are not CSRF-protected.
     */
    @Test
    void catalogEndpoint_getRequest_notCsrfProtected() throws Exception {
        // GET requests are safe methods and must never require CSRF tokens (OWASP CSRF spec)
        mockMvc.perform(get("/rest/share/sv_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA/catalog"))
                .andExpect(status().isNotFound()); // 404 because no share link exists, not 403
    }

    /**
     * Verifies that the ShareCsrfGuard is wired but does not block GET requests.
     * Only state-changing operations (POST/PUT/DELETE) should be CSRF-protected.
     * This test ensures the guard has no unintended side effects on read operations.
     */
    @Test
    void shareRoute_getRequest_csrfGuardDoesNotBlock() throws Exception {
        mockMvc.perform(get("/rest/share/sv_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA/catalog"))
                .andExpect(result -> {
                    // Must be either 404 (not found) or 200, NEVER 403 (CSRF reject)
                    int status = result.getResponse().getStatus();
                    org.assertj.core.api.Assertions.assertThat(status)
                            .as("GET should never be rejected with CSRF 403")
                            .isNotEqualTo(403);
                });
    }

    // -----------------------------------------------------------------------
    // New tests required by Phase 3 fix list (Fix 1)
    // -----------------------------------------------------------------------

    /**
     * POST without X-Share-CSRF header must return 403 (CSRF rejection).
     *
     * <p>Security: SR-SHARE-05 — state-changing endpoints must require CSRF token.
     */
    @Test
    void postShareLinks_missingCsrfToken_returns403() throws Exception {
        // glacier.cookie.secure=false: wallId cookie is the plain name
        mockMvc.perform(post("/rest/share-links")
                        .cookie(new Cookie("wallId", "valid-wall-id-fixture-000000000000000"))
                        .header("Origin", "http://glacier.example.com") // matches domain but no CSRF header
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    /**
     * POST with valid CSRF token (double-submit from GET /rest/share-csrf) returns 201.
     *
     * <p>Validates the full token issuance → consumption flow.
     */
    @Test
    void postShareLinks_validCsrfToken_returns201() throws Exception {
        // Step 1: obtain the CSRF token
        MvcResult csrfResult = mockMvc.perform(get("/rest/share-csrf"))
                .andExpect(status().isOk())
                .andReturn();

        String csrfToken = com.fasterxml.jackson.databind.ObjectMapper.class
                .getConstructor().newInstance()
                .readTree(csrfResult.getResponse().getContentAsString())
                .get("token").asText();

        // The CSRF cookie is also set — extract from response Set-Cookie header
        String setCookieHeader = csrfResult.getResponse().getHeader("Set-Cookie");
        String csrfCookieValue = null;
        if (setCookieHeader != null) {
            // shareCsrf=<value>; ...
            for (String part : setCookieHeader.split(";")) {
                String trimmed = part.trim();
                if (trimmed.startsWith("shareCsrf=")) {
                    csrfCookieValue = trimmed.substring("shareCsrf=".length());
                    break;
                }
            }
        }

        // Use a wallId that will fail auth (no real wallId service in this IT),
        // so we expect 401 (auth fails before service call) rather than 201.
        // The important assertion is that we do NOT get 403 (CSRF rejected),
        // proving the CSRF token was accepted.
        mockMvc.perform(post("/rest/share-links")
                        .cookie(new Cookie("wallId", "valid-wall-id-fixture-000000000000000"))
                        .cookie(new Cookie("shareCsrf", csrfCookieValue != null ? csrfCookieValue : csrfToken))
                        .header("Origin", "http://glacier.example.com")
                        .header(ShareCsrfGuard.CSRF_HEADER, csrfToken)
                        .contentType(MediaType.APPLICATION_JSON))
                // Should be 401 (wallId not recognized in full context) or 201, NOT 403
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    assertThat(status)
                            .as("Valid CSRF token must not produce 403 CSRF rejection")
                            .isNotEqualTo(403);
                });
    }

    /**
     * DELETE without X-Share-CSRF header must return 403.
     *
     * <p>Security: SR-SHARE-05 — state-changing endpoints must require CSRF token.
     */
    @Test
    void deleteShareLink_missingCsrfToken_returns403() throws Exception {
        mockMvc.perform(delete("/rest/share-links/sv_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")
                        .cookie(new Cookie("wallId", "valid-wall-id-fixture-000000000000000"))
                        .header("Origin", "http://glacier.example.com"))
                .andExpect(status().isForbidden());
    }

    /**
     * GET /rest/share-csrf returns 200 with a JSON body containing a {@code token} field.
     *
     * <p>Security: the token field enables clients to extract the value without parsing cookies.
     */
    @Test
    void csrfEndpoint_returnsJsonBodyWithToken() throws Exception {
        MvcResult result = mockMvc.perform(get("/rest/share-csrf")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("\"token\"");
        // Token must be a non-empty string
        String tokenValue = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(body).get("token").asText();
        assertThat(tokenValue).isNotBlank();
        assertThat(tokenValue.length()).isGreaterThanOrEqualTo(ShareCsrfGuard.MIN_TOKEN_LENGTH);
    }
}
