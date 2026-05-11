package de.seism0saurus.glacier.webservice;

import de.seism0saurus.glacier.GlacierCookieProperties;
import de.seism0saurus.glacier.GlacierOperatorProperties;
import de.seism0saurus.glacier.MastodonProperties;
import de.seism0saurus.glacier.mastodon.MastodonHandleFactory;
import de.seism0saurus.glacier.webservice.cache.FallbackRateLimiter;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
 * Covers (SR-3, D-09, SR-NEW-07, AC-12, AC-13):
 * - New cookie carries HttpOnly, Secure, SameSite=Lax, Path=/, Max-Age=2592000
 * - With glacier.cookie.secure=true (default): Secure flag present (SR-NEW-07, AC-12)
 * - With glacier.cookie.secure=false: Secure absent, HttpOnly + SameSite=Lax still present (AC-13)
 * - Existing valid cookie: no re-issue, no Set-Cookie header
 * - Rate limit exceeded on /rest/wall-id: 429 with Retry-After
 *
 * <p>Security reference: C5 — Secure Defaults; C7 — Digital Identities;
 * ASVS V7.1.1 (L1); WSTG-SESS-02.
 */
@WebMvcTest(InformationController.class)
@Import({MastodonProperties.class, MastodonHandleFactory.class, GlacierCookieProperties.class})
@TestPropertySource(properties = {
        "mastodon.handle=glacier@example.com",
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

    // GlacierOperatorProperties is a @Component @ConfigurationProperties bean not loaded by
    // @WebMvcTest slice — mock it so InformationController's constructor can be satisfied.
    @MockitoBean
    @SuppressWarnings("unused")
    private GlacierOperatorProperties glacierOperatorProperties;

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

    // =========================================================================
    // SR-NEW-07 / AC-12: glacier.cookie.secure=true (default) → Secure flag present
    // =========================================================================

    /**
     * IT-cookie-SR7-A: when {@code glacier.cookie.secure=true} (the production default),
     * every {@code Set-Cookie} header for {@code wallId} and for any issued cookie MUST
     * carry the {@code Secure} flag.
     *
     * <p>Tested here for the {@code wallId} cookie (issued by {@link InformationController}).
     * The class-level {@code @TestPropertySource} already sets {@code glacier.cookie.secure=true},
     * so these tests verify the default-on-Secure behaviour (SR-NEW-07, AC-12).
     *
     * <p>Security reference: C5 — Secure Defaults; C7 — Digital Identities;
     * ASVS V7.1.1 (L1); WSTG-SESS-02.
     */
    @Test
    void readCookie_secureTrueDefault_wallIdCookieCarriesSecureFlag_AC12() throws Exception {
        // glacier.cookie.secure=true is set at class level (line 31) — this confirms AC-12
        MvcResult result = mockMvc.perform(get("/rest/wall-id")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

        String setCookieHeader = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookieHeader)
                .as("AC-12 (SR-NEW-07, C5, ASVS V7.1.1, WSTG-SESS-02): "
                        + "with glacier.cookie.secure=true (default), "
                        + "Set-Cookie for wallId must carry the Secure flag. "
                        + "Actual header: [%s]", setCookieHeader)
                .containsIgnoringCase("Secure");
    }

    @Test
    void readCookie_secureTrueDefault_allSetCookieHeadersCarrySecureFlag_AC12() throws Exception {
        // Verify ALL Set-Cookie headers in the response carry Secure (not just the first)
        MvcResult result = mockMvc.perform(get("/rest/wall-id")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

        java.util.List<String> allSetCookieHeaders =
                result.getResponse().getHeaders(HttpHeaders.SET_COOKIE);

        assertThat(allSetCookieHeaders)
                .as("AC-12 (SR-NEW-07): there must be at least one Set-Cookie header")
                .isNotEmpty();

        for (String header : allSetCookieHeaders) {
            assertThat(header)
                    .as("AC-12 (SR-NEW-07, C5, ASVS V7.1.1, WSTG-SESS-02): "
                            + "every Set-Cookie with glacier.cookie.secure=true must carry Secure. "
                            + "Violating header: [%s]", header)
                    .containsIgnoringCase("Secure");
        }
    }

    // =========================================================================
    // SR-NEW-07 / AC-13: glacier.cookie.secure=false → Secure flag absent
    // (insecure-transport mode honesty — HTTP dev environment)
    // =========================================================================

    /**
     * IT-cookie-SR7-B: insecure-transport mode ({@code glacier.cookie.secure=false}).
     *
     * <p>When running over plain HTTP (e.g., developer loopback or the insecure Glacier
     * deployment mode), the {@code Secure} flag must be absent from {@code Set-Cookie}.
     * If {@code Secure} were present on HTTP, the browser would silently drop the cookie,
     * breaking the identity flow. The absence of {@code Secure} is intentional and required
     * in this mode (AC-13, glacier-fallback-mode-discipline).
     *
     * <p>Security reference: C5 — Secure Defaults; ASVS V7.1.1 (L1); WSTG-SESS-02.
     * Insecure-transport note: This mode is ONLY for dev/loopback. In production,
     * {@code glacier.cookie.secure=true} must always be set.
     */
    @Nested
    @WebMvcTest(InformationController.class)
    @Import({MastodonProperties.class, MastodonHandleFactory.class, GlacierCookieProperties.class})
    @TestPropertySource(properties = {
            "mastodon.handle=glacier@example.com",
            "glacier.cookie.secure=false",    // AC-13: insecure-transport mode (HTTP, dev/loopback)
            "glacier.fallback.ratelimit.perMinute=30",
            "glacier.fallback.ratelimit.perMinutePerIp=120",
            "glacier.ratelimit.eviction.intervalMs=600000"
    })
    class InsecureTransportCookieMode {

        @Autowired
        private MockMvc mockMvc;

        @MockitoBean
        private FallbackRateLimiter rateLimiter;

        // Note: GlacierOperatorProperties mock is inherited from the outer class.

        @org.junit.jupiter.api.BeforeEach
        void allowAll() {
            when(rateLimiter.checkIpOnly(any())).thenReturn(FallbackRateLimiter.RateLimitResult.allowed());
        }

        /**
         * AC-13: with {@code glacier.cookie.secure=false}, the {@code Secure} flag MUST be
         * absent from the {@code wallId} Set-Cookie header.
         *
         * <p>If Secure were present on HTTP, browsers would reject the cookie entirely,
         * breaking the identity flow. The absence is intentional and mode-correct.
         */
        @Test
        void readCookie_secureFalse_wallIdCookieDoesNotCarrySecureFlag_AC13() throws Exception {
            MvcResult result = mockMvc.perform(get("/rest/wall-id")
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andReturn();

            String setCookieHeader = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
            assertThat(setCookieHeader)
                    .as("AC-13 (SR-NEW-07, ASVS V7.1.1, WSTG-SESS-02): "
                            + "with glacier.cookie.secure=false (insecure-transport mode), "
                            + "Set-Cookie for wallId must NOT carry the Secure flag. "
                            + "If Secure is present in HTTP mode, browsers silently drop the cookie. "
                            + "Actual header: [%s]", setCookieHeader)
                    .isNotNull()
                    .doesNotContainIgnoringCase("Secure");
        }

        /**
         * AC-13 supplement: HttpOnly and SameSite=Lax must still be present even when
         * Secure is absent (insecure transport does not remove other security attributes).
         */
        @Test
        void readCookie_secureFalse_httpOnlyAndSameSiteLaxStillPresent_AC13() throws Exception {
            MvcResult result = mockMvc.perform(get("/rest/wall-id")
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andReturn();

            String setCookieHeader = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
            assertThat(setCookieHeader)
                    .as("AC-13 supplement: HttpOnly must still be present in insecure mode (T-02)")
                    .isNotNull()
                    .containsIgnoringCase("HttpOnly");

            assertThat(setCookieHeader)
                    .as("AC-13 supplement: SameSite=Lax must still be present in insecure mode (T-04)")
                    .containsIgnoringCase("SameSite=Lax");
        }
    }
}
