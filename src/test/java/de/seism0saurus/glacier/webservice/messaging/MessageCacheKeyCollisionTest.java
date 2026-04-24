package de.seism0saurus.glacier.webservice.messaging;

import de.seism0saurus.glacier.share.domain.ShareLinkId;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves that a forged {@code sv_…} {@link WallPrincipal} cannot access or overwrite
 * a legitimate {@link ShareViewerPrincipal}'s bucket in a {@link PrincipalKey}-keyed map.
 *
 * <p>This is the compile-enforced cross-namespace collision prevention required by
 * ADR-SHARE-05 (revised). The prior {@code sv_} string-prefix convention could be
 * bypassed by constructing a {@link WallPrincipal} with an {@code sv_}-prefixed wallId
 * value — the prefix is client-controlled.
 *
 * <p>The typed hierarchy + {@link PrincipalKey} makes this structurally impossible:
 * {@link PrincipalKind#WALL} ≠ {@link PrincipalKind#SHARE_VIEWER} even when the name
 * strings are identical. Any {@link ConcurrentHashMap} keyed by {@link PrincipalKey}
 * therefore maintains strict per-namespace isolation.
 */
class MessageCacheKeyCollisionTest {

    private static final String SHARED_NAME = "sv_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

    @Test
    void forgedWallPrincipal_cannotReadShareViewerBucket() {
        // Simulate a PrincipalKey-keyed cache map (as MessageCacheImpl will use)
        ConcurrentHashMap<PrincipalKey, String> cache = new ConcurrentHashMap<>();

        // Legitimate viewer's bucket is keyed by ShareViewerPrincipal
        ShareLinkId linkId = ShareLinkId.fromUrlPath(SHARED_NAME); // 43 chars — valid
        ShareViewerPrincipal legitimateViewer = new ShareViewerPrincipal(SHARED_NAME, linkId);
        PrincipalKey viewerKey = PrincipalKey.of(legitimateViewer);
        cache.put(viewerKey, "viewer-secret-data");

        // Attacker constructs a WallPrincipal with the same name (forged sv_ wallId)
        WallPrincipal forgedWall = new WallPrincipal(SHARED_NAME);
        PrincipalKey forgedKey = PrincipalKey.of(forgedWall);

        // Forged key MUST NOT retrieve the viewer's bucket entry
        String accessed = cache.get(forgedKey);
        assertThat(accessed).isNull();
    }

    @Test
    void forgedWallPrincipal_cannotOverwriteShareViewerBucket() {
        ConcurrentHashMap<PrincipalKey, String> cache = new ConcurrentHashMap<>();

        ShareLinkId linkId = ShareLinkId.fromUrlPath(SHARED_NAME);
        ShareViewerPrincipal legitimateViewer = new ShareViewerPrincipal(SHARED_NAME, linkId);
        PrincipalKey viewerKey = PrincipalKey.of(legitimateViewer);
        cache.put(viewerKey, "viewer-original-data");

        // Attacker writes to what they think is the viewer's bucket via a forged WallPrincipal
        WallPrincipal forgedWall = new WallPrincipal(SHARED_NAME);
        PrincipalKey forgedKey = PrincipalKey.of(forgedWall);
        cache.put(forgedKey, "attacker-data");

        // Viewer's data must be untouched
        assertThat(cache.get(viewerKey)).isEqualTo("viewer-original-data");
        // Attacker's write went to a different bucket (different kind)
        assertThat(cache.get(forgedKey)).isEqualTo("attacker-data");
        // Two separate entries exist — no collision
        assertThat(cache).hasSize(2);
    }

    @Test
    void forgedShareViewerPrincipal_cannotReadWallBucket() {
        ConcurrentHashMap<PrincipalKey, String> cache = new ConcurrentHashMap<>();

        // Sharer's bucket
        WallPrincipal sharer = new WallPrincipal("my-wall-id");
        PrincipalKey sharerKey = PrincipalKey.of(sharer);
        cache.put(sharerKey, "sharer-subscription-data");

        // Attacker constructs a ShareViewerPrincipal with the same name
        ShareLinkId linkId = ShareLinkId.fromUrlPath("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
        ShareViewerPrincipal forgedViewer = new ShareViewerPrincipal("my-wall-id", linkId);
        PrincipalKey forgedKey = PrincipalKey.of(forgedViewer);

        assertThat(cache.get(forgedKey)).isNull();
    }

    @Test
    void differentKindSameName_producesDifferentHashCode() {
        PrincipalKey wallKey = new PrincipalKey(PrincipalKind.WALL, SHARED_NAME);
        PrincipalKey viewerKey = new PrincipalKey(PrincipalKind.SHARE_VIEWER, SHARED_NAME);
        // Collision would cause performance degradation (DDoS risk) and logical isolation failure
        assertThat(wallKey.hashCode()).isNotEqualTo(viewerKey.hashCode());
    }

    @Test
    void sameKindSameName_producesEqualKeysAndSameHashCode() {
        // Idempotency: two lookups for the same principal produce the same key
        PrincipalKey k1 = new PrincipalKey(PrincipalKind.WALL, "wall-id");
        PrincipalKey k2 = new PrincipalKey(PrincipalKind.WALL, "wall-id");
        assertThat(k1).isEqualTo(k2);
        assertThat(k1.hashCode()).isEqualTo(k2.hashCode());
    }
}
