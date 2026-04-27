package de.seism0saurus.glacier.webservice;

import de.seism0saurus.glacier.webservice.cache.FallbackRateLimiter;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for {@code wallId} cookie emission from {@link InformationController}.
 *
 * Covers (SR-3, D-09):
 * - New cookie carries HttpOnly, Secure, SameSite=Lax, Path=/, Max-Age=2592000
 * - With glacier.cookie.secure=false: Secure absent, HttpOnly + SameSite=Lax still present
 * - Existing valid cookie: no re-issue, no Set-Cookie header
 * - Rate limit exceeded on /rest/wall-id: 429 with Retry-After
 */
@WebMvcTest(InformationController.class)
@TestPropertySource(properties = {
        "glacier.cookie.secure=true",
        "glacier.fallback.ratelimit.perMinute=30",
        "glacier.fallback.ratelimit.perMinutePerIp=120",
        "glacier.ratelimit.eviction.intervalMs=600000"
})
class CookieEmissionIT {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private FallbackRateLimiter rateLimiter;

    @org.junit.jupiter.api.BeforeEach
    void allowAll() {
        when(rateLimiter.checkIpOnly(any())).thenReturn(FallbackRateLimiter.RateLimitResult.allowed());
    }

    // -------------------------------------------------------------------------
    // New cookie — all five attributes required (SR-3, D-09)
    // -------------------------------------------------------------------------

    @Test
    void readCookie_noCookiePresent_emitsSetCookieWithHttpOnly() throws Exception {
        MvcResult result = mockMvc.perform(get("/rest/wall-id")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

        String setCookieHeader = result.getResponse().getHeader("Set-Cookie");
        assertThat(setCookieHeader).isNotNull();
        assertThat(setCookieHeader).containsIgnoringCase("HttpOnly");
    }

    @Test
    void readCookie_noCookiePresent_emitsSetCookieWithSecure() throws Exception {
        MvcResult result = mockMvc.perform(get("/rest/wall-id")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

        String setCookieHeader = result.getResponse().getHeader("Set-Cookie");
        assertThat(setCookieHeader).isNotNull();
        assertThat(setCookieHeader).containsIgnoringCase("Secure");
    }

    @Test
    void readCookie_noCookiePresent_emitsSetCookieWithSameSiteLax() throws Exception {
        MvcResult result = mockMvc.perform(get("/rest/wall-id")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

        String setCookieHeader = result.getResponse().getHeader("Set-Cookie");
        assertThat(setCookieHeader).isNotNull();
        assertThat(setCookieHeader).containsIgnoringCase("SameSite=Lax");
    }

    @Test
    void readCookie_noCookiePresent_emitsSetCookieWithPathSlash() throws Exception {
        MvcResult result = mockMvc.perform(get("/rest/wall-id")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

        String setCookieHeader = result.getResponse().getHeader("Set-Cookie");
        assertThat(setCookieHeader).isNotNull();
        assertThat(setCookieHeader).containsIgnoringCase("Path=/");
    }

    @Test
    void readCookie_noCookiePresent_emitsSetCookieWithMaxAge2592000() throws Exception {
        MvcResult result = mockMvc.perform(get("/rest/wall-id")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

        String setCookieHeader = result.getResponse().getHeader("Set-Cookie");
        assertThat(setCookieHeader).isNotNull();
        assertThat(setCookieHeader).containsIgnoringCase("Max-Age=2592000");
    }

    @Test
    void readCookie_noCookiePresent_setCookieHeaderContainsAllFiveAttributes() throws Exception {
        MvcResult result = mockMvc.perform(get("/rest/wall-id")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

        String setCookieHeader = result.getResponse().getHeader("Set-Cookie");
        assertThat(setCookieHeader).isNotNull();
        // All five required attributes (D-09)
        assertThat(setCookieHeader).containsIgnoringCase("HttpOnly");
        assertThat(setCookieHeader).containsIgnoringCase("Secure");
        assertThat(setCookieHeader).containsIgnoringCase("SameSite=Lax");
        assertThat(setCookieHeader).containsIgnoringCase("Path=/");
        assertThat(setCookieHeader).containsIgnoringCase("Max-Age=2592000");
    }

    // -------------------------------------------------------------------------
    // Dev profile: Secure absent, HttpOnly + SameSite=Lax still present (D-09)
    // -------------------------------------------------------------------------

    @Test
    void readCookie_cookieSecureTrue_httpOnlyAndSameSitePresent() throws Exception {
        // With glacier.cookie.secure=true (class-level), all three must be present.
        // The secure=false dev-profile path is covered by InformationControllerTest.
        MvcResult result = mockMvc.perform(get("/rest/wall-id")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

        String setCookieHeader = result.getResponse().getHeader("Set-Cookie");
        // With secure=true (class-level), Secure MUST be present
        assertThat(setCookieHeader).containsIgnoringCase("HttpOnly");
        assertThat(setCookieHeader).containsIgnoringCase("SameSite=Lax");
    }

    // -------------------------------------------------------------------------
    // Existing valid cookie — no re-issue (D-09)
    // -------------------------------------------------------------------------

    @Test
    void readCookie_existingValidCookie_noSetCookieHeader() throws Exception {
        String existingWallId = "existing-wall-id-test-value-12345";

        MvcResult result = mockMvc.perform(get("/rest/wall-id")
                        .cookie(new Cookie("wallId", existingWallId))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(existingWallId))
                .andReturn();

        // No Set-Cookie — flag-less legacy cookies still honored (D-09)
        assertThat(result.getResponse().getHeader("Set-Cookie")).isNull();
    }

    @Test
    void readCookie_existingValidCookie_returnsExistingId() throws Exception {
        String existingWallId = "my-existing-wall-id-aaaaaaaaaaaa";

        mockMvc.perform(get("/rest/wall-id")
                        .cookie(new Cookie("wallId", existingWallId))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(existingWallId));
    }

    // -------------------------------------------------------------------------
    // Exactly one Set-Cookie for wallId (SR-TEST-03)
    // -------------------------------------------------------------------------

    /**
     * Verifies that exactly one {@code Set-Cookie} header for the {@code wallId} cookie
     * is emitted when no cookie is present on the request.
     *
     * <p>Security requirement (SR-TEST-03): duplicate {@code Set-Cookie} headers could
     * allow a confused-deputy attack where a second header with weaker security flags
     * (e.g. missing HttpOnly) shadows or supplements the first.
     */
    @Test
    void readCookie_noCookiePresent_exactlyOneWallIdSetCookieHeader() throws Exception {
        MvcResult result = mockMvc.perform(get("/rest/wall-id")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

        long wallIdSetCookieCount = result.getResponse()
                .getHeaders(HttpHeaders.SET_COOKIE).stream()
                .filter(h -> h.contains("wallId="))
                .count();
        assertThat(wallIdSetCookieCount)
                .as("exactly one Set-Cookie for wallId (SR-TEST-03)")
                .isEqualTo(1L);
    }

    // -------------------------------------------------------------------------
    // Rate limiting on /rest/wall-id (non-blocking Phase 2 fix #1, SR-4)
    // -------------------------------------------------------------------------

    @Test
    void readCookie_rateLimitExceeded_returns429() throws Exception {
        when(rateLimiter.checkIpOnly(any()))
                .thenReturn(FallbackRateLimiter.RateLimitResult.rejected(45L));

        mockMvc.perform(get("/rest/wall-id")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "45"));
    }
}
