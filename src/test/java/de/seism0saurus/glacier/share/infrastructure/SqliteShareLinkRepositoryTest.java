package de.seism0saurus.glacier.share.infrastructure;

import de.seism0saurus.glacier.share.domain.SecureRandomTokenGenerator;
import de.seism0saurus.glacier.share.domain.ShareLink;
import de.seism0saurus.glacier.share.domain.ShareLinkId;
import de.seism0saurus.glacier.share.domain.ShareLinkStatus;
import de.seism0saurus.glacier.share.domain.ShareLinkSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

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
 * Unit tests for {@link SqliteShareLinkRepository} using an in-memory SQLite database.
 *
 * <p>Mirrors the scenario coverage of {@link InMemoryShareLinkRepositoryTest} exactly,
 * plus SQLite-specific scenarios (token at rest, IP at rest, revocation race).
 *
 * <h2>In-memory connection strategy</h2>
 * {@link SingleConnectionDataSource} is used instead of HikariCP because SQLite
 * {@code :memory:} creates a separate, empty database per connection. Using a single
 * connection ensures that schema DDL applied in {@link #setUp()} is visible to all
 * subsequent JDBC operations within the same test.
 *
 * <p>References: SR-SQLITE-01; SR-SQLITE-04; ADR-SQLITE-04; R-05.
 */
class SqliteShareLinkRepositoryTest {

    private static final String SHARER_WALL_ID = "sharer-wall-id-00000000000000000000000";
    private static final String SHARER_IP = "10.0.0.1";
    private static final Duration TTL = Duration.ofDays(7);
    private static final Instant T0 = Instant.parse("2025-06-01T00:00:00Z");
    // Valid 44-char Base64 key (256 bits; SR-SQLITE-22)
    private static final String HMAC_KEY = "A".repeat(44);

    private SqliteShareLinkRepository repository;
    private SecureRandomTokenGenerator tokenGenerator;
    private SingleConnectionDataSource dataSource;

    @BeforeEach
    void setUp() {
        dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        SharePersistenceProperties props = new SharePersistenceProperties();
        props.setPath(":memory:");
        props.setIpHmacKey(HMAC_KEY);
        repository = new SqliteShareLinkRepository(jdbcTemplate, props);
        repository.init();
        tokenGenerator = new SecureRandomTokenGenerator();
    }

    private ShareLink buildLink(final Instant createdAt) {
        return ShareLink.create(tokenGenerator.generateShareLinkId(), SHARER_WALL_ID, createdAt, TTL);
    }

    private ShareLink buildLinkForSharer(final String sharerWallId, final String creatorIp,
                                          final Instant createdAt) {
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
    void savedLink_allFieldsMatchOriginal() {
        ShareLink link = buildLink(T0);
        repository.save(link);

        ShareLink found = repository.findById(link.id()).orElseThrow();

        assertThat(found.sharerWallId()).isEqualTo(SHARER_WALL_ID);
        assertThat(found.createdAt()).isEqualTo(T0);
        assertThat(found.expiresAt()).isEqualTo(T0.plus(TTL));
        assertThat(found.revokedAt()).isEmpty();
        assertThat(found.status(T0)).isEqualTo(ShareLinkStatus.ACTIVE);
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
        ShareLinkId unknownId = ShareLinkId.fromUrlPath("CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC");
        // Must not throw
        repository.markRevoked(unknownId, T0);
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
        ShareLink found = repository.findById(link.id()).orElseThrow();
        assertThat(found.status(revokeTime)).isEqualTo(ShareLinkStatus.REVOKED);
    }

    @Test
    void markRevokedByHash8_wrongSharer_isNoOpReturningFalse() {
        ShareLink link = buildLink(T0);
        repository.save(link);

        boolean revoked = repository.markRevokedByHash8(
                link.id().hash8(), "someone-else-wall-id-0000000000000000000", T0.plusSeconds(60));

        assertThat(revoked).isFalse();
        // The link is untouched — anti-enumeration: a foreign sharer cannot revoke it.
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

        // Second revoke (now via hash) is idempotent — no row transitions, returns false.
        assertThat(repository.markRevokedByHash8(link.id().hash8(), SHARER_WALL_ID, T0.plusSeconds(60)))
                .isFalse();
    }

    // ---------------------------------------------------------------------------
    // sweepExpired
    // ---------------------------------------------------------------------------

    @Test
    void sweepExpiredRemovesOnlyExpiredLinks() {
        ShareLink active = buildLink(T0);
        ShareLink expired = buildLink(T0.minus(TTL).minusSeconds(1));

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
        repository.save(buildLink(T0.minus(TTL).minusSeconds(1))); // expired

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
    void listSummaryBySharer_returnsAllLinksForSharer() {
        ShareLink link1 = buildLinkForSharer(SHARER_WALL_ID, SHARER_IP, T0);
        ShareLink link2 = buildLinkForSharer(SHARER_WALL_ID, SHARER_IP, T0.plusSeconds(10));
        repository.save(link1);
        repository.save(link2);
        // Different sharer — must not appear
        repository.save(buildLinkForSharer("other-wall-id-00000000000000000000000", SHARER_IP, T0));

        List<ShareLinkSummary> summaries = repository.listSummaryBySharer(SHARER_WALL_ID, T0);

        assertThat(summaries).hasSize(2);
        assertThat(summaries).allSatisfy(s ->
                assertThat(s.sharerWallId()).isEqualTo(SHARER_WALL_ID));
    }

    @Test
    void listSummaryBySharer_orderedByCreatedAtDesc() {
        ShareLink older = buildLinkForSharer(SHARER_WALL_ID, SHARER_IP, T0);
        ShareLink newer = buildLinkForSharer(SHARER_WALL_ID, SHARER_IP, T0.plusSeconds(30));
        repository.save(older);
        repository.save(newer);

        List<ShareLinkSummary> summaries = repository.listSummaryBySharer(SHARER_WALL_ID, T0);

        // Ordered DESC by createdAt — newer first
        assertThat(summaries.get(0).createdAt()).isAfterOrEqualTo(summaries.get(1).createdAt());
    }

    @Test
    void listSummaryBySharer_idHash8_isFirst8CharsOfStoredHash() {
        ShareLink link = buildLinkForSharer(SHARER_WALL_ID, SHARER_IP, T0);
        repository.save(link);

        List<ShareLinkSummary> summaries = repository.listSummaryBySharer(SHARER_WALL_ID, T0);

        assertThat(summaries).hasSize(1);
        // idHash8 must be exactly 8 hex chars
        assertThat(summaries.get(0).idHash8())
                .as("idHash8 must be exactly 8 hex characters")
                .matches("[0-9a-f]{8}");
    }

    @Test
    void listSummaryBySharer_revokedLink_hasRevokedStatus() {
        ShareLink link = buildLinkForSharer(SHARER_WALL_ID, SHARER_IP, T0);
        repository.save(link);
        repository.markRevoked(link.id(), T0.plusSeconds(60));

        List<ShareLinkSummary> summaries = repository.listSummaryBySharer(SHARER_WALL_ID, T0.plusSeconds(120));

        assertThat(summaries).hasSize(1);
        assertThat(summaries.get(0).status()).isEqualTo(ShareLinkStatus.REVOKED);
        assertThat(summaries.get(0).revokedAt()).isNotNull();
    }

    // ---------------------------------------------------------------------------
    // findAllBySharer (deprecated — returns empty for SQLite adapter)
    // ---------------------------------------------------------------------------

    @Test
    @SuppressWarnings("deprecation")
    void findAllBySharer_returnsEmptyForSqliteAdapter() {
        repository.save(buildLinkForSharer(SHARER_WALL_ID, SHARER_IP, T0));

        List<ShareLink> result = repository.findAllBySharer(SHARER_WALL_ID);

        assertThat(result)
                .as("findAllBySharer on SQLite adapter must return empty — raw token not recoverable (ADR-SQLITE-04)")
                .isEmpty();
    }

    // ---------------------------------------------------------------------------
    // Concurrency — 32 virtual threads saving
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
            for (Future<Void> f : futures) {
                f.get();
            }
        }

        long count = repository.countActive(T0);
        assertThat(count).isEqualTo(threadCount);
    }
}
