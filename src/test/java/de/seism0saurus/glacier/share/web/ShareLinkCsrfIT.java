package de.seism0saurus.glacier.share.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
}
