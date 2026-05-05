package de.seism0saurus.glacier.mastodon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

// Named is retained — goldenVectors() wraps each vector as Named<GoldenVector> for display-name
// formatting in the @ParameterizedTest name pattern "[{index}] {0}". JUnit 5 auto-unwraps
// Named<T> before injecting into the test method, so the parameter type is GoldenVector (not Named).

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
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
    // Pattern.quote() regression tests — T-PQ-A through T-PQ-E (SR-PQ-01)
    //
    // The vulnerability: domain.toUpperCase(Locale.ROOT) is interpolated raw into
    // a String.matches() pattern. A '.' in a domain like "glacier.events" acts as
    // a regex wildcard, allowing a lookalike host (e.g. "glacierXevents") to satisfy
    // the frame-ancestors check and pass through as embeddable.
    //
    // Fix (Lane B): Pattern.quote(domain.toUpperCase(Locale.ROOT)) — tests T-PQ-A
    // through T-PQ-D must fail (RED) before that fix is applied, and pass (GREEN)
    // after. T-PQ-E must always pass (over-rejection guard).
    // -------------------------------------------------------------------------

    /**
     * T-PQ-A: Dot-substitution lookalike — regex wildcard bypass.
     *
     * <p>The '.' in the configured domain "glacier.events" is interpolated raw into
     * the regex, where it matches any character. An attacker who controls the remote
     * Mastodon instance can set {@code frame-ancestors https://glacierXevents} (any
     * character in place of the dot) and the unfixed regex accepts it.
     *
     * <p>Arrange: configured domain {@code glacier.events}; CSP directive contains the
     * lookalike host {@code https://glacierXevents} where 'X' replaces the literal dot.
     * Act: Call {@code isEmbeddable} with this CSP and no XFO.
     * Assert: Returns {@code false} — a lookalike must never satisfy the domain check.
     *
     * <p>RED before fix (regex wildcard matches 'X'), GREEN after fix (Pattern.quote
     * enforces literal-dot matching).
     */
    @Test
    void isEmbeddable_returnsFalse_whenFrameAncestorsContainsLookalikeHostWithSubstitutedDots() {
        List<String> csp = List.of("frame-ancestors https://glacierXevents");

        boolean result = IframeEmbedPolicy.isEmbeddable(null, csp, "glacier.events");

        assertThat(result)
                .as("Lookalike host with substituted dot must not satisfy the domain check")
                .isFalse();
    }

    /**
     * T-PQ-B: Underscore substitution — exact-match semantics enforcement.
     *
     * <p>An underscore is not a regex metacharacter, so this test proves that
     * Pattern.quote() enforces exact-match semantics beyond metacharacter neutralization:
     * even a non-metacharacter substitute ('_') must not match the literal dot in the
     * domain name. The unfixed regex accepts the underscore because the raw '.' in
     * "GLACIER.EVENTS" matches any character, including '_'.
     *
     * <p>Arrange: configured domain {@code glacier.events}; CSP directive contains the
     * lookalike host {@code https://glacier_events} where '_' replaces the literal dot.
     * Act: Call {@code isEmbeddable} with this CSP and no XFO.
     * Assert: Returns {@code false} — no substitute character may pass as the literal dot.
     *
     * <p>RED before fix (raw '.' matches '_'), GREEN after fix (Pattern.quote requires
     * an exact '.' at that position).
     */
    @Test
    void isEmbeddable_returnsFalse_whenFrameAncestorsContainsLookalikeHostWithUnderscoreSubstitution() {
        List<String> csp = List.of("frame-ancestors https://glacier_events");

        boolean result = IframeEmbedPolicy.isEmbeddable(null, csp, "glacier.events");

        assertThat(result)
                .as("Lookalike host with underscore substitution must not satisfy the domain check")
                .isFalse();
    }

    /**
     * T-PQ-C: Metacharacter '+' substitution in a multi-segment domain.
     *
     * <p>The configured domain {@code glacier.example.com} contains two literal dots.
     * When interpolated raw, the regex becomes {@code GLACIER.EXAMPLE.COM} — three
     * wildcards instead of three literal dots. An attacker substitutes each dot with
     * the '+' character (a regex quantifier for the preceding character). The unfixed
     * regex accepts the input because each raw '.' in the pattern matches the '+'
     * character in the attacker host.
     *
     * <p>Arrange: configured domain {@code glacier.example.com}; CSP directive contains
     * the lookalike host {@code https://glacier+example+com}.
     * Act: Call {@code isEmbeddable} with this CSP and no XFO.
     * Assert: Returns {@code false} — the metacharacter-bearing lookalike must not match.
     *
     * <p>RED before fix (raw '.' matches '+'), GREEN after fix (Pattern.quote quotes the
     * '+' before it reaches the regex engine).
     */
    @Test
    void isEmbeddable_returnsFalse_whenFrameAncestorsContainsLookalikeHostWithMetacharacterSubstitution() {
        List<String> csp = List.of("frame-ancestors https://glacier+example+com");

        boolean result = IframeEmbedPolicy.isEmbeddable(null, csp, "glacier.example.com");

        assertThat(result)
                .as("Lookalike host with metacharacter '+' substitution must not satisfy the domain check")
                .isFalse();
    }

    /**
     * T-PQ-D: Multi-token frame-ancestors list containing a lookalike without the legitimate domain.
     *
     * <p>A real {@code frame-ancestors} directive may contain multiple space-separated tokens.
     * This test verifies that the policy correctly rejects a directive that includes the
     * lookalike host alongside other trusted origins, but does not include the actual
     * Glacier domain {@code glacier.events}.
     *
     * <p>Arrange: configured domain {@code glacier.events}; CSP directive is
     * {@code "frame-ancestors 'self' https://glacierXevents https://trusted.example.com"}.
     * Act: Call {@code isEmbeddable} with this multi-token CSP and no XFO.
     * Assert: Returns {@code false} — presence of the lookalike in a multi-token list
     * must not satisfy the domain check when the legitimate domain is absent.
     *
     * <p>RED before fix (raw '.' matches 'X' in the lookalike token), GREEN after fix.
     */
    @Test
    void isEmbeddable_returnsFalse_whenFrameAncestorsMultiTokenContainsLookalikeHost() {
        List<String> csp = List.of(
                "frame-ancestors 'self' https://glacierXevents https://trusted.example.com");

        boolean result = IframeEmbedPolicy.isEmbeddable(null, csp, "glacier.events");

        assertThat(result)
                .as("Multi-token frame-ancestors with lookalike but without the legitimate domain must not permit embedding")
                .isFalse();
    }

    /**
     * T-PQ-E: Legitimate exact domain — over-rejection guard (GREEN from the start).
     *
     * <p>After applying Pattern.quote(), the regex must still accept the legitimate
     * exact-match host {@code https://glacier.events} when the configured domain is
     * {@code glacier.events}. This test ensures the fix does not produce an
     * over-rejection side-effect where valid embeds are incorrectly blocked.
     *
     * <p>Arrange: configured domain {@code glacier.events}; CSP directive is
     * {@code "frame-ancestors https://glacier.events"}.
     * Act: Call {@code isEmbeddable} with this CSP and no XFO.
     * Assert: Returns {@code true} — the literal domain must continue to be accepted.
     *
     * <p>This test passes both BEFORE and AFTER the fix and therefore has no RED phase.
     */
    @Test
    void isEmbeddable_returnsTrue_whenFrameAncestorsContainsExactDomain() {
        List<String> csp = List.of("frame-ancestors https://glacier.events");

        boolean result = IframeEmbedPolicy.isEmbeddable(null, csp, "glacier.events");

        assertThat(result)
                .as("Exact legitimate domain must be accepted by the frame-ancestors check")
                .isTrue();
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
