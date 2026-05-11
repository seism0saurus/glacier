package de.seism0saurus.glacier.share.infrastructure;

import de.seism0saurus.glacier.share.domain.SecureRandomTokenGenerator;
import de.seism0saurus.glacier.share.domain.ShareLink;
import de.seism0saurus.glacier.share.domain.ShareLinkId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the "first revocation wins" invariant of {@link SqliteShareLinkRepository}.
 *
 * <p>ADR-SQLITE-03: {@code markRevoked} uses {@code WHERE revoked_at IS NULL}, which means
 * only the first call actually writes. Sequential re-calls must leave {@code revokedAt}
 * equal to the first call's timestamp.
 *
 * <p>This test verifies the single-connection sequential case. Concurrent multi-threaded
 * coverage is in {@link SqliteShareLinkRepositoryConcurrencyIT}.
 */
class SqliteShareLinkRepositoryRevocationRaceTest {

    private static final String SHARER_WALL_ID = "sharer-wall-id-revoke-race-00000000000";
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
     * Two sequential calls to {@code markRevoked} with different timestamps must store
     * only the FIRST revocation instant — the second call is a no-op (ADR-SQLITE-03).
     *
     * <p>Arrange: save one link; compute two different revocation instants T1 < T2.
     * <p>Act:     call markRevoked(id, T1); then markRevoked(id, T2).
     * <p>Assert:  stored revokedAt == T1 (first call wins; second is a no-op).
     */
    @Test
    void secondMarkRevokedCallIsNoOp_firstRevocationWins() {
        ShareLink link = ShareLink.create(tokenGenerator.generateShareLinkId(), SHARER_WALL_ID, T0, TTL);
        ShareLinkId id = link.id();
        repository.save(link);

        Instant t1 = T0.plusSeconds(60);
        Instant t2 = T0.plusSeconds(120);

        // First revocation — should succeed (affects 1 row)
        repository.markRevoked(id, t1);
        // Second revocation — should be a no-op (WHERE revoked_at IS NULL fails)
        repository.markRevoked(id, t2);

        Optional<ShareLink> found = repository.findById(id);
        assertThat(found).isPresent();
        assertThat(found.get().revokedAt())
                .as("First revocation must win — stored revokedAt must equal t1, not t2 "
                        + "(ADR-SQLITE-03: WHERE revoked_at IS NULL ensures atomicity)")
                .hasValue(t1);
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
