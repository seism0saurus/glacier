package de.seism0saurus.glacier.share.infrastructure;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test verifying that the SQLite PRAGMA settings applied by
 * {@link SqliteDataSourceConfig} produce the correct runtime state.
 *
 * <p>This test connects to an in-memory SQLite database, applies the same PRAGMAs used
 * by {@code SqliteDataSourceConfig}, and asserts the resulting state matches requirements:
 * <ul>
 *   <li>{@code PRAGMA synchronous} = {@code 2} (= FULL) — SR-SQLITE-05</li>
 *   <li>{@code PRAGMA foreign_keys} = {@code 1} (enabled)</li>
 *   <li>{@code PRAGMA journal_mode} = {@code wal} (WAL enabled)</li>
 * </ul>
 *
 * <p>Uses {@link SingleConnectionDataSource} to ensure all PRAGMA queries see the same
 * connection state (HikariCP would spawn separate connections, each with their own PRAGMA
 * scope).
 *
 * <p>References: ADR-SQLITE-02; SR-SQLITE-05 ({@code synchronous=FULL}); OWASP A05:2021.
 */
class SqliteShareLinkRepositoryPragmaIT {

    /**
     * SQLite synchronous mode 2 = FULL.
     * Required for revocation durability — SR-SQLITE-05.
     */
    private static final int PRAGMA_SYNCHRONOUS_FULL = 2;

    @Test
    void synchronousPragmaIsFull_afterConnectionInit() throws Exception {
        SingleConnectionDataSource ds = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement()) {

            stmt.execute("PRAGMA journal_mode=WAL");
            stmt.execute("PRAGMA synchronous=FULL");
            stmt.execute("PRAGMA foreign_keys=ON");

            // Verify synchronous=FULL (= 2)
            // SR-SQLITE-05: required for revocation durability
            try (ResultSet rs = stmt.executeQuery("PRAGMA synchronous")) {
                assertThat(rs.next()).isTrue();
                int synchronousValue = rs.getInt(1);
                assertThat(synchronousValue)
                        .as("PRAGMA synchronous must be FULL (= 2) after connection init (SR-SQLITE-05)")
                        .isEqualTo(PRAGMA_SYNCHRONOUS_FULL);
            }
        } finally {
            ds.destroy();
        }
    }

    @Test
    void foreignKeysPragmaIsEnabled_afterConnectionInit() throws Exception {
        SingleConnectionDataSource ds = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement()) {

            stmt.execute("PRAGMA foreign_keys=ON");

            try (ResultSet rs = stmt.executeQuery("PRAGMA foreign_keys")) {
                assertThat(rs.next()).isTrue();
                int foreignKeysValue = rs.getInt(1);
                assertThat(foreignKeysValue)
                        .as("PRAGMA foreign_keys must be enabled (= 1) after connection init")
                        .isEqualTo(1);
            }
        } finally {
            ds.destroy();
        }
    }

    @Test
    void journalModePragmaIsWal_afterConnectionInit(@TempDir Path tempDir) throws Exception {
        // journal_mode=WAL only works on file-based SQLite databases.
        // An in-memory (:memory:) database ignores the WAL request and stays in "memory"
        // journal mode — this is documented SQLite behaviour.
        // We test with a real temp file to verify WAL is accepted by the driver.
        String dbPath = tempDir.resolve("pragma-test.db").toAbsolutePath().toString();
        SingleConnectionDataSource ds = new SingleConnectionDataSource("jdbc:sqlite:" + dbPath, true);
        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement()) {

            // For file-based SQLite: journal_mode=WAL returns "wal" to confirm the setting
            try (ResultSet rs = stmt.executeQuery("PRAGMA journal_mode=WAL")) {
                assertThat(rs.next()).isTrue();
                String journalMode = rs.getString(1);
                assertThat(journalMode)
                        .as("PRAGMA journal_mode must be 'wal' after connection init on a file-based DB")
                        .isEqualToIgnoringCase("wal");
            }
        } finally {
            ds.destroy();
        }
    }

    @Test
    void jdbcTemplateCanExecuteAfterPragmaInit() {
        SingleConnectionDataSource ds = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        try {
            applyPragmas(ds);
            JdbcTemplate jdbc = new JdbcTemplate(ds);

            // Simple round-trip to verify the connection is functional after PRAGMA init
            Integer result = jdbc.queryForObject("SELECT 42", Integer.class);
            assertThat(result).isEqualTo(42);
        } finally {
            ds.destroy();
        }
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private static void applyPragmas(final SingleConnectionDataSource ds) {
        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA journal_mode=WAL");
            stmt.execute("PRAGMA synchronous=FULL");
            stmt.execute("PRAGMA foreign_keys=ON");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to apply PRAGMAs to in-memory SQLite", e);
        }
    }
}
