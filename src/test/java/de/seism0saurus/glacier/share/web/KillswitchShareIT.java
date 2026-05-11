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
 * Integration tests verifying share endpoint behavior when the fallback/killswitch is disabled.
 *
 * <p>When {@code glacier.fallback.enabled=false}, the share-link fallback polling path
 * (catalog, CSRF token, img-proxy) must return appropriate responses.
 * The killswitch must not prevent the share application from starting.
 *
 * <p>Security: SR-SHARE-16 (killswitch graceful degradation).
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "glacier.domain=glacier.example.com",
        "glacier.cookie.secure=false",
        "glacier.fallback.enabled=false", // KILLSWITCH: fallback disabled
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
        "glacier.share.imgproxy.hmacSecret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
})
class KillswitchShareIT {

    @Autowired
    private MockMvc mockMvc;

    /**
     * Verifies the application starts successfully even with fallback disabled.
     * The killswitch must not cause a boot failure.
     */
    @Test
    void applicationContext_loadsWithKillswitch() throws Exception {
        // If the context loads and this test runs, the application started correctly.
        // An additional request confirms the context is responding.
        mockMvc.perform(get("/rest/share-csrf"))
                .andExpect(result -> {
                    // May be 200 (endpoint available) or 503 (killswitch applied)
                    // but must NOT be a 5xx caused by context failure
                    int status = result.getResponse().getStatus();
                    org.assertj.core.api.Assertions.assertThat(status)
                            .as("Application must respond — not a context boot failure")
                            .isIn(200, 404, 503);
                });
    }

    /**
     * With fallback disabled, the catalog endpoint should still return 404
     * (not found for unknown share ID), not an internal error.
     * The killswitch must not break the anti-enumeration invariant.
     */
    @Test
    void catalogEndpoint_withKillswitch_returns404ForUnknown() throws Exception {
        mockMvc.perform(get("/rest/share/sv_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA/catalog"))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    // Must be 404 (not found) or 503 (service unavailable — killswitch)
                    // Must NOT be 500 (internal server error) — that would be a bug
                    org.assertj.core.api.Assertions.assertThat(status)
                            .as("Killswitch must not cause 500 — must be 404 or 503")
                            .isIn(404, 503);
                });
    }

    /**
     * The killswitch mode must not expose internal error messages or stack traces.
     * Response body for rejected requests must be minimal (no debug info).
     */
    @Test
    void killswitchResponse_doesNotLeakInternalInfo() throws Exception {
        mockMvc.perform(get("/rest/share/sv_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA/catalog"))
                .andExpect(result -> {
                    String body = result.getResponse().getContentAsString();
                    org.assertj.core.api.Assertions.assertThat(body)
                            .as("Response body must not contain stack trace or class names")
                            .doesNotContain("java.lang.", "Exception", "at de.seism0saurus");
                });
    }

    /**
     * Fix 7: GET /rest/share/{id}/messages in killswitch mode must return 404.
     *
     * <p>glacier-fallback-mode-discipline: when {@code glacier.fallback.enabled=false},
     * the fallback polling endpoint must be completely disabled (404), not just rate-limited.
     * This prevents clients from discovering that the endpoint exists in killswitch mode.
     *
     * <p>Security: SR-SHARE-13 (killswitch disables polling path).
     */
    @Test
    void messagesEndpoint_withKillswitch_returns404() throws Exception {
        mockMvc.perform(get("/rest/share/sv_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA/messages")
                        .param("hashtag", "cats"))
                .andExpect(status().isNotFound());
    }
}
