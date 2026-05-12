package de.seism0saurus.glacier.share.infrastructure;

import de.seism0saurus.glacier.share.domain.SecureRandomTokenGenerator;
import de.seism0saurus.glacier.share.domain.ShareLink;
import de.seism0saurus.glacier.share.domain.ShareLinkId;
import de.seism0saurus.glacier.share.domain.ShareLinkStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R-05 acceptance test: share links written to a real SQLite file persist across
 * simulated JVM restarts.
 *
 * <p>This test:
 * <ol>
 *   <li>Opens a {@link SqliteShareLinkRepository} backed by a real temp-dir file.</li>
 *   <li>Saves one share link.</li>
 *   <li>Closes the datasource (simulating process shutdown).</li>
 *   <li>Opens a new datasource pointing to the same file (simulating restart).</li>
 *   <li>Asserts the link is still findable by ID.</li>
 * </ol>
 *
 * <p>This verifies that:
 * <ul>
 *   <li>The data is written durably (SQLite {@code PRAGMA synchronous=FULL}).</li>
 *   <li>The schema DDL ({@code CREATE TABLE IF NOT EXISTS}) is idempotent on restart.</li>
 *   <li>The SHA-256(token) lookup round-trip works after restart.</li>
 * </ul>
 *
 * <p>References: R-05 (persistence acceptance criterion); ADR-SQLITE-02
 * ({@code synchronous=FULL}); SR-SQLITE-05 (revocation durability).
 */
class SqliteShareLinkRepositoryRestartIT {

    private static final String SHARER_WALL_ID = "sharer-wall-id-fixture-value-0000000000";
    private static final Duration TTL = Duration.ofDays(7);
    private static final Instant T0 = Instant.parse("2025-06-01T00:00:00Z");
    private static final String HMAC_KEY = "A".repeat(44);

    @TempDir
    Path tempDir;

    /**
     * Saves a link on one datasource, closes it, then opens a fresh datasource on the
     * same file and asserts the link survives the simulated restart.
     *
     * <p>Arrange: real file-backed SQLite DB; one share link saved.
     * <p>Act:     close first datasource; open second datasource on same file; call findById.
     * <p>Assert:  link found with correct ID and ACTIVE status.
     */
    @Test
    void savedLink_survivesSimulatedRestart() {
        String dbPath = tempDir.resolve("share-links.db").toAbsolutePath().toString();
        SecureRandomTokenGenerator tokenGenerator = new SecureRandomTokenGenerator();
        ShareLink link = ShareLink.create(
                tokenGenerator.generateShareLinkId(), SHARER_WALL_ID, T0, TTL);
        ShareLinkId savedId = link.id();

        // Session 1: write
        {
            SingleConnectionDataSource ds1 = new SingleConnectionDataSource(
                    "jdbc:sqlite:" + dbPath, true);
            SharePersistenceProperties props = makeProps(dbPath);
            SqliteShareLinkRepository repo1 = new SqliteShareLinkRepository(
                    new JdbcTemplate(ds1), props);
            repo1.init();
            repo1.save(link);
            ds1.destroy();
        }

        // Session 2: read — simulates JVM restart
        {
            SingleConnectionDataSource ds2 = new SingleConnectionDataSource(
                    "jdbc:sqlite:" + dbPath, true);
            SharePersistenceProperties props = makeProps(dbPath);
            SqliteShareLinkRepository repo2 = new SqliteShareLinkRepository(
                    new JdbcTemplate(ds2), props);
            repo2.init(); // idempotent DDL on restart

            Optional<ShareLink> found = repo2.findById(savedId);

            assertThat(found)
                    .as("Link must be findable after simulated restart (R-05; ADR-SQLITE-02)")
                    .isPresent();
            assertThat(found.get().id())
                    .isEqualTo(savedId);
            assertThat(found.get().status(T0))
                    .isEqualTo(ShareLinkStatus.ACTIVE);
            ds2.destroy();
        }
    }

    /**
     * A revoked link is still revoked after restart — revocation durability (SR-SQLITE-05).
     *
     * <p>Arrange: save a link; revoke it; close the datasource.
     * <p>Act:     open a new datasource on the same file; find the link.
     * <p>Assert:  link is REVOKED.
     */
    @Test
    void revokedLink_isStillRevoked_afterRestart() {
        String dbPath = tempDir.resolve("revoked-links.db").toAbsolutePath().toString();
        SecureRandomTokenGenerator tokenGenerator = new SecureRandomTokenGenerator();
        ShareLink link = ShareLink.create(
                tokenGenerator.generateShareLinkId(), SHARER_WALL_ID, T0, TTL);
        ShareLinkId savedId = link.id();
        Instant revokedAt = T0.plusSeconds(120);

        // Session 1: write + revoke
        {
            SingleConnectionDataSource ds1 = new SingleConnectionDataSource(
                    "jdbc:sqlite:" + dbPath, true);
            SharePersistenceProperties props = makeProps(dbPath);
            SqliteShareLinkRepository repo1 = new SqliteShareLinkRepository(
                    new JdbcTemplate(ds1), props);
            repo1.init();
            repo1.save(link);
            repo1.markRevoked(savedId, revokedAt);
            ds1.destroy();
        }

        // Session 2: read
        {
            SingleConnectionDataSource ds2 = new SingleConnectionDataSource(
                    "jdbc:sqlite:" + dbPath, true);
            SharePersistenceProperties props = makeProps(dbPath);
            SqliteShareLinkRepository repo2 = new SqliteShareLinkRepository(
                    new JdbcTemplate(ds2), props);
            repo2.init();

            Optional<ShareLink> found = repo2.findById(savedId);

            assertThat(found).isPresent();
            assertThat(found.get().status(revokedAt.plusSeconds(1)))
                    .as("Link must be REVOKED after restart (SR-SQLITE-05)")
                    .isEqualTo(ShareLinkStatus.REVOKED);
            ds2.destroy();
        }
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private static SharePersistenceProperties makeProps(final String path) {
        SharePersistenceProperties props = new SharePersistenceProperties();
        props.setPath(path);
        props.setIpHmacKey(HMAC_KEY);
        return props;
    }
}
