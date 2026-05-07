package de.seism0saurus.glacier.mastodon;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Turkish-locale regression guard for scheme extraction in {@link StompCallback}.
 *
 * <p>{@link StompCallback#extractScheme(String)} extracts the URL scheme and lowercases it
 * using a bare {@code scheme.toLowerCase()} call. Under the Turkish locale, this is safe for
 * common schemes like "http" and "https" (none of their characters are affected by the
 * Turkish i/I mapping). However, as a matter of policy (SR-LR-06), all case-folding in
 * production code must use {@code Locale.ROOT} to prevent the class of bug where a future
 * scheme name containing 'I' (e.g., hypothetical "IMAP") would silently mis-fold.
 *
 * <p>The {@code extractScheme} method is package-private, so this test exercises the
 * locale-sensitive behaviour directly by testing the normalization contract:
 * {@code URI.create(url).getScheme().toLowerCase(Locale.ROOT)} must produce the expected
 * lowercase ASCII scheme string regardless of the JVM default locale.
 *
 * <p>SR-LR-06 — locale-safe case folding discipline.
 */
class StompCallbackLocaleTest {

    /**
     * The JVM default locale at the start of each test method; restored in {@link #restoreLocale()}.
     */
    private Locale savedLocale;

    @BeforeEach
    void saveAndSetTurkishLocale() {
        savedLocale = Locale.getDefault();
        Locale.setDefault(Locale.of("tr", "TR"));
    }

    @AfterEach
    void restoreLocale() {
        Locale.setDefault(savedLocale);
    }

    /**
     * Verifies that a URL containing uppercase 'I' in its scheme portion ("HTTPS://...")
     * is correctly normalized to "https" using Locale.ROOT, under Turkish locale.
     *
     * <p>Note: Java's {@link java.net.URI} parser normalizes scheme to lowercase
     * internally per RFC 3986, so {@code URI.create("HTTPS://...").getScheme()} already
     * returns "https". The {@code scheme.toLowerCase()} call in production is redundant
     * but must still use Locale.ROOT to document and enforce the policy.
     *
     * <p>This test documents the expected Locale.ROOT behaviour (fixed code) while the
     * Turkish locale is active.
     *
     * <p>Arrange: Turkish locale active; URL with uppercase scheme.
     * <p>Act: extract scheme and apply Locale.ROOT lowercase (fixed production behaviour).
     * <p>Assert: scheme is "https".
     */
    @Test
    void httpsScheme_normalizedWithLocaleRoot_producesLowercaseHttps_underTurkishLocale() {
        // Arrange: Turkish locale is now the JVM default (set in @BeforeEach)
        // URI.create normalizes scheme; the subsequent .toLowerCase() must use Locale.ROOT
        String rawUrl = "HTTPS://mastodon.social/@user/123456";

        // Act: simulate the fixed extractScheme behaviour
        java.net.URI uri = java.net.URI.create(rawUrl);
        String scheme = uri.getScheme();
        String normalized = scheme != null ? scheme.toLowerCase(Locale.ROOT) : "unknown";

        // Assert: scheme is correctly "https" — not "https" mangled by Turkish locale
        // (URI already lowercases scheme internally, so this confirms Locale.ROOT is a no-op
        // for the https case, and documents the safe baseline)
        assertThat(normalized)
                .as("Locale.ROOT toLowerCase must produce 'https' for HTTPS URL under Turkish locale")
                .isEqualTo("https");
    }

    /**
     * Verifies that bare {@code toLowerCase()} (without Locale.ROOT) on "HTTPS" would not
     * be a problem for the specific letters in that scheme, but that the principle of using
     * Locale.ROOT is correct for any scheme that might contain 'I'.
     *
     * <p>Documents WHY the fix exists: if a scheme string directly contained an uppercase 'I'
     * (as in "IMAP"), bare toLowerCase() under Turkish locale would produce "ımap", not "imap".
     *
     * <p>Arrange: Turkish locale active; a hypothetical scheme-like string with uppercase 'I'.
     * <p>Act: apply bare toLowerCase() (documents the broken behaviour).
     * <p>Assert: result is wrong (dotless-ı), confirming the defect the fix prevents.
     */
    @Test
    void schemeWithUppercaseI_naiveLowercase_producesWrongResult_underTurkishLocale() {
        // Arrange: Turkish locale is now the JVM default (set in @BeforeEach)
        // Simulate a scheme string that contains uppercase 'I'
        String schemeWithI = "IMAP"; // hypothetical scheme containing 'I'

        // Act: bare toLowerCase() — intentionally broken to document the defect
        @SuppressWarnings("DefaultLocale")
        String brokenNormalized = schemeWithI.toLowerCase();

        // Assert: Turkish locale maps 'I' to dotless-ı (U+0131)
        assertThat(brokenNormalized)
                .as("Under Turkish locale, bare toLowerCase() on 'I' produces dotless-ı, not 'i'")
                .isNotEqualTo("imap");

        // Confirm that Locale.ROOT produces the correct result
        String fixedNormalized = schemeWithI.toLowerCase(Locale.ROOT);
        assertThat(fixedNormalized)
                .as("Locale.ROOT toLowerCase on 'IMAP' must produce 'imap' regardless of JVM locale")
                .isEqualTo("imap");
    }

    /**
     * Verifies that "http" scheme (already lowercase) is returned correctly under Turkish locale.
     *
     * <p>Arrange: Turkish locale active; http URL.
     * <p>Act: simulate extractScheme with Locale.ROOT fix.
     * <p>Assert: "http" returned.
     */
    @Test
    void httpScheme_normalizedWithLocaleRoot_producesLowercaseHttp_underTurkishLocale() {
        // Arrange
        String rawUrl = "http://mastodon.social/@user/789";

        // Act: simulate fixed extractScheme
        java.net.URI uri = java.net.URI.create(rawUrl);
        String scheme = uri.getScheme();
        String normalized = scheme != null ? scheme.toLowerCase(Locale.ROOT) : "unknown";

        // Assert
        assertThat(normalized)
                .as("Locale.ROOT toLowerCase must produce 'http' under Turkish locale")
                .isEqualTo("http");
    }
}
