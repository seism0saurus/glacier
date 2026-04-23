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
}
