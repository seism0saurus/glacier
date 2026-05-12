package de.seism0saurus.glacier.share.infrastructure;

import de.seism0saurus.glacier.share.domain.SecureRandomTokenGenerator;
import de.seism0saurus.glacier.share.domain.ShareLink;
import de.seism0saurus.glacier.share.domain.ShareLinkId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the sweep semantics of {@link SqliteShareLinkRepository#sweepExpired}.
 *
 * <p>A mix of ACTIVE, EXPIRED, and REVOKED links is created. After sweep:
 * <ul>
 *   <li>Expired (but not revoked) links are removed.</li>
 *   <li>ACTIVE links remain.</li>
 *   <li>Revoked links (regardless of expiry) survive — the sweep only removes
 *       time-expired non-revoked rows.</li>
 * </ul>
 *
 * <p>References: {@code sweepExpired} SQL predicate ({@code WHERE expires_at <= ? AND revoked_at IS NULL});
 * ADR-SQLITE-01; SR-SQLITE-09.
 */
class SqliteShareLinkRepositorySweepIT {

    private static final String SHARER_WALL_ID = "sharer-wall-id-fixture-value-0000000000";
    private static final Duration TTL = Duration.ofDays(7);
    private static final Instant NOW = Instant.parse("2025-06-08T00:00:00Z");
    // Link created 8 days ago = already past 7-day TTL
    private static final Instant PAST = NOW.minus(Duration.ofDays(8));
    private static final String HMAC_KEY = "A".repeat(44);

    private SqliteShareLinkRepository repository;
    private SecureRandomTokenGenerator tokenGenerator;

    @BeforeEach
    void setUp() {
        SingleConnectionDataSource ds = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(ds);
        SharePersistenceProperties props = new SharePersistenceProperties();
        props.setPath(":memory:");
        props.setIpHmacKey(HMAC_KEY);
        repository = new SqliteShareLinkRepository(jdbcTemplate, props);
        repository.init();
        tokenGenerator = new SecureRandomTokenGenerator();
    }

    /**
     * Active links survive sweep; expired links are removed; revoked links survive.
     *
     * <p>Setup:
     * <ul>
     *   <li>1 ACTIVE link (created now, within TTL).</li>
     *   <li>1 EXPIRED link (created 8 days ago, past 7-day TTL).</li>
     *   <li>1 REVOKED link (created 8 days ago, past TTL — but also revoked).</li>
     * </ul>
     *
     * <p>After sweep at NOW:
     * <ul>
     *   <li>Removed: 1 (the EXPIRED link).</li>
     *   <li>Remaining: active + revoked = 2.</li>
     * </ul>
     */
    @Test
    void sweep_removesExpiredOnly_leavesActiveAndRevoked() {
        // ACTIVE link (created at T=NOW-1s, expires at T=NOW+7d-1s)
        ShareLink active = ShareLink.create(
                tokenGenerator.generateShareLinkId(), SHARER_WALL_ID,
                NOW.minusSeconds(1), TTL);

        // EXPIRED link (created 8 days ago, TTL=7d, so expired yesterday)
        ShareLink expired = ShareLink.create(
                tokenGenerator.generateShareLinkId(), SHARER_WALL_ID,
                PAST, TTL);

        // REVOKED link (created 8 days ago, also past TTL, but has revoked_at set)
        ShareLink revoked = ShareLink.create(
                tokenGenerator.generateShareLinkId(), SHARER_WALL_ID,
                PAST, TTL);

        repository.save(active);
        repository.save(expired);
        repository.save(revoked);

        // Mark the third link as revoked before sweep
        ShareLinkId revokedId = revoked.id();
        repository.markRevoked(revokedId, PAST.plusSeconds(60));

        int removed = repository.sweepExpired(NOW);

        assertThat(removed)
                .as("Sweep must remove exactly 1 expired link (not the revoked one)")
                .isEqualTo(1);

        // ACTIVE link must remain
        assertThat(repository.findById(active.id()))
                .as("ACTIVE link must survive sweep")
                .isPresent();

        // EXPIRED link must be gone
        assertThat(repository.findById(expired.id()))
                .as("EXPIRED link must be removed by sweep")
                .isEmpty();

        // REVOKED link must remain (revoked links survive the sweep)
        assertThat(repository.findById(revokedId))
                .as("REVOKED link must survive sweep regardless of expiry")
                .isPresent();
    }

    @Test
    void sweep_returnsZero_whenNoExpiredLinks() {
        ShareLink active = ShareLink.create(
                tokenGenerator.generateShareLinkId(), SHARER_WALL_ID,
                NOW.minusSeconds(1), TTL);
        repository.save(active);

        int removed = repository.sweepExpired(NOW);

        assertThat(removed).isEqualTo(0);
    }
}
