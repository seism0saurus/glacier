package de.seism0saurus.glacier.share.web;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests verifying that CSRF validation is enforced BEFORE rate-limit token
 * consumption on share-link state-changing endpoints (F-10).
 *
 * <h2>Security rationale</h2>
 * <p>If the rate limiter consumed a token BEFORE CSRF validation, an attacker could
 * exhaust a legitimate user's rate-limit bucket by sending requests with invalid CSRF
 * tokens — a Denial-of-Service vector against the victim's own endpoint quota.
 *
 * <p>The correct order enforced by {@link ShareLinkController} is:
 * <ol>
 *   <li>Authentication ({@link de.seism0saurus.glacier.webservice.FallbackAuthGuard}) → 401</li>
 *   <li>CSRF verification ({@link ShareCsrfGuard}) → 403</li>
 *   <li>Rate limiting ({@link ShareRateLimiter}) → 429</li>
 * </ol>
 *
 * <p>Two scenarios are tested:
 * <ul>
 *   <li>{@code csrf_fail_does_not_consume_rate_limit_token} — POST with bad CSRF → 403,
 *       then POST with good CSRF → NOT 429 (bucket must still have capacity).</li>
 *   <li>{@code rate_limit_failure_after_csrf_pass} — Exhaust the per-wallId bucket with
 *       valid CSRF requests → eventually 429 with {@code Retry-After} header.</li>
 * </ul>
 *
 * <p>Security: OWASP API4 (Lack of Resources &amp; Rate Limiting), SR-SHARE-05,
 * SR-SHARE-12, NIST SP 800-53 AC-3.
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
        "glacier.operatorName=Test",
        "glacier.operatorStreetAndNumber=Test 1",
        "glacier.operatorZipcode=12345",
        "glacier.operatorCity=Test",
        "glacier.operatorCountry=Test",
        "glacier.operatorPhone=+1",
        "glacier.operatorMail=test@test.com",
        "glacier.operatorWebsite=test.com",
        "glacier.share.imgproxy.hmacSecret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
        // FallbackRateLimiter requires these properties to construct (used by InformationController)
        "glacier.fallback.ratelimit.perMinute=30",
        "glacier.fallback.ratelimit.perMinutePerIp=120",
        "glacier.ratelimit.eviction.intervalMs=600000",
        // Set create-per-wallId to 1 so we can verify that a single failed CSRF
        // does NOT deplete the only available token.
        "glacier.share.ratelimit.create.perMinutePerWallId=1",
        // Keep the per-IP limit higher so only the per-wallId bucket is the constraint.
        "glacier.share.ratelimit.create.perMinutePerIp=10000",
        // CSRF token issuance is unlimited for these tests.
        "glacier.share.ratelimit.csrf.perMinutePerIp=10000",
        "glacier.share.ratelimit.fallback.perMinutePerViewer=10000",
        "glacier.share.ratelimit.fallback.perMinutePerIp=10000"
})
class ShareLinkCsrfRateLimitOrderingIT {

    /** wallId long enough (>32 chars) to pass the minimum length guard in ShareLinkController. */
    private static final String WALL_ID = "valid-wall-id-fixture-000000000000000";

    @Autowired
    private MockMvc mockMvc;

    // -------------------------------------------------------------------------
    // Scenario 1: CSRF failure must NOT consume a rate-limit token (F-10)
    // -------------------------------------------------------------------------

    /**
     * Verifies that a POST with an invalid CSRF token returns 403 and does NOT consume
     * a rate-limit token.
     *
     * <p>Setup: the per-wallId create bucket has capacity=1.
     * <ol>
     *   <li>First POST with BAD CSRF → 403 (CSRF rejected before rate-limit consulted).</li>
     *   <li>Obtain a valid CSRF token via GET /rest/share-csrf.</li>
     *   <li>Second POST with GOOD CSRF → must NOT be 429 (bucket still at capacity=1).</li>
     * </ol>
     *
     * <p>If the rate limiter ran before CSRF validation, step 3 would return 429 because
     * step 1 would have consumed the only token.
     */
    @Test
    void csrf_fail_does_not_consume_rate_limit_token() throws Exception {
        // Step 1: POST with missing CSRF token — must return 403, not 429.
        // The wallId cookie is present so authentication passes; CSRF fails next.
        mockMvc.perform(post("/rest/share-links")
                        .cookie(new Cookie("wallId", WALL_ID))
                        .header("Origin", "http://glacier.example.com")
                        // Deliberately omit the CSRF header — no X-Share-CSRF sent.
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden()); // 403 — CSRF rejected

        // Step 2: obtain a valid CSRF token from GET /rest/share-csrf.
        MvcResult csrfResult = mockMvc.perform(get("/rest/share-csrf"))
                .andExpect(status().isOk())
                .andReturn();

        String csrfToken = extractCsrfToken(csrfResult);
        String csrfCookieValue = extractCsrfCookieValue(csrfResult, csrfToken);

        // Step 3: POST with a valid CSRF token.
        // The per-wallId bucket still has capacity=1 because the failing CSRF call
        // in step 1 must NOT have consumed a token.
        // Authentication will succeed (wallId cookie present with sufficient length);
        // CSRF passes; rate limit should NOT be exhausted (returns 201 or 401, NOT 429).
        mockMvc.perform(post("/rest/share-links")
                        .cookie(new Cookie("wallId", WALL_ID))
                        .cookie(new Cookie("shareCsrf", csrfCookieValue))
                        .header("Origin", "http://glacier.example.com")
                        .header(ShareCsrfGuard.CSRF_HEADER, csrfToken)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    // Must NOT be 429 — the rate limit was not consumed by the failed CSRF call.
                    // May be 201 (created), 401 (wallId not recognized), or other non-429 status.
                    assertThat(status)
                            .as("CSRF failure must not consume a rate-limit token — "
                                    + "subsequent valid CSRF request must not be rate-limited (F-10)")
                            .isNotEqualTo(429);
                });
    }

    // -------------------------------------------------------------------------
    // Scenario 2: Rate-limit 429 with Retry-After after valid CSRF passes (F-10)
    // -------------------------------------------------------------------------

    /**
     * Verifies that repeated POST requests with valid CSRF tokens eventually exhaust
     * the rate-limit bucket and return 429 with a {@code Retry-After} header.
     *
     * <p>With perMinutePerWallId=1, the first valid POST consumes the only token;
     * the second valid POST must return 429.
     *
     * <p>This confirms:
     * <ul>
     *   <li>Rate limiting IS enforced for valid, authenticated, CSRF-passing requests.</li>
     *   <li>The {@code Retry-After} header is present (OWASP API4, SR-SHARE-12).</li>
     * </ul>
     */
    @Test
    void rate_limit_failure_after_csrf_pass() throws Exception {
        // Obtain first CSRF token.
        MvcResult csrf1 = mockMvc.perform(get("/rest/share-csrf"))
                .andExpect(status().isOk())
                .andReturn();
        String token1 = extractCsrfToken(csrf1);
        String cookie1 = extractCsrfCookieValue(csrf1, token1);

        // First POST with valid CSRF — consumes the only token in the per-wallId bucket.
        // The response may be 201 (share created) or 401 (wallId not fully recognized),
        // but it must NOT be 429 (rate limit not yet exhausted).
        mockMvc.perform(post("/rest/share-links")
                        .cookie(new Cookie("wallId", WALL_ID))
                        .cookie(new Cookie("shareCsrf", cookie1))
                        .header("Origin", "http://glacier.example.com")
                        .header(ShareCsrfGuard.CSRF_HEADER, token1)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    assertThat(status)
                            .as("First valid request must not be rate-limited")
                            .isNotEqualTo(429);
                });

        // Obtain second CSRF token (new token; first was single-use or re-issued).
        MvcResult csrf2 = mockMvc.perform(get("/rest/share-csrf"))
                .andExpect(status().isOk())
                .andReturn();
        String token2 = extractCsrfToken(csrf2);
        String cookie2 = extractCsrfCookieValue(csrf2, token2);

        // Second POST with valid CSRF — per-wallId bucket is exhausted (capacity=1).
        // Rate limit must fire and return 429 with Retry-After.
        mockMvc.perform(post("/rest/share-links")
                        .cookie(new Cookie("wallId", WALL_ID))
                        .cookie(new Cookie("shareCsrf", cookie2))
                        .header("Origin", "http://glacier.example.com")
                        .header(ShareCsrfGuard.CSRF_HEADER, token2)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isTooManyRequests())  // 429
                .andExpect(header().exists("Retry-After")); // SR-SHARE-12
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Extracts the {@code token} field from the JSON body of a CSRF issuance response.
     *
     * @param result the MVC result from GET /rest/share-csrf
     * @return the CSRF token string
     * @throws Exception on JSON parse error
     */
    private String extractCsrfToken(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString();
        return new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(body)
                .get("token")
                .asText();
    }

    /**
     * Extracts the CSRF cookie value from the {@code Set-Cookie} header of a
     * CSRF issuance response.  Falls back to the token value itself if parsing fails
     * (the double-submit pattern requires both header and cookie to match).
     *
     * @param result     the MVC result from GET /rest/share-csrf
     * @param tokenValue the token value extracted from the JSON body (used as fallback)
     * @return the cookie value to supply as the {@code shareCsrf} cookie
     */
    private String extractCsrfCookieValue(MvcResult result, String tokenValue) {
        String setCookieHeader = result.getResponse().getHeader("Set-Cookie");
        if (setCookieHeader != null) {
            for (String part : setCookieHeader.split(";")) {
                String trimmed = part.trim();
                if (trimmed.startsWith("shareCsrf=")) {
                    return trimmed.substring("shareCsrf=".length());
                }
            }
        }
        // Fallback: use the token body value as the cookie value (they should be equal)
        return tokenValue;
    }
}
