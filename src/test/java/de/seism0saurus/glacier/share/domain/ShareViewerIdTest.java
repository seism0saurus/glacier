package de.seism0saurus.glacier.share.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link ShareViewerId} value object.
 *
 * <p>Mirrors {@link ShareLinkIdTest} but for the viewer-side principal.  The two types share
 * the same validation rules yet are distinct Java types — this test ensures:
 * <ul>
 *   <li>The same structural invariants (length, character set) are enforced.</li>
 *   <li>A {@link ShareViewerId} is not equal to a {@link ShareLinkId} with the same token —
 *       the compiler-level separation is reflected at runtime too.</li>
 * </ul>
 */
class ShareViewerIdTest {

    // ---------------------------------------------------------------------------
    // Rejection of invalid tokens
    // ---------------------------------------------------------------------------

    @Test
    void rejectsNullToken() {
        assertThatThrownBy(() -> new ShareViewerId(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBlankToken() {
        assertThatThrownBy(() -> new ShareViewerId(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "too-short token [{0}] is rejected")
    @ValueSource(strings = {
            "abc",
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",   // 40 chars
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"  // 42 chars
    })
    void rejectsTokenShorterThan43Characters(final String shortToken) {
        assertThatThrownBy(() -> new ShareViewerId(shortToken))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("length");
    }

    @ParameterizedTest(name = "token with forbidden character [{0}] is rejected")
    @ValueSource(strings = {
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA+",
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA/",
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA A",
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\t"
    })
    void rejectsTokenWithForbiddenCharacters(final String badToken) {
        assertThatThrownBy(() -> new ShareViewerId(badToken))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------------------------------------------------------------------------
    // Acceptance of valid tokens
    // ---------------------------------------------------------------------------

    @Test
    void acceptsMinimumLengthToken() {
        String validToken = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
        ShareViewerId id = new ShareViewerId(validToken);
        assertThat(id.value()).isEqualTo(validToken);
    }

    // ---------------------------------------------------------------------------
    // Type separation — same token, different type ⟹ not equal
    // ---------------------------------------------------------------------------

    @Test
    void isNotEqualToShareLinkIdWithSameToken() {
        String token = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
        ShareViewerId viewerId = new ShareViewerId(token);
        ShareLinkId linkId = new ShareLinkId(token);
        assertThat(viewerId).isNotEqualTo(linkId);
    }

    // ---------------------------------------------------------------------------
    // Equality and hash-code
    // ---------------------------------------------------------------------------

    @Test
    void twoViewerIdsWithSameTokenAreEqual() {
        String token = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
        assertThat(new ShareViewerId(token)).isEqualTo(new ShareViewerId(token));
    }

    @Test
    void hash8ReturnsDeterministicEightCharHex() {
        ShareViewerId id = new ShareViewerId("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
        assertThat(id.hash8()).hasSize(8).matches("[0-9a-f]+");
        assertThat(id.hash8()).isEqualTo(id.hash8());
    }

    // ---------------------------------------------------------------------------
    // Mutation-kill tests (PITest survivors)
    // ---------------------------------------------------------------------------

    /**
     * Kills L45 <init> "removed isBlank + RemoveConditional": a whitespace-only token
     * (non-empty but blank) must be rejected. If the {@code isBlank()} call or its
     * conditional is removed, this whitespace string proceeds past the blank guard and is
     * then rejected only by length/charset checks with a different message — or accepted if
     * long enough. A pure-whitespace 43-char string would slip past the blank guard once it
     * is removed (it satisfies length) and fail at the charset check instead; pinning the
     * message proves the blank branch is the one that fires.
     */
    @Test
    void rejectsWhitespaceOnlyToken_blankGuardFires() {
        String whitespace = "   ";
        assertThatThrownBy(() -> new ShareViewerId(whitespace))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("null or blank");
    }

    /**
     * Kills L50 <init> "removed String::length": the rejection message for a too-short token
     * must embed the actual {@code token.length()}. Removing the {@code length()} call would
     * change the interpolated number, so asserting the exact length value in the message
     * fails the mutant. Also asserts a too-short token (length 42) is rejected while the
     * minimum-length token (43) is accepted — pinning the length comparison itself.
     */
    @Test
    void tooShortTokenMessageContainsActualLength_lengthCallSurvives() {
        String token42 = "A".repeat(42);
        assertThatThrownBy(() -> new ShareViewerId(token42))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("length 42")
                .hasMessageContaining("minimum of 43");

        // Boundary: exactly MIN_LENGTH (43) is accepted, proving the < comparison bound.
        String token43 = "A".repeat(43);
        assertThat(new ShareViewerId(token43).value()).hasSize(43);
    }

    /**
     * Kills L68 fromUrlPath "ConstructorCall + NullReturnVals": the factory must return a
     * real, non-null {@link ShareViewerId} carrying the supplied token for valid input. A
     * mutant that returns {@code null} (or skips the constructor) fails these assertions.
     */
    @Test
    void fromUrlPathReturnsRealInstanceForValidInput() {
        String token = "A".repeat(43);
        ShareViewerId id = ShareViewerId.fromUrlPath(token);
        assertThat(id).isNotNull();
        assertThat(id.value()).isEqualTo(token);
        assertThat(id).isInstanceOf(ShareViewerId.class);
    }

    /**
     * Kills L95 equals "BooleanTrueReturnVals": two ShareViewerIds with DIFFERENT tokens must
     * NOT be equal. A mutant forcing {@code equals} to return {@code true} fails here.
     */
    @Test
    void twoViewerIdsWithDifferentTokensAreNotEqual() {
        ShareViewerId a = new ShareViewerId("A".repeat(43));
        ShareViewerId b = new ShareViewerId("B".repeat(43));
        assertThat(a).isNotEqualTo(b);
    }
}
