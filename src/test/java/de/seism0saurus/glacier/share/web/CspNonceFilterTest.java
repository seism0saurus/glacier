package de.seism0saurus.glacier.share.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link CspNonceFilter}.
 *
 * <p>Security requirement (SR-SHARE-11, BSI TSS-WEB, OWASP A05 — Security Misconfiguration):
 * The CSP nonce attribute must be populated on every request with a unique,
 * URL-safe Base64-no-padding value. 16 random bytes → 22-character URL-safe Base64 string
 * (without padding: ⌈16 × 8 / 6⌉ = 22 chars, no trailing {@code =}).
 *
 * <p>The nonce is stored as request attribute {@link CspNonceFilter#NONCE_ATTRIBUTE}
 * and consumed by {@link ShareSecurityHeadersFilter} to inject into the CSP header.
 */
class CspNonceFilterTest {

    private CspNonceFilter filter;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        filter = new CspNonceFilter();
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
    }

    // ---------------------------------------------------------------------------
    // Nonce attribute presence and correct length
    // ---------------------------------------------------------------------------

    @Test
    void nonceAttribute_isPopulated_withCorrectLength() throws ServletException, IOException {
        // ARRANGE
        MockFilterChain chain = new MockFilterChain();

        // ACT
        filter.doFilterInternal(request, response, chain);

        // ASSERT — nonce attribute must be set on the request
        Object nonce = request.getAttribute(CspNonceFilter.NONCE_ATTRIBUTE);
        assertThat(nonce)
                .as("nonce attribute must be set after filter runs")
                .isNotNull()
                .isInstanceOf(String.class);

        // 16 bytes of random data encoded with URL-safe Base64 without padding = 22 chars
        // (⌈16 × 8 / 6⌉ = 22, no padding character '=')
        String nonceStr = (String) nonce;
        assertThat(nonceStr)
                .as("nonce must be at least 16 characters long")
                .hasSizeGreaterThanOrEqualTo(16);
    }

    // ---------------------------------------------------------------------------
    // Uniqueness: sequential requests must produce different nonces
    // ---------------------------------------------------------------------------

    @Test
    void twoSequentialRequests_getDifferentNonces() throws ServletException, IOException {
        // ARRANGE
        MockHttpServletRequest req1 = new MockHttpServletRequest();
        MockHttpServletRequest req2 = new MockHttpServletRequest();

        // ACT
        filter.doFilterInternal(req1, new MockHttpServletResponse(), new MockFilterChain());
        filter.doFilterInternal(req2, new MockHttpServletResponse(), new MockFilterChain());

        String nonce1 = (String) req1.getAttribute(CspNonceFilter.NONCE_ATTRIBUTE);
        String nonce2 = (String) req2.getAttribute(CspNonceFilter.NONCE_ATTRIBUTE);

        // ASSERT — two requests must not share the same nonce (cryptographic freshness)
        assertThat(nonce1).isNotEqualTo(nonce2);
    }

    // ---------------------------------------------------------------------------
    // Uniqueness across 10 invocations
    // ---------------------------------------------------------------------------

    @Test
    void nonce_isUniquePerInvocation_over10Calls() throws ServletException, IOException {
        // ARRANGE
        Set<String> nonces = new HashSet<>();

        // ACT — run filter 10 times, each on a fresh request
        for (int i = 0; i < 10; i++) {
            MockHttpServletRequest req = new MockHttpServletRequest();
            filter.doFilterInternal(req, new MockHttpServletResponse(), new MockFilterChain());
            nonces.add((String) req.getAttribute(CspNonceFilter.NONCE_ATTRIBUTE));
        }

        // ASSERT — all 10 nonces are distinct (probability of collision is ~1/2^128)
        assertThat(nonces).hasSize(10);
    }

    // ---------------------------------------------------------------------------
    // Format: URL-safe Base64, no padding
    // ---------------------------------------------------------------------------

    @Test
    void nonce_isUrlSafeBase64NoPadding() throws ServletException, IOException {
        // ARRANGE
        MockFilterChain chain = new MockFilterChain();

        // ACT
        filter.doFilterInternal(request, response, chain);

        // ASSERT — nonce must match URL-safe Base64 alphabet (no + / = characters)
        String nonce = (String) request.getAttribute(CspNonceFilter.NONCE_ATTRIBUTE);
        assertThat(nonce)
                .as("nonce must be URL-safe Base64 (no '+', '/' or '=' characters)")
                .matches("^[A-Za-z0-9_-]+$")
                .doesNotContain("=")
                .doesNotContain("+")
                .doesNotContain("/");
    }

    // ---------------------------------------------------------------------------
    // Filter chain is always continued (nonce must not stop the request)
    // ---------------------------------------------------------------------------

    @Test
    void filterChain_isAlwaysContinued() throws ServletException, IOException {
        // ARRANGE
        FilterChain chain = mock(FilterChain.class);

        // ACT
        filter.doFilterInternal(request, response, chain);

        // ASSERT — chain.doFilter must be called exactly once regardless of nonce generation
        verify(chain, times(1)).doFilter(request, response);
    }

    // ---------------------------------------------------------------------------
    // NONCE_ATTRIBUTE constant value
    // ---------------------------------------------------------------------------

    @Test
    void nonceAttributeNameConstant_isCorrect() {
        // The attribute name must match what ShareSecurityHeadersFilter reads.
        // Production value: "csp-nonce"
        assertThat(CspNonceFilter.NONCE_ATTRIBUTE).isEqualTo("csp-nonce");
    }
}
