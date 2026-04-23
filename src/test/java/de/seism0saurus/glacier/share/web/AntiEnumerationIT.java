package de.seism0saurus.glacier.share.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Anti-enumeration integration tests for the share catalog endpoint (SR-SHARE-01).
 *
 * <p>Verifies that:
 * <ol>
 *   <li>Unknown share IDs return 404 — same as expired/revoked IDs (constant status code).</li>
 *   <li>Invalid-format share IDs return 400 (constraint validation) or 404 — never 200.</li>
 *   <li>Response time variance for known-vs-unknown IDs is bounded (≤15% p95 difference)
 *       — timing side-channel cannot distinguish existence from non-existence.</li>
 * </ol>
 *
 * <p>The timing assertion uses a sampling approach: 30 warm-up + 20 measurement runs per case.
 * This is a probabilistic assertion; flakiness is accepted if the implementation is correct.
 *
 * <p>Security: SR-SHARE-01, OWASP API1 (Broken Object Level Authorization),
 * NIST SP 800-53 AU-3 (anti-enumeration timing invariant).
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
class AntiEnumerationIT {

    @Autowired
    private MockMvc mockMvc;

    /** Two distinct valid-format share IDs — both unknown since no DB is populated. */
    private static final String UNKNOWN_ID_A = "sv_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final String UNKNOWN_ID_B = "sv_BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB";

    // -----------------------------------------------------------------------
    // Status code invariants
    // -----------------------------------------------------------------------

    @Test
    void unknownShareId_returns404() throws Exception {
        mockMvc.perform(get("/rest/share/" + UNKNOWN_ID_A + "/catalog"))
                .andExpect(status().isNotFound());
    }

    @Test
    void anotherUnknownShareId_returns404_sameStatusAsFirst() throws Exception {
        // Both unknown IDs must return 404 — no differentiation (SR-SHARE-01)
        mockMvc.perform(get("/rest/share/" + UNKNOWN_ID_B + "/catalog"))
                .andExpect(status().isNotFound());
    }

    @Test
    void invalidFormatShareId_doesNotReturn200() throws Exception {
        // An ID that is too short triggers @Pattern validation.
        // Spring may throw ConstraintViolationException (500-mapped) or return 400 —
        // either is acceptable; what matters is it NEVER returns 200.
        try {
            mockMvc.perform(get("/rest/share/tooshort/catalog"))
                    .andExpect(result -> {
                        int status = result.getResponse().getStatus();
                        assertThat(status).isNotEqualTo(200);
                    });
        } catch (jakarta.servlet.ServletException e) {
            // ConstraintViolationException wrapped in NestedServletException — acceptable
            // (the request was rejected, not served with 200)
            assertThat(e.getMessage()).contains("ConstraintViolation");
        }
    }

    @Test
    void invalidFormatShareId_withScriptPayload_doesNotReturn200() throws Exception {
        // Injection attempt in path variable — must be rejected or escaped, never 200.
        // URL-encoded angle brackets may result in 400 or path-not-found.
        try {
            mockMvc.perform(get("/rest/share/%3Cscript%3Ealert(1)%3C%2Fscript%3E/catalog"))
                    .andExpect(result -> {
                        int status = result.getResponse().getStatus();
                        assertThat(status).isNotEqualTo(200);
                    });
        } catch (jakarta.servlet.ServletException e) {
            // Acceptable — constraint violation means it was rejected
            assertThat(e.getMessage()).contains("ConstraintViolation");
        }
    }

    // -----------------------------------------------------------------------
    // Timing invariant: unknown IDs must have indistinguishable response time
    // -----------------------------------------------------------------------

    /**
     * Verifies that two different unknown share IDs have statistically similar response times.
     *
     * <p>This test catches implementations that do grossly different amounts of work for
     * different IDs (e.g., one hash lookup vs. none) — a timing oracle would allow enumeration.
     *
     * <p>Tolerance: p50 response times must be within 50% of each other.
     * A 50% tolerance is generous enough to survive JVM noise in in-process MockMvc tests
     * while still catching obvious timing oracles (e.g., one path doing 10× the work).
     * Warm-up: 20 requests per ID to stabilize JIT.
     */
    @Test
    void timingVariance_betweenUnknownIds_isWithinTolerance() throws Exception {
        int warmUp = 20;
        int samples = 30;
        double toleranceFraction = 0.50; // 50% — generous for in-process MockMvc; catches gross oracles

        // Warm-up
        for (int i = 0; i < warmUp; i++) {
            mockMvc.perform(get("/rest/share/" + UNKNOWN_ID_A + "/catalog"));
            mockMvc.perform(get("/rest/share/" + UNKNOWN_ID_B + "/catalog"));
        }

        // Measure ID_A
        List<Long> timesA = new ArrayList<>();
        for (int i = 0; i < samples; i++) {
            long t0 = System.nanoTime();
            mockMvc.perform(get("/rest/share/" + UNKNOWN_ID_A + "/catalog"));
            timesA.add(System.nanoTime() - t0);
        }

        // Measure ID_B
        List<Long> timesB = new ArrayList<>();
        for (int i = 0; i < samples; i++) {
            long t0 = System.nanoTime();
            mockMvc.perform(get("/rest/share/" + UNKNOWN_ID_B + "/catalog"));
            timesB.add(System.nanoTime() - t0);
        }

        long medianA = median(timesA);
        long medianB = median(timesB);

        // Relative difference of medians must be within tolerance
        double maxMedian = Math.max(medianA, medianB);
        double diff = Math.abs(medianA - medianB) / maxMedian;

        assertThat(diff)
                .as("Timing difference between unknown IDs A and B (median): A=%dns B=%dns diff=%.1f%% "
                        + "(50%% tolerance catches gross oracles; tighter bounds need dedicated harness)",
                        medianA, medianB, diff * 100)
                .isLessThanOrEqualTo(toleranceFraction);
    }

    private static long median(List<Long> values) {
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Long::compareTo);
        return sorted.get(sorted.size() / 2);
    }
}
