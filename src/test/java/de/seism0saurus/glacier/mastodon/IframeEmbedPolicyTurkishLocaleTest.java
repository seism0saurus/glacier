package de.seism0saurus.glacier.mastodon;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Turkish-locale regression tests for {@link IframeEmbedPolicy#isEmbeddable}.
 *
 * <p>On a JVM started with Turkish locale ({@code -Duser.language=tr}),
 * {@code 'I'.toLowerCase()} returns {@code 'ı'} (dotless-i, U+0131) instead of
 * {@code 'i'}, and {@code 'i'.toUpperCase()} returns {@code 'İ'} (U+0130) instead
 * of {@code 'I'}. This silently corrupts the {@code frame-ancestors} directive
 * detection in {@link IframeEmbedPolicy}, allowing forbidden embeds to pass through.
 *
 * <p>Security requirements: SR-LR-02 (CWE-176/CWE-178), OWASP Proactive Controls C3
 * (Input Validation), C8 (Browser Security). All four methods document which
 * Turkish-locale behaviour they guard against.
 *
 * <p>ADR-LR-04: {@link BeforeEach}/{@link AfterEach} locale-restore pattern (not per-test
 * JVM fork) — sufficient because Surefire runs one JVM per module and the restore is
 * guaranteed via {@code @AfterEach} even on assertion failure.
 */
class IframeEmbedPolicyTurkishLocaleTest {

    private static final String GLACIER_DOMAIN = "glacier.example.com";

    /**
     * Saved default locale — restored unconditionally after each test (SR-LR-06).
     */
    private Locale savedLocale;

    @BeforeEach
    void setTurkishLocale() {
        savedLocale = Locale.getDefault();
        Locale.setDefault(new Locale("tr", "TR"));
    }

    @AfterEach
    void restoreLocale() {
        Locale.setDefault(savedLocale);
    }

    // -----------------------------------------------------------------------
    // Test 1: lowercase frame-ancestors in CSP under Turkish locale
    // -----------------------------------------------------------------------

    /**
     * Verifies that the {@code frame-ancestors} directive is detected correctly when the
     * CSP header value is all-lowercase under Turkish locale.
     *
     * <p>Without {@code Locale.ROOT}, {@code "frame-ancestors".toUpperCase()} produces
     * {@code "FRAME-ANCESTORS"} in most locales, but under Turkish locale the dotless-i
     * issue causes {@code 'i'.toUpperCase()} → {@code 'İ'} (U+0130), corrupting the
     * comparison.
     *
     * <p>SR-LR-02; OWASP C8 — the iframe gate must not silently ALLOW when the
     * CSP contains a permitting {@code frame-ancestors} under any JVM locale.
     */
    @Test
    void frameAncestorsDirectiveDetectedUnderTurkishLocale() {
        // ARRANGE — lowercase CSP with https: wildcard ancestor
        List<String> csp = List.of("frame-ancestors https: https://glacier.example.com");

        // ACT
        boolean result = IframeEmbedPolicy.isEmbeddable(null, csp, GLACIER_DOMAIN);

        // ASSERT — must be ALLOW (glacier.example.com is in frame-ancestors)
        assertThat(result)
                .as("frame-ancestors with glacier domain must be detected as ALLOW under Turkish locale")
                .isTrue();
    }

    // -----------------------------------------------------------------------
    // Test 2: mixed-case frame-ancestors header under Turkish locale
    // -----------------------------------------------------------------------

    /**
     * Verifies that a mixed-case {@code Frame-Ancestors} directive is detected under
     * Turkish locale.
     *
     * <p>A locale-unsafe {@code toUpperCase()} call on mixed-case input like
     * {@code "Frame-Ancestors"} produces {@code "FRAME-ANCESTORS"} in most locales
     * but may corrupt the 'i' and 'a' characters under Turkish locale.
     *
     * <p>SR-LR-02; CWE-178.
     */
    @Test
    void frameAncestorsDirectiveDetectedWhenHeaderUsesMixedCaseUnderTurkishLocale() {
        // ARRANGE — mixed-case form as a remote server might return
        List<String> csp = List.of("Frame-Ancestors https://glacier.example.com");

        // ACT
        boolean result = IframeEmbedPolicy.isEmbeddable(null, csp, GLACIER_DOMAIN);

        // ASSERT — must be ALLOW
        assertThat(result)
                .as("Mixed-case Frame-Ancestors must be detected as ALLOW under Turkish locale")
                .isTrue();
    }

    // -----------------------------------------------------------------------
    // Test 3: domain containing dotted-i under Turkish locale
    // -----------------------------------------------------------------------

    /**
     * Verifies that a {@code frame-ancestors} host value containing the ASCII letter 'i'
     * (e.g. {@code https://invite.example}) is not corrupted when compared with the
     * glacier domain under Turkish locale.
     *
     * <p>The regex match in {@link IframeEmbedPolicy} uses
     * {@code domain.toUpperCase().matches(...)}. Under Turkish locale,
     * {@code "invite.example.com".toUpperCase()} produces the wrong uppercase
     * 'I' variant for Turkish, breaking the regex match.
     *
     * <p>SR-LR-02; CWE-176.
     */
    @Test
    void dottedIInCspHostNameDoesNotCorruptMatchUnderTurkishLocale() {
        // ARRANGE — use a glacier domain containing 'i' characters
        String glacierDomainWithI = "invite.glacier-instance.com";
        List<String> csp = List.of(
                "frame-ancestors https://invite.glacier-instance.com");

        // ACT
        boolean result = IframeEmbedPolicy.isEmbeddable(null, csp, glacierDomainWithI);

        // ASSERT — must be ALLOW because the glacier domain is listed
        assertThat(result)
                .as("Domain with 'i' characters must match frame-ancestors correctly under Turkish locale")
                .isTrue();
    }

    // -----------------------------------------------------------------------
    // Test 4: DENY result is stable across locales
    // -----------------------------------------------------------------------

    /**
     * Verifies that when the CSP {@code frame-ancestors} explicitly forbids the glacier
     * domain (no wildcard, no matching host), the result is DENY under Turkish locale.
     *
     * <p>This guards against a regression where locale fixup could cause a DENY path
     * to accidentally become ALLOW.
     *
     * <p>SR-LR-02; OWASP C8.
     */
    @Test
    void denyResultStableAcrossLocales() {
        // ARRANGE — CSP allows only a different domain, not glacier
        List<String> csp = List.of("frame-ancestors https://other.example.com");

        // ACT
        boolean result = IframeEmbedPolicy.isEmbeddable(null, csp, GLACIER_DOMAIN);

        // ASSERT — must be DENY
        assertThat(result)
                .as("DENY result must be stable and must not be corrupted under Turkish locale")
                .isFalse();
    }
}
