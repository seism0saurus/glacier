package de.seism0saurus.glacier.webservice.messaging;

import de.seism0saurus.glacier.share.domain.ShareLinkId;
import de.seism0saurus.glacier.webservice.cache.FallbackRateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for principal-type isolation in {@link FallbackRateLimiter}.
 *
 * <p>The critical invariant (ADR-SHARE-05 revised): a {@link WallPrincipal} and a
 * {@link ShareViewerPrincipal} that share the <em>same {@code getName()} value</em> must
 * use <em>distinct rate-limit buckets</em>. Without this isolation, an attacker could
 * exhaust a share-viewer's quota by issuing wall requests from a {@link WallPrincipal}
 * whose wallId matches the viewer's {@code sv_}-prefixed cookie value — or vice versa.
 *
 * <p>The implementation guard is {@link PrincipalKey}: the map key combines
 * {@link PrincipalKind} (WALL vs SHARE_VIEWER) with the principal name, so two principals
 * with the same name but different kinds hash to different map entries.
 *
 * <h2>Tests</h2>
 * <ol>
 *   <li>Bucket exhaustion on {@link WallPrincipal} does NOT exhaust the corresponding
 *       {@link ShareViewerPrincipal} bucket — the viewer can still make requests.</li>
 *   <li>Bucket exhaustion on {@link ShareViewerPrincipal} does NOT exhaust the
 *       {@link WallPrincipal} bucket — the wall owner is unaffected.</li>
 *   <li>No {@link ClassCastException} is thrown when both principal types coexist in the
 *       same {@link FallbackRateLimiter} instance.</li>
 *   <li>Exhausting BOTH buckets independently produces the expected rejection for each.</li>
 * </ol>
 *
 * <p>These tests are structured as Spring-context ITs ({@code *IT.java}) because
 * {@link FallbackRateLimiter} is a Spring-managed {@code @Component} wired by
 * {@code @Value} properties. They do not require a running WebSocket server.
 *
 * <p>Security: ADR-SHARE-05 (revised), OWASP API4 (Lack of Resources & Rate Limiting),
 * NIST SP 800-53 AC-3 (access control — cross-namespace bucket isolation).
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@TestPropertySource(properties = {
        "glacier.domain=glacier.example.com",
        "glacier.cookie.secure=false",
        "glacier.fallback.enabled=true",
        "mastodon.instance=mastodon.social",
        "mastodon.accessToken=dummy",
        "mastodon.handle=glacier@mastodon.social",
        "glacier.operatorName=Test",
        "glacier.operatorStreetAndNumber=Test 1",
        "glacier.operatorZipcode=12345",
        "glacier.operatorCity=Test",
        "glacier.operatorCountry=Test",
        "glacier.operatorPhone=+1",
        "glacier.operatorMail=test@test.com",
        "glacier.operatorWebsite=test.com",
        "glacier.share.imgproxy.hmacSecret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
        // Per-principal limit of 100; IP limit is very high so it does not interfere
        "glacier.fallback.ratelimit.perMinute=100",
        "glacier.fallback.ratelimit.perMinutePerIp=100000"
})
class PrincipalHandlerIT {

    @Autowired
    private FallbackRateLimiter rateLimiter;

    /**
     * A name that starts with "sv_" — the legacy naming that caused the namespace collision
     * before ADR-SHARE-05 was revised.  Both principal types intentionally share this name
     * to prove the type-discriminant prevents bucket collision.
     */
    private static final String SHARED_NAME = "sv_abc";

    /** Stable ShareLinkId fixture for binding ShareViewerPrincipal objects in these tests. */
    private static final ShareLinkId SHARE_LINK_ID =
            ShareLinkId.fromUrlPath("AbCdEfGhIjKlMnOpQrStUvWxYzAbCdEfGhIjKlMnOpq");

    /** IP constant used throughout — high IP limit ensures IP axis never fires. */
    private static final String REMOTE_IP = "127.0.0.1";

    // -----------------------------------------------------------------------
    // Test 1: WallPrincipal exhaustion does NOT affect ShareViewerPrincipal bucket
    // -----------------------------------------------------------------------

    /**
     * Exhausting the {@link WallPrincipal("sv_abc")} bucket by issuing 100 requests must
     * NOT exhaust the {@link ShareViewerPrincipal("sv_abc", ...)} bucket — confirming that
     * the two principals use distinct rate-limit entries despite sharing the same name.
     *
     * <p>Arrange: create WallPrincipal and ShareViewerPrincipal with the same name.
     * <br>Act: consume all 100 tokens of the WallPrincipal bucket.
     * <br>Assert: the ShareViewerPrincipal can still be admitted (bucket not exhausted).
     */
    @Test
    void wallPrincipalBucketExhaustion_doesNotExhaustShareViewerPrincipalBucket() {
        WallPrincipal wallPrincipal = new WallPrincipal(SHARED_NAME);
        ShareViewerPrincipal shareViewerPrincipal =
                new ShareViewerPrincipal(SHARED_NAME, SHARE_LINK_ID);

        PrincipalKey wallKey = PrincipalKey.of(wallPrincipal);
        PrincipalKey viewerKey = PrincipalKey.of(shareViewerPrincipal);

        // Exhaust the WallPrincipal bucket (100 requests, limit = 100 per minute)
        for (int i = 0; i < 100; i++) {
            rateLimiter.check(wallKey, REMOTE_IP);
        }

        // The 101st WallPrincipal request must be rejected (bucket is exhausted)
        FallbackRateLimiter.RateLimitResult wallResult = rateLimiter.check(wallKey, REMOTE_IP);
        assertThat(wallResult.permitted())
                .as("WallPrincipal bucket must be exhausted after 100 requests")
                .isFalse();

        // The ShareViewerPrincipal bucket must still have tokens — it was never touched
        FallbackRateLimiter.RateLimitResult viewerResult = rateLimiter.check(viewerKey, REMOTE_IP);
        assertThat(viewerResult.permitted())
                .as("ShareViewerPrincipal bucket must NOT be exhausted by WallPrincipal requests "
                        + "(ADR-SHARE-05 revised: distinct buckets despite same getName() value)")
                .isTrue();
    }

    // -----------------------------------------------------------------------
    // Test 2: ShareViewerPrincipal exhaustion does NOT affect WallPrincipal bucket
    // -----------------------------------------------------------------------

    /**
     * Exhausting the {@link ShareViewerPrincipal("sv_abc", ...)} bucket must NOT exhaust
     * the {@link WallPrincipal("sv_abc")} bucket.
     *
     * <p>Arrange: create ShareViewerPrincipal and WallPrincipal with the same name.
     * <br>Act: consume all 100 tokens of the ShareViewerPrincipal bucket.
     * <br>Assert: the WallPrincipal can still be admitted.
     */
    @Test
    void shareViewerPrincipalBucketExhaustion_doesNotExhaustWallPrincipalBucket() {
        WallPrincipal wallPrincipal = new WallPrincipal(SHARED_NAME);
        ShareViewerPrincipal shareViewerPrincipal =
                new ShareViewerPrincipal(SHARED_NAME, SHARE_LINK_ID);

        PrincipalKey wallKey = PrincipalKey.of(wallPrincipal);
        PrincipalKey viewerKey = PrincipalKey.of(shareViewerPrincipal);

        // Exhaust the ShareViewerPrincipal bucket
        for (int i = 0; i < 100; i++) {
            rateLimiter.check(viewerKey, REMOTE_IP);
        }

        // The 101st ShareViewerPrincipal request must be rejected
        FallbackRateLimiter.RateLimitResult viewerResult = rateLimiter.check(viewerKey, REMOTE_IP);
        assertThat(viewerResult.permitted())
                .as("ShareViewerPrincipal bucket must be exhausted after 100 requests")
                .isFalse();

        // The WallPrincipal bucket must still have tokens — it was never touched
        FallbackRateLimiter.RateLimitResult wallResult = rateLimiter.check(wallKey, REMOTE_IP);
        assertThat(wallResult.permitted())
                .as("WallPrincipal bucket must NOT be exhausted by ShareViewerPrincipal requests "
                        + "(ADR-SHARE-05 revised: type-discriminant prevents cross-namespace collision)")
                .isTrue();
    }

    // -----------------------------------------------------------------------
    // Test 3: No ClassCastException when both types coexist
    // -----------------------------------------------------------------------

    /**
     * Verifies that interleaving {@link WallPrincipal} and {@link ShareViewerPrincipal} checks
     * with the same name does not throw a {@link ClassCastException} or any other runtime error.
     *
     * <p>This catches regressions where a raw-string map is used instead of a typed key —
     * inserting a {@link WallPrincipal} under name "sv_abc" and then retrieving it as a
     * {@link ShareViewerPrincipal} would cause a {@code ClassCastException} at runtime.
     */
    @Test
    void wallPrincipalAndShareViewerPrincipal_withSameName_coexistWithoutClassCastException() {
        WallPrincipal wallPrincipal = new WallPrincipal(SHARED_NAME);
        ShareViewerPrincipal shareViewerPrincipal =
                new ShareViewerPrincipal(SHARED_NAME, SHARE_LINK_ID);

        PrincipalKey wallKey = PrincipalKey.of(wallPrincipal);
        PrincipalKey viewerKey = PrincipalKey.of(shareViewerPrincipal);

        // Interleave requests from both types — must not throw ClassCastException
        for (int i = 0; i < 10; i++) {
            FallbackRateLimiter.RateLimitResult wall = rateLimiter.check(wallKey, REMOTE_IP);
            FallbackRateLimiter.RateLimitResult viewer = rateLimiter.check(viewerKey, REMOTE_IP);

            // Both must be permitted (far below the 100/min limit)
            assertThat(wall.permitted())
                    .as("WallPrincipal check %d must be permitted", i + 1)
                    .isTrue();
            assertThat(viewer.permitted())
                    .as("ShareViewerPrincipal check %d must be permitted", i + 1)
                    .isTrue();
        }
    }

    // -----------------------------------------------------------------------
    // Test 4: PrincipalKey discriminates WALL from SHARE_VIEWER
    // -----------------------------------------------------------------------

    /**
     * Verifies at the {@link PrincipalKey} level that two principals of different types
     * with the same name produce keys that are not equal — confirming the type-discriminant
     * is compile-enforced, not merely runtime-checked.
     *
     * <p>This is a pure unit assertion embedded in the IT to confirm the key contract
     * that the {@link FallbackRateLimiter} relies on.
     */
    @Test
    void principalKey_wallAndShareViewer_withSameName_areNotEqual() {
        WallPrincipal wallPrincipal = new WallPrincipal(SHARED_NAME);
        ShareViewerPrincipal shareViewerPrincipal =
                new ShareViewerPrincipal(SHARED_NAME, SHARE_LINK_ID);

        PrincipalKey wallKey = PrincipalKey.of(wallPrincipal);
        PrincipalKey viewerKey = PrincipalKey.of(shareViewerPrincipal);

        assertThat(wallKey)
                .as("WallPrincipal key and ShareViewerPrincipal key must be distinct "
                        + "even when getName() values are equal (ADR-SHARE-05 revised)")
                .isNotEqualTo(viewerKey);

        assertThat(wallKey.kind()).isEqualTo(PrincipalKind.WALL);
        assertThat(viewerKey.kind()).isEqualTo(PrincipalKind.SHARE_VIEWER);
        assertThat(wallKey.name()).isEqualTo(viewerKey.name()); // same name, different kind
    }
}
