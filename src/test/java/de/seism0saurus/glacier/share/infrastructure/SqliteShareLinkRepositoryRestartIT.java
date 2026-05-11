package de.seism0saurus.glacier.share.infrastructure;

import de.seism0saurus.glacier.share.domain.SecureRandomTokenGenerator;
import de.seism0saurus.glacier.share.domain.ShareLink;
import de.seism0saurus.glacier.share.domain.ShareLinkId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test verifying that share links survive a simulated server restart.
 *
 * <p>This is the R-05 acceptance test: write N links to a temp-file DB, close the
 * datasource (simulating a restart), construct a new {@link SqliteShareLinkRepository}
 * pointing at the same file, and assert all N links are found by {@code findById}.
 *
 * <p>Test plan ref: {@code SqliteShareLinkRepositoryRestartIT} — restart durability.
 *
 * <p>References: P3-05; ADR-SQLITE-01; ADR-SQLITE-02 (IF NOT EXISTS idempotency).
 */
class SqliteShareLinkRepositoryRestartIT {

    private static final String SHARER_WALL_ID = "sharer-wall-id-restart-it-0000000000";
    private static final Duration TTL = Duration.ofDays(7);
    private static final Instant T0 = Instant.parse("2025-06-01T00:00:00Z");
    private static final String VALID_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
    private static final int LINK_COUNT = 5;

    /**
     * Write N links to a disk-based DB, reopen the repository, assert all links survive.
     *
     * <p>Arrange: create a temp-file DB; save N links with distinct IDs.
     * <p>Act:     close the first datasource; create a new repository with the same path.
     * <p>Assert:  all N links are found by findById.
     */
    @Test
    void linksWrittenToFile_surviveSimulatedRestart(@TempDir final Path tempDir) throws Exception {
        Path dbFile = tempDir.resolve("share.db");
        String dbPath = dbFile.toAbsolutePath().toString();

        SecureRandomTokenGenerator tokenGenerator = new SecureRandomTokenGenerator();
        List<ShareLinkId> savedIds = new ArrayList<>();

        // --- First "boot": write N links ---
        {
            DataSource ds = buildFileDataSource(dbPath);
            SharePersistenceProperties props = buildProperties(dbPath);
            SqliteShareLinkRepository repo = new SqliteShareLinkRepository(props, ds);
            repo.init();

            for (int i = 0; i < LINK_COUNT; i++) {
                ShareLink link = ShareLink.create(tokenGenerator.generateShareLinkId(),
                        SHARER_WALL_ID, T0, TTL);
                savedIds.add(link.id());
                repo.save(link);
            }
            // Simulate shutdown: close the connection
            if (ds instanceof AutoCloseable closeable) {
                closeable.close();
            }
        }

        // --- Second "boot": open the same file and verify all links are present ---
        {
            DataSource ds2 = buildFileDataSource(dbPath);
            SharePersistenceProperties props2 = buildProperties(dbPath);
            SqliteShareLinkRepository repo2 = new SqliteShareLinkRepository(props2, ds2);
            repo2.init(); // idempotent DDL — must not corrupt existing data

            for (ShareLinkId id : savedIds) {
                assertThat(repo2.findById(id))
                        .as("Link with id-hash8=%s must survive a simulated restart "
                                + "(R-05 acceptance test)", id.hash8())
                        .isPresent();
            }
        }
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
     * Returns a file-based SQLite datasource (not in-memory, so data is persisted to disk).
     *
     * <p>Uses {@link SimpleDriverDataSource} for simplicity in this test — no HikariCP needed.
     */
    private static DataSource buildFileDataSource(final String dbPath) {
        SimpleDriverDataSource ds = new SimpleDriverDataSource();
        ds.setUrl("jdbc:sqlite:" + dbPath);
        try {
            ds.setDriverClass(org.sqlite.JDBC.class);
        } catch (Exception e) {
            throw new RuntimeException("SQLite JDBC driver not available", e);
        }
        return ds;
    }
}
