package de.seism0saurus.glacier.webservice;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import social.bigbone.MastodonClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Security tests for the {@code wallId} cookie flags issued by
 * {@link InformationController#readCookie}.
 *
 * <p>Security requirements tested (D-09, SR-3, SR-TEST-02, OWASP A05, BSI TSS-WEB §5.3):
 * <ul>
 *   <li>{@code HttpOnly=true} — prevents JavaScript access to the session identifier
 *       (T-02 XSS theft mitigation).</li>
 *   <li>{@code SameSite=Lax} — CSRF mitigation; allows top-level navigations.</li>
 *   <li>{@code Path=/} — cookie scoped to the entire site.</li>
 *   <li>{@code Max-Age=2592000} — 30 days lifetime.</li>
 *   <li>Cookie value is a UUID (validates the UUID generation logic).</li>
 * </ul>
 *
 * <p>Tests run against the default test application properties which configure
 * {@code glacier.cookie.secure=false} (localhost dev mode). The HttpOnly, SameSite,
 * and Path flags are tested here — the Secure flag is verified separately in production
 * configuration tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
class S1_WallIdCookieFlagsTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    @SuppressWarnings("unused")
    private MastodonClient mastodonClient;

    // ---------------------------------------------------------------------------
    // HttpOnly must be set (T-02: XSS theft prevention)
    // ---------------------------------------------------------------------------

    @Test
    void wallIdCookie_hasHttpOnlyFlag() throws Exception {
        var result = mockMvc.perform(MockMvcRequestBuilders.get("/rest/wall-id"))
                .andExpect(status().isOk())
                .andReturn();

        String setCookieHeader = getSetCookieHeaderForWallId(result);
        assertThat(setCookieHeader)
                .as("wallId cookie must have HttpOnly flag (OWASP A05, D-09, T-02)")
                .containsIgnoringCase("HttpOnly");
    }

    // ---------------------------------------------------------------------------
    // SameSite=Lax must be set (T-04: CSRF-on-read mitigation)
    // ---------------------------------------------------------------------------

    @Test
    void wallIdCookie_hasSameSiteLax() throws Exception {
        var result = mockMvc.perform(MockMvcRequestBuilders.get("/rest/wall-id"))
                .andExpect(status().isOk())
                .andReturn();

        String setCookieHeader = getSetCookieHeaderForWallId(result);
        assertThat(setCookieHeader)
                .as("wallId cookie must have SameSite=Lax (D-09, T-04 CSRF mitigation)")
                .containsIgnoringCase("SameSite=Lax");
    }

    // ---------------------------------------------------------------------------
    // Path=/ must be set
    // ---------------------------------------------------------------------------

    @Test
    void wallIdCookie_hasPathRoot() throws Exception {
        var result = mockMvc.perform(MockMvcRequestBuilders.get("/rest/wall-id"))
                .andExpect(status().isOk())
                .andReturn();

        String setCookieHeader = getSetCookieHeaderForWallId(result);
        assertThat(setCookieHeader)
                .as("wallId cookie must have Path=/ (accessible to all paths)")
                .containsIgnoringCase("Path=/");
    }

    // ---------------------------------------------------------------------------
    // Max-Age=2592000 (30 days) must be set (D-09)
    // ---------------------------------------------------------------------------

    @Test
    void wallIdCookie_hasMaxAge30Days() throws Exception {
        var result = mockMvc.perform(MockMvcRequestBuilders.get("/rest/wall-id"))
                .andExpect(status().isOk())
                .andReturn();

        String setCookieHeader = getSetCookieHeaderForWallId(result);
        assertThat(setCookieHeader)
                .as("wallId cookie must have Max-Age=2592000 (30 days, per D-09)")
                .containsIgnoringCase("Max-Age=2592000");
    }

    // ---------------------------------------------------------------------------
    // Cookie value must be a valid UUID
    // ---------------------------------------------------------------------------

    @Test
    void wallIdCookie_valueIsUuid() throws Exception {
        var result = mockMvc.perform(MockMvcRequestBuilders.get("/rest/wall-id"))
                .andExpect(status().isOk())
                .andReturn();

        String setCookieHeader = getSetCookieHeaderForWallId(result);
        // Extract value from "wallId=<uuid>;"
        String cookiePart = setCookieHeader.split(";")[0];
        String value = cookiePart.substring(cookiePart.indexOf('=') + 1).trim();
        assertThat(value)
                .as("wallId cookie value must be a UUID (UUID v4 format)")
                .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    // ---------------------------------------------------------------------------
    // Exactly one Set-Cookie for wallId (SR-TEST-03)
    // ---------------------------------------------------------------------------

    /**
     * Verifies that exactly one {@code Set-Cookie} header for the {@code wallId} cookie
     * is emitted on first visit.
     *
     * <p>Security requirement (SR-TEST-03): duplicate {@code Set-Cookie} headers for the
     * same cookie name would allow a confused-deputy attack where a second header with
     * weaker flags shadows the first.  Exactly one header must be present.
     */
    @Test
    void wallIdCookie_exactlyOneSetCookieHeaderEmitted() throws Exception {
        var result = mockMvc.perform(MockMvcRequestBuilders.get("/rest/wall-id"))
                .andExpect(status().isOk())
                .andReturn();

        long wallIdSetCookieCount = result.getResponse()
                .getHeaders("Set-Cookie").stream()
                .filter(h -> h.startsWith("wallId="))
                .count();
        assertThat(wallIdSetCookieCount)
                .as("exactly one Set-Cookie for wallId (SR-TEST-03)")
                .isEqualTo(1L);
    }

    // ============================================================================
    // Helpers
    // ============================================================================

    private String getSetCookieHeaderForWallId(
            org.springframework.test.web.servlet.MvcResult result) {
        List<String> headers = result.getResponse().getHeaders("Set-Cookie");
        return headers.stream()
                .filter(h -> h.startsWith("wallId="))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "No Set-Cookie header found for 'wallId'. Headers: " + headers));
    }
}
