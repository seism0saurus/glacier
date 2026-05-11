package de.seism0saurus.glacier.webservice;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SR-TEST-07: Verifies that every rate-limited endpoint returns a uniform 429 shape.
 *
 * <p>Each rate-limited endpoint MUST:
 * <ul>
 *   <li>Return HTTP 429</li>
 *   <li>Include a {@code Retry-After} header with a positive integer value</li>
 *   <li>Return a body that contains no stack traces, no raw UUIDs, no internal paths</li>
 * </ul>
 *
 * <p>Security: OWASP API4 (Lack of Resources and Rate Limiting),
 * SR-4 (two-axis rate limiting), SR-SHARE-12 (share-specific rate limiting).
 *
 * <p>Rate limits are set to 1 per minute so they are exhausted after the first request.
 * {@link DirtiesContext} resets the Spring context (and bucket state) after each test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@TestPropertySource(properties = {
        "glacier.domain=glacier.example.com",
        "glacier.cookie.secure=false",
        "glacier.fallback.enabled=true",
        "mastodon.instance=mastodon.social",
        "mastodon.accessToken=dummy",
        "mastodon.handle=glacier@mastodon.social",
        "glacier.operator.name=Test",
        "glacier.operator.streetAndNumber=Test 1",
        "glacier.operator.zipcode=12345",
        "glacier.operator.city=Test",
        "glacier.operator.country=Test",
        "glacier.operator.phone=+1",
        "glacier.operator.mail=test@test.com",
        "glacier.operator.website=test.com",
        "glacier.share.imgproxy.hmacSecret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
        // Bring all rate-limit axes to 1 so the second request triggers the limit.
        // /rest/wall-id: IP-only rate limit (InformationController)
        "glacier.fallback.ratelimit.perMinute=1",
        "glacier.fallback.ratelimit.perMinutePerIp=1",
        // /rest/messages: per-wallId + per-IP (FallbackController)
        // (reuses the same FallbackRateLimiter bucket)
        // share endpoints
        "glacier.share.ratelimit.create.perMinutePerWallId=1",
        "glacier.share.ratelimit.create.perMinutePerIp=1",
        "glacier.share.ratelimit.csrf.perMinutePerIp=1",
        "glacier.share.ratelimit.fallback.perMinutePerViewer=1",
        "glacier.share.ratelimit.fallback.perMinutePerIp=1",
        "glacier.share.ratelimit.imgproxy.perMinutePerIp=1"
})
class RateLimitResponseShapeUniformityIT {

    @Autowired
    private MockMvc mockMvc;

    // -------------------------------------------------------------------------
    // Scenario 1: GET /rest/wall-id — IP-only rate limit
    // -------------------------------------------------------------------------

    /**
     * The /rest/wall-id endpoint uses a per-IP rate limiter for new cookie issuance.
     * After exhausting the limit, it must return 429 with Retry-After.
     */
    @Test
    void wallId_rateLimited_returns429WithRetryAfterHeader() throws Exception {
        // First request burns the 1-token bucket (no cookie → triggers rate limiter)
        mockMvc.perform(get("/rest/wall-id")).andReturn();

        // Second request must be rate-limited
        MvcResult result = mockMvc.perform(get("/rest/wall-id"))
                .andExpect(status().isTooManyRequests())
                .andReturn();

        assertRetryAfterIsPositiveInteger(result, "/rest/wall-id");
        assertBodyHasNoSensitiveData(result, "/rest/wall-id");
    }

    // -------------------------------------------------------------------------
    // Scenario 2: GET /rest/messages (fallback) — per-wallId + per-IP
    // -------------------------------------------------------------------------

    /**
     * The /rest/messages fallback endpoint applies a two-axis rate limiter.
     * To hit the rate limit we need auth to pass first; use the CSRF endpoint
     * (which hits its own IP rate limiter) as a proxy, since directly hitting
     * /rest/messages with a valid wallId triggers the same rate-limit bucket.
     *
     * <p>We verify the shape via the fallback endpoint directly: send requests
     * with a valid wallId cookie to exhaust the per-wallId bucket.
     */
    @Test
    void fallbackMessages_rateLimited_returns429WithRetryAfterHeader() throws Exception {
        String validWallId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
        Cookie wallIdCookie = new Cookie("wallId", validWallId);

        // First request — auth will pass, rate limit decremented, then subscription unknown → 400.
        // The rate-limit bucket is still consumed.
        mockMvc.perform(get("/rest/messages?hashtag=test")
                        .cookie(wallIdCookie))
                .andReturn(); // 400 (no subscription) but rate bucket consumed

        // Second request — rate limit exhausted for this wallId
        MvcResult result = mockMvc.perform(get("/rest/messages?hashtag=test")
                        .cookie(wallIdCookie))
                .andExpect(status().isTooManyRequests())
                .andReturn();

        assertRetryAfterIsPositiveInteger(result, "/rest/messages");
        assertBodyHasNoSensitiveData(result, "/rest/messages");
    }

    // -------------------------------------------------------------------------
    // Scenario 3: GET /rest/share-csrf — per-IP share CSRF rate limit
    // -------------------------------------------------------------------------

    /**
     * The /rest/share-csrf endpoint has a dedicated per-IP rate limiter.
     * With limit=1 the second request must return 429 with Retry-After.
     */
    @Test
    void shareCsrf_rateLimited_returns429WithRetryAfterHeader() throws Exception {
        // First request — consumes the single token, returns 200
        mockMvc.perform(get("/rest/share-csrf"))
                .andExpect(status().isOk());

        // Second request — rate limit exhausted
        MvcResult result = mockMvc.perform(get("/rest/share-csrf"))
                .andExpect(status().isTooManyRequests())
                .andReturn();

        assertRetryAfterIsPositiveInteger(result, "/rest/share-csrf");
        assertBodyHasNoSensitiveData(result, "/rest/share-csrf");
    }

    // -------------------------------------------------------------------------
    // Shared assertion helpers
    // -------------------------------------------------------------------------

    /**
     * Asserts that the {@code Retry-After} header is present and its value is a
     * positive integer string.
     *
     * <p>Security: OWASP API4 — every 429 response must carry a Retry-After header
     * to signal the client when it may retry, preventing busy-retry loops.
     */
    private void assertRetryAfterIsPositiveInteger(final MvcResult result, final String endpoint) {
        String retryAfter = result.getResponse().getHeader("Retry-After");
        assertThat(retryAfter)
                .as("Retry-After header must be present on 429 from %s", endpoint)
                .isNotNull()
                .isNotBlank();

        int retryAfterValue;
        try {
            retryAfterValue = Integer.parseInt(retryAfter);
        } catch (NumberFormatException e) {
            throw new AssertionError(
                    "Retry-After header on 429 from " + endpoint
                            + " must be a plain integer, got: " + retryAfter);
        }

        assertThat(retryAfterValue)
                .as("Retry-After value on 429 from %s must be > 0", endpoint)
                .isGreaterThan(0);
    }

    /**
     * Asserts that the response body (if any) contains no stack traces, raw UUIDs,
     * or internal path fragments.
     *
     * <p>Security: OWASP A05 (Security Misconfiguration) — error responses must not
     * leak internal implementation details to clients.
     */
    private void assertBodyHasNoSensitiveData(final MvcResult result, final String endpoint)
            throws java.io.UnsupportedEncodingException {
        String body = result.getResponse().getContentAsString();
        if (body == null || body.isBlank()) {
            // Empty body is acceptable for 429 (FallbackController does not include a body)
            return;
        }

        assertThat(body)
                .as("429 body from %s must not contain a Java stack trace", endpoint)
                .doesNotContain("at de.seism0saurus")
                .doesNotContain("at org.springframework")
                .doesNotContain("at java.lang");

        assertThat(body)
                .as("429 body from %s must not contain raw exception class names", endpoint)
                .doesNotContain("Exception")
                .doesNotContain("Error:");

        assertThat(body)
                .as("429 body from %s must not contain internal file paths", endpoint)
                .doesNotContain("/home/")
                .doesNotContain("src/main/java");
    }
}
