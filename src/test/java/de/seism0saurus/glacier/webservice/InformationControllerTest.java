package de.seism0saurus.glacier.webservice;

import de.seism0saurus.glacier.webservice.cache.FallbackRateLimiter;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import social.bigbone.MastodonClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// SR-SHARE-10: ImageProxyHmacSecretValidator fails-closed in prod (glacier.cookie.secure=true default);
// supply a valid 32-byte hmacSecret so the Spring context boots successfully under @SpringBootTest.
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "glacier.cookie.secure=true",
        "glacier.fallback.ratelimit.perMinute=30",
        "glacier.fallback.ratelimit.perMinutePerIp=120",
        "glacier.ratelimit.eviction.intervalMs=600000",
        "glacier.share.imgproxy.hmacSecret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
})
class InformationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    /**
     * MastodonClient needs to be mocked because it directly tests the connection to a nonexistent webservice.
     */
    @SuppressWarnings("unused")
    @MockitoBean
    private MastodonClient mastodonClient;

    @MockitoBean
    private FallbackRateLimiter rateLimiter;

    @BeforeEach
    void allowAll() {
        when(rateLimiter.checkIpOnly(any())).thenReturn(FallbackRateLimiter.RateLimitResult.allowed());
    }

    // -------------------------------------------------------------------------
    // Existing tests (preserved)
    // -------------------------------------------------------------------------

    @Test
    void testReadCookieWithExistingWallId() throws Exception {
        String existingWallId = "test-existing-wall-id";

        mockMvc.perform(MockMvcRequestBuilders.get("/rest/wall-id")
                        .cookie(new Cookie("wallId", existingWallId)))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(MockMvcResultMatchers.jsonPath("$.id").value(existingWallId));
    }

    @Test
    void testReadCookieWhenWallIdIsNotPresent() throws Exception {

        mockMvc.perform(MockMvcRequestBuilders.get("/rest/wall-id"))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(MockMvcResultMatchers.jsonPath("$.id").isNotEmpty());
    }

    @Test
    void testGetMastodonHandle() throws Exception {

        mockMvc.perform(MockMvcRequestBuilders.get("/rest/mastodon-handle"))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(MockMvcResultMatchers.jsonPath("$.name").value("bot-account@my-instance.social"));
    }

    @Test
    void testGetInstanceOperator() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/rest/operator"))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(MockMvcResultMatchers.jsonPath("$.domain").value("example.com"))
                .andExpect(MockMvcResultMatchers.jsonPath("$.operatorName").value("Jon Doe"))
                .andExpect(MockMvcResultMatchers.jsonPath("$.operatorStreetAndNumber").value("somewhere 1"))
                .andExpect(MockMvcResultMatchers.jsonPath("$.operatorZipcode").value("12345"))
                .andExpect(MockMvcResultMatchers.jsonPath("$.operatorCity").value("somecity"))
                .andExpect(MockMvcResultMatchers.jsonPath("$.operatorCountry").value("Germany"))
                .andExpect(MockMvcResultMatchers.jsonPath("$.operatorPhone").value("+123456789"))
                .andExpect(MockMvcResultMatchers.jsonPath("$.operatorMail").value("mail@example.com"))
                .andExpect(MockMvcResultMatchers.jsonPath("$.operatorWebsite").value("example.com"));
    }

    // -------------------------------------------------------------------------
    // SR-3 / D-09: Cookie attribute assertions (new tests)
    // -------------------------------------------------------------------------

    @Test
    void readCookie_newCookie_hasHttpOnlyAttribute() throws Exception {
        MvcResult result = mockMvc.perform(get("/rest/wall-id")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

        String setCookieHeader = result.getResponse().getHeader("Set-Cookie");
        assertThat(setCookieHeader).isNotNull();
        assertThat(setCookieHeader).containsIgnoringCase("HttpOnly");
    }

    @Test
    void readCookie_newCookie_hasSecureAttribute() throws Exception {
        MvcResult result = mockMvc.perform(get("/rest/wall-id")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

        String setCookieHeader = result.getResponse().getHeader("Set-Cookie");
        assertThat(setCookieHeader).isNotNull();
        assertThat(setCookieHeader).containsIgnoringCase("Secure");
    }

    @Test
    void readCookie_newCookie_hasSameSiteLaxAttribute() throws Exception {
        MvcResult result = mockMvc.perform(get("/rest/wall-id")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

        String setCookieHeader = result.getResponse().getHeader("Set-Cookie");
        assertThat(setCookieHeader).isNotNull();
        assertThat(setCookieHeader).containsIgnoringCase("SameSite=Lax");
    }

    @Test
    void readCookie_newCookie_hasMaxAge2592000() throws Exception {
        MvcResult result = mockMvc.perform(get("/rest/wall-id")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

        String setCookieHeader = result.getResponse().getHeader("Set-Cookie");
        assertThat(setCookieHeader).isNotNull();
        assertThat(setCookieHeader).containsIgnoringCase("Max-Age=2592000");
    }

    @Test
    void readCookie_existingCookie_noSetCookieHeader() throws Exception {
        // Legacy cookie still accepted — no re-issue (D-09)
        MvcResult result = mockMvc.perform(get("/rest/wall-id")
                        .cookie(new Cookie("wallId", "existing-wall-id"))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getResponse().getHeader("Set-Cookie")).isNull();
    }

    // -------------------------------------------------------------------------
    // SR-4: Per-IP rate limiting on /rest/wall-id (non-blocking Phase 2 fix #1)
    // -------------------------------------------------------------------------

    @Test
    void readCookie_perIpRateLimitExceeded_returns429() throws Exception {
        when(rateLimiter.checkIpOnly(any()))
                .thenReturn(FallbackRateLimiter.RateLimitResult.rejected(30L));

        mockMvc.perform(get("/rest/wall-id")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "30"));
    }

    @Test
    void readCookie_perIpRateLimitExceeded_noNewCookieEmitted() throws Exception {
        when(rateLimiter.checkIpOnly(any()))
                .thenReturn(FallbackRateLimiter.RateLimitResult.rejected(30L));

        MvcResult result = mockMvc.perform(get("/rest/wall-id")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isTooManyRequests())
                .andReturn();

        // No Set-Cookie header on 429
        assertThat(result.getResponse().getHeader("Set-Cookie")).isNull();
    }
}
