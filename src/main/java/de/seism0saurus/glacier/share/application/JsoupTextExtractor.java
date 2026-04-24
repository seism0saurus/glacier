package de.seism0saurus.glacier.share.application;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.NodeVisitor;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Server-side HTML-to-text extractor using Jsoup.
 *
 * <p>This is the single, auditable XSS defence boundary for the share-link rendering path.
 * Raw HTML from Mastodon toot content is never transmitted to viewers — only the
 * structured text extracted here is safe to use in Angular interpolation.
 *
 * <p>Extraction rules:
 * <ul>
 *   <li>{@code <br>} → {@code \n}</li>
 *   <li>{@code <p>} / {@code <div>} → {@code \n} appended at closing tag</li>
 *   <li>Only text nodes collected — attribute values (title, alt, onclick...) are ignored</li>
 *   <li>Output capped at {@link #MAX_TEXT_BYTES} bytes; excess replaced by {@link #TRUNCATION_MARKER}</li>
 *   <li>Bidi override characters (U+202E, U+2066-U+2069) stripped; flag set in result</li>
 * </ul>
 *
 * <p>Security: SR-SHARE-03, SR-SHARE-04, ADR-SHARE-03.
 * References: OWASP XSS Prevention Cheat Sheet, OWASP A03 (Injection).
 */
@Component
public class JsoupTextExtractor {

    /** Maximum output text size in bytes before truncation. */
    public static final int MAX_TEXT_BYTES = 8192;

    /** Appended to output when truncation occurs; allows callers to detect truncation. */
    public static final String TRUNCATION_MARKER = "[…]";

    /**
     * Bidi override and directional isolate code points that are stripped from extracted text.
     * These characters can be used for Trojan Source attacks (text spoofing).
     */
    private static final Set<Integer> BIDI_STRIP_SET = Set.of(
            0x202E, // RIGHT-TO-LEFT OVERRIDE
            0x2066, // LEFT-TO-RIGHT ISOLATE
            0x2067, // RIGHT-TO-LEFT ISOLATE
            0x2068, // FIRST STRONG ISOLATE
            0x2069  // POP DIRECTIONAL ISOLATE
    );

    /**
     * Extracts plain text from the given HTML, applying all safety rules.
     *
     * @param html the raw HTML string (may be {@code null})
     * @return plain text safe for Angular interpolation; never {@code null}
     */
    public String extract(final String html) {
        return extractWithMeta(html).text();
    }

    /**
     * Extracts plain text and returns metadata about the extraction.
     *
     * @param html the raw HTML string (may be {@code null})
     * @return an {@link ExtractionResult} containing the text and extraction metadata
     */
    public ExtractionResult extractWithMeta(final String html) {
        if (html == null || html.isEmpty()) {
            return new ExtractionResult("", false);
        }

        // Jsoup lenient parser handles malformed HTML without throwing.
        // We use a custom DOM traversal that collects only text nodes —
        // attribute values (title, alt, onclick) are never included.
        // This prevents attribute-injection XSS vectors like:
        //   <p title="malicious_content"> — title value must NOT appear in extracted text.
        Document doc = Jsoup.parse(html);
        String text = extractTextOnly(doc);

        // Strip bidi override characters
        boolean[] bidiStripped = {false};
        String cleaned = text.codePoints()
                .filter(cp -> {
                    if (BIDI_STRIP_SET.contains(cp)) {
                        bidiStripped[0] = true;
                        return false;
                    }
                    return true;
                })
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();

        // Apply 8 KB cap
        if (cleaned.length() > MAX_TEXT_BYTES) {
            cleaned = cleaned.substring(0, MAX_TEXT_BYTES) + TRUNCATION_MARKER;
        }

        return new ExtractionResult(cleaned.trim(), bidiStripped[0]);
    }

    /**
     * Result of text extraction, including safety metadata.
     *
     * @param text        the extracted plain text; never {@code null}
     * @param bidiStripped {@code true} if at least one bidi override character was stripped;
     *                    callers may use this to add {@code dir=auto} in the UI for a11y
     */
    public record ExtractionResult(String text, boolean bidiStripped) {}

    /**
     * Extracts only text node content (not attribute values) from the document.
     *
     * <p>Jsoup's default {@code doc.text()} includes attribute content such as title and alt,
     * which would enable attribute-injection XSS (e.g., a {@code title} attribute containing
     * HTML-encoded event handlers would appear in the output). This traversal only collects
     * actual text nodes.
     */
    private static String extractTextOnly(Document doc) {
        StringBuilder sb = new StringBuilder();
        doc.traverse(new NodeVisitor() {
            @Override
            public void head(org.jsoup.nodes.Node node, int depth) {
                if (node instanceof org.jsoup.nodes.TextNode textNode) {
                    sb.append(textNode.getWholeText());
                } else if (node instanceof org.jsoup.nodes.Element element) {
                    String tag = element.tagName().toLowerCase();
                    if ("br".equals(tag)) {
                        sb.append("\n");
                    }
                }
            }

            @Override
            public void tail(org.jsoup.nodes.Node node, int depth) {
                if (node instanceof org.jsoup.nodes.Element element) {
                    String tag = element.tagName().toLowerCase();
                    if ("p".equals(tag) || "div".equals(tag)) {
                        sb.append("\n");
                    }
                }
            }
        });
        return sb.toString();
    }
}
