package de.seism0saurus.glacier.share.infrastructure;

import de.seism0saurus.glacier.share.domain.ShareLinkId;
import de.seism0saurus.glacier.share.domain.ShareViewerId;
import de.seism0saurus.glacier.share.domain.SecureRandomTokenGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
