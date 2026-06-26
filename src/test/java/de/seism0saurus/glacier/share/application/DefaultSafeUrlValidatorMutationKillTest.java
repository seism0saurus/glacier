package de.seism0saurus.glacier.share.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.URI;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Mutation-kill tests for {@link DefaultSafeUrlValidator}.
 *
 * <p>These tests are designed to pin the exact observable behaviour of the SSRF guard so that
 * PITest mutants in the following hotspots are killed (each test documents which survivor it
 * targets):
 * <ul>
 *   <li>{@code containsBidiOrControlChars} lambda — exact char-range boundaries
 *       (9/10/13 exemptions, {@code < 0x20} control boundary, {@code == 0x7F} DEL,
 *       bidi-override set membership).</li>
 *   <li>{@code obfuscateHost} — the {@code length() <= 4} boundary, the {@code substring(0, 4)}
 *       indices, and the {@code "***"} sentinel return.</li>
 *   <li>{@code resolveAndPin} — public-accept vs private-reject + null/blank guards.</li>
 *   <li>The constructor's {@code trim().toLowerCase(ROOT)} normalisation of the configured
 *       dev instance host.</li>
 *   <li>{@code validate()} {@code uri.normalize()} behaviour.</li>
 * </ul>
 *
 * <p>The two private static helpers are exercised directly via reflection (an established
 * pattern in this test suite, e.g. {@code WebSocketConfigurationTest}) because routing every
 * boundary through {@code validate()} is impossible: {@code URI.create(..)} rejects raw ASCII
 * control characters before the bidi check can observe them, which would mask the lambda
 * mutants behind a fail-secure parse error.
 *
 * <p>No real DNS is required: literal IP strings resolve to themselves and {@code localhost}
 * is guaranteed to resolve to a loopback address (same approach as the existing
 * {@code DefaultSafeUrlValidatorCorpusTest} / {@code DefaultSafeUrlValidatorDevExemptionTest}).
 *
 * <p>Security requirement: SR-SHARE-09 (safe-URL gating before proxy fetch / anchor emission).
 */
class DefaultSafeUrlValidatorMutationKillTest {

    private final DefaultSafeUrlValidator validator = new DefaultSafeUrlValidator();

    // =======================================================================
    // Reflection helpers for the two private static methods
    // =======================================================================

    private static boolean containsBidiOrControlChars(final String value) throws Exception {
        Method m = DefaultSafeUrlValidator.class
                .getDeclaredMethod("containsBidiOrControlChars", String.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, value);
    }

    private static String obfuscateHost(final String host) throws Exception {
        Method m = DefaultSafeUrlValidator.class
                .getDeclaredMethod("obfuscateHost", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, host);
    }

    private static String withCp(final String prefix, final int cp, final String suffix) {
        return prefix + new String(Character.toChars(cp)) + suffix;
    }

    // =======================================================================
    // containsBidiOrControlChars — char-range boundaries
    // =======================================================================

    /**
     * Codepoints that MUST be flagged as control/bidi.
     * <ul>
     *   <li>0x00 NUL, 0x01, 0x1F US — kills RemoveConditional on the {@code cp < 0x20} clause
     *       and InlineConstant {@code 0x20 -> 33 / boundary} (0x1F stays below either).</li>
     *   <li>0x7F DEL — kills NegateConditionals / InlineConstant {@code 127->128 / -128}
     *       and RemoveConditional on the {@code cp == 0x7F} clause.</li>
     *   <li>0x202E RLO, 0x2066, 0x2069, 0x200B ZWSP — kills RemoveConditional on the
     *       {@code BIDI_OVERRIDE_CHARS.contains(cp)} clause.</li>
     * </ul>
     */
    @ParameterizedTest(name = "flagged control/bidi cp=0x{0}")
    @ValueSource(ints = {0x00, 0x01, 0x1F, 0x7F, 0x202E, 0x2066, 0x2069, 0x200B})
    void flagsControlAndBidiCodepoints(int cp) throws Exception {
        assertThat(containsBidiOrControlChars(withCp("https://example.com/", cp, "path")))
                .as("codepoint 0x%s must be flagged as control/bidi", Integer.toHexString(cp))
                .isTrue();
    }

    /**
     * Codepoints that MUST NOT be flagged.
     * <ul>
     *   <li>0x09 TAB, 0x0A LF, 0x0D CR — exempted whitespace; kills InlineConstant
     *       {@code 9->10}, {@code 10->11} and ConditionalsBoundary on the {@code != 0x09/0x0A/0x0D}
     *       clauses (flipping any of those would flag the corresponding char).</li>
     *   <li>0x20 SPACE — the lower boundary; kills ConditionalsBoundary {@code < -> <=} and
     *       InlineConstant {@code 0x20 -> 33} on {@code cp < 0x20} (either would flag space).</li>
     *   <li>0x21 '!', 0x41 'A' — normal printable; sanity.</li>
     *   <li>0x7E '~' — the codepoint just below DEL; kills NegateConditionals on {@code cp == 0x7F}
     *       (a negate would flag 0x7E instead of 0x7F).</li>
     * </ul>
     */
    @ParameterizedTest(name = "not flagged cp=0x{0}")
    @ValueSource(ints = {0x09, 0x0A, 0x0D, 0x20, 0x21, 0x41, 0x7E})
    void doesNotFlagExemptOrPrintableCodepoints(int cp) throws Exception {
        assertThat(containsBidiOrControlChars(withCp("https://example.com/", cp, "path")))
                .as("codepoint 0x%s must NOT be flagged", Integer.toHexString(cp))
                .isFalse();
    }

    @Test
    void plainAsciiUrlIsNotFlagged() throws Exception {
        assertThat(containsBidiOrControlChars("https://mastodon.social/@user/123"))
                .as("a plain ASCII URL must not be flagged")
                .isFalse();
    }

    // =======================================================================
    // obfuscateHost — length boundary + substring indices + sentinel
    // =======================================================================

    /**
     * Hosts of length &le; 4 (and null) MUST be fully masked to {@code "***"}.
     * <ul>
     *   <li>null — the {@code host == null} guard.</li>
     *   <li>"abcd" (length 4) — kills ConditionalsBoundary {@code <= -> <} on
     *       {@code length() <= 4} (a {@code <} would let length-4 fall through to substring).</li>
     *   <li>"abc" (length 3) — sanity below the boundary.</li>
     * </ul>
     * Each also kills EmptyObjectReturnVals (the mutant would return {@code ""}, not {@code "***"}).
     */
    @Test
    void obfuscateHost_shortHostsAreFullyMasked() throws Exception {
        assertThat(obfuscateHost(null)).isEqualTo("***");
        assertThat(obfuscateHost("abc")).isEqualTo("***");
        assertThat(obfuscateHost("abcd")).isEqualTo("***");
    }

    /**
     * Hosts of length &gt; 4 MUST be masked to {@code <first 4 chars> + "..."}.
     * <ul>
     *   <li>"abcde" (length 5) — kills InlineConstant {@code 4 -> 5} on {@code length() <= 4}
     *       (a 5 would mask the whole 5-char host to {@code "***"}). Expected {@code "abcd..."}.</li>
     *   <li>"abcdef" — kills InlineConstant {@code 0 -> 1} on {@code substring(0, 4)}
     *       (would yield {@code "bcd..."}), InlineConstant {@code 4 -> 5} on the same
     *       (would yield {@code "abcde..."}), the removed/NakedReceiver {@code substring}
     *       (would yield {@code "abcdef..."}), and EmptyObjectReturnVals (would yield {@code ""}).</li>
     *   <li>"example.com" — a realistic host; expected exactly {@code "exam..."}.</li>
     * </ul>
     */
    @Test
    void obfuscateHost_longHostsKeepFirstFourCharsPlusEllipsis() throws Exception {
        assertThat(obfuscateHost("abcde")).isEqualTo("abcd...");
        assertThat(obfuscateHost("abcdef")).isEqualTo("abcd...");
        assertThat(obfuscateHost("example.com")).isEqualTo("exam...");
    }

    // =======================================================================
    // resolveAndPin — public accept / private reject / guards
    // =======================================================================

    /**
     * A public IP literal MUST be returned unchanged (not blocked). Kills NegateConditionals on
     * {@code isBlockedAddress(addr)} inside the loop (a negate would throw for a public address)
     * and RemoveConditional on that guard's else-path. 93.184.216.34 is a public, globally
     * routable literal (does not match any RFC1918 / loopback / link-local / CGNAT block).
     */
    @Test
    void resolveAndPin_publicIpLiteral_returnsAddress() throws Exception {
        InetAddress result = DefaultSafeUrlValidator.resolveAndPin("93.184.216.34");

        assertThat(result)
                .as("a public IP literal must be pinned, not blocked")
                .isNotNull();
        assertThat(result.getHostAddress()).isEqualTo("93.184.216.34");
    }

    /**
     * A private (RFC1918) IP literal MUST be rejected. Kills NegateConditionals / RemoveConditional
     * on the {@code isBlockedAddress(addr)} loop guard (a negate/removal would let 10/8 through).
     */
    @Test
    void resolveAndPin_privateIpLiteral_throws() {
        assertThatThrownBy(() -> DefaultSafeUrlValidator.resolveAndPin("10.1.2.3"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * A {@code null} host MUST be rejected with IllegalArgumentException. Kills NegateConditionals /
     * RemoveConditional on the {@code host == null} half of the leading guard and the removed
     * {@code isBlank} call (the existing CorpusTest only covers the blank-string branch).
     */
    @Test
    void resolveAndPin_nullHost_throws() {
        assertThatThrownBy(() -> DefaultSafeUrlValidator.resolveAndPin(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * A blank (whitespace-only) host MUST be rejected. Kills the removed {@code isBlank()} call:
     * with {@code isBlank} removed only the {@code == null} check remains, so a whitespace host
     * would fall through to {@code InetAddress.getAllByName("   ")}. The leading guard must reject
     * it with IllegalArgumentException before any resolution attempt.
     */
    @Test
    void resolveAndPin_whitespaceHost_throwsIllegalArgument() {
        assertThatThrownBy(() -> DefaultSafeUrlValidator.resolveAndPin("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");
    }

    // =======================================================================
    // Constructor — trim().toLowerCase(ROOT) normalisation of dev instance host
    // =======================================================================

    /**
     * Configures the dev instance host as {@code "  LOCALHOST  "} (uppercase + surrounding
     * whitespace) and asserts the exemption still matches the lower-case, untrimmed runtime host
     * {@code localhost} (which resolves to loopback 127.0.0.1 and is otherwise blocked).
     *
     * <p>Kills:
     * <ul>
     *   <li>NakedReceiver on {@code mastodonInstance.trim()} (without trim,
     *       {@code "  localhost  " != "localhost"} → no exemption → blocked → empty).</li>
     *   <li>NakedReceiver on {@code .toLowerCase(Locale.ROOT)} (without it,
     *       {@code "LOCALHOST" != "localhost"} → no exemption → blocked → empty).</li>
     * </ul>
     */
    @Test
    void constructor_normalisesConfiguredInstanceHost_caseAndWhitespace() {
        DefaultSafeUrlValidator devValidator =
                new DefaultSafeUrlValidator(true, "  LOCALHOST  ");

        Optional<URI> result = devValidator.validate("http://localhost/@user/1/embed");

        assertThat(result)
                .as("dev exemption must match after trim()+toLowerCase(ROOT) normalisation")
                .isPresent();
    }

    /**
     * Confirms the normalisation actually narrows the match: the same configured host must NOT
     * exempt a different private host. Backstops the constructor test above so a mutant that
     * makes {@code devInstanceHost} match everything (or empty) is also caught.
     */
    @Test
    void constructor_normalisedHost_doesNotExemptOtherPrivateHost() {
        DefaultSafeUrlValidator devValidator =
                new DefaultSafeUrlValidator(true, "  LOCALHOST  ");

        Optional<URI> result = devValidator.validate("http://10.9.9.9/x/embed");

        assertThat(result)
                .as("only the configured host is exempt")
                .isEmpty();
    }

    /**
     * A {@code null} configured instance host must coerce to {@code ""} (the {@code ? "" :}
     * ternary branch at L95-97) and exempt nothing, so a private host stays blocked even in dev
     * mode. Kills the InlineConstant / ArgumentPropagation mutants that would replace the empty
     * string and the NegateConditionals on the null-check.
     */
    @Test
    void constructor_nullInstanceHost_exemptsNothing() {
        DefaultSafeUrlValidator devValidator = new DefaultSafeUrlValidator(true, null);

        Optional<URI> result = devValidator.validate("http://localhost/@user/1/embed");

        assertThat(result)
                .as("null configured host coerces to empty → nothing exempt → loopback blocked")
                .isEmpty();
    }

    // =======================================================================
    // validate() — uri.normalize() must collapse dot segments
    // =======================================================================

    /**
     * Drives a URL with a {@code /../} dot-segment through {@code validate()} and asserts the
     * returned URI is the normalised form ({@code /b}, not {@code /a/../b}). Kills the NakedReceiver
     * / removed-call mutant on {@code uri.normalize()} at L169 (which would return the un-normalised
     * URI and leave the {@code ..} segment in the path).
     */
    @Test
    void validate_returnsNormalisedPath_collapsingDotSegments() {
        Optional<URI> result = validator.validate("https://mastodon.social/a/../b");

        assertThat(result).isPresent();
        assertThat(result.get().getPath())
                .as("validate() must return the normalised path")
                .isEqualTo("/b");
        assertThat(result.get().toString())
                .as("normalised URI must not contain dot segments")
                .doesNotContain("..");
    }

    // =======================================================================
    // validate() — blank / non-empty host guards (defence backstops)
    // =======================================================================

    /**
     * A whitespace-only raw input must be rejected by the {@code raw.isBlank()} guard at L102.
     * Kills the removed {@code isBlank()} mutant (with it gone, a non-null whitespace string would
     * proceed to parsing). Distinct from the existing CorpusTest empty-string case.
     */
    @Test
    void validate_whitespaceRaw_isRejected() {
        assertThat(validator.validate("    ")).isEmpty();
    }
}
