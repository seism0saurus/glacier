package de.seism0saurus.glacier;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;
import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based fuzz tests for the {@code MASTODON_HANDLE_PATTERN} regex.
 *
 * <p>Verifies that strings containing Unicode control characters, direction-override
 * characters, and injection markers all fail the handle pattern, while normal handles
 * always pass.
 *
 * <p>Uses jqwik {@code @Property} for property-based testing as described in
 * SR-P3A-13 and the test plan.
 *
 * <p>References: SR-P3A-13; ADR-P3A-9; ASVS V5.1.3 (L1).
 */
class MastodonHandlePatternUnicodeFuzzTest {

    /**
     * The MASTODON_HANDLE_PATTERN regex, as it will be defined in {@link MastodonProperties}.
     *
     * <p>Must use {@code \A}/{\code \z} anchors (not {@code ^}/{@code $}) so that
     * multiline strings with embedded CRLF do not match partial lines (SR-P3A-13).
     *
     * <p>Format: {@code @?[a-zA-Z0-9._-]{1,64}@[a-zA-Z0-9.-]{1,253}}
     */
    private static final Pattern HANDLE_PATTERN =
            Pattern.compile("\\A@?[a-zA-Z0-9._-]{1,64}@[a-zA-Z0-9.-]{1,253}\\z");

    // -------------------------------------------------------------------------
    // Positive: normal handles always pass
    // -------------------------------------------------------------------------

    @Test
    void normalHandle_passes() {
        assertThat(HANDLE_PATTERN.matcher("glacier@instance.social").matches())
                .as("normal handle must match MASTODON_HANDLE_PATTERN")
                .isTrue();
    }

    @Test
    void normalHandleWithLeadingAt_passes() {
        assertThat(HANDLE_PATTERN.matcher("@glacier@instance.social").matches())
                .as("handle with leading @ must match MASTODON_HANDLE_PATTERN")
                .isTrue();
    }

    @Test
    void handleWithDots_passes() {
        assertThat(HANDLE_PATTERN.matcher("alice.bob@sub.example.org").matches())
                .as("handle with dots must match MASTODON_HANDLE_PATTERN")
                .isTrue();
    }

    // -------------------------------------------------------------------------
    // Negative: injection characters must fail
    // -------------------------------------------------------------------------

    @Test
    void handleWithCRLF_fails() {
        // The \A/\z anchors prevent matching partial lines, and \r\n are not in [a-zA-Z0-9._-@]
        assertThat(HANDLE_PATTERN.matcher("alice@srv\r\nX-Injected: 1").matches())
                .as("handle with CRLF must fail MASTODON_HANDLE_PATTERN (SR-P3A-13, CWE-117)")
                .isFalse();
    }

    @Test
    void handleWithRtlOverride_fails() {
        assertThat(HANDLE_PATTERN.matcher("alice‮@srv.com").matches())
                .as("handle with U+202E RTL override must fail MASTODON_HANDLE_PATTERN")
                .isFalse();
    }

    @Test
    void handleWithZwsp_fails() {
        assertThat(HANDLE_PATTERN.matcher("alice​@srv.com").matches())
                .as("handle with U+200B ZWSP must fail MASTODON_HANDLE_PATTERN")
                .isFalse();
    }

    @Test
    void handleWithBom_fails() {
        assertThat(HANDLE_PATTERN.matcher("﻿alice@srv.com").matches())
                .as("handle with U+FEFF BOM must fail MASTODON_HANDLE_PATTERN")
                .isFalse();
    }

    @Test
    void handleWithLineSeparator_U2028_fails() {
        assertThat(HANDLE_PATTERN.matcher("alice @srv.com").matches())
                .as("handle with U+2028 line separator must fail MASTODON_HANDLE_PATTERN")
                .isFalse();
    }

    @Test
    void handleWithParagraphSeparator_U2029_fails() {
        assertThat(HANDLE_PATTERN.matcher("alice @srv.com").matches())
                .as("handle with U+2029 paragraph separator must fail MASTODON_HANDLE_PATTERN")
                .isFalse();
    }

    @Test
    void handleWithNullByte_fails() {
        assertThat(HANDLE_PATTERN.matcher("alice\0@srv.com").matches())
                .as("handle with null byte must fail MASTODON_HANDLE_PATTERN")
                .isFalse();
    }

    @Test
    void handleWithTab_fails() {
        assertThat(HANDLE_PATTERN.matcher("alice\t@srv.com").matches())
                .as("handle with tab must fail MASTODON_HANDLE_PATTERN")
                .isFalse();
    }

    @Test
    void handleWithoutAtSeparator_fails() {
        assertThat(HANDLE_PATTERN.matcher("alice").matches())
                .as("handle without @ separator must fail MASTODON_HANDLE_PATTERN")
                .isFalse();
    }

    // -------------------------------------------------------------------------
    // Property-based: all strings with control characters must fail
    // -------------------------------------------------------------------------

    @Property(tries = 500)
    void handleWithControlChar_alwaysFails(@ForAll("controlCharHandles") String input) {
        // Any input containing a control character must fail the pattern
        assertThat(HANDLE_PATTERN.matcher(input).matches())
                .as("handle containing control character must fail MASTODON_HANDLE_PATTERN")
                .isFalse();
    }

    @Provide
    Arbitrary<String> controlCharHandles() {
        // Mix a real handle base with injected control/injection characters
        Arbitrary<String> controlChars = Arbitraries.of(
                "\r", "\n", "\r\n", "\t", "\0",
                "‮",  // RTL override
                "​",  // ZWSP
                "﻿",  // BOM
                " ",  // Line separator
                " "   // Paragraph separator
        );

        // Create strings of the form "alice<control>@srv.com"
        return controlChars.map(ctrl -> "alice" + ctrl + "@srv.com");
    }

    @Property(tries = 200)
    void normalHandle_alwaysPasses(@ForAll("normalHandles") String handle) {
        assertThat(HANDLE_PATTERN.matcher(handle).matches())
                .as("normal handle must always match MASTODON_HANDLE_PATTERN")
                .isTrue();
    }

    @Provide
    Arbitrary<String> normalHandles() {
        // Generate handles of the form "localpart@server"
        // localpart: 1-10 chars from [a-zA-Z0-9._-]
        // server: 3-20 chars from [a-zA-Z0-9.-]
        Arbitrary<String> localPart = Arbitraries
                .strings()
                .withChars('a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j',
                        'k', 'l', 'm', 'n', 'o', 'p', 'q', 'r', 's', 't',
                        'u', 'v', 'w', 'x', 'y', 'z',
                        'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J',
                        '0', '1', '2', '3', '4', '5', '6', '7', '8', '9')
                .ofMinLength(1)
                .ofMaxLength(10);

        Arbitrary<String> serverPart = Arbitraries
                .strings()
                .withChars('a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j',
                        'k', 'l', 'm', 'n', 'o', 'p', 'q', 'r', 's', 't',
                        'u', 'v', 'w', 'x', 'y', 'z', '.')
                .ofMinLength(3)
                .ofMaxLength(20);

        return Arbitraries.frequencyOf(
                Tuple.of(3, localPart.flatMap(lp -> serverPart.map(s -> lp + "@" + s))),
                Tuple.of(1, localPart.flatMap(lp -> serverPart.map(s -> "@" + lp + "@" + s)))
        );
    }
}
