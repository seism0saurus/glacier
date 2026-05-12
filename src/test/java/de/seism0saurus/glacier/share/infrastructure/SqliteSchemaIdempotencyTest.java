package de.seism0saurus.glacier.share.infrastructure;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * Unit test verifying that the SQLite schema DDL is idempotent.
 *
 * <p>The schema uses {@code CREATE TABLE IF NOT EXISTS} and {@code CREATE INDEX IF NOT EXISTS}
 * (ADR-SQLITE-02). This test creates the schema DDL twice against the same in-memory SQLite
 * database and asserts that no exception is thrown.
 *
 * <p>Uses {@link SingleConnectionDataSource} to ensure both DDL executions share the same
 * underlying connection. HikariCP creates a new empty in-memory DB per connection, which would
 * make the test trivially pass by hiding schema conflicts.
 *
 * <p>Note: this test exercises the raw DDL SQL rather than a complete
 * {@code SqliteShareLinkRepository} (which is implemented in Lane 3). Its purpose is to gate
 * on the DDL correctness before the full repository is available.
 *
 * <p>References: ADR-SQLITE-02; SR-SQLITE-05 ({@code synchronous=FULL} applied at setup).
 */
class SqliteSchemaIdempotencyTest {

    /**
     * DDL that matches the planned schema from ADR-SQLITE-02.
     * <p>Using IF NOT EXISTS on both TABLE and INDEX declarations is the idempotency invariant.
     */
    private static final String CREATE_TABLE_SQL = """
            CREATE TABLE IF NOT EXISTS share_links (
                id              TEXT    NOT NULL PRIMARY KEY,
                sharer_wall_id  TEXT    NOT NULL,
                creator_ip_hmac TEXT,
                created_at      INTEGER NOT NULL,
                expires_at      INTEGER NOT NULL,
                revoked_at      INTEGER
            ) WITHOUT ROWID
            """;

    private static final String CREATE_INDEX_SHARER_SQL = """
            CREATE INDEX IF NOT EXISTS idx_share_links_sharer_active
                ON share_links (sharer_wall_id, expires_at)
                WHERE revoked_at IS NULL
            """;

    private static final String CREATE_INDEX_IP_SQL = """
            CREATE INDEX IF NOT EXISTS idx_share_links_creator_ip_active
                ON share_links (creator_ip_hmac, expires_at)
                WHERE revoked_at IS NULL AND creator_ip_hmac IS NOT NULL
            """;

    private static final String CREATE_INDEX_EXPIRES_SQL = """
            CREATE INDEX IF NOT EXISTS idx_share_links_expires_at
                ON share_links (expires_at)
                WHERE revoked_at IS NULL
            """;

    @Test
    void schemaCreationIsIdempotent_noExceptionOnDoubleInit() {
        // SR-SQLITE-05: apply synchronous=FULL (and WAL) before running schema DDL
        // SingleConnectionDataSource keeps one open connection — all DDL sees the same DB
        assertThatNoException().isThrownBy(() -> {
            SingleConnectionDataSource ds = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
            try {
                applyPragmas(ds);
                JdbcTemplate jdbc = new JdbcTemplate(ds);

                // First schema creation
                applySchema(jdbc);

                // Second schema creation — must be a no-op, not throw
                applySchema(jdbc);
            } finally {
                ds.destroy();
            }
        });
    }

    @Test
    void schemaCreationWithExistingDataIsIdempotent_noExceptionOnReinit() {
        // Verify that IF NOT EXISTS is safe even after data has been inserted
        assertThatNoException().isThrownBy(() -> {
            SingleConnectionDataSource ds = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
            try {
                applyPragmas(ds);
                JdbcTemplate jdbc = new JdbcTemplate(ds);

                // Create schema and insert a row
                applySchema(jdbc);
                jdbc.update(
                        "INSERT INTO share_links (id, sharer_wall_id, created_at, expires_at) VALUES (?, ?, ?, ?)",
                        "abc123", "wall-1", 1000L, 2000L);

                // Re-applying DDL after data must not throw
                applySchema(jdbc);
            } finally {
                ds.destroy();
            }
        });
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static void applyPragmas(final DataSource ds) throws Exception {
        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA journal_mode=WAL");
            stmt.execute("PRAGMA synchronous=FULL");
            stmt.execute("PRAGMA foreign_keys=ON");
        }
    }

    private static void applySchema(final JdbcTemplate jdbc) {
        jdbc.execute(CREATE_TABLE_SQL);
        jdbc.execute(CREATE_INDEX_SHARER_SQL);
        jdbc.execute(CREATE_INDEX_IP_SQL);
        jdbc.execute(CREATE_INDEX_EXPIRES_SQL);
    }
}
