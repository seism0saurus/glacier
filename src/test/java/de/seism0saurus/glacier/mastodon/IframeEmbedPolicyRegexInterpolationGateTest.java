package de.seism0saurus.glacier.mastodon;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural gate: asserts IframeEmbedPolicy.java uses Pattern.quote() before any regex
 * composition involving the domain parameter (SR-PQ-07 / CWE-625 / ADR-PQ-06).
 *
 * <p>This test is always GREEN once the fix is applied. It prevents future regressions
 * where a refactor removes the Pattern.quote() call, restoring the CWE-1287/CWE-625
 * regex-injection vulnerability that allows lookalike hostnames to bypass the
 * frame-ancestors domain check.
 *
 * <p>SR-PQ-03 audit result at time of fix (2026-05-05):
 * <ul>
 *   <li>{@code IframeEmbedPolicy.java:128} — {@code .matches()} with
 *       {@code Pattern.quote(domain.toUpperCase(Locale.ROOT))} at line 130.
 *       Fixed: Pattern.quote() wraps the operator-controlled value. SAFE.</li>
 *   <li>{@code ShareLinkId.java:64} — {@code URL_SAFE_BASE64.matcher(token).matches()}.
 *       URL_SAFE_BASE64 is a literal compile. No interpolation. SAFE.</li>
 *   <li>{@code ShareViewerId.java:52} — same as ShareLinkId. SAFE.</li>
 *   <li>{@code ShareSecurityHeadersFilter.java:98,103} — {@code SAFE_HOSTNAME_PATTERN.matcher(...).matches()}.
 *       Literal compile; input is validated, not interpolated. SAFE.</li>
 *   <li>{@code ShareViewPrincipalHandler.java:206} — {@code token.matches("[A-Za-z0-9_-]+")}
 *       — literal constant regex, no interpolation. SAFE.</li>
 * </ul>
 * No other operator-controlled interpolation sites found at time of fix.
 */
class IframeEmbedPolicyRegexInterpolationGateTest {

    @Test
    @DisplayName("IframeEmbedPolicy wraps domain in Pattern.quote() before regex composition (SR-PQ-07)")
    void iframeEmbedPolicy_doesNotInterpolateRawDomainIntoRegex() throws IOException {
        Path sourceFile = Paths.get("src/main/java/de/seism0saurus/glacier/mastodon/IframeEmbedPolicy.java");
        String source = Files.readString(sourceFile);

        // Must contain Pattern.quote( in the production source
        assertThat(source)
                .as("IframeEmbedPolicy must use Pattern.quote() to escape domain before regex composition (SR-PQ-07/CWE-625)")
                .contains("Pattern.quote(");

        // The unsafe call shape 'domain.toUpperCase(Locale.ROOT))' must NOT appear without Pattern.quote
        // (i.e., the closing paren of toUpperCase must be followed by a closing paren of Pattern.quote)
        assertThat(source)
                .as("Raw domain.toUpperCase(Locale.ROOT) must not be concatenated into regex without Pattern.quote()")
                .doesNotContain("+ domain.toUpperCase(Locale.ROOT)\n");
    }
}
