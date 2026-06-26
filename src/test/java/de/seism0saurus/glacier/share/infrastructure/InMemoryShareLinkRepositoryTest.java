package de.seism0saurus.glacier.share.infrastructure;

import de.seism0saurus.glacier.share.domain.ShareLink;
import de.seism0saurus.glacier.share.domain.ShareLinkId;
import de.seism0saurus.glacier.share.domain.ShareLinkStatus;
import de.seism0saurus.glacier.share.domain.ShareLinkSummary;
import de.seism0saurus.glacier.share.domain.SecureRandomTokenGenerator;
import de.seism0saurus.glacier.share.domain.ShareLinkRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link InMemoryShareLinkRepository}.
 *
 * <p>Covers:
 * <ul>
 *   <li>Basic CRUD: save, findById, markRevoked.</li>
 *   <li>Sweep: {@code sweepExpired} removes only links whose TTL has passed.</li>
 *   <li>Count methods: {@code countActive}, {@code countActiveForSharer},
 *       {@code countActiveForIp}.</li>
 *   <li>Concurrency: 32 virtual-thread simultaneous saves produce no lost updates.</li>
 * </ul>
 */
class InMemoryShareLinkRepositoryTest {

    private static final String SHARER_WALL_ID = "sharer-wall-id-00000000000000000000000";
    private static final String SHARER_IP = "10.0.0.1";
    private static final Duration TTL = Duration.ofDays(7);
    private static final Instant T0 = Instant.parse("2025-06-01T00:00:00Z");

    private InMemoryShareLinkRepository repository;
    private SecureRandomTokenGenerator tokenGenerator;

    @BeforeEach
    void setUp() {
        repository = new InMemoryShareLinkRepository();
        tokenGenerator = new SecureRandomTokenGenerator();
    }

    private ShareLink buildLink(final Instant createdAt) {
        return ShareLink.create(tokenGenerator.generateShareLinkId(), SHARER_WALL_ID, createdAt, TTL);
    }

    private ShareLink buildLinkForSharer(final String sharerWallId, final String creatorIp, final Instant createdAt) {
        return ShareLink.create(tokenGenerator.generateShareLinkId(), sharerWallId, creatorIp, createdAt, TTL);
    }

    // ---------------------------------------------------------------------------
    // Save and findById
    // ---------------------------------------------------------------------------

    @Test
    void savedLinkCanBeFoundById() {
        ShareLink link = buildLink(T0);
        repository.save(link);

        Optional<ShareLink> found = repository.findById(link.id());

        assertThat(found).isPresent();
        assertThat(found.get().id()).isEqualTo(link.id());
    }

    @Test
    void findByIdReturnsEmptyForUnknownId() {
        ShareLinkId unknownId = ShareLinkId.fromUrlPath("BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB");
        assertThat(repository.findById(unknownId)).isEmpty();
    }

    // ---------------------------------------------------------------------------
    // markRevoked
    // ---------------------------------------------------------------------------

    @Test
    void markRevokedTransitionsLinkToRevokedStatus() {
        ShareLink link = buildLink(T0);
        repository.save(link);

        Instant revokeTime = T0.plusSeconds(60);
        repository.markRevoked(link.id(), revokeTime);

        Optional<ShareLink> found = repository.findById(link.id());
        assertThat(found).isPresent();
        assertThat(found.get().status(revokeTime)).isEqualTo(ShareLinkStatus.REVOKED);
    }

    @Test
    void markRevokedOnUnknownIdIsNoOp() {
        // Should not throw; anti-enumeration design — unknown and forbidden look the same
        ShareLinkId unknownId = ShareLinkId.fromUrlPath("CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC");
        repository.markRevoked(unknownId, T0); // must not throw
    }

    // ---------------------------------------------------------------------------
    // markRevokedByHash8 — sharer-scoped revoke by the non-secret idHash8 (list path)
    // ---------------------------------------------------------------------------

    @Test
    void markRevokedByHash8_transitionsMatchingLinkToRevoked() {
        ShareLink link = buildLink(T0);
        repository.save(link);
        Instant revokeTime = T0.plusSeconds(60);

        boolean revoked = repository.markRevokedByHash8(link.id().hash8(), SHARER_WALL_ID, revokeTime);

        assertThat(revoked).isTrue();
        Optional<ShareLink> found = repository.findById(link.id());
        assertThat(found).isPresent();
        assertThat(found.get().status(revokeTime)).isEqualTo(ShareLinkStatus.REVOKED);
    }

    @Test
    void markRevokedByHash8_wrongSharer_isNoOpReturningFalse() {
        ShareLink link = buildLink(T0);
        repository.save(link);

        boolean revoked = repository.markRevokedByHash8(
                link.id().hash8(), "someone-else-wall-id-0000000000000000000", T0.plusSeconds(60));

        assertThat(revoked).isFalse();
        assertThat(repository.findById(link.id()).orElseThrow().status(T0)).isEqualTo(ShareLinkStatus.ACTIVE);
    }

    @Test
    void markRevokedByHash8_unknownHash_returnsFalse() {
        assertThat(repository.markRevokedByHash8("deadbeef", SHARER_WALL_ID, T0)).isFalse();
    }

    @Test
    void markRevokedByHash8_alreadyRevoked_returnsFalse() {
        ShareLink link = buildLink(T0);
        repository.save(link);
        repository.markRevoked(link.id(), T0.plusSeconds(30));

        assertThat(repository.markRevokedByHash8(link.id().hash8(), SHARER_WALL_ID, T0.plusSeconds(60)))
                .isFalse();
    }

    // ---------------------------------------------------------------------------
    // sweepExpired
    // ---------------------------------------------------------------------------

    @Test
    void sweepExpiredRemovesOnlyExpiredLinks() {
        ShareLink active = buildLink(T0);
        ShareLink expired = buildLink(T0.minus(TTL).minusSeconds(1)); // already past expiry

        repository.save(active);
        repository.save(expired);

        int removed = repository.sweepExpired(T0);

        assertThat(removed).isEqualTo(1);
        assertThat(repository.findById(active.id())).isPresent();
        assertThat(repository.findById(expired.id())).isEmpty();
    }

    @Test
    void sweepExpiredReturnsZeroWhenNothingExpired() {
        repository.save(buildLink(T0));
        assertThat(repository.sweepExpired(T0)).isEqualTo(0);
    }

    @Test
    void sweepExpiredDoesNotRemoveRevokedButNotYetExpiredLinks() {
        ShareLink link = buildLink(T0);
        repository.save(link);
        repository.markRevoked(link.id(), T0.plusSeconds(30));

        // Sweep before TTL has elapsed — revoked links must survive sweep
        int removed = repository.sweepExpired(T0.plusSeconds(60));

        assertThat(removed).isEqualTo(0);
        assertThat(repository.findById(link.id())).isPresent();
    }

    // ---------------------------------------------------------------------------
    // countActive
    // ---------------------------------------------------------------------------

    @Test
    void countActiveReturnsNumberOfNonExpiredNonRevokedLinks() {
        repository.save(buildLink(T0));
        repository.save(buildLink(T0));
        ShareLink expired = buildLink(T0.minus(TTL).minusSeconds(1));
        repository.save(expired);

        long count = repository.countActive(T0);

        assertThat(count).isEqualTo(2);
    }

    // ---------------------------------------------------------------------------
    // countActiveForSharer
    // ---------------------------------------------------------------------------

    @Test
    void countActiveForSharerCountsOnlyMatchingSharer() {
        repository.save(buildLinkForSharer(SHARER_WALL_ID, SHARER_IP, T0));
        repository.save(buildLinkForSharer(SHARER_WALL_ID, SHARER_IP, T0));
        repository.save(buildLinkForSharer("other-sharer-wall-id-00000000000000", SHARER_IP, T0));

        int count = repository.countActiveForSharer(SHARER_WALL_ID, T0);

        assertThat(count).isEqualTo(2);
    }

    // ---------------------------------------------------------------------------
    // countActiveForIp
    // ---------------------------------------------------------------------------

    @Test
    void countActiveForIpCountsOnlyMatchingIp() {
        repository.save(buildLinkForSharer(SHARER_WALL_ID, SHARER_IP, T0));
        repository.save(buildLinkForSharer(SHARER_WALL_ID, SHARER_IP, T0));
        repository.save(buildLinkForSharer(SHARER_WALL_ID, "10.0.0.2", T0));

        int count = repository.countActiveForIp(SHARER_IP, T0);

        assertThat(count).isEqualTo(2);
    }

    // ---------------------------------------------------------------------------
    // listSummaryBySharer
    // ---------------------------------------------------------------------------

    @Test
    void listSummaryBySharer_returnsCorrectProjectionsForSharer() {
        ShareLink link1 = buildLinkForSharer(SHARER_WALL_ID, SHARER_IP, T0);
        ShareLink link2 = buildLinkForSharer(SHARER_WALL_ID, SHARER_IP, T0.plusSeconds(10));
        repository.save(link1);
        repository.save(link2);
        // Different sharer — must not appear in the result
        repository.save(buildLinkForSharer("other-wall-id-0000000000000000000000000", SHARER_IP, T0));

        List<ShareLinkSummary> summaries = repository.listSummaryBySharer(SHARER_WALL_ID, T0);

        assertThat(summaries).hasSize(2);
        assertThat(summaries).allSatisfy(s -> {
            assertThat(s.sharerWallId()).isEqualTo(SHARER_WALL_ID);
            assertThat(s.idHash8()).matches("[0-9a-f]{8}");
            assertThat(s.status()).isEqualTo(ShareLinkStatus.ACTIVE);
        });
    }

    @Test
    void listSummaryBySharer_returnsEmpty_forUnknownSharer() {
        repository.save(buildLinkForSharer(SHARER_WALL_ID, SHARER_IP, T0));

        List<ShareLinkSummary> summaries = repository.listSummaryBySharer("unknown-sharer-00000000000000000000", T0);

        assertThat(summaries).isEmpty();
    }

    // ---------------------------------------------------------------------------
    // Concurrency — 32 virtual threads, no lost updates
    // ---------------------------------------------------------------------------

    @Test
    void thirtyTwoVirtualThreadsSavingConcurrentlyProduceNoLostUpdates() throws Exception {
        int threadCount = 32;
        List<ShareLink> linksToSave = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            linksToSave.add(buildLink(T0));
        }

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Callable<Void>> tasks = linksToSave.stream()
                    .<Callable<Void>>map(link -> () -> {
                        repository.save(link);
                        return null;
                    })
                    .toList();

            List<Future<Void>> futures = executor.invokeAll(tasks);
            // Ensure all completed without exception
            for (Future<Void> f : futures) {
                f.get(); // re-throws any ExecutionException
            }
        }

        // All 32 saves must be visible — no lost updates
        long count = repository.countActive(T0);
        assertThat(count).isEqualTo(threadCount);
    }

    @Test
    void concurrentRevocationsDoNotCorruptRepository() throws Exception {
        // Save 32 links, then revoke them concurrently
        List<ShareLink> links = new ArrayList<>();
        for (int i = 0; i < 32; i++) {
            ShareLink link = buildLink(T0);
            repository.save(link);
            links.add(link);
        }

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Callable<Void>> tasks = links.stream()
                    .<Callable<Void>>map(link -> () -> {
                        repository.markRevoked(link.id(), T0.plusSeconds(1));
                        return null;
                    })
                    .toList();
            List<Future<Void>> futures = executor.invokeAll(tasks);
            for (Future<Void> f : futures) {
                f.get();
            }
        }

        // All 32 links should be revoked; countActive must return 0
        assertThat(repository.countActive(T0.plusSeconds(2))).isEqualTo(0);
    }
}
