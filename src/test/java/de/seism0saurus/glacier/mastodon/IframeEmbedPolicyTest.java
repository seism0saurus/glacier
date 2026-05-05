package de.seism0saurus.glacier.mastodon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import ch.qos.logback.classic.Level;
import org.slf4j.LoggerFactory;

// Named is retained — goldenVectors() wraps each vector as Named<GoldenVector> for display-name
// formatting in the @ParameterizedTest name pattern "[{index}] {0}". JUnit 5 auto-unwraps
// Named<T> before injecting into the test method, so the parameter type is GoldenVector (not Named).

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Branch-coverage unit tests and golden-vector corpus verification for
 * {@link IframeEmbedPolicy#isEmbeddable}.
 *
 * <p>Two complementary test strategies are combined here:
 * <ol>
 *   <li><strong>Golden-vector parametrized test</strong> (SR-FUZZ-17): loads the
 *       {@code iframe-embed-policy-golden-vectors.json} corpus (≥ 30 vectors) and asserts that
 *       {@code IframeEmbedPolicy.isEmbeddable} produces the recorded {@code expected} value for
 *       every vector. This is the "before photo" confirming byte-for-byte parity between the
 *       extracted class and the original {@code StompCallback.isLoadable} logic.</li>
 *   <li><strong>Explicit branch-coverage unit tests</strong> (SR-FUZZ-14): five named tests, one
 *       per {@code return} branch of the extracted logic, so that mutation testing tools can
 *       distinguish each decision point.</li>
 * </ol>
 *
 * <p>Security: SR-FUZZ-02 — assertion messages never echo the raw arbitrary input. All
 * failure messages use fixed, descriptive strings.
 */
class IframeEmbedPolicyTest {

    /**
     * The Glacier domain used across all test vectors where a domain-specific match is tested.
     */
    private static final String GLACIER_DOMAIN = "glacier.example.com";

    // -------------------------------------------------------------------------
    // Golden-vector parametrized test (SR-FUZZ-17)
    // -------------------------------------------------------------------------

    /**
     * Loads the golden-vector corpus from the test resources and returns one
     * {@link GoldenVector} per JSON element that contains an {@code expected} field.
     *
     * <p>Comment-only elements (containing {@code _comment} but no {@code expected}) are
     * filtered out so they can serve as section separators in the JSON file without
     * contributing test cases.
     *
     * @return stream of test arguments, one per vector
     * @throws Exception if the corpus file is missing or malformed
     */
    static Stream<Named<GoldenVector>> goldenVectors() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        InputStream stream = IframeEmbedPolicyTest.class.getClassLoader()
                .getResourceAsStream("iframe-embed-policy-golden-vectors.json");
        assertThat(stream)
                .as("Golden-vector corpus file must exist on the test classpath")
                .isNotNull();

        JsonNode root = mapper.readTree(stream);
        assertThat(root.isArray())
                .as("Golden-vector corpus must be a JSON array")
                .isTrue();

        List<Named<GoldenVector>> vectors = new ArrayList<>();
        for (JsonNode node : root) {
            if (!node.has("expected")) {
                // Skip comment-only nodes (section separators)
                continue;
            }
            List<String> xFrameOptions = readStringList(node, "xFrameOptions");
            List<String> csp = readStringList(node, "csp");
            String domain = node.has("domain") ? node.get("domain").asText() : GLACIER_DOMAIN;
            boolean expected = node.get("expected").asBoolean();
            String description = node.has("_description")
                    ? node.get("_description").asText()
                    : "vector-" + vectors.size();

            vectors.add(Named.of(description, new GoldenVector(xFrameOptions, csp, domain, expected)));
        }
        return vectors.stream();
    }

    /**
     * SR-FUZZ-17: Verifies that {@code IframeEmbedPolicy.isEmbeddable} produces the expected
     * result for every vector in the golden-vector corpus.
     *
     * <p>This test acts as the "before photo" — a regression gate that ensures the extraction
     * refactor preserves byte-for-byte identical behaviour compared to the original
     * {@code StompCallback.isLoadable}.
     *
     * <p>SR-FUZZ-02: Assertion messages use a fixed string; raw header values are never echoed.
     */
    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("goldenVectors")
    void goldenVectorCorpusMatchesExpectedEmbeddability(final GoldenVector vector) {
        boolean actual = IframeEmbedPolicy.isEmbeddable(
                vector.xFrameOptions(),
                vector.csp(),
                vector.domain());

        assertThat(actual)
                .as("Golden-vector corpus: expected embeddability did not match actual result")
                .isEqualTo(vector.expected());
    }

    // -------------------------------------------------------------------------
    // Explicit branch-coverage unit tests (SR-FUZZ-14)
    // -------------------------------------------------------------------------

    /**
     * Branch 1: {@code frameAncestorsExists && frameAncestorsContainsServerOrWildcard} → {@code true}.
     *
     * <p>Arrange: CSP contains {@code frame-ancestors} with the Glacier domain explicitly listed.
     * Act: Call {@code isEmbeddable} with the CSP header and no XFO.
     * Assert: Returns {@code true} because the Glacier domain is permitted.
     */
    @Test
    void isEmbeddable_returnsTrue_whenFrameAncestorsExistsAndContainsGlacierDomain() {
        List<String> csp = List.of("frame-ancestors https://glacier.example.com");

        boolean result = IframeEmbedPolicy.isEmbeddable(null, csp, GLACIER_DOMAIN);

        assertThat(result)
                .as("Expected embeddable when frame-ancestors explicitly allows glacier domain")
                .isTrue();
    }

    /**
     * Branch 2: {@code frameAncestorsExists && !frameAncestorsContainsServerOrWildcard} → {@code false}.
     *
     * <p>Arrange: CSP contains {@code frame-ancestors} but only lists a different domain; Glacier is absent.
     * Act: Call {@code isEmbeddable} with the CSP header and no XFO.
     * Assert: Returns {@code false} because the Glacier domain is not among the allowed ancestors.
     */
    @Test
    void isEmbeddable_returnsFalse_whenFrameAncestorsExistsButGlacierDomainIsAbsent() {
        List<String> csp = List.of("frame-ancestors https://other.example.com");

        boolean result = IframeEmbedPolicy.isEmbeddable(null, csp, GLACIER_DOMAIN);

        assertThat(result)
                .as("Expected not embeddable when frame-ancestors exists but does not include glacier domain")
                .isFalse();
    }

    /**
     * Branch 3: {@code !frameAncestorsExists && xFrameDefaultAllowed} → {@code true}.
     *
     * <p>Arrange: No CSP, no XFO header (both null).
     * Act: Call {@code isEmbeddable} with null headers.
     * Assert: Returns {@code true} because the browser default (embed allowed) applies
     *         when neither header is present.
     */
    @Test
    void isEmbeddable_returnsTrue_whenNoHeadersPresent_browserDefaultAllows() {
        boolean result = IframeEmbedPolicy.isEmbeddable(null, null, GLACIER_DOMAIN);

        assertThat(result)
                .as("Expected embeddable when neither CSP frame-ancestors nor X-Frame-Options is present")
                .isTrue();
    }

    /**
     * Branch 4: {@code !frameAncestorsExists && !xFrameDefaultAllowed && xFrameExplicitlyNotAllowed} → {@code false}.
     *
     * <p>Arrange: No CSP; XFO header set to {@code DENY}.
     * Act: Call {@code isEmbeddable} with the XFO header and no CSP.
     * Assert: Returns {@code false} because {@code DENY} explicitly forbids embedding.
     */
    @Test
    void isEmbeddable_returnsFalse_whenXFrameOptionsIsDeny() {
        List<String> xFrameOptions = List.of("DENY");

        boolean result = IframeEmbedPolicy.isEmbeddable(xFrameOptions, null, GLACIER_DOMAIN);

        assertThat(result)
                .as("Expected not embeddable when X-Frame-Options is DENY")
                .isFalse();
    }

    /**
     * Branch 5: {@code !frameAncestorsExists && !xFrameDefaultAllowed && xFrameExplicitlyAllowed} → {@code true}.
     *
     * <p>Arrange: No CSP; XFO header set to {@code ALLOWALL}.
     * Act: Call {@code isEmbeddable} with the XFO header and no CSP.
     * Assert: Returns {@code true} because {@code ALLOWALL} explicitly permits embedding.
     */
    @Test
    void isEmbeddable_returnsTrue_whenXFrameOptionsIsAllowAll() {
        List<String> xFrameOptions = List.of("ALLOWALL");

        boolean result = IframeEmbedPolicy.isEmbeddable(xFrameOptions, null, GLACIER_DOMAIN);

        assertThat(result)
                .as("Expected embeddable when X-Frame-Options is ALLOWALL")
                .isTrue();
    }

    // -------------------------------------------------------------------------
    // T-PQ-F — Blank-domain guard (SR-PQ-12)
    // T-PQ-G — AUDIT-logger event on block (SR-PQ-10R2)
    // -------------------------------------------------------------------------

    /**
     * T-PQ-F: Blank-domain guard — fail-closed behaviour (SR-PQ-12).
     *
     * <p>When the configured domain is blank, empty, or null, {@code Pattern.quote("")}
     * would produce a trivially-matching literal that accepts any host in the
     * {@code frame-ancestors} position. The production code must guard against this by
     * returning {@code false} immediately (fail-closed) before performing any regex match.
     *
     * <p>Arrange: CSP with an explicit {@code frame-ancestors} directive allowing an
     * arbitrary host; domain is blank/empty/null.
     * Act: Call {@code isEmbeddable} with each blank-domain variant.
     * Assert: Returns {@code false} in all cases — no host may be accepted when the
     *         configured domain cannot be validated.
     *
     * <p>RED before fix (no guard: NPE or trivial match), GREEN after fix (guard returns false).
     */
    @Test
    @DisplayName("isEmbeddable returns false when domain is blank — fail-closed (SR-PQ-12)")
    void isEmbeddable_returnsFalse_whenDomainIsBlank() {
        // blank domain → Pattern.quote("") would match any host — must fail closed
        List<String> csp = List.of("frame-ancestors https://any-host.example.com");
        assertThat(IframeEmbedPolicy.isEmbeddable(null, csp, "")).isFalse();
        assertThat(IframeEmbedPolicy.isEmbeddable(null, csp, "   ")).isFalse();
        assertThat(IframeEmbedPolicy.isEmbeddable(null, csp, null)).isFalse();
    }

    /**
     * T-PQ-G: AUDIT-logger security event emitted when domain is blocked (SR-PQ-10R2).
     *
     * <p>When {@code isEmbeddable} rejects an embed because the configured Glacier domain
     * is not in the {@code frame-ancestors} list, it must emit a security-relevant warning
     * via the production logger with a scrubbed message format:
     * {@code reason=domain_mismatch}. This key-value pair enables SOC/SIEM tooling to
     * identify blocked embed attempts without exposing raw peer-controlled values.
     *
     * <p>D-13/SR-8/CWE-117: the logged message must NOT contain the raw domain value
     * or any raw header content — only the static reason tag.
     *
     * <p>Arrange: CSP lists a different host; configured domain is {@code glacier.events}.
     * Act: Call {@code isEmbeddable}, which must block and emit the security event.
     * Assert: (1) Result is {@code false}. (2) A WARN-level log event exists.
     *         (3) The message contains {@code reason=domain_mismatch}.
     *         (4) The message does NOT contain the raw domain string or the CSP host.
     *
     * <p>RED before fix (current warn message lacks {@code reason=domain_mismatch}),
     * GREEN after fix.
     */
    @Test
    @DisplayName("isEmbeddable emits AUDIT security event (reason=domain_mismatch) when domain is blocked (SR-PQ-10R2)")
    void isEmbeddable_emitsAuditEventOnBlockedHost() {
        // Capture log events from the production logger used in IframeEmbedPolicy
        ch.qos.logback.classic.Logger productionLogger = (ch.qos.logback.classic.Logger)
                LoggerFactory.getLogger(IframeEmbedPolicy.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> listAppender =
                new ch.qos.logback.core.read.ListAppender<>();
        listAppender.start();
        productionLogger.addAppender(listAppender);

        try {
            List<String> csp = List.of("frame-ancestors https://trusted.example.com");
            boolean result = IframeEmbedPolicy.isEmbeddable(null, csp, "glacier.events");

            assertThat(result).isFalse();

            // Verify security event was emitted
            List<ch.qos.logback.classic.spi.ILoggingEvent> warnEvents = listAppender.list.stream()
                    .filter(e -> e.getLevel() == Level.WARN)
                    .collect(Collectors.toList());
            assertThat(warnEvents).isNotEmpty();

            // Security event must contain reason= key for SOC/SIEM tooling
            String warnMessage = warnEvents.get(0).getFormattedMessage();
            assertThat(warnMessage).contains("reason=domain_mismatch");

            // Raw domain must NOT appear in log (CWE-117 — log injection prevention)
            assertThat(warnMessage).doesNotContain("glacier.events");
            assertThat(warnMessage).doesNotContain("trusted.example.com");
        } finally {
            productionLogger.detachAppender(listAppender);
        }
    }

    // -------------------------------------------------------------------------
    // Helper types
    // -------------------------------------------------------------------------

    /**
     * Reads a JSON array field from a node and returns it as a {@code List<String>},
     * or returns {@code null} if the field is {@code null} in JSON.
     *
     * @param node      the parent JSON node
     * @param fieldName the name of the array field
     * @return a {@code List<String>} or {@code null} if the JSON value is null
     */
    private static List<String> readStringList(final JsonNode node, final String fieldName) {
        if (!node.has(fieldName) || node.get(fieldName).isNull()) {
            return null;
        }
        JsonNode arrayNode = node.get(fieldName);
        if (!arrayNode.isArray()) {
            return null;
        }
        List<String> result = new ArrayList<>();
        for (JsonNode element : arrayNode) {
            result.add(element.asText());
        }
        return result;
    }

    /**
     * Value object for a single golden-vector test case.
     *
     * @param xFrameOptions the XFO header values (null if absent)
     * @param csp           the CSP header values (null if absent)
     * @param domain        the Glacier domain to test against
     * @param expected      the expected embeddability result
     */
    record GoldenVector(
            List<String> xFrameOptions,
            List<String> csp,
            String domain,
            boolean expected) {
    }
}
