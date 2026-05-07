package de.seism0saurus.glacier.webservice;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Turkish-locale regression tests for {@link HttpMethodRejectFilter}.
 *
 * <p>On a JVM with Turkish locale ({@code -Duser.language=tr}), the no-arg
 * {@code String.toUpperCase()} call on the HTTP method token produces incorrect
 * results for characters sensitive to Turkish locale rules:
 * <ul>
 *   <li>{@code 'i'.toUpperCase(Locale.TURKEY)} → {@code 'İ'} (U+0130) instead of {@code 'I'}</li>
 *   <li>{@code 'I'.toLowerCase(Locale.TURKEY)} → {@code 'ı'} (U+0131) instead of {@code 'i'}</li>
 * </ul>
 * This means {@code "trace".toUpperCase()} produces {@code "TRACE"} in most locales
 * but the uppercase 'İ' variant for the 'i', potentially causing a mismatch against the
 * {@code Set.of("TRACE", "TRACK")} allowlist, which allows TRACE/TRACK through.
 *
 * <p>Security requirements: SR-LR-03 (CWE-178), ASVS V14.5.1 (L1),
 * OWASP Proactive Controls C1 (Access Control), C3 (Input Validation).
 *
 * <p>ADR-LR-04: {@link BeforeEach}/{@link AfterEach} locale-restore pattern (SR-LR-06).
 */
class HttpMethodRejectFilterTurkishLocaleTest {

    private HttpMethodRejectFilter filter;
    private MockHttpServletResponse response;

    /**
     * Saved default locale — restored unconditionally after each test (SR-LR-06).
     */
    private Locale savedLocale;

    @BeforeEach
    void setUp() {
        savedLocale = Locale.getDefault();
        Locale.setDefault(Locale.of("tr", "TR"));
        filter = new HttpMethodRejectFilter();
        response = new MockHttpServletResponse();
    }

    @AfterEach
    void restoreLocale() {
        Locale.setDefault(savedLocale);
    }

    // -----------------------------------------------------------------------
    // Test 1: lowercase "trace" must be rejected under Turkish locale
    // -----------------------------------------------------------------------

    /**
     * Verifies that an HTTP request with method {@code "trace"} (lowercase) is
     * rejected with 405 under Turkish locale.
     *
     * <p>Without {@code Locale.ROOT}, {@code "trace".toUpperCase(Locale.TURKEY)} may
     * produce {@code "TRACİ"} (U+0130 instead of 'I' for 'i'), breaking the set lookup.
     *
     * <p>SR-LR-03; C8/ASVS V14.5.1; WSTG-CONF-06.
     */
    @Test
    void traceMethodRejectedWhenRequestSentInLowercaseUnderTurkishLocale() throws Exception {
        // ARRANGE
        MockHttpServletRequest request = new MockHttpServletRequest("trace", "/");
        FilterChain chain = new MockFilterChain();

        // ACT
        filter.doFilter(request, response, chain);

        // ASSERT — must be rejected with 405
        assertThat(response.getStatus())
                .as("lowercase 'trace' must be rejected with 405 under Turkish locale")
                .isEqualTo(405);
    }

    // -----------------------------------------------------------------------
    // Test 2: mixed-case "Track" must be rejected under Turkish locale
    // -----------------------------------------------------------------------

    /**
     * Verifies that an HTTP request with method {@code "Track"} (mixed-case) is
     * rejected with 405 under Turkish locale.
     *
     * <p>SR-LR-03; C8/ASVS V14.5.1; WSTG-CONF-06.
     */
    @Test
    void trackMethodRejectedWhenRequestSentInMixedCaseUnderTurkishLocale() throws Exception {
        // ARRANGE
        MockHttpServletRequest request = new MockHttpServletRequest("Track", "/");
        FilterChain chain = new MockFilterChain();

        // ACT
        filter.doFilter(request, response, chain);

        // ASSERT — must be rejected with 405
        assertThat(response.getStatus())
                .as("mixed-case 'Track' must be rejected with 405 under Turkish locale")
                .isEqualTo(405);
    }

    // -----------------------------------------------------------------------
    // Test 3: GET must pass through under Turkish locale (parity check)
    // -----------------------------------------------------------------------

    /**
     * Verifies that a normal {@code GET} request is not rejected under Turkish locale.
     *
     * <p>This parity test ensures the {@code Locale.ROOT} fix does not accidentally
     * break legitimate method processing.
     *
     * <p>SR-LR-03; C8/ASVS V14.5.1.
     */
    @Test
    void getMethodAcceptedUnderTurkishLocale() throws Exception {
        // ARRANGE
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/rest/wall-id");
        MockFilterChain chain = new MockFilterChain();

        // ACT
        filter.doFilter(request, response, chain);

        // ASSERT — must NOT be rejected (status should remain 200 default, not 405)
        assertThat(response.getStatus())
                .as("GET method must pass through filter chain under Turkish locale — must not be 405")
                .isNotEqualTo(405);

        // The filter chain must have been invoked (request passed through)
        assertThat(chain.getRequest())
                .as("filter chain must have been called for GET — request should be set")
                .isNotNull();
    }
}
