package de.seism0saurus.glacier.webservice;

import de.seism0saurus.glacier.webservice.cache.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for CORS configuration on {@code /rest/*} (SR-6, D-08, ADR-06).
 *
 * Covers:
 * - Preflight from http://localhost:4200 → allowed
 * - Preflight from https://attacker.example → denied (no CORS headers)
 * - Access-Control-Allow-Credentials never present
 * - Allowed-origins list never contains wildcard '*'
 * - Production origin (https://${glacier.domain}) is allowed
 */
@WebMvcTest(controllers = {FallbackController.class, FallbackControllerAdvice.class})
@Import({CookieBasedFallbackAuthGuard.class, FallbackSecurityHeadersFilter.class})
@TestPropertySource(properties = {
        "glacier.domain=glacier.example.com",
        "glacier.fallback.enabled=true",
        "glacier.cache.maxHashtagsPerPrincipal=10",
        "glacier.fallback.ratelimit.perMinute=30",
        "glacier.fallback.ratelimit.perMinutePerIp=120",
        "glacier.ratelimit.eviction.intervalMs=600000"
})
class CorsConfigurationIT {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private MessageCache messageCache;

    @MockBean
    private FallbackRateLimiter rateLimiter;

    @BeforeEach
    void allowAll() {
        when(rateLimiter.check(any(), any())).thenReturn(FallbackRateLimiter.RateLimitResult.allowed());
    }

    // -------------------------------------------------------------------------
    // Allowed origin: http://localhost:4200 (Angular dev server)
    // -------------------------------------------------------------------------

    @Test
    void preflight_fromLocalhost4200_isAllowed() throws Exception {
        mockMvc.perform(options("/rest/messages")
                        .header(HttpHeaders.ORIGIN, "http://localhost:4200")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, HttpMethod.GET.name()))
                .andExpect(status().isOk())
                .andExpect(header().exists(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    void preflight_fromLocalhost4200_allowOriginMatchesRequest() throws Exception {
        MvcResult result = mockMvc.perform(options("/rest/messages")
                        .header(HttpHeaders.ORIGIN, "http://localhost:4200")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, HttpMethod.GET.name()))
                .andReturn();

        String allowOrigin = result.getResponse().getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
        assertThat(allowOrigin).isEqualTo("http://localhost:4200");
    }

    // -------------------------------------------------------------------------
    // Allowed origin: production domain
    // -------------------------------------------------------------------------

    @Test
    void preflight_fromProductionDomain_isAllowed() throws Exception {
        mockMvc.perform(options("/rest/messages")
                        .header(HttpHeaders.ORIGIN, "https://glacier.example.com")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, HttpMethod.GET.name()))
                .andExpect(status().isOk())
                .andExpect(header().exists(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    // -------------------------------------------------------------------------
    // Denied origin: attacker.example (T-18)
    // -------------------------------------------------------------------------

    @Test
    void preflight_fromAttackerOrigin_hasNoAllowOriginHeader() throws Exception {
        MvcResult result = mockMvc.perform(options("/rest/messages")
                        .header(HttpHeaders.ORIGIN, "https://attacker.example")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, HttpMethod.GET.name()))
                .andReturn();

        // CORS reject: no Access-Control-Allow-Origin header means browser blocks the request
        String allowOrigin = result.getResponse().getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
        assertThat(allowOrigin).isNull();
    }

    @Test
    void preflight_fromSubdomainOfProductionDomain_isDenied() throws Exception {
        // Only exact origin is allowed — subdomains are not
        MvcResult result = mockMvc.perform(options("/rest/messages")
                        .header(HttpHeaders.ORIGIN, "https://evil.glacier.example.com")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, HttpMethod.GET.name()))
                .andReturn();

        String allowOrigin = result.getResponse().getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
        assertThat(allowOrigin).isNull();
    }

    // -------------------------------------------------------------------------
    // Access-Control-Allow-Credentials must NEVER be present (D-08, ADR-06)
    // -------------------------------------------------------------------------

    @Test
    void preflight_allowCredentialsHeader_neverPresent() throws Exception {
        MvcResult result = mockMvc.perform(options("/rest/messages")
                        .header(HttpHeaders.ORIGIN, "http://localhost:4200")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, HttpMethod.GET.name()))
                .andReturn();

        String credentials = result.getResponse().getHeader("Access-Control-Allow-Credentials");
        // Must be null or "false" — never "true"
        assertThat(credentials).isNotEqualTo("true");
    }

    @Test
    void preflight_allowedOriginList_neverContainsWildcard() throws Exception {
        // Verify that the allowed-origins response is never '*' (ADR-06, SR-6)
        MvcResult result = mockMvc.perform(options("/rest/messages")
                        .header(HttpHeaders.ORIGIN, "http://localhost:4200")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, HttpMethod.GET.name()))
                .andReturn();

        String allowOrigin = result.getResponse().getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
        assertThat(allowOrigin).isNotEqualTo("*");
    }
}
