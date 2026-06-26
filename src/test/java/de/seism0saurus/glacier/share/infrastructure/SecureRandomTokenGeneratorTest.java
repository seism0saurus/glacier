package de.seism0saurus.glacier.share.infrastructure;

import de.seism0saurus.glacier.share.domain.ShareLinkId;
import de.seism0saurus.glacier.share.domain.ShareViewerId;
import de.seism0saurus.glacier.share.domain.SecureRandomTokenGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SecureRandomTokenGenerator}.
 *
 * <p>Verifies:
 * <ul>
 *   <li>Generated {@link ShareLinkId} tokens meet the 43-character URL-safe base64url
 *       length floor (256-bit entropy).</li>
 *   <li>Generated tokens contain only {@code [A-Za-z0-9_-]} characters.</li>
 *   <li>1 000-sample collision test: no two generated IDs collide.</li>
 *   <li>Both {@link ShareLinkId} and {@link ShareViewerId} generation work.</li>
 * </ul>
 */
class SecureRandomTokenGeneratorTest {

    private SecureRandomTokenGenerator generator;

    @BeforeEach
    void setUp() {
        generator = new SecureRandomTokenGenerator();
    }

    // ---------------------------------------------------------------------------
    // Format correctness
    // ---------------------------------------------------------------------------

    @Test
    void generatedShareLinkIdHasAtLeast43Characters() {
        ShareLinkId id = generator.generateShareLinkId();
        assertThat(id.value()).hasSizeGreaterThanOrEqualTo(43);
    }

    /**
     * MUT-KILL L71 (InlineConstant "Substituted 32 with 33"): 32 entropy bytes encode to
     * EXACTLY 43 base64url chars (⌈32×8/6⌉, no padding); 33 bytes would encode to 44.
     *
     * <p>The pre-existing tests only assert {@code >= 43}, so a 44-char token (the 33-byte
     * mutant) would still pass them. Pinning the exact length to 43 kills the mutation.
     */
    @Test
    void generatedShareLinkIdHasExactlyFortyThreeCharacters() {
        ShareLinkId id = generator.generateShareLinkId();
        assertThat(id.value()).hasSize(43);
    }

    /**
     * MUT-KILL L71 (complementary, ShareViewerId path): exactly 43 chars.
     */
    @Test
    void generatedShareViewerIdHasExactlyFortyThreeCharacters() {
        ShareViewerId id = generator.generateShareViewerId();
        assertThat(id.value()).hasSize(43);
    }

    /**
     * MUT-KILL L71 (decoded-byte anchor): with a deterministic all-zero SecureRandom,
     * the generated token decodes back to EXACTLY 32 bytes. The 33-byte mutant would decode
     * to 33 bytes (and produce a 44-char token). Asserting both the decoded byte length (32)
     * and the exact token string for known input bytes double-pins the entropy size.
     */
    @Test
    void generatedTokenDecodesToExactlyThirtyTwoBytes() {
        SecureRandom zeros = new SecureRandom() {
            @Override
            public void nextBytes(byte[] bytes) {
                // leave as all-zero; only the length of the buffer matters for this assertion
            }
        };
        SecureRandomTokenGenerator deterministic = new SecureRandomTokenGenerator(zeros);

        String token = deterministic.generateShareLinkId().value();
        assertThat(token).hasSize(43);

        byte[] decoded = Base64.getUrlDecoder().decode(token);
        assertThat(decoded).hasSize(32);
        // 32 zero bytes -> base64url without padding is 43 'A's
        assertThat(token).isEqualTo("A".repeat(43));
    }

    @Test
    void generatedShareLinkIdContainsOnlyUrlSafeBase64Characters() {
        ShareLinkId id = generator.generateShareLinkId();
        assertThat(id.value()).matches("[A-Za-z0-9_-]+");
    }

    @Test
    void generatedShareViewerIdHasAtLeast43Characters() {
        ShareViewerId id = generator.generateShareViewerId();
        assertThat(id.value()).hasSizeGreaterThanOrEqualTo(43);
    }

    @Test
    void generatedShareViewerIdContainsOnlyUrlSafeBase64Characters() {
        ShareViewerId id = generator.generateShareViewerId();
        assertThat(id.value()).matches("[A-Za-z0-9_-]+");
    }

    // ---------------------------------------------------------------------------
    // Collision test — 1 000 samples, statistically expected 0 collisions
    // ---------------------------------------------------------------------------

    @Test
    void oneThousandShareLinkIdsAreAllDistinct() {
        Set<ShareLinkId> seen = new HashSet<>();
        for (int i = 0; i < 1_000; i++) {
            seen.add(generator.generateShareLinkId());
        }
        assertThat(seen).hasSize(1_000);
    }

    @Test
    void oneThousandShareViewerIdsAreAllDistinct() {
        Set<ShareViewerId> seen = new HashSet<>();
        for (int i = 0; i < 1_000; i++) {
            seen.add(generator.generateShareViewerId());
        }
        assertThat(seen).hasSize(1_000);
    }

    // ---------------------------------------------------------------------------
    // Type safety — IDs of different types are not equal even with same token bytes
    // ---------------------------------------------------------------------------

    @Test
    void shareViewerIdAndShareLinkIdWithSameRawValueAreNotEqual() {
        // Force same raw token by providing a known string directly — test the type separation
        ShareLinkId linkId = ShareLinkId.fromUrlPath("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
        ShareViewerId viewerId = ShareViewerId.fromUrlPath("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
        assertThat((Object) linkId).isNotEqualTo(viewerId);
    }
}
