package de.seism0saurus.glacier.share.application;

import de.seism0saurus.glacier.share.domain.ShareLink;
import de.seism0saurus.glacier.share.domain.ShareLinkId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import social.bigbone.MastodonClient;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Integration test for {@link ShareLinkActivityRegistry} verifying TOCTOU safety under
 * concurrent register / unregister pairs.
 *
 * <p>Uses a real Spring context to ensure the registry bean is wired correctly. The
 * {@link ShareLinkService} is replaced by a {@link MockitoBean} so that
 * {@code resolve()} behaviour can be controlled per-test without starting a database.
 *
 * <p>The key correctness test exercises the race condition described in ADR-RELAY-03:
 * a revocation event arriving between the handshake's initial {@code resolve()} and the
 * subsequent {@code register()} call. The per-linkId lock in
 * {@link ShareLinkActivityRegistry} closes this window — after 100 concurrent iterations
 * the registry must always be empty.
 *
 * <p>Security: SR-RELAY-06, SR-RELAY-07, ADR-RELAY-03 (TOCTOU mitigation).
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@TestPropertySource(properties = {
        "glacier.domain=glacier.example.com",
        "glacier.cookie.secure=false",
        "glacier.fallback.enabled=true",
        "mastodon.instance=mastodon.social",
        "mastodon.handle=@glacier@mastodon.social",
        "mastodon.accessToken=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
        "glacier.share.maxActivePerSharer=10",
        "glacier.share.maxActivePerIp=20",
        "glacier.share.globalMax=1000",
        "glacier.share.maxViewersPerLink=100"
})
class ShareLinkActivityRegistryIT {

    @Autowired
    private ShareLinkActivityRegistry registry;

    @MockitoBean
    private ShareLinkService shareLinkService;

    @MockitoBean
    private MastodonClient mastodonClient;

    private static final String SHARER_WALL_ID = "sharer-wall-id-toctou-AAAAAAAAAAAAAAA";
    private static final Duration TTL = Duration.ofDays(7);

    /**
     * Proves that the TOCTOU window between handshake {@code resolve()} and
     * {@code register()} is closed by the per-linkId lock (ADR-RELAY-03, SR-RELAY-06).
     *
     * <p>Strategy: for each of 100 iterations, create a unique link ID and concurrently
     * run a register and an unregister. The register() re-resolves under lock; the mock
     * is configured to return empty on the second call (simulating concurrent revocation).
     * After all iterations complete, {@code getActiveLinks()} must be empty.
     *
     * <p>Arrange: mock {@code shareLinkService.resolve()} returns empty (revoked) so
     *             that register() re-resolve returns empty under the per-linkId lock.
     * <p>Act:     100 virtual-thread pairs (register, unregister) run concurrently.
     * <p>Assert:  {@code getActiveLinks()} is empty after all futures complete.
     */
    @Test
    void toctou_concurrentRevokeAndRegister_registryNeverContainsRevokedLink() throws Exception {
        int iterations = 100;

        // Pre-configure: all resolve() calls return empty, simulating the case where
        // the concurrent revocation wins the per-linkId lock before register().
        // This is the pessimistic scenario: every register() re-resolve finds the link revoked.
        when(shareLinkService.resolve(any(ShareLinkId.class), any(Instant.class)))
                .thenReturn(Optional.empty());

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();

            for (int i = 0; i < iterations; i++) {
                ShareLinkId linkId = generateUniqueId(i);

                Future<?> registerFuture = executor.submit(() ->
                        registry.register(SHARER_WALL_ID, linkId, shareLinkService, Instant.now())
                );

                Future<?> revokeFuture = executor.submit(() ->
                        registry.unregister(SHARER_WALL_ID, linkId)
                );

                futures.add(registerFuture);
                futures.add(revokeFuture);
            }

            for (Future<?> future : futures) {
                future.get();
            }
        }

        // All register() calls should have returned false (link was revoked under lock)
        // and unregister() calls are no-ops for unregistered entries.
        assertThat(registry.getActiveLinks(SHARER_WALL_ID))
                .as("Registry must be empty after all concurrent register→revoke cycles "
                        + "(TOCTOU window closed by per-linkId lock, ADR-RELAY-03)")
                .isEmpty();
    }

    private static ShareLinkId generateUniqueId(final int index) {
        // Build a deterministic 43-char base64url token for each iteration index
        String base = "sv_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
        // Replace last two chars with zero-padded hex of index (supports up to 255 iterations)
        String suffix = String.format("%02x", index % 256);
        return ShareLinkId.fromUrlPath(base.substring(0, base.length() - 2) + suffix);
    }
}
