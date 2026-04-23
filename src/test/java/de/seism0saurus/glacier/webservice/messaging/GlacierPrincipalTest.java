package de.seism0saurus.glacier.webservice.messaging;

import de.seism0saurus.glacier.share.domain.ShareLinkId;
import org.junit.jupiter.api.Test;

import java.security.Principal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the sealed {@link GlacierPrincipal} hierarchy.
 *
 * <p>Security requirement: ADR-SHARE-05 (revised) — typed principals replace the
 * {@code sv_} prefix convention. A type hierarchy is compile-enforced; a string
 * prefix is client-controlled and bypassable.
 *
 * <p>Key invariant: a {@link WallPrincipal} and a {@link ShareViewerPrincipal}
 * with the same {@code getName()} value MUST NOT be equal (cross-namespace
 * collision prevention — the core of the MessageCacheKeyCollisionTest).
 */
class GlacierPrincipalTest {

    // -----------------------------------------------------------------------
    // WallPrincipal
    // -----------------------------------------------------------------------

    @Test
    void wallPrincipal_getNameReturnsWallId() {
        WallPrincipal p = new WallPrincipal("my-wall-id");
        assertThat(p.getName()).isEqualTo("my-wall-id");
    }

    @Test
    void wallPrincipal_implementsPrincipal() {
        assertThat(new WallPrincipal("x")).isInstanceOf(Principal.class);
    }

    @Test
    void wallPrincipal_implementsGlacierPrincipal() {
        assertThat(new WallPrincipal("x")).isInstanceOf(GlacierPrincipal.class);
    }

    @Test
    void wallPrincipal_equalityByWallId() {
        WallPrincipal a = new WallPrincipal("abc");
        WallPrincipal b = new WallPrincipal("abc");
        assertThat(a).isEqualTo(b);
    }

    @Test
    void wallPrincipal_nullWallId_rejected() {
        assertThatThrownBy(() -> new WallPrincipal(null))
                .isInstanceOf(NullPointerException.class);
    }

    // -----------------------------------------------------------------------
    // ShareViewerPrincipal
    // -----------------------------------------------------------------------

    @Test
    void shareViewerPrincipal_getNameReturnsViewerId() {
        ShareLinkId linkId = ShareLinkId.fromUrlPath("sv_" + "A".repeat(40));
        ShareViewerPrincipal p = new ShareViewerPrincipal("sv_viewerid1234", linkId);
        assertThat(p.getName()).isEqualTo("sv_viewerid1234");
    }

    @Test
    void shareViewerPrincipal_implementsPrincipal() {
        ShareLinkId linkId = ShareLinkId.fromUrlPath("sv_" + "A".repeat(40));
        assertThat(new ShareViewerPrincipal("sv_x", linkId)).isInstanceOf(Principal.class);
    }

    @Test
    void shareViewerPrincipal_implementsGlacierPrincipal() {
        ShareLinkId linkId = ShareLinkId.fromUrlPath("sv_" + "A".repeat(40));
        assertThat(new ShareViewerPrincipal("sv_x", linkId)).isInstanceOf(GlacierPrincipal.class);
    }

    @Test
    void shareViewerPrincipal_holdsBoundShareLinkId() {
        ShareLinkId linkId = ShareLinkId.fromUrlPath("sv_" + "B".repeat(40));
        ShareViewerPrincipal p = new ShareViewerPrincipal("sv_viewer", linkId);
        assertThat(p.boundShareLinkId()).isEqualTo(linkId);
    }

    @Test
    void shareViewerPrincipal_nullViewerId_rejected() {
        ShareLinkId linkId = ShareLinkId.fromUrlPath("sv_" + "A".repeat(40));
        assertThatThrownBy(() -> new ShareViewerPrincipal(null, linkId))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shareViewerPrincipal_nullShareLinkId_rejected() {
        assertThatThrownBy(() -> new ShareViewerPrincipal("sv_viewer", null))
                .isInstanceOf(NullPointerException.class);
    }

    // -----------------------------------------------------------------------
    // Cross-namespace collision invariant (ADR-SHARE-05 core requirement)
    // -----------------------------------------------------------------------

    @Test
    void wallPrincipal_doesNotEqualShareViewerPrincipal_withSameName() {
        // CRITICAL: a forged sv_... wallId must NOT compare equal to a ShareViewerPrincipal
        // This is the anti-collision guarantee that replaced the sv_ prefix convention.
        String sharedName = "sv_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
        WallPrincipal wall = new WallPrincipal(sharedName);
        ShareLinkId linkId = ShareLinkId.fromUrlPath(sharedName); // sv_ + 40 chars = 43 total
        ShareViewerPrincipal viewer = new ShareViewerPrincipal(sharedName, linkId);

        // Must NOT be equal — different types = different namespaces
        assertThat(wall).isNotEqualTo(viewer);
        assertThat(viewer).isNotEqualTo(wall);
    }

    // -----------------------------------------------------------------------
    // PrincipalKey keying
    // -----------------------------------------------------------------------

    @Test
    void principalKey_forWallPrincipal_hasKindWall() {
        WallPrincipal wall = new WallPrincipal("my-wall");
        PrincipalKey key = PrincipalKey.of(wall);
        assertThat(key.kind()).isEqualTo(PrincipalKind.WALL);
        assertThat(key.name()).isEqualTo("my-wall");
    }

    @Test
    void principalKey_forShareViewerPrincipal_hasKindShareViewer() {
        ShareLinkId linkId = ShareLinkId.fromUrlPath("sv_" + "C".repeat(40));
        ShareViewerPrincipal viewer = new ShareViewerPrincipal("sv_viewer", linkId);
        PrincipalKey key = PrincipalKey.of(viewer);
        assertThat(key.kind()).isEqualTo(PrincipalKind.SHARE_VIEWER);
        assertThat(key.name()).isEqualTo("sv_viewer");
    }

    @Test
    void principalKey_wallVsShareViewer_sameNameNotEqual() {
        // PrincipalKey collision test: same name, different kind → different map key
        PrincipalKey wallKey = new PrincipalKey(PrincipalKind.WALL, "sv_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
        PrincipalKey viewerKey = new PrincipalKey(PrincipalKind.SHARE_VIEWER, "sv_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");

        assertThat(wallKey).isNotEqualTo(viewerKey);
        assertThat(wallKey.hashCode()).isNotEqualTo(viewerKey.hashCode());
    }

    @Test
    void principalKey_sameKindSameName_equal() {
        PrincipalKey k1 = new PrincipalKey(PrincipalKind.WALL, "wall-id");
        PrincipalKey k2 = new PrincipalKey(PrincipalKind.WALL, "wall-id");
        assertThat(k1).isEqualTo(k2);
        assertThat(k1.hashCode()).isEqualTo(k2.hashCode());
    }
}
