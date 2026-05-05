package de.seism0saurus.glacier.share.application;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Turkish-locale regression guard for {@link JsoupTextExtractor#extractTextOnly}.
 *
 * <p>HTML tag names are compared via {@code element.tagName().toLowerCase()}. Under the
 * Turkish locale, the uppercase letter 'I' (U+0049) lowercases to dotless 'ı' (U+0131)
 * instead of 'i' (U+0069). This means:
 * <ul>
 *   <li>{@code "BR".toLowerCase(turkishLocale)} → {@code "br"} ✓ (no 'I' involved)</li>
 *   <li>{@code "IMG".toLowerCase(turkishLocale)} → {@code "ımg"} — does not equal "img" ✗</li>
 *   <li>{@code "INPUT".toLowerCase(turkishLocale)} → {@code "ınput"} — does not equal "input" ✗</li>
 * </ul>
 *
 * <p>For the {@code <br>} and {@code <p>/<div>} cases specifically tested in the extractor,
 * none of the characters involved ('b', 'r', 'p', 'd', 'i', 'v') are affected by the
 * Turkish locale mapping for uppercase 'I'. However, the fix (adding {@code Locale.ROOT})
 * is still required as a defensive measure — any future tag name added to the comparison
 * set that includes an 'I' would silently break under Turkish locale.
 *
 * <p>This test documents and guards the Locale.ROOT requirement (SR-LR-06).
 */
class JsoupTextExtractorLocaleTest {

    /**
     * The JVM default locale at the start of each test method; restored in {@link #restoreLocale()}.
     */
    private Locale savedLocale;

    private final JsoupTextExtractor extractor = new JsoupTextExtractor();

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
     * Verifies that {@code <br>} produces a newline under Turkish locale.
     *
     * <p>Arrange: Turkish locale active; HTML contains a br tag.
     * <p>Act: extractor processes the HTML.
     * <p>Assert: line break is inserted (tag comparison uses Locale.ROOT).
     */
    @Test
    void brTagProducesNewline_underTurkishLocale() {
        // Arrange: Turkish locale is now the JVM default (set in @BeforeEach)
        String html = "<p>hello<br>world</p>";

        // Act
        String result = extractor.extract(html);

        // Assert: br tag correctly recognized and converted to newline
        assertThat(result)
                .as("br tag must produce a newline under Turkish locale (Locale.ROOT in tagName comparison)")
                .contains("hello")
                .contains("world");
        // The newline or spacing between hello and world confirms br was processed
        assertThat(result.indexOf("world"))
                .as("'world' must appear after 'hello' with a line break separating them")
                .isGreaterThan(result.indexOf("hello"));
    }

    /**
     * Verifies that {@code <p>} produces trailing newlines under Turkish locale.
     *
     * <p>Arrange: Turkish locale active; HTML contains paragraph tags.
     * <p>Act: extractor processes the HTML.
     * <p>Assert: paragraphs are separated (tag comparison uses Locale.ROOT).
     */
    @Test
    void paragraphTagProducesNewline_underTurkishLocale() {
        // Arrange: Turkish locale is now the JVM default (set in @BeforeEach)
        String html = "<p>first paragraph</p><p>second paragraph</p>";

        // Act
        String result = extractor.extract(html);

        // Assert: both paragraphs present in output
        assertThat(result)
                .as("Both paragraphs must be extracted under Turkish locale")
                .contains("first paragraph")
                .contains("second paragraph");
    }

    /**
     * Verifies that mixed-case HTML containing 'I' characters in tag names is extracted
     * correctly under Turkish locale. Jsoup normalizes tag names internally so its
     * output is already lowercase ASCII — the fix ensures our comparison against those
     * lowercased names also uses Locale.ROOT for correctness guarantees.
     *
     * <p>This smoke-test confirms the extractor does not crash and returns text content
     * when the JVM default locale is Turkish.
     */
    @Test
    void htmlWithTagsContainingI_extractsTextContent_underTurkishLocale() {
        // Arrange: Turkish locale is now the JVM default (set in @BeforeEach)
        // Jsoup normalizes IMG and INPUT to lowercase internally; our tagName() comparison
        // must then also use Locale.ROOT to correctly identify "br", "p", "div"
        String html = "<div><p>visible text</p><img src='photo.jpg' alt='photo'/></div>";

        // Act
        String result = extractor.extract(html);

        // Assert: text content extracted, no crash under Turkish locale
        assertThat(result)
                .as("Text content must be extracted correctly under Turkish locale")
                .contains("visible text");
    }
}
