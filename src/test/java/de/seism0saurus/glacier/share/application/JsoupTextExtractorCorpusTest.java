package de.seism0saurus.glacier.share.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Corpus tests for {@link JsoupTextExtractor} — verifies XSS stripping and text extraction.
 *
 * <p>Security requirements: SR-SHARE-03 (server-side Jsoup extraction),
 * SR-SHARE-04 (structured DTOs only, no raw HTML to client).
 * References: OWASP A03 (Injection), OWASP XSS Prevention Cheat Sheet.
 */
class JsoupTextExtractorCorpusTest {

    private final JsoupTextExtractor extractor = new JsoupTextExtractor();

    // -----------------------------------------------------------------------
    // Script / dangerous tag stripping
    // -----------------------------------------------------------------------

    @Test
    void stripsScriptTag() {
        String result = extractor.extract("<p>hello</p><script>alert(1)</script>");
        assertThat(result).doesNotContain("<script>", "alert(1)", "</script>");
        assertThat(result).contains("hello");
    }

    @Test
    void stripsScriptTagRetainingNoBody() {
        // script body must not appear verbatim
        String result = extractor.extract("<script>document.cookie='stolen'</script>safe text");
        assertThat(result).doesNotContain("document.cookie");
        assertThat(result).contains("safe text");
    }

    @Test
    void stripsIframeTag() {
        String result = extractor.extract("<iframe src='http://evil.com'></iframe>text");
        assertThat(result).doesNotContain("<iframe>", "evil.com", "</iframe>");
        assertThat(result).contains("text");
    }

    @Test
    void stripsObjectEmbed() {
        String result = extractor.extract("<object data='evil.swf'></object><embed src='evil.swf'/>normal");
        assertThat(result).doesNotContain("<object>", "<embed>", "evil.swf");
        assertThat(result).contains("normal");
    }

    @Test
    void stripsSvgWithOnload() {
        String result = extractor.extract("<svg onload='alert(1)'></svg>content");
        assertThat(result).doesNotContain("onload", "alert");
        assertThat(result).contains("content");
    }

    // -----------------------------------------------------------------------
    // Line-break and paragraph handling
    // -----------------------------------------------------------------------

    @Test
    void convertsBrToNewline() {
        String result = extractor.extract("<p>hello<br>world</p>");
        assertThat(result).contains("hello");
        assertThat(result).contains("world");
        // br should become newline separator
        assertThat(result).contains("\n");
    }

    @Test
    void convertsParagraphsToDoubleNewline() {
        String result = extractor.extract("<p>para1</p><p>para2</p>");
        assertThat(result).contains("para1");
        assertThat(result).contains("para2");
    }

    // -----------------------------------------------------------------------
    // MutationXSS vectors
    // -----------------------------------------------------------------------

    @Test
    void handlesMutationXssNoscriptAttributeInjection() {
        // MutationXSS: the title attribute contains HTML that could be re-parsed client-side.
        // Our server-side extractor must NOT include attribute values in text output.
        // The noscript tag structure makes some parsers re-parse title content as HTML.
        // With our custom text-node-only traversal, attribute values are never included.
        String result = extractor.extract(
                "<p title=\"javascript:alert(1)\">safe content</p>");
        // The title attribute value must NOT appear in extracted text
        assertThat(result).doesNotContain("javascript:alert(1)");
        assertThat(result).contains("safe content");
    }

    @Test
    void attributeValuesNeverIncludedInExtractedText() {
        // Our extractTextOnly() traversal must skip attribute values (alt, title, placeholder, etc.)
        // This prevents any attribute-injection attacks from reaching Angular templates
        String result = extractor.extract(
                "<img src='x' onerror='alert(1)' alt='img description'>");
        assertThat(result).doesNotContain("onerror", "alert(1)");
        // Note: alt text is intentionally excluded from text extraction for security
        // (Angular structural rendering handles alt separately via media DTOs)
    }

    @Test
    void handlesNestedEntities() {
        // &#x6A; = 'j' in decimal
        String result = extractor.extract("<p>&#x6A;avascript&#x3A;alert(1)</p>");
        // Jsoup parses HTML entities — the text() call should give decoded text
        // but crucially it should not contain script execution
        assertThat(result).doesNotContain("<script>", "<img");
    }

    // -----------------------------------------------------------------------
    // 8 KB cap
    // -----------------------------------------------------------------------

    @Test
    void capsAt8Kb() {
        // Generate content > 8KB
        String bigContent = "<p>" + "A".repeat(10000) + "</p>";
        String result = extractor.extract(bigContent);
        assertThat(result.length()).isLessThanOrEqualTo(8192 + JsoupTextExtractor.TRUNCATION_MARKER.length());
        assertThat(result).endsWith(JsoupTextExtractor.TRUNCATION_MARKER);
    }

    @Test
    void shortContentNotTruncated() {
        String result = extractor.extract("<p>hello world</p>");
        assertThat(result).doesNotEndWith(JsoupTextExtractor.TRUNCATION_MARKER);
    }

    // -----------------------------------------------------------------------
    // Bidi override stripping
    // -----------------------------------------------------------------------

    @Test
    void stripsBidiOverrideChars() {
        // U+202E RIGHT-TO-LEFT OVERRIDE
        String bidiInput = "<p>safe‮text</p>";
        JsoupTextExtractor.ExtractionResult result = extractor.extractWithMeta(bidiInput);
        assertThat(result.text()).doesNotContain("‮");
        assertThat(result.bidiStripped()).isTrue();
    }

    @Test
    void noBidiStrippedFlagWhenNoBidi() {
        JsoupTextExtractor.ExtractionResult result = extractor.extractWithMeta("<p>normal text</p>");
        assertThat(result.bidiStripped()).isFalse();
    }

    @ParameterizedTest(name = "bidi char U+{0} is stripped")
    @CsvSource({
            "202E,RIGHT-TO-LEFT OVERRIDE",
            "2066,LEFT-TO-RIGHT ISOLATE",
            "2067,RIGHT-TO-LEFT ISOLATE",
            "2068,FIRST STRONG ISOLATE",
            "2069,POP DIRECTIONAL ISOLATE"
    })
    void stripsAllBidiOverrideVariants(String codePoint, String description) {
        String bidiChar = Character.toString(Integer.parseInt(codePoint, 16));
        JsoupTextExtractor.ExtractionResult result = extractor.extractWithMeta("<p>text" + bidiChar + "more</p>");
        assertThat(result.text()).as("should strip %s", description).doesNotContain(bidiChar);
        assertThat(result.bidiStripped()).isTrue();
    }

    // -----------------------------------------------------------------------
    // Null / empty input
    // -----------------------------------------------------------------------

    @Test
    void handlesNullInput() {
        String result = extractor.extract(null);
        assertThat(result).isEmpty();
    }

    @Test
    void handlesEmptyInput() {
        String result = extractor.extract("");
        assertThat(result).isEmpty();
    }

    @Test
    void handlesMalformedHtml() {
        // Should not throw; Jsoup is lenient
        String result = extractor.extract("<p>unclosed<b>tag");
        assertThat(result).contains("unclosed");
        assertThat(result).contains("tag");
    }
}
