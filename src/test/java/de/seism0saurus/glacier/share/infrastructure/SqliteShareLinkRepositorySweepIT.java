package de.seism0saurus.glacier.share.infrastructure;

import de.seism0saurus.glacier.share.domain.SecureRandomTokenGenerator;
import de.seism0saurus.glacier.share.domain.ShareLink;
import de.seism0saurus.glacier.share.domain.ShareLinkStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test verifying the sweep behaviour of {@link SqliteShareLinkRepository}.
 *
 * <p>Scenario: insert 3 active, 2 expired, 1 revoked (but not yet expired).
 * After {@code sweepExpired(now)}: 2 expired removed; 3 active + 1 revoked remain.
 *
 * <p>The revoked-but-not-expired link must survive the sweep — revoked links are retained
 * until they naturally expire (same contract as the in-memory adapter).
 *
 * <p>References: ADR-SQLITE-07 (sweep); {@link InMemoryShareLinkRepositoryTest#sweepExpiredDoesNotRemoveRevokedButNotYetExpiredLinks}.
 */
class SqliteShareLinkRepositorySweepIT {

    private static final String SHARER_WALL_ID = "sharer-wall-id-sweep-it-000000000000000";
    private static final Duration TTL = Duration.ofDays(7);
    private static final Instant T0 = Instant.parse("2025-06-01T00:00:00Z");
    private static final String VALID_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    private SqliteShareLinkRepository repository;
    private SecureRandomTokenGenerator tokenGenerator;

    @BeforeEach
    void setUp() {
        tokenGenerator = new SecureRandomTokenGenerator();
        DataSource ds = buildInMemoryDataSource();
        SharePersistenceProperties props = buildProperties(":memory:");
        repository = new SqliteShareLinkRepository(props, ds);
        repository.init();
    }

    /**
     * Arrange: 3 active + 2 expired + 1 revoked (not yet expired).
     * Act:     sweepExpired(T0).
     * Assert:  2 rows removed; 3 active + 1 revoked remain; count checks consistent.
     */
    @Test
    void sweepRemovesExpiredLinks_andPreservesActiveAndRevoked() {
        // 3 active links
        ShareLink active1 = ShareLink.create(tokenGenerator.generateShareLinkId(), SHARER_WALL_ID, T0, TTL);
        ShareLink active2 = ShareLink.create(tokenGenerator.generateShareLinkId(), SHARER_WALL_ID, T0, TTL);
        ShareLink active3 = ShareLink.create(tokenGenerator.generateShareLinkId(), SHARER_WALL_ID, T0, TTL);

        // 2 expired links (created TTL + 1s before T0, so expiresAt is 1s before T0)
        ShareLink expired1 = ShareLink.create(tokenGenerator.generateShareLinkId(), SHARER_WALL_ID,
                T0.minus(TTL).minusSeconds(1), TTL);
        ShareLink expired2 = ShareLink.create(tokenGenerator.generateShareLinkId(), SHARER_WALL_ID,
                T0.minus(TTL).minusSeconds(1), TTL);

        // 1 revoked link (not yet expired: expiresAt = T0 + TTL, but revoked at T0 + 30s)
        ShareLink revoked = ShareLink.create(tokenGenerator.generateShareLinkId(), SHARER_WALL_ID, T0, TTL);

        repository.save(active1);
        repository.save(active2);
        repository.save(active3);
        repository.save(expired1);
        repository.save(expired2);
        repository.save(revoked);
        repository.markRevoked(revoked.id(), T0.plusSeconds(30));

        // Act: sweep at T0
        int removed = repository.sweepExpired(T0);

        // Assert: 2 expired removed
        assertThat(removed)
                .as("sweepExpired must remove exactly 2 expired links (the 2 with expiresAt <= T0)")
                .isEqualTo(2);

        // Active links must be findable
        assertThat(repository.findById(active1.id())).isPresent();
        assertThat(repository.findById(active2.id())).isPresent();
        assertThat(repository.findById(active3.id())).isPresent();

        // Expired links must be gone
        assertThat(repository.findById(expired1.id())).isEmpty();
        assertThat(repository.findById(expired2.id())).isEmpty();

        // Revoked link must survive sweep
        assertThat(repository.findById(revoked.id()))
                .as("Revoked-but-not-yet-expired link must survive the sweep")
                .isPresent();
        assertThat(repository.findById(revoked.id()).get().status(T0))
                .as("Revoked link status must still be REVOKED after sweep")
                .isEqualTo(ShareLinkStatus.REVOKED);

        // countActive must reflect the 3 remaining active links
        assertThat(repository.countActive(T0))
                .as("countActive must return 3 after sweeping 2 expired links")
                .isEqualTo(3);
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

    private static DataSource buildInMemoryDataSource() {
        SingleConnectionDataSource ds = new SingleConnectionDataSource();
        ds.setUrl("jdbc:sqlite::memory:");
        ds.setSuppressClose(true);
        return ds;
    }
}
