package de.seism0saurus.glacier.share.web;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link ShareSecurityHeadersFilter}.
 *
 * <p>Security requirements tested (ADR-TEST-03, SR-SHARE-11, SR-SHARE-15,
 * OWASP A05 — Security Misconfiguration, BSI TSS-WEB §5.2):
 * <ul>
 *   <li>connect-src uses explicit WebSocket hosts — no bare {@code wss:} token (ADR-TEST-03).</li>
 *   <li>connect-src contains {@code wss://share.test.example.com} and {@code wss://test.example.com}.</li>
 *   <li>frame-ancestors is {@code 'none'} (prevents clickjacking on share pages).</li>
 *   <li>COOP, COEP, CORP isolation headers are present.</li>
 *   <li>HSTS is set.</li>
 *   <li>X-Content-Type-Options is {@code nosniff}.</li>
 *   <li>Filter applies on share paths, skips non-share routes.</li>
 *   <li>Filter chain is always continued.</li>
 * </ul>
 */
class ShareSecurityHeadersFilterTest {

    private static final String TEST_GLACIER_DOMAIN = "test.example.com";
    private static final String TEST_SHARE_HOST = "share.test.example.com";

    private ShareSecurityHeadersFilter filter;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        // Construct with explicit test domain values — matching the Fix 3 constructor signature.
        filter = new ShareSecurityHeadersFilter(TEST_GLACIER_DOMAIN, TEST_SHARE_HOST);
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
    }

    // ---------------------------------------------------------------------------
    // connect-src: explicit hosts, no bare wss: token (ADR-TEST-03)
    // ---------------------------------------------------------------------------

    @Test
    void connectSrc_containsExplicitShareHost() throws Exception {
        // ARRANGE
        request.setRequestURI("/share/some-link-id");

        // ACT
        filter.doFilterInternal(request, response, new MockFilterChain());

        // ASSERT
        String csp = response.getHeader("Content-Security-Policy");
        assertThat(csp).as("CSP header must be present on share paths").isNotNull();

        String connectSrc = extractDirective(csp, "connect-src");
        assertThat(connectSrc)
                .as("connect-src must contain explicit wss://share.test.example.com")
                .contains("wss://" + TEST_SHARE_HOST);
    }

    @Test
    void connectSrc_containsExplicitGlacierDomain() throws Exception {
        // ARRANGE
        request.setRequestURI("/share/some-link-id");

        // ACT
        filter.doFilterInternal(request, response, new MockFilterChain());

        // ASSERT
        String csp = response.getHeader("Content-Security-Policy");
        assertThat(csp).isNotNull();

        String connectSrc = extractDirective(csp, "connect-src");
        assertThat(connectSrc)
                .as("connect-src must contain explicit wss://test.example.com")
                .contains("wss://" + TEST_GLACIER_DOMAIN);
    }

    @Test
    void connectSrc_noBareWssToken() throws Exception {
        // ARRANGE — ADR-TEST-03: bare wss: permits any TLS WebSocket host
        request.setRequestURI("/share/some-link-id");

        // ACT
        filter.doFilterInternal(request, response, new MockFilterChain());

        // ASSERT
        String csp = response.getHeader("Content-Security-Policy");
        assertThat(csp).isNotNull();

        String connectSrc = extractDirective(csp, "connect-src");
        assertThat(connectSrc).isNotNull();

        // Split on whitespace and check that none of the tokens is exactly "wss:"
        String[] tokens = connectSrc.split("\\s+");
        assertThat(tokens)
                .as("connect-src must not contain bare 'wss:' scheme-only token (ADR-TEST-03)")
                .doesNotContain("wss:");
    }

    // ---------------------------------------------------------------------------
    // frame-ancestors: 'none' (prevents clickjacking on share pages)
    // ---------------------------------------------------------------------------

    @Test
    void frameAncestors_isNone() throws Exception {
        // ARRANGE
        request.setRequestURI("/share/link-456");

        // ACT
        filter.doFilterInternal(request, response, new MockFilterChain());

        // ASSERT
        String csp = response.getHeader("Content-Security-Policy");
        assertThat(csp).isNotNull();

        String frameAncestors = extractDirective(csp, "frame-ancestors");
        assertThat(frameAncestors)
                .as("frame-ancestors must be 'none' to prevent clickjacking on share pages")
                .containsIgnoringCase("'none'");
    }

    @Test
    void baseUri_isSelf_toHonorSpaBaseTag() throws Exception {
        // ARRANGE
        request.setRequestURI("/share/link-456");

        // ACT
        filter.doFilterInternal(request, response, new MockFilterChain());

        // ASSERT
        String csp = response.getHeader("Content-Security-Policy");
        assertThat(csp).isNotNull();

        // base-uri must be 'self' (NOT 'none'): the readonly SPA shell's <base href="/">
        // must be honored so root-relative bundle assets resolve on deep links. 'self'
        // still blocks a base tag pointing at a different (attacker) origin.
        String baseUri = extractDirective(csp, "base-uri");
        assertThat(baseUri)
                .as("base-uri must allow the same-origin SPA base tag")
                .containsIgnoringCase("'self'");
        assertThat(baseUri)
                .as("base-uri must not be 'none' (that blocks the SPA base tag)")
                .doesNotContainIgnoringCase("'none'");
    }

    // ---------------------------------------------------------------------------
    // X-Content-Type-Options: nosniff
    // ---------------------------------------------------------------------------

    @Test
    void xContentTypeOptions_isNosniff() throws Exception {
        // ARRANGE
        request.setRequestURI("/share/link-789");

        // ACT
        filter.doFilterInternal(request, response, new MockFilterChain());

        // ASSERT
        assertThat(response.getHeader("X-Content-Type-Options"))
                .as("X-Content-Type-Options must be 'nosniff' (OWASP A05)")
                .isEqualTo("nosniff");
    }

    // ---------------------------------------------------------------------------
    // COOP, COEP, CORP headers present (cross-origin isolation)
    // ---------------------------------------------------------------------------

    @Test
    void coopCoepCorpPresent() throws Exception {
        // ARRANGE
        request.setRequestURI("/share/link-789");

        // ACT
        filter.doFilterInternal(request, response, new MockFilterChain());

        // ASSERT
        assertThat(response.getHeader("Cross-Origin-Opener-Policy"))
                .as("COOP header must be set for cross-origin isolation")
                .isNotNull();
        assertThat(response.getHeader("Cross-Origin-Embedder-Policy"))
                .as("COEP header must be set for cross-origin isolation")
                .isNotNull();
        assertThat(response.getHeader("Cross-Origin-Resource-Policy"))
                .as("CORP header must be set for cross-origin isolation")
                .isNotNull();
    }

    // ---------------------------------------------------------------------------
    // HSTS present
    // ---------------------------------------------------------------------------

    @Test
    void hstsPresent() throws Exception {
        // ARRANGE
        request.setRequestURI("/share/link-abc");

        // ACT
        filter.doFilterInternal(request, response, new MockFilterChain());

        // ASSERT
        assertThat(response.getHeader("Strict-Transport-Security"))
                .as("HSTS header must be set on share routes")
                .isNotNull()
                .contains("max-age=");
    }

    // ---------------------------------------------------------------------------
    // Filter applies on share paths, skips non-share routes
    // ---------------------------------------------------------------------------

    @Test
    void appliesOnSharePaths_skipsWallRoute() throws Exception {
        // ARRANGE — wall route (not a share path)
        request.setRequestURI("/rest/wall-id");
        MockHttpServletResponse wallResponse = new MockHttpServletResponse();

        // ACT
        filter.doFilterInternal(request, wallResponse, new MockFilterChain());

        // ASSERT — CSP header must NOT be set on non-share paths
        assertThat(wallResponse.getHeader("Content-Security-Policy"))
                .as("CSP must not be set on non-share paths")
                .isNull();
    }

    @Test
    void appliesOnRestSharePaths() throws Exception {
        // ARRANGE
        request.setRequestURI("/rest/share/some-endpoint");

        // ACT
        filter.doFilterInternal(request, response, new MockFilterChain());

        // ASSERT — filter applies on /rest/share/ paths
        assertThat(response.getHeader("Content-Security-Policy"))
                .as("CSP must be set on /rest/share/ paths")
                .isNotNull();
    }

    // ---------------------------------------------------------------------------
    // Filter chain is always continued
    // ---------------------------------------------------------------------------

    @Test
    void filterChainAlwaysContinued() throws Exception {
        // ARRANGE
        request.setRequestURI("/share/test");
        FilterChain chain = mock(FilterChain.class);

        // ACT
        filter.doFilterInternal(request, response, chain);

        // ASSERT
        verify(chain, times(1)).doFilter(request, response);
    }

    // ---------------------------------------------------------------------------
    // F-3: @PostConstruct domain validation — rejects injection payloads at startup
    // ---------------------------------------------------------------------------

    /**
     * F-3: Verifies that constructing the filter with an injection payload in
     * {@code glacier.domain} and calling {@code validateDomains()} throws
     * {@link IllegalStateException}.
     *
     * <p>In production Spring calls {@code validateDomains()} automatically via
     * {@code @PostConstruct}.  In plain unit tests the lifecycle is not active, so
     * we call it explicitly — this is exactly the pattern documented in the method's
     * Javadoc and intentional in the design.
     *
     * <p>Security: F-3, OWASP A05 — operator misconfiguration must not silently produce
     * a malformed CSP header that weakens security for all share routes.
     */
    @Test
    void constructor_throwsIllegalStateException_whenDomainContainsInjectionPayload() {
        assertThatThrownBy(() -> {
            ShareSecurityHeadersFilter f = new ShareSecurityHeadersFilter(
                    "evil.com; default-src *", "share.example.com");
            // @PostConstruct is not called by Spring in a plain unit test — invoke explicitly:
            f.validateDomains();
        }).isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("glacier.domain");
    }

    @Test
    void constructor_throwsIllegalStateException_whenShareHostContainsInjectionPayload() {
        assertThatThrownBy(() -> {
            ShareSecurityHeadersFilter f = new ShareSecurityHeadersFilter(
                    "example.com", "evil.com; script-src *");
            f.validateDomains();
        }).isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("glacier.share.host");
    }

    @Test
    void constructor_throwsIllegalStateException_whenDomainContainsUpperCaseLetter() {
        // The safe pattern is lowercase-only; uppercase would allow case-confusion attacks
        assertThatThrownBy(() -> {
            ShareSecurityHeadersFilter f = new ShareSecurityHeadersFilter(
                    "Example.COM", "share.example.com");
            f.validateDomains();
        }).isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("glacier.domain");
    }

    @Test
    void validateDomains_allowsValidHostnameWithPort() throws Exception {
        // localhost:8080 must be accepted for dev mode (F-3)
        ShareSecurityHeadersFilter f = new ShareSecurityHeadersFilter(
                "localhost:8080", "share.localhost:8080");
        // Must not throw — validateDomains() succeeds for valid hostnames with port
        f.validateDomains();
    }

    @Test
    void validateDomains_allowsSubdomainHostname() throws Exception {
        // share.glacier.events must be accepted
        ShareSecurityHeadersFilter f = new ShareSecurityHeadersFilter(
                "glacier.events", "share.glacier.events");
        f.validateDomains();
    }

    // ============================================================================
    // Helpers
    // ============================================================================

    /**
     * Extracts the value of a named CSP directive from a full CSP header string.
     *
     * @param csp       the full Content-Security-Policy header value
     * @param directive the directive name (e.g. "connect-src", "frame-ancestors")
     * @return the directive value string, or null if not found
     */
    private String extractDirective(String csp, String directive) {
        for (String part : csp.split(";")) {
            String trimmed = part.trim();
            if (trimmed.startsWith(directive)) {
                return trimmed;
            }
        }
        return null;
    }
}
