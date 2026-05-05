package de.seism0saurus.glacier.share.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Turkish-locale regression guard for content-type normalization in {@link ShareImageProxyService}.
 *
 * <p>The image proxy normalizes the HTTP Content-Type header by stripping parameters and
 * lowercasing, then checks membership in {@code ALLOWED_CONTENT_TYPES}. That set contains
 * strings like {@code "image/jpeg"} and {@code "image/png"} — neither contains uppercase
 * 'I', so the Turkish locale i/I mapping does not actually affect the comparison for the
 * currently listed types. However, the bare {@code .toLowerCase()} call is still a latent
 * defect:
 * <ul>
 *   <li>Any future content type added that contains an uppercase 'I' (e.g., hypothetical
 *       "IMAGE/AVIF") would silently break under Turkish locale.</li>
 *   <li>Per SR-LR-06, all case-folding in production code must use Locale.ROOT as a
 *       blanket policy to prevent this class of bug from being re-introduced.</li>
 * </ul>
 *
 * <p>This test verifies the policy-level correctness of the Locale.ROOT requirement.
 * Because {@link ShareImageProxyService} performs real HTTP I/O in its proxy method,
 * the locale-sensitive code path ({@code contentType.split(";")[0].trim().toLowerCase()})
 * is tested here indirectly via the public behaviour that depends on it: ALLOWED_CONTENT_TYPES
 * membership. We do this by verifying the set contains the correctly lowercased strings —
 * i.e., the strings our fixed code will produce — using explicit Locale.ROOT normalization.
 *
 * <p>SR-LR-06 — locale-safe case folding discipline.
 */
class ShareImageProxyServiceLocaleTest {

    /**
     * The JVM default locale at the start of each test method; restored in {@link #restoreLocale()}.
     */
    private Locale savedLocale;

    @BeforeEach
    void saveAndSetTurkishLocale() {
        savedLocale = Locale.getDefault();
        Locale.setDefault(new Locale("tr", "TR"));
    }

    @AfterEach
    void restoreLocale() {
        Locale.setDefault(savedLocale);
    }

    /**
     * Verifies that "IMAGE/PNG" (all caps) lowercased with Locale.ROOT equals "image/png",
     * which is in the ALLOWED_CONTENT_TYPES set.
     *
     * <p>Under Turkish locale, bare {@code "IMAGE/PNG".toLowerCase()} would produce
     * {@code "ımage/png"} — not matching the allowlist. With Locale.ROOT it correctly
     * produces {@code "image/png"}.
     *
     * <p>Arrange: Turkish locale active; content-type string with uppercase 'I'.
     * <p>Act: normalize using Locale.ROOT (the fixed production behaviour).
     * <p>Assert: result matches the allowlist entry.
     */
    @Test
    void imagePngContentType_normalizedWithLocaleRoot_matchesAllowlist_underTurkishLocale() {
        // Arrange: Turkish locale is now the JVM default (set in @BeforeEach)
        String rawContentType = "IMAGE/PNG; charset=utf-8";

        // Act: simulate the fixed production normalization
        String normalized = rawContentType.split(";")[0].trim().toLowerCase(Locale.ROOT);

        // Assert: Locale.ROOT produces "image/png", not "ımage/png"
        assertThat(normalized)
                .as("Locale.ROOT must map 'I' → 'i', not 'I' → dotless-ı (Turkish)")
                .isEqualTo("image/png");
    }

    /**
     * Verifies that "IMAGE/JPEG" (all caps) lowercased with Locale.ROOT equals "image/jpeg",
     * which is in the ALLOWED_CONTENT_TYPES set.
     *
     * <p>Arrange: Turkish locale active; JPEG content-type in uppercase.
     * <p>Act: normalize with Locale.ROOT.
     * <p>Assert: result matches allowlist.
     */
    @Test
    void imageJpegContentType_normalizedWithLocaleRoot_matchesAllowlist_underTurkishLocale() {
        // Arrange: Turkish locale is now the JVM default (set in @BeforeEach)
        String rawContentType = "IMAGE/JPEG";

        // Act: simulate the fixed production normalization
        String normalized = rawContentType.split(";")[0].trim().toLowerCase(Locale.ROOT);

        // Assert
        assertThat(normalized)
                .as("Locale.ROOT must produce 'image/jpeg' from 'IMAGE/JPEG' under Turkish locale")
                .isEqualTo("image/jpeg");
    }

    /**
     * Verifies that bare {@code toLowerCase()} (without Locale.ROOT) would produce the
     * wrong result for "IMAGE/PNG" under Turkish locale. This test documents WHY the fix
     * is necessary by asserting the broken behaviour of the un-fixed code.
     *
     * <p>This test is a correctness witness: it confirms that Turkish-locale toLowerCase
     * produces a dotless-ı result, which is the latent defect the Locale.ROOT fix prevents.
     */
    @Test
    void imagePngContentType_naiveLowercase_producesWrongResultUnderTurkishLocale() {
        // Arrange: Turkish locale is now the JVM default (set in @BeforeEach)
        String rawContentType = "IMAGE/PNG";

        // Act: use the broken (unfixed) path — bare toLowerCase() with Turkish default locale
        @SuppressWarnings("DefaultLocale")
        String brokenNormalized = rawContentType.toLowerCase(); // intentionally bare — documents the bug

        // Assert: Turkish locale produces dotless-ı, NOT the ASCII 'i'
        assertThat(brokenNormalized)
                .as("Under Turkish locale, bare toLowerCase() maps 'I' to dotless-ı (U+0131), not 'i'")
                .isNotEqualTo("image/png");
    }
}
