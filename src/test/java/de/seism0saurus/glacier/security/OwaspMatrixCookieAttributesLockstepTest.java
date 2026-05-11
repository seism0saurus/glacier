package de.seism0saurus.glacier.security;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import de.seism0saurus.glacier.GlacierCookieProperties;
import de.seism0saurus.glacier.GlacierOperatorProperties;
import de.seism0saurus.glacier.MastodonProperties;
import de.seism0saurus.glacier.mastodon.MastodonHandleFactory;
import de.seism0saurus.glacier.share.application.ShareLinkService;
import de.seism0saurus.glacier.share.application.ShareViewStompRelay;
import de.seism0saurus.glacier.share.web.CsrfTokenCookieFactory;
import de.seism0saurus.glacier.share.web.ShareRateLimiter;
import de.seism0saurus.glacier.share.web.ShareViewController;
import de.seism0saurus.glacier.share.web.ShareViewerCookieFactory;
import de.seism0saurus.glacier.webservice.InformationController;
import de.seism0saurus.glacier.webservice.cache.FallbackRateLimiter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * UT-sec-LOCK-01: OWASP Coverage Matrix cookie-attribute lockstep test.
 *
 * <p><b>Purpose</b>: Prevent documentation drift between
 * {@code infrastructure/security/OWASP_COVERAGE_MATRIX.md} (which claims specific cookie
 * security attributes) and the actual {@code Set-Cookie} headers emitted by the running
 * Spring application.
 *
 * <p><b>Critical ordering note (ADR-3, SR-NEW-04)</b>:<br>
 * This test is committed FIRST so it fails RED on the current matrix, which incorrectly
 * states {@code SameSite=Strict} for the {@code wallId} cookie in rows EP-02 and EP-05.
 * The actual code ({@link InformationController}, line ~158) sets {@code SameSite=Lax}.
 * A separate commit by the doc lane corrects the matrix and turns this test GREEN.
 * The two commits must NOT be squashed — the RED commit documents that a regression
 * prevention test was added BECAUSE the matrix was lying.
 *
 * <p><b>What is tested</b>:
 * <ol>
 *   <li>The matrix is parsed for the SameSite token on any table row (starting with {@code |})
 *       that also contains the word {@code wallId}.</li>
 *   <li>The actual {@code Set-Cookie} header emitted by MockMvc for GET /rest/wall-id is retrieved.</li>
 *   <li>The claimed SameSite token is asserted to be present in the actual header.</li>
 * </ol>
 *
 * <p><b>Expected matrix state after doc lane correction</b>:
 * <ul>
 *   <li>{@code wallId} rows must say {@code SameSite=Lax} (matching actual code in
 *       {@link InformationController#readCookie})</li>
 * </ul>
 *
 * <p>Security reference: C5 — Secure By Default; ASVS V7.1.1 (L1); WSTG-SESS-02.
 */
@WebMvcTest(controllers = {InformationController.class})
@Import({MastodonProperties.class, MastodonHandleFactory.class, GlacierCookieProperties.class})
@TestPropertySource(properties = {
        "mastodon.handle=glacier@example.com",
        "glacier.cookie.secure=true",
        "glacier.fallback.ratelimit.perMinute=30",
        "glacier.fallback.ratelimit.perMinutePerIp=120",
        "glacier.ratelimit.eviction.intervalMs=600000",
        "glacier.domain=example.com"
})
class OwaspMatrixCookieAttributesLockstepTest {

    /**
     * Path to the OWASP coverage matrix relative to the project root.
     * Tests run with the project root as working directory under Maven.
     */
    private static final Path MATRIX_PATH =
            Path.of("infrastructure/security/OWASP_COVERAGE_MATRIX.md");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FallbackRateLimiter fallbackRateLimiter;

    // GlacierOperatorProperties is a @Component @ConfigurationProperties bean not loaded by
    // @WebMvcTest slice — mock it so InformationController's constructor can be satisfied.
    @MockitoBean
    @SuppressWarnings("unused")
    private GlacierOperatorProperties glacierOperatorProperties;

    // -------------------------------------------------------------------------
    // wallId cookie lockstep
    // -------------------------------------------------------------------------

    /**
     * UT-sec-LOCK-01a: the matrix claims a specific SameSite value for the wallId cookie.
     * Verify the actual Set-Cookie header emitted by GET /rest/wall-id matches.
     *
     * <p><b>RED pre-condition</b>: on the current matrix the table says {@code SameSite=Strict},
     * but {@link InformationController} sets {@code SameSite=Lax} — so this assertion
     * fails until the doc lane corrects the matrix to say {@code SameSite=Lax}. This is
     * intentional per ADR-3 (SR-NEW-04): the RED commit proves the matrix was lying.
     */
    @Test
    void wallIdCookie_actualSameSite_matchesMatrixClaim() throws Exception {
        when(fallbackRateLimiter.checkIpOnly(any()))
                .thenReturn(FallbackRateLimiter.RateLimitResult.allowed());

        // Fetch actual Set-Cookie header from the endpoint that issues the wallId cookie
        MvcResult result = mockMvc.perform(get("/rest/wall-id"))
                .andExpect(status().isOk())
                .andReturn();

        String setCookieHeader = extractWallIdSetCookieHeader(result);
        assertThat(setCookieHeader)
                .as("GET /rest/wall-id must emit a Set-Cookie header for wallId")
                .isNotNull();

        // Parse the matrix claim for wallId SameSite — fails explicitly if token absent (ADR-3)
        String matrixSameSite = parseMatrixWallIdSameSite();

        assertThat(setCookieHeader)
                .as("UT-sec-LOCK-01a (C5, ASVS V7.1.1, WSTG-SESS-02): "
                        + "actual Set-Cookie SameSite token for wallId cookie must match "
                        + "the claim in OWASP_COVERAGE_MATRIX.md. "
                        + "Matrix claims: [%s]. "
                        + "If this fails RED, the matrix has documentation drift — "
                        + "update the matrix to match the code (ADR-3, SR-NEW-04). "
                        + "Actual Set-Cookie: [%s]",
                        matrixSameSite, setCookieHeader)
                .containsIgnoringCase(matrixSameSite);
    }

    /**
     * UT-sec-LOCK-01b: the matrix must claim the wallId cookie carries the Secure flag.
     * Verify both that the matrix makes the claim, and that the actual header carries it.
     *
     * <p>This test is expected GREEN immediately because both the matrix and the code
     * agree on Secure (with {@code glacier.cookie.secure=true}).
     */
    @Test
    void wallIdCookie_secureFlag_claimedByMatrixAndPresentInActualHeader() throws Exception {
        when(fallbackRateLimiter.checkIpOnly(any()))
                .thenReturn(FallbackRateLimiter.RateLimitResult.allowed());

        MvcResult result = mockMvc.perform(get("/rest/wall-id"))
                .andExpect(status().isOk())
                .andReturn();

        String setCookieHeader = extractWallIdSetCookieHeader(result);
        assertThat(setCookieHeader)
                .as("GET /rest/wall-id must emit a Set-Cookie header for wallId")
                .isNotNull();

        // Matrix must explicitly claim Secure for wallId — fail explicitly if absent (no silent skip)
        assertMatrixClaimsSecureForWallId();

        assertThat(setCookieHeader)
                .as("UT-sec-LOCK-01b (C5, ASVS V7.1.1, WSTG-SESS-02): "
                        + "actual Set-Cookie for wallId must carry the Secure flag "
                        + "because the matrix claims it and glacier.cookie.secure=true (ADR-3)")
                .containsIgnoringCase("Secure");
    }

    /**
     * UT-sec-LOCK-01c: the wallId cookie must carry HttpOnly — XSS theft prevention (T-02).
     * Verifies the actual Set-Cookie header contains HttpOnly.
     */
    @Test
    void wallIdCookie_httpOnlyFlag_presentInActualHeader() throws Exception {
        when(fallbackRateLimiter.checkIpOnly(any()))
                .thenReturn(FallbackRateLimiter.RateLimitResult.allowed());

        MvcResult result = mockMvc.perform(get("/rest/wall-id"))
                .andExpect(status().isOk())
                .andReturn();

        String setCookieHeader = extractWallIdSetCookieHeader(result);
        assertThat(setCookieHeader)
                .as("GET /rest/wall-id must emit a Set-Cookie header for wallId")
                .isNotNull();

        assertThat(setCookieHeader)
                .as("UT-sec-LOCK-01c: wallId Set-Cookie must carry HttpOnly flag "
                        + "(T-02: prevents XSS theft of identity cookie)")
                .containsIgnoringCase("HttpOnly");
    }

    // -------------------------------------------------------------------------
    // Matrix structural sanity
    // -------------------------------------------------------------------------

    /**
     * UT-sec-LOCK-03: Sanity — the matrix file must exist and be parseable.
     * Fails immediately if the file is missing rather than silently reporting no claims.
     */
    @Test
    void matrixFile_exists_andIsReadable() throws IOException {
        assertThat(MATRIX_PATH.toFile())
                .as("OWASP_COVERAGE_MATRIX.md must exist at %s", MATRIX_PATH.toAbsolutePath())
                .exists()
                .canRead();

        List<String> lines = Files.readAllLines(MATRIX_PATH);
        assertThat(lines)
                .as("OWASP_COVERAGE_MATRIX.md must not be empty")
                .isNotEmpty();

        // The file must contain at least one SameSite token (structural sanity)
        long sameSiteCount = lines.stream()
                .filter(l -> l.contains("SameSite="))
                .count();
        assertThat(sameSiteCount)
                .as("OWASP_COVERAGE_MATRIX.md must contain at least one SameSite= token "
                        + "(structural sanity — parser cannot find claims in an empty or truncated file)")
                .isGreaterThan(0);
    }

    /**
     * UT-sec-LOCK-03-canary: a nonexistent cookie name must not produce a false positive.
     * Proves the positive assertions in the other tests are not vacuously true.
     */
    @Test
    void matrixParser_missingCookieEntry_returnsNoToken() throws IOException {
        List<String> lines = Files.readAllLines(MATRIX_PATH);
        Pattern sameSitePattern = Pattern.compile("(SameSite=(?:Lax|Strict|None))");

        boolean foundTokenForCanary = lines.stream()
                .filter(l -> l.startsWith("|"))
                .filter(l -> l.contains("__canary_cookie_that_does_not_exist__"))
                .anyMatch(l -> sameSitePattern.matcher(l).find());

        assertThat(foundTokenForCanary)
                .as("UT-sec-LOCK-03-canary: the parser must return false for a nonexistent cookie name "
                        + "(proves the positive assertions are not vacuously true)")
                .isFalse();
    }

    // -------------------------------------------------------------------------
    // Helpers: matrix parsing
    // -------------------------------------------------------------------------

    /**
     * Parses the OWASP coverage matrix and returns the SameSite claim for the {@code wallId} cookie.
     *
     * <p>The parser searches all table rows (lines starting with {@code |}) for the word
     * {@code wallId} and extracts the first {@code SameSite=Lax}, {@code SameSite=Strict},
     * or {@code SameSite=None} token found on such a row.
     *
     * <p>Fails explicitly with a descriptive message if no token is found (ADR-3, no silent skip).
     *
     * @return the SameSite attribute string, e.g. {@code "SameSite=Lax"} or {@code "SameSite=Strict"}
     * @throws AssertionError  if no well-anchored SameSite token is found for wallId
     * @throws IOException     if the matrix file cannot be read
     */
    private String parseMatrixWallIdSameSite() throws IOException {
        List<String> lines = Files.readAllLines(MATRIX_PATH);
        Pattern sameSitePattern = Pattern.compile("(SameSite=(?:Lax|Strict|None))");

        for (String line : lines) {
            // Only examine table rows (lines starting with |) that mention wallId
            if (!line.startsWith("|")) continue;
            if (!line.contains("wallId")) continue;

            Matcher m = sameSitePattern.matcher(line);
            if (m.find()) {
                return m.group(1); // return first SameSite token found on a wallId table row
            }
        }

        throw new AssertionError(
                "UT-sec-LOCK-01a (ADR-3, SR-NEW-04): OWASP_COVERAGE_MATRIX.md does not contain "
                        + "a well-anchored SameSite= token on any table row (starting with '|') "
                        + "that also mentions 'wallId'. "
                        + "Add the explicit SameSite claim to the matrix. "
                        + "Accepted tokens: SameSite=Lax, SameSite=Strict, SameSite=None.");
    }

    /**
     * Asserts that the OWASP coverage matrix explicitly claims {@code Secure} for the
     * {@code wallId} cookie. Fails with a descriptive message if the claim is absent
     * (no silent skip).
     *
     * @throws AssertionError if no table row mentioning wallId also contains "Secure"
     * @throws IOException    if the matrix file cannot be read
     */
    private void assertMatrixClaimsSecureForWallId() throws IOException {
        List<String> lines = Files.readAllLines(MATRIX_PATH);

        for (String line : lines) {
            if (!line.startsWith("|")) continue;
            if (!line.contains("wallId")) continue;
            if (line.contains("Secure")) {
                return; // claim found
            }
        }

        throw new AssertionError(
                "UT-sec-LOCK-01b (ADR-3, SR-NEW-04): OWASP_COVERAGE_MATRIX.md does not contain "
                        + "'Secure' on any table row (starting with '|') that also mentions 'wallId'. "
                        + "Add the explicit Secure claim to the matrix.");
    }

    /**
     * Extracts the {@code Set-Cookie} header value for the {@code wallId} cookie from
     * the MockMvc result. Returns {@code null} if no such header is present.
     */
    private static String extractWallIdSetCookieHeader(MvcResult result) {
        return result.getResponse()
                .getHeaders(HttpHeaders.SET_COOKIE)
                .stream()
                .filter(h -> h.startsWith("wallId="))
                .findFirst()
                .orElse(null);
    }

    // -------------------------------------------------------------------------
    // CSRF cookie lockstep (EP-09 rows — __Host-shareCsrf / shareCsrf)
    // -------------------------------------------------------------------------

    /**
     * UT-sec-LOCK-02: OWASP Coverage Matrix CSRF cookie lockstep tests.
     *
     * <p><b>Purpose</b>: Prevent documentation drift between the EP-09 rows in
     * {@code infrastructure/security/OWASP_COVERAGE_MATRIX.md} (which claim
     * {@code SameSite=Strict} and {@code NOT HttpOnly}) and the actual
     * {@code Set-Cookie} headers emitted by {@code GET /rest/share-csrf}.
     *
     * <p>The CSRF cookie ({@code __Host-shareCsrf} in secure mode) uses the double-submit
     * pattern: it must be readable by JS ({@code HttpOnly=false}) and must not be sent
     * cross-site ({@code SameSite=Strict}).
     *
     * <p>{@link CsrfTokenCookieFactory} is imported as a real bean (not mocked) so the actual
     * {@code Set-Cookie} header — including the manually-added {@code SameSite=Strict} header —
     * is produced by the production code under test.
     *
     * <p>Security reference: C5 — Secure By Default; ASVS V7.1.1 (L1); WSTG-SESS-02;
     * SR-SHARE-05, SR-SHARE-07, SR-SHARE-12.
     */
    @Nested
    @WebMvcTest(controllers = {ShareViewController.class})
    @Import({CsrfTokenCookieFactory.class, GlacierCookieProperties.class})
    @TestPropertySource(properties = {
            "glacier.cookie.secure=true",
            "glacier.domain=example.com",
            "glacier.fallback.enabled=true"
    })
    class CsrfCookieLockstep {

        @Autowired
        private MockMvc mockMvc;

        // ShareViewController dependencies — mocked so the web slice starts without
        // a full application context. CsrfTokenCookieFactory is @Imported as a real bean.
        @MockitoBean
        @SuppressWarnings("unused")
        private ShareLinkService shareLinkService;

        @MockitoBean
        @SuppressWarnings("unused")
        private ShareViewerCookieFactory shareViewerCookieFactory;

        @MockitoBean
        private ShareRateLimiter shareRateLimiter;

        @MockitoBean
        @SuppressWarnings("unused")
        private ShareViewStompRelay shareViewStompRelay;

        /**
         * UT-sec-LOCK-02a: the matrix EP-09 rows claim a specific SameSite value for the
         * {@code __Host-shareCsrf} cookie. Verify the actual {@code Set-Cookie} header
         * emitted by {@code GET /rest/share-csrf} matches.
         *
         * <p>ADR-4: filter by cookie name first ({@code __Host-shareCsrf=}), assert
         * exactly one header (I-CSRF-1), then check the SameSite token via substring
         * containment. {@code anyMatch} over an unfiltered Set-Cookie stream is banned.
         *
         * <p>ADR-2: assertions use substring containment only — {@code ResponseCookie}
         * co-emits {@code Expires=} alongside {@code Max-Age}; never assert on that value.
         */
        @Test
        void csrfCookie_actualSameSite_matchesMatrixClaim() throws Exception {
            when(shareRateLimiter.checkCsrfIssuance(any()))
                    .thenReturn(ShareRateLimiter.RateLimitResult.allowed());

            MvcResult result = mockMvc.perform(get("/rest/share-csrf"))
                    .andExpect(status().isOk())
                    .andReturn();

            // ADR-4: filter by name prefix, then assert single emission (I-CSRF-1)
            List<String> csrfHeaders = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE)
                    .stream()
                    .filter(h -> h.startsWith("__Host-shareCsrf="))
                    .collect(Collectors.toList());

            assertThat(csrfHeaders)
                    .as("I-CSRF-1 (SR-CSRF-13): GET /rest/share-csrf must emit exactly one "
                            + "Set-Cookie header for '__Host-shareCsrf' (after Lane B fix)")
                    .hasSize(1);

            String setCookieHeader = csrfHeaders.get(0);

            // Parse matrix claim — fails explicitly if EP-09 / share-csrf row lacks SameSite token
            String matrixSameSite = parseMatrixCsrfSameSite();

            assertThat(setCookieHeader)
                    .as("UT-sec-LOCK-02a (C5, ASVS V7.1.1, WSTG-SESS-02, SR-SHARE-07): "
                            + "actual Set-Cookie SameSite token for __Host-shareCsrf must match "
                            + "the claim in OWASP_COVERAGE_MATRIX.md EP-09 rows. "
                            + "Matrix claims: [%s]. "
                            + "If this fails RED, update the matrix to match the code (ADR-3). "
                            + "Actual Set-Cookie: [%s]",
                            matrixSameSite, setCookieHeader)
                    .containsIgnoringCase(matrixSameSite);
        }

        /**
         * UT-sec-LOCK-02b: the {@code __Host-shareCsrf} cookie must NOT carry the
         * {@code HttpOnly} flag.
         *
         * <p>The double-submit CSRF pattern requires JS to read the token from the cookie
         * and echo it in a request header. Setting {@code HttpOnly=true} would break the
         * frontend and is explicitly documented as {@code NOT HttpOnly} in the matrix EP-09 rows.
         *
         * <p>ADR-4: filter by cookie name first, then assert the attribute on the single
         * resulting header — no {@code anyMatch} over an unfiltered stream (banned).
         */
        @Test
        void csrfCookie_notHttpOnly_perDoubleSubmitPattern() throws Exception {
            when(shareRateLimiter.checkCsrfIssuance(any()))
                    .thenReturn(ShareRateLimiter.RateLimitResult.allowed());

            MvcResult result = mockMvc.perform(get("/rest/share-csrf"))
                    .andExpect(status().isOk())
                    .andReturn();

            // ADR-4: filter by name prefix, assert single emission (I-CSRF-1)
            List<String> csrfHeaders = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE)
                    .stream()
                    .filter(h -> h.startsWith("__Host-shareCsrf="))
                    .collect(Collectors.toList());

            assertThat(csrfHeaders)
                    .as("I-CSRF-1 (SR-CSRF-13): exactly one Set-Cookie header for '__Host-shareCsrf'")
                    .hasSize(1);

            String setCookieHeader = csrfHeaders.get(0);

            // I-CSRF-3: the single header must NOT contain HttpOnly
            assertThat(setCookieHeader.toLowerCase())
                    .as("UT-sec-LOCK-02b (SR-SHARE-05, SR-SHARE-07, OWASP CSRF Prevention Cheat Sheet): "
                            + "CSRF cookie must NOT carry HttpOnly flag — JS must be able to read the token "
                            + "for the double-submit CSRF pattern. "
                            + "If this fails, a regression has introduced HttpOnly on the CSRF cookie, "
                            + "which would break the share-view frontend. "
                            + "Actual Set-Cookie header: [%s]",
                            setCookieHeader)
                    .doesNotContain("httponly");
        }

        // -----------------------------------------------------------------------
        // Helper: matrix parsing
        // -----------------------------------------------------------------------

        /**
         * Parses the OWASP coverage matrix for the SameSite claim on rows containing
         * {@code EP-09} or {@code share-csrf}.
         *
         * @return the SameSite token, e.g. {@code "SameSite=Strict"}
         * @throws AssertionError if no SameSite token is found on any EP-09 / share-csrf row
         * @throws IOException    if the matrix file cannot be read
         */
        private String parseMatrixCsrfSameSite() throws IOException {
            List<String> lines = Files.readAllLines(MATRIX_PATH);
            Pattern sameSitePattern = Pattern.compile("(SameSite=(?:Lax|Strict|None))");

            for (String line : lines) {
                // Only examine table rows (lines starting with |) mentioning EP-09 or share-csrf
                if (!line.startsWith("|")) continue;
                if (!line.contains("EP-09") && !line.contains("share-csrf")) continue;

                Matcher m = sameSitePattern.matcher(line);
                if (m.find()) {
                    return m.group(1);
                }
            }

            throw new AssertionError(
                    "UT-sec-LOCK-02a (ADR-3, SR-SHARE-07): OWASP_COVERAGE_MATRIX.md does not contain "
                            + "a well-anchored SameSite= token on any table row (starting with '|') "
                            + "that also mentions 'EP-09' or 'share-csrf'. "
                            + "Add the explicit SameSite claim to the matrix EP-09 rows. "
                            + "Accepted tokens: SameSite=Lax, SameSite=Strict, SameSite=None.");
        }
    }

    // -------------------------------------------------------------------------
    // CSRF cookie lockstep — insecure mode (FU-R3)
    // -------------------------------------------------------------------------

    /**
     * UT-sec-LOCK-02-insecure: parity tests for the {@code shareCsrf} cookie in
     * insecure/dev mode ({@code glacier.cookie.secure=false}).
     *
     * <p>In insecure mode the cookie is named {@code shareCsrf} (no {@code __Host-} prefix),
     * scoped to {@code Path=/share}, carries no {@code Secure} flag, but retains
     * {@code SameSite=Strict} and the double-submit requirement ({@code HttpOnly=false}).
     *
     * <p>FU-R3: extends I-CSRF-1 (single-emission invariant) and the HttpOnly gate to the
     * insecure transport path so regressions introduced by dev-mode configuration changes
     * are caught as early as secure-mode regressions.
     */
    @Nested
    @WebMvcTest(controllers = {ShareViewController.class})
    @Import({CsrfTokenCookieFactory.class, GlacierCookieProperties.class})
    @TestPropertySource(properties = {
            "glacier.cookie.secure=false",
            "glacier.domain=example.com",
            "glacier.fallback.enabled=true"
    })
    class CsrfCookieLockstepInsecure {

        @Autowired
        private MockMvc mockMvc;

        @MockitoBean
        @SuppressWarnings("unused")
        private ShareLinkService shareLinkService;

        @MockitoBean
        @SuppressWarnings("unused")
        private ShareViewerCookieFactory shareViewerCookieFactory;

        @MockitoBean
        private ShareRateLimiter shareRateLimiter;

        @MockitoBean
        @SuppressWarnings("unused")
        private ShareViewStompRelay shareViewStompRelay;

        /**
         * UT-sec-LOCK-02-insecure-a: GET /rest/share-csrf in insecure mode must emit exactly
         * one {@code Set-Cookie} header for the {@code shareCsrf} cookie (I-CSRF-1).
         *
         * <p>ADR-4: filter by cookie name prefix, assert {@code hasSize(1)}.
         */
        @Test
        void csrfCookie_insecureMode_emitsExactlyOneSetCookieHeader() throws Exception {
            when(shareRateLimiter.checkCsrfIssuance(any()))
                    .thenReturn(ShareRateLimiter.RateLimitResult.allowed());

            MvcResult result = mockMvc.perform(get("/rest/share-csrf"))
                    .andExpect(status().isOk())
                    .andReturn();

            List<String> csrfHeaders = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE)
                    .stream()
                    .filter(h -> h.startsWith("shareCsrf="))
                    .toList();

            assertThat(csrfHeaders)
                    .as("FU-R3 / I-CSRF-1: GET /rest/share-csrf in insecure mode must emit exactly "
                            + "one Set-Cookie header for 'shareCsrf'")
                    .hasSize(1);
        }

        /**
         * UT-sec-LOCK-02-insecure-b: the {@code shareCsrf} cookie must NOT carry
         * {@code HttpOnly} in insecure mode — double-submit pattern requires JS to read it.
         *
         * <p>ADR-4: filter by cookie name, assert single emission, then check attribute.
         */
        @Test
        void csrfCookie_insecureMode_notHttpOnly_perDoubleSubmitPattern() throws Exception {
            when(shareRateLimiter.checkCsrfIssuance(any()))
                    .thenReturn(ShareRateLimiter.RateLimitResult.allowed());

            MvcResult result = mockMvc.perform(get("/rest/share-csrf"))
                    .andExpect(status().isOk())
                    .andReturn();

            List<String> csrfHeaders = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE)
                    .stream()
                    .filter(h -> h.startsWith("shareCsrf="))
                    .toList();

            assertThat(csrfHeaders)
                    .as("FU-R3 / I-CSRF-1: exactly one Set-Cookie header for 'shareCsrf' in insecure mode")
                    .hasSize(1);

            assertThat(csrfHeaders.get(0).toLowerCase())
                    .as("FU-R3 / UT-sec-LOCK-02b-insecure: CSRF cookie in insecure mode must NOT "
                            + "carry HttpOnly — double-submit pattern requires JS to read the token")
                    .doesNotContain("httponly");
        }

        /**
         * UT-sec-LOCK-02-insecure-c: the {@code shareCsrf} cookie SameSite token must match
         * the matrix EP-09 claim — same requirement as secure mode.
         */
        @Test
        void csrfCookie_insecureMode_actualSameSite_matchesMatrixClaim() throws Exception {
            when(shareRateLimiter.checkCsrfIssuance(any()))
                    .thenReturn(ShareRateLimiter.RateLimitResult.allowed());

            MvcResult result = mockMvc.perform(get("/rest/share-csrf"))
                    .andExpect(status().isOk())
                    .andReturn();

            List<String> csrfHeaders = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE)
                    .stream()
                    .filter(h -> h.startsWith("shareCsrf="))
                    .toList();

            assertThat(csrfHeaders)
                    .as("FU-R3 / I-CSRF-1: exactly one Set-Cookie header for 'shareCsrf' in insecure mode")
                    .hasSize(1);

            String setCookieHeader = csrfHeaders.get(0);
            String matrixSameSite = parseMatrixCsrfSameSite();

            assertThat(setCookieHeader)
                    .as("FU-R3 / UT-sec-LOCK-02-insecure-c: actual SameSite for 'shareCsrf' must "
                            + "match OWASP_COVERAGE_MATRIX.md EP-09 claim [%s]. "
                            + "Actual Set-Cookie: [%s]",
                            matrixSameSite, setCookieHeader)
                    .containsIgnoringCase(matrixSameSite);
        }

        private String parseMatrixCsrfSameSite() throws IOException {
            List<String> lines = Files.readAllLines(MATRIX_PATH);
            Pattern sameSitePattern = Pattern.compile("(SameSite=(?:Lax|Strict|None))");

            for (String line : lines) {
                if (!line.startsWith("|")) continue;
                if (!line.contains("EP-09") && !line.contains("share-csrf")) continue;

                Matcher m = sameSitePattern.matcher(line);
                if (m.find()) {
                    return m.group(1);
                }
            }

            throw new AssertionError(
                    "FU-R3 (ADR-3): OWASP_COVERAGE_MATRIX.md does not contain a SameSite= token "
                            + "on any table row mentioning 'EP-09' or 'share-csrf'. "
                            + "Accepted tokens: SameSite=Lax, SameSite=Strict, SameSite=None.");
        }
    }
}
