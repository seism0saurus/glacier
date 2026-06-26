package de.seism0saurus.glacier.share.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Targeted PITest mutation-kill tests for {@link ShareSecurityHeadersFilter}.
 *
 * <p>Each test pins down an exact observable behavior that survives in the
 * baseline {@code ShareSecurityHeadersFilterTest} suite. The tests here are
 * intentionally narrow: each one fails under one (or a small group of) specific
 * mutant(s) so the mutation is reported as KILLED.
 *
 * <p>Survivors addressed:
 * <ul>
 *   <li>L101 / L106 {@code validateDomains} NonVoidMethodCall (removed
 *       {@code Pattern.pattern()}) — the exception message embeds the regex source.</li>
 *   <li>L128 {@code isSharePath} removed {@code startsWith} + RemoveConditional_EQUAL_IF
 *       — the {@code /rest/share-csrf} prefix branch.</li>
 *   <li>L129 {@code isSharePath} removed {@code equals} + RemoveConditional_EQUAL_ELSE
 *       — the exact {@code /share-view-ws} branch.</li>
 *   <li>L151 {@code applyShareSecurityHeaders} NonVoidMethodCall (removed
 *       {@code getAttribute}) + L152 NegateConditionals / RemoveConditional — the
 *       nonce-present vs nonce-absent guard.</li>
 *   <li>L174 / L180 / L183 VoidMethodCall (removed {@code response.setHeader}) —
 *       X-Frame-Options, Referrer-Policy, Permissions-Policy exact values.</li>
 * </ul>
 */
class ShareSecurityHeadersFilterMutationKillTest {

    private static final String TEST_GLACIER_DOMAIN = "test.example.com";
    private static final String TEST_SHARE_HOST = "share.test.example.com";

    /**
     * The exact regex source the production code embeds via {@code Pattern.pattern()}.
     * Mirrors {@link ShareSecurityHeadersFilter#SAFE_HOSTNAME_PATTERN}.
     */
    private static final String SAFE_HOSTNAME_REGEX =
            "^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*(:[0-9]{1,5})?$";

    private ShareSecurityHeadersFilter filter;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        filter = new ShareSecurityHeadersFilter(TEST_GLACIER_DOMAIN, TEST_SHARE_HOST);
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
    }

    // ---------------------------------------------------------------------------
    // L101 / L106 — validateDomains embeds Pattern.pattern() in the message.
    // Removing the Pattern.pattern() call (NonVoidMethodCall) would drop the regex
    // source from the message; asserting the regex text is present kills both.
    // ---------------------------------------------------------------------------

    @Test
    void validateDomains_invalidGlacierDomain_messageContainsRegexSource() {
        assertThatThrownBy(() -> {
            ShareSecurityHeadersFilter f = new ShareSecurityHeadersFilter(
                    "evil.com; default-src *", TEST_SHARE_HOST);
            f.validateDomains();
        }).isInstanceOf(IllegalStateException.class)
          // L101: message ends with "Must match pattern: " + SAFE_HOSTNAME_PATTERN.pattern()
          .hasMessageContaining("Must match pattern: " + SAFE_HOSTNAME_REGEX);
    }

    @Test
    void validateDomains_invalidShareHost_messageContainsRegexSource() {
        assertThatThrownBy(() -> {
            ShareSecurityHeadersFilter f = new ShareSecurityHeadersFilter(
                    TEST_GLACIER_DOMAIN, "evil.com; script-src *");
            f.validateDomains();
        }).isInstanceOf(IllegalStateException.class)
          // L106: message ends with "Must match pattern: " + SAFE_HOSTNAME_PATTERN.pattern()
          .hasMessageContaining("Must match pattern: " + SAFE_HOSTNAME_REGEX);
    }

    // ---------------------------------------------------------------------------
    // L128 — isSharePath: path.startsWith(SHARE_CSRF_PATH) branch ("/rest/share-csrf").
    // Removing startsWith (or flipping the IF) would stop headers being applied for
    // a /rest/share-csrf path; asserting the CSP IS present kills it.
    // ---------------------------------------------------------------------------

    @Test
    void shareCsrfPath_isTreatedAsSharePath_headersApplied() throws Exception {
        request.setRequestURI("/rest/share-csrf");

        filter.doFilterInternal(request, response, new MockFilterChain());

        assertThat(response.getHeader("Content-Security-Policy"))
                .as("/rest/share-csrf must be a share path and receive the CSP header")
                .isNotNull();
    }

    @Test
    void shareCsrfPrefixPath_isTreatedAsSharePath_headersApplied() throws Exception {
        // A longer path that still starts with /rest/share-csrf — exercises startsWith,
        // distinguishing it from an exact equals.
        request.setRequestURI("/rest/share-csrf/token");

        filter.doFilterInternal(request, response, new MockFilterChain());

        assertThat(response.getHeader("Content-Security-Policy"))
                .as("a path with the /rest/share-csrf prefix must receive the CSP header")
                .isNotNull();
    }

    // ---------------------------------------------------------------------------
    // L129 — isSharePath: "/share-view-ws".equals(path) branch (exact match).
    // Removing equals (or flipping the ELSE conditional) would stop headers being
    // applied for exactly "/share-view-ws"; asserting CSP present kills it.
    // A near-miss that is NOT equal must NOT get headers (so the branch is exact).
    // ---------------------------------------------------------------------------

    @Test
    void shareViewWsPath_exactMatch_headersApplied() throws Exception {
        request.setRequestURI("/share-view-ws");

        filter.doFilterInternal(request, response, new MockFilterChain());

        assertThat(response.getHeader("Content-Security-Policy"))
                .as("exact path /share-view-ws must be a share path and receive the CSP header")
                .isNotNull();
    }

    @Test
    void shareViewWsNearMiss_isNotSharePath_headersNotApplied() throws Exception {
        // Does not start with /share (it is /websocket...) and is not equal to
        // /share-view-ws, so none of the isSharePath branches match.
        request.setRequestURI("/websocket-share-view-ws");

        filter.doFilterInternal(request, response, new MockFilterChain());

        assertThat(response.getHeader("Content-Security-Policy"))
                .as("a path that matches no isSharePath branch must NOT receive the CSP header")
                .isNull();
    }

    // ---------------------------------------------------------------------------
    // L151 / L152 — nonce attribute guard in applyShareSecurityHeaders.
    // When the CspNonceFilter attribute is present, the CSP script-src must embed
    // 'nonce-<value>'. When absent, it must NOT. This exercises both getAttribute
    // (L151) and the (nonce != null) conditional (L152).
    // ---------------------------------------------------------------------------

    @Test
    void nonceAttributePresent_cspContainsNonceDirective() throws Exception {
        request.setRequestURI("/share/link-with-nonce");
        request.setAttribute(CspNonceFilter.NONCE_ATTRIBUTE, "abc123NONCE");

        filter.doFilterInternal(request, response, new MockFilterChain());

        String csp = response.getHeader("Content-Security-Policy");
        assertThat(csp).isNotNull();
        assertThat(csp)
                .as("when the nonce attribute is set, script-src must embed 'nonce-<value>'")
                .contains("'nonce-abc123NONCE'");
    }

    @Test
    void nonceAttributeAbsent_cspHasNoNonceDirective() throws Exception {
        request.setRequestURI("/share/link-no-nonce");
        // No NONCE_ATTRIBUTE set.

        filter.doFilterInternal(request, response, new MockFilterChain());

        String csp = response.getHeader("Content-Security-Policy");
        assertThat(csp).isNotNull();
        assertThat(csp)
                .as("when no nonce attribute is set, the CSP must not contain a nonce directive")
                .doesNotContain("'nonce-");
    }

    // ---------------------------------------------------------------------------
    // L174 / L180 / L183 — individual setHeader calls. Each asserted with its exact
    // value so removing any single setHeader makes exactly one test fail.
    // ---------------------------------------------------------------------------

    @Test
    void xFrameOptions_isExactlyDeny() throws Exception {
        request.setRequestURI("/share/link-xfo");

        filter.doFilterInternal(request, response, new MockFilterChain());

        // L174: response.setHeader("X-Frame-Options", "DENY")
        assertThat(response.getHeader("X-Frame-Options"))
                .as("X-Frame-Options must be exactly DENY")
                .isEqualTo("DENY");
    }

    @Test
    void referrerPolicy_isExactlyNoReferrer() throws Exception {
        request.setRequestURI("/share/link-ref");

        filter.doFilterInternal(request, response, new MockFilterChain());

        // L180: response.setHeader("Referrer-Policy", "no-referrer")
        assertThat(response.getHeader("Referrer-Policy"))
                .as("Referrer-Policy must be exactly no-referrer (SR-SHARE-14)")
                .isEqualTo("no-referrer");
    }

    @Test
    void permissionsPolicy_hasExactMinimalValue() throws Exception {
        request.setRequestURI("/share/link-pp");

        filter.doFilterInternal(request, response, new MockFilterChain());

        // L183: response.setHeader("Permissions-Policy", "...")
        assertThat(response.getHeader("Permissions-Policy"))
                .as("Permissions-Policy must be exactly the minimal readonly-view footprint")
                .isEqualTo("camera=(), microphone=(), geolocation=(), payment=(), "
                        + "clipboard-read=(), clipboard-write=(self), "
                        + "display-capture=()");
    }
}
