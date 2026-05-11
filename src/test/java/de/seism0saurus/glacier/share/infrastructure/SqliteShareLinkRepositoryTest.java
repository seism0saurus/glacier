package de.seism0saurus.glacier.share.infrastructure;

import de.seism0saurus.glacier.share.domain.SecureRandomTokenGenerator;
import de.seism0saurus.glacier.share.domain.ShareLink;
import de.seism0saurus.glacier.share.domain.ShareLinkId;
import de.seism0saurus.glacier.share.domain.ShareLinkStatus;
import de.seism0saurus.glacier.share.domain.ShareLinkSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SqliteShareLinkRepository} using an in-memory SQLite database.
 *
 * <p>Mirrors {@link InMemoryShareLinkRepositoryTest} 1:1 to ensure the SQLite adapter
 * satisfies the same {@link de.seism0saurus.glacier.share.domain.ShareLinkRepository} contract
 * (ADR-SQLITE-01; test plan §Unit tests).
 *
 * <p>Uses {@link SingleConnectionDataSource} so that all {@link org.springframework.jdbc.core.JdbcTemplate}
 * calls share the same in-memory SQLite connection — required because SQLite in-memory databases
 * are connection-scoped: a second connection would see an empty database.
 *
 * <p>References: ADR-SQLITE-01; ADR-SQLITE-02; ADR-SQLITE-03; ADR-SQLITE-04; ADR-SQLITE-06;
 * SR-SQLITE-01; SR-SQLITE-02; SR-SQLITE-04; OWASP A03:2021.
 */
class SqliteShareLinkRepositoryTest {

    private static final String SHARER_WALL_ID = "sharer-wall-id-00000000000000000000000";
    private static final String OTHER_SHARER_WALL_ID = "other-sharer-wall-id-00000000000000";
    private static final String SHARER_IP = "10.0.0.1";
    private static final Duration TTL = Duration.ofDays(7);
    private static final Instant T0 = Instant.parse("2025-06-01T00:00:00Z");

    // Valid 44-char base64 HMAC key (SR-SQLITE-22)
    private static final String VALID_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    private SqliteShareLinkRepository repository;
    private SecureRandomTokenGenerator tokenGenerator;

    @BeforeEach
    void setUp() {
        tokenGenerator = new SecureRandomTokenGenerator();
        DataSource ds = buildInMemoryDataSource();
        SharePersistenceProperties props = buildProperties(":memory:");
        repository = new SqliteShareLinkRepository(props, ds);
        repository.init(); // initialise schema (idempotent)
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

    /**
     * Arrange: save a link.
     * Act:     call findById with the same ID.
     * Assert:  the link is present and its ID matches.
     */
    @Test
    void savedLinkCanBeFoundById() {
        ShareLink link = buildLink(T0);
        repository.save(link);

        Optional<ShareLink> found = repository.findById(link.id());

        assertThat(found).isPresent();
        assertThat(found.get().id()).isEqualTo(link.id());
    }

    /**
     * Arrange: empty repository.
     * Act:     findById with an unknown ID.
     * Assert:  empty Optional returned.
     */
    @Test
    void findByIdReturnsEmptyForUnknownId() {
        ShareLinkId unknownId = ShareLinkId.fromUrlPath("BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB");
        assertThat(repository.findById(unknownId)).isEmpty();
    }

    /**
     * Arrange: save a link; revoke it.
     * Act:     findById after markRevoked.
     * Assert:  status is REVOKED.
     */
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

    /**
     * Arrange: unknown ID.
     * Act:     markRevoked — must not throw.
     * Assert:  no exception (anti-enumeration; no-op for unknown IDs).
     */
    @Test
    void markRevokedOnUnknownIdIsNoOp() {
        ShareLinkId unknownId = ShareLinkId.fromUrlPath("CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC");
        // Must not throw
        repository.markRevoked(unknownId, T0);
    }

    // ---------------------------------------------------------------------------
    // sweepExpired
    // ---------------------------------------------------------------------------

    /**
     * Arrange: one active link (created T0, expires T0+7d); one expired link (already past expiry).
     * Act:     sweepExpired(T0).
     * Assert:  1 link removed; active link still findable; expired link gone.
     */
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

    /**
     * Arrange: one active link.
     * Act:     sweepExpired(T0).
     * Assert:  0 removed.
     */
    @Test
    void sweepExpiredReturnsZeroWhenNothingExpired() {
        repository.save(buildLink(T0));
        assertThat(repository.sweepExpired(T0)).isEqualTo(0);
    }

    /**
     * Arrange: link created at T0; revoked 30s later; sweep at T0+60s (before expiry).
     * Act:     sweepExpired(T0+60s).
     * Assert:  revoked (but not yet expired) link is NOT swept — sweep only removes EXPIRED links.
     */
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

    /**
     * Arrange: 2 active links + 1 expired link.
     * Act:     countActive(T0).
     * Assert:  2.
     */
    @Test
    void countActiveReturnsCorrectCount() {
        repository.save(buildLink(T0));
        repository.save(buildLink(T0));
        repository.save(buildLink(T0.minus(TTL).minusSeconds(1))); // expired

        long count = repository.countActive(T0);

        assertThat(count).isEqualTo(2);
    }

    // ---------------------------------------------------------------------------
    // countActiveForSharer
    // ---------------------------------------------------------------------------

    /**
     * Arrange: 2 links for SHARER_WALL_ID + 1 for a different sharer.
     * Act:     countActiveForSharer(SHARER_WALL_ID, T0).
     * Assert:  2 (only the matching-sharer links are counted).
     */
    @Test
    void countActiveForSharerCountsOnlyMatchingSharer() {
        repository.save(buildLinkForSharer(SHARER_WALL_ID, SHARER_IP, T0));
        repository.save(buildLinkForSharer(SHARER_WALL_ID, SHARER_IP, T0));
        repository.save(buildLinkForSharer(OTHER_SHARER_WALL_ID, SHARER_IP, T0));

        int count = repository.countActiveForSharer(SHARER_WALL_ID, T0);

        assertThat(count).isEqualTo(2);
    }

    // ---------------------------------------------------------------------------
    // countActiveForIp
    // ---------------------------------------------------------------------------

    /**
     * Arrange: 2 links from SHARER_IP + 1 from a different IP.
     * Act:     countActiveForIp(SHARER_IP, T0).
     * Assert:  2 (only the matching-IP links are counted).
     */
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

    /**
     * Arrange: 2 active links + 1 expired link for SHARER_WALL_ID + 1 active for another sharer.
     * Act:     listSummaryBySharer(SHARER_WALL_ID, T0).
     * Assert:  2 summaries — only ACTIVE links for SHARER_WALL_ID; none for the other sharer.
     */
    @Test
    void listSummaryBySharerReturnsOnlyActiveLinksForSharer() {
        ShareLink active1 = buildLinkForSharer(SHARER_WALL_ID, SHARER_IP, T0);
        ShareLink active2 = buildLinkForSharer(SHARER_WALL_ID, SHARER_IP, T0);
        ShareLink expired = buildLinkForSharer(SHARER_WALL_ID, SHARER_IP, T0.minus(TTL).minusSeconds(1));
        ShareLink otherSharer = buildLinkForSharer(OTHER_SHARER_WALL_ID, SHARER_IP, T0);

        repository.save(active1);
        repository.save(active2);
        repository.save(expired);
        repository.save(otherSharer);

        List<ShareLinkSummary> summaries = repository.listSummaryBySharer(SHARER_WALL_ID, T0);

        assertThat(summaries).hasSize(2);
        assertThat(summaries).allMatch(s -> s.status() == ShareLinkStatus.ACTIVE);
        assertThat(summaries).allMatch(s -> SHARER_WALL_ID.equals(s.sharerWallId()));
    }

    /**
     * SR-SQLITE-20: the summary must not contain the raw token — idHash8 must be an
     * 8-character hex string (SHA-256 prefix), not the raw base64url token.
     *
     * <p>Arrange: save one link.
     * <p>Act:     listSummaryBySharer.
     * <p>Assert:  idHash8 is 8 hex chars and does NOT equal the raw token value.
     */
    @Test
    void listSummaryBySharerDoesNotIncludeRawToken() {
        ShareLink link = buildLinkForSharer(SHARER_WALL_ID, SHARER_IP, T0);
        repository.save(link);

        List<ShareLinkSummary> summaries = repository.listSummaryBySharer(SHARER_WALL_ID, T0);

        assertThat(summaries).hasSize(1);
        String idHash8 = summaries.get(0).idHash8();
        assertThat(idHash8)
                .as("idHash8 must be exactly 8 hex characters")
                .matches("[0-9a-f]{8}");
        assertThat(idHash8)
                .as("idHash8 must NOT equal the raw token value — token must never be in the list view")
                .isNotEqualTo(link.id().value());
    }

    // ---------------------------------------------------------------------------
    // findAllBySharer — deprecated; SQLite adapter returns empty list
    // ---------------------------------------------------------------------------

    /**
     * The SQLite adapter cannot reconstruct raw {@link ShareLinkId} from the hashed PK;
     * {@code findAllBySharer} always returns an empty list (ADR-SQLITE-05).
     *
     * <p>Arrange: save 2 links for SHARER_WALL_ID.
     * <p>Act:     findAllBySharer.
     * <p>Assert:  empty list (deprecated no-op in SQLite adapter).
     */
    @Test
    void findAllBySharerReturnsEmptyList_sqliteAdapterCannotReconstructRawId() {
        repository.save(buildLinkForSharer(SHARER_WALL_ID, SHARER_IP, T0));
        repository.save(buildLinkForSharer(SHARER_WALL_ID, SHARER_IP, T0));

        @SuppressWarnings("deprecation")
        List<ShareLink> result = repository.findAllBySharer(SHARER_WALL_ID);

        assertThat(result)
                .as("findAllBySharer must return empty list in SQLite adapter "
                        + "(raw token cannot be reconstructed from SHA-256 PK, ADR-SQLITE-05)")
                .isEmpty();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static SharePersistenceProperties buildProperties(final String path) {
        SharePersistenceProperties props = new SharePersistenceProperties();
        props.setPath(path);
        props.setIpHmacKey(VALID_KEY);
        return props;
    }

    /**
     * Returns a {@link SingleConnectionDataSource} to ensure all JdbcTemplate calls share
     * the same in-memory SQLite connection (connection-scoped schema in SQLite in-memory mode).
     */
    private static DataSource buildInMemoryDataSource() {
        SingleConnectionDataSource ds = new SingleConnectionDataSource();
        ds.setUrl("jdbc:sqlite::memory:");
        ds.setSuppressClose(true);
        return ds;
    }
}
