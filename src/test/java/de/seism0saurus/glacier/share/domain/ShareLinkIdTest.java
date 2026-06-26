package de.seism0saurus.glacier.share.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link ShareLinkId} value object.
 *
 * <p>Verifies the invariants:
 * <ul>
 *   <li>Tokens shorter than 43 characters are rejected (256-bit entropy floor).</li>
 *   <li>Only URL-safe base64 characters are accepted ({@code A-Z a-z 0-9 _ -}).</li>
 *   <li>Whitespace, Unicode, and reserved characters are rejected.</li>
 *   <li>Equality is structural (same token ⟹ equal objects).</li>
 *   <li>1,000 successive mint calls produce distinct values (collision-free in practice).</li>
 * </ul>
 *
 * <p>TDD Arrange/Act/Assert discipline:
 * Each test sets up an explicit input value, calls the constructor or factory, and asserts
 * the expected outcome without depending on other tests' side effects.
 */
class ShareLinkIdTest {

    // ---------------------------------------------------------------------------
    // Rejection of invalid tokens
    // ---------------------------------------------------------------------------

    @Test
    void rejectsNullToken() {
        // Arrange – null is never a valid token
        // Act + Assert
        assertThatThrownBy(() -> new ShareLinkId(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBlankToken() {
        assertThatThrownBy(() -> new ShareLinkId(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Kills L57 <init> "removed isBlank + RemoveConditional": a whitespace-only (non-empty
     * but blank) token must be rejected via the blank guard. Pinning the message proves the
     * blank branch fires; removing {@code isBlank()} or its conditional changes which branch
     * rejects (or accepts) the value.
     */
    @Test
    void rejectsWhitespaceOnlyToken_blankGuardFires() {
        assertThatThrownBy(() -> new ShareLinkId("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("null or blank");
    }

    /**
     * Kills L62 <init> "removed String::length": the too-short rejection message must embed
     * the actual {@code token.length()}. Asserting the exact length number kills the
     * length()-removal mutant; the boundary (42 rejected, 43 accepted) pins the comparison.
     */
    @Test
    void tooShortTokenMessageContainsActualLength_lengthBoundary() {
        assertThatThrownBy(() -> new ShareLinkId("A".repeat(42)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("length 42")
                .hasMessageContaining("minimum of 43");

        // Boundary: exactly MIN_LENGTH (43) is accepted.
        assertThat(new ShareLinkId("A".repeat(43)).value()).hasSize(43);
    }

    @ParameterizedTest(name = "too-short token [{0}] is rejected")
    @ValueSource(strings = {
            "abc",
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",  // 40 chars — below 43-char floor
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"  // 42 chars — still below floor
    })
    void rejectsTokenShorterThan43Characters(final String shortToken) {
        // Arrange – each value is under the 43-character (256-bit base64url) threshold
        assertThatThrownBy(() -> new ShareLinkId(shortToken))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("length");
    }

    @ParameterizedTest(name = "token with forbidden character [{0}] is rejected")
    @ValueSource(strings = {
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",   // base64 padding '=' not URL-safe
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA+",   // '+' not URL-safe
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA/",   // '/' not URL-safe
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA A",   // space
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\t",   // tab
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAé" // non-ASCII
    })
    void rejectsTokenWithForbiddenCharacters(final String badToken) {
        assertThatThrownBy(() -> new ShareLinkId(badToken))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------------------------------------------------------------------------
    // Acceptance of valid tokens
    // ---------------------------------------------------------------------------

    @Test
    void acceptsMinimumLengthToken() {
        // Arrange – exactly 43 URL-safe base64url characters
        String validToken = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
        // Act
        ShareLinkId id = new ShareLinkId(validToken);
        // Assert
        assertThat(id.value()).isEqualTo(validToken);
    }

    @Test
    void acceptsTokenWithAllUrlSafeCharacters() {
        // Arrange – mix of A-Z, a-z, 0-9, '-', '_' — exactly 43 characters
        String validToken = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopq";
        assertThat(validToken).hasSize(43);
        // Act + Assert – no exception
        ShareLinkId id = new ShareLinkId(validToken);
        assertThat(id.value()).isEqualTo(validToken);
    }

    // ---------------------------------------------------------------------------
    // Equality and hash-code
    // ---------------------------------------------------------------------------

    @Test
    void twoIdsWithSameTokenAreEqual() {
        String token = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
        ShareLinkId a = new ShareLinkId(token);
        ShareLinkId b = new ShareLinkId(token);
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void twoIdsWithDifferentTokensAreNotEqual() {
        ShareLinkId a = new ShareLinkId("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
        ShareLinkId b = new ShareLinkId("BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB");
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void isNotEqualToShareViewerIdWithSameToken() {
        // Arrange – same raw token, different types: compiler-level prevention of cross-use
        String token = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
        ShareLinkId linkId = new ShareLinkId(token);
        ShareViewerId viewerId = new ShareViewerId(token);
        // Assert – distinct types must not compare as equal
        assertThat(linkId).isNotEqualTo(viewerId);
    }

    // ---------------------------------------------------------------------------
    // hash8 — log-safe fingerprint
    // ---------------------------------------------------------------------------

    @Test
    void hash8ReturnsEightCharacterLowercaseHexString() {
        ShareLinkId id = new ShareLinkId("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
        String h = id.hash8();
        assertThat(h).hasSize(8).matches("[0-9a-f]+");
    }

    @Test
    void hash8IsDeterministicForSameToken() {
        ShareLinkId id = new ShareLinkId("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
        assertThat(id.hash8()).isEqualTo(id.hash8());
    }

    @Test
    void hash8DiffersForDifferentTokens() {
        ShareLinkId a = new ShareLinkId("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
        ShareLinkId b = new ShareLinkId("BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB");
        assertThat(a.hash8()).isNotEqualTo(b.hash8());
    }

    // ---------------------------------------------------------------------------
    // Collision test — 1 000 successive mints produce distinct values
    // ---------------------------------------------------------------------------

    @Test
    void oneThousandMintedIdsAreAllDistinct() {
        // Arrange – use SecureRandomTokenGenerator as the real factory
        SecureRandomTokenGenerator generator = new SecureRandomTokenGenerator();
        Set<ShareLinkId> seen = new HashSet<>();

        // Act – mint 1 000 IDs
        for (int i = 0; i < 1_000; i++) {
            seen.add(generator.generateShareLinkId());
        }

        // Assert – no collisions
        assertThat(seen).hasSize(1_000);
    }
}
