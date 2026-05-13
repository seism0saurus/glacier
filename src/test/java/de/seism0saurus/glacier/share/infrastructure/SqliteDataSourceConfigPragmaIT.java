package de.seism0saurus.glacier.share.infrastructure;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test verifying that {@link SqliteDataSourceConfig#shareDataSource} encodes
 * the required SQLite PRAGMA settings as JDBC URL query parameters and that they are
 * effective on connections vended by the HikariCP pool.
 *
 * <h2>Motivation</h2>
 * The sqlite-jdbc driver's {@code Statement.execute()} calls {@code sqlite3_prepare_v2()}
 * internally, which compiles only the <em>first</em> statement in a semicolon-separated
 * string. A prior implementation used {@code connectionInitSql} with four semicolon-joined
 * PRAGMAs, meaning only {@code PRAGMA journal_mode=WAL} was actually applied — the
 * remaining three were silently dropped (SR-SQLITE-05 regression).
 *
 * <p>The fix encodes all PRAGMAs as JDBC URL query parameters so they are applied
 * atomically by the driver before any SQL executes. This test exercises a real
 * {@link HikariDataSource} (not {@code SingleConnectionDataSource}) to confirm that
 * pool-vended connections carry the correct PRAGMA state.
 *
 * <h2>Test strategy</h2>
 * <ol>
 *   <li>Construct a {@link SharePersistenceProperties} with a temp-file path and a
 *       valid HMAC key, then call {@link SqliteDataSourceConfig#shareDataSource(SharePersistenceProperties)}
 *       to obtain a real HikariCP-backed {@link DataSource}.</li>
 *   <li>Acquire a pool-vended connection and query the PRAGMA state that the URL
 *       parameters should have established.</li>
 *   <li>Assert {@code PRAGMA synchronous = 2} (FULL) and {@code PRAGMA foreign_keys = 1}.</li>
 *   <li>Ensure the connection and data source are closed cleanly to avoid
 *       resource leaks in the test harness.</li>
 * </ol>
 *
 * <p>References: ADR-SQLITE-02; SR-SQLITE-05 ({@code synchronous=FULL}); OWASP A05:2021.
 */
class SqliteDataSourceConfigPragmaIT {

    /**
     * SQLite synchronous mode 2 = FULL.
     * Required for revocation durability — SR-SQLITE-05.
     */
    private static final int PRAGMA_SYNCHRONOUS_FULL = 2;

    /**
     * Minimum-length Base64 HMAC key (44 chars = 256-bit minimum; SR-SQLITE-22).
     * Value is synthetic — not a secret.
     */
    private static final String VALID_HMAC_KEY = "A".repeat(44);

    /**
     * Verifies that {@code synchronous=FULL} (mode 2) is in effect on a connection
     * vended by the HikariCP pool constructed from the JDBC URL parameters.
     *
     * <p>Arrange: SharePersistenceProperties with a temp-file path and valid HMAC key.
     * <p>Act:     call shareDataSource(), acquire a pool connection, query PRAGMA synchronous.
     * <p>Assert:  result is 2 (FULL) — SR-SQLITE-05.
     */
    @Test
    void synchronousPragmaIsFull_onHikariPoolVendedConnection(@TempDir Path tempDir) throws Exception {
        SharePersistenceProperties props = propertiesWithTempFile(tempDir, "pragma-sync.db");
        DataSource ds = new SqliteDataSourceConfig().shareDataSource(props);
        try (HikariDataSource hikari = (HikariDataSource) ds;
             Connection conn = hikari.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA synchronous")) {

            assertThat(rs.next()).isTrue();
            int synchronousValue = rs.getInt(1);
            assertThat(synchronousValue)
                    .as("PRAGMA synchronous must be FULL (= 2) on HikariCP pool connection "
                            + "when set via JDBC URL parameter (SR-SQLITE-05)")
                    .isEqualTo(PRAGMA_SYNCHRONOUS_FULL);
        }
    }

    /**
     * Verifies that {@code foreign_keys=ON} (value 1) is in effect on a connection
     * vended by the HikariCP pool.
     *
     * <p>Arrange: SharePersistenceProperties with a temp-file path and valid HMAC key.
     * <p>Act:     call shareDataSource(), acquire a pool connection, query PRAGMA foreign_keys.
     * <p>Assert:  result is 1 (enabled).
     */
    @Test
    void foreignKeysPragmaIsEnabled_onHikariPoolVendedConnection(@TempDir Path tempDir) throws Exception {
        SharePersistenceProperties props = propertiesWithTempFile(tempDir, "pragma-fk.db");
        DataSource ds = new SqliteDataSourceConfig().shareDataSource(props);
        try (HikariDataSource hikari = (HikariDataSource) ds;
             Connection conn = hikari.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA foreign_keys")) {

            assertThat(rs.next()).isTrue();
            int foreignKeysValue = rs.getInt(1);
            assertThat(foreignKeysValue)
                    .as("PRAGMA foreign_keys must be enabled (= 1) on HikariCP pool connection "
                            + "when set via JDBC URL parameter")
                    .isEqualTo(1);
        }
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    /**
     * Constructs a {@link SharePersistenceProperties} instance pointing at a named file
     * inside the given temp directory.
     *
     * <p>The {@code @SafeFilesystemPath} constraint is bypassed here by constructing
     * the bean directly — the annotation is enforced at Spring binding time only,
     * not by the getter/setter. Using an absolute temp path ensures the SQLite driver
     * creates a real file-backed database (required for WAL mode and PRAGMA assertions).
     *
     * @param tempDir  JUnit {@code @TempDir} directory
     * @param filename database filename within the temp directory
     * @return a populated {@link SharePersistenceProperties} suitable for test use
     */
    private static SharePersistenceProperties propertiesWithTempFile(
            final Path tempDir, final String filename) {
        SharePersistenceProperties props = new SharePersistenceProperties();
        props.setPath(tempDir.resolve(filename).toAbsolutePath().toString());
        props.setIpHmacKey(VALID_HMAC_KEY);
        return props;
    }
}
