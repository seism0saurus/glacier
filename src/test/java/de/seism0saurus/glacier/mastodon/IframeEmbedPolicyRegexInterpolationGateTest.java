package de.seism0saurus.glacier.mastodon;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

        // Quick sanity check: Pattern.quote( must appear at least once in the source.
        assertThat(source)
                .as("IframeEmbedPolicy must use Pattern.quote() to escape domain before regex composition (SR-PQ-07/CWE-625)")
                .contains("Pattern.quote(");

        // Robust count-match assertion: every occurrence of domain.toUpperCase(Locale.ROOT)
        // must be immediately wrapped by Pattern.quote(). Count-based and CRLF-insensitive —
        // survives whitespace/line-ending changes and inline reformatting (unlike a fragile
        // doesNotContain on a line-terminator-sensitive substring).
        //
        // If an unsafe raw interpolation is introduced, allDomainUpper > wrappedDomainUpper
        // and the assertion fails regardless of how the line is formatted.
        Matcher allDomainUpper = Pattern
                .compile("domain\\.toUpperCase\\(Locale\\.ROOT\\)")
                .matcher(source);
        Matcher wrappedDomainUpper = Pattern
                .compile("Pattern\\.quote\\(domain\\.toUpperCase\\(Locale\\.ROOT\\)\\)")
                .matcher(source);

        long total = allDomainUpper.results().count();
        long wrapped = wrappedDomainUpper.results().count();

        assertThat(wrapped)
                .as("Every domain.toUpperCase(Locale.ROOT) in IframeEmbedPolicy must be wrapped in "
                        + "Pattern.quote() — found %d unwrapped occurrence(s)", total - wrapped)
                .isEqualTo(total);
    }
}
