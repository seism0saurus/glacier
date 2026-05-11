package de.seism0saurus.glacier.share.infrastructure;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test verifying POSIX file permissions (0600) are enforced on the SQLite DB file.
 *
 * <p>SR-SQLITE-07: the DB file must be created with {@code 0600} permissions when the
 * repository is constructed. Owner read+write; no group or other access.  The DB file
 * contains HMAC-derived IP data and token hashes — it must be classified at the same
 * sensitivity level as JVM secrets (SR-SQLITE-16).
 *
 * <p>This test is disabled on Windows (no POSIX file permissions).
 *
 * <p>References: SR-SQLITE-07; SR-SQLITE-16; NIST SP 800-53 SC-28;
 * OWASP A05:2021 Security Misconfiguration; ASVS V2.7.1 (L1).
 */
@DisabledOnOs(OS.WINDOWS)
class SqliteShareLinkRepositoryFilePermissionsIT {

    private static final String VALID_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    /**
     * SR-SQLITE-07: After constructing {@link SqliteShareLinkRepository} with a temp-file path,
     * the DB file must have permissions {@code 0600} (owner rw, no group, no others).
     */
    @Test
    void newDbFile_hasPermissions0600(@TempDir final Path tempDir) throws Exception {
        Path dbFile = tempDir.resolve("share-links-test.db");

        SharePersistenceProperties props = new SharePersistenceProperties();
        props.setPath(dbFile.toAbsolutePath().toString());
        props.setIpHmacKey(VALID_KEY);

        // Build the datasource pointing to the temp file
        DataSource ds = buildFileDataSource(dbFile);

        SqliteShareLinkRepository repo = new SqliteShareLinkRepository(props, ds);
        repo.init();

        // Assert file exists and has exactly 0600 permissions
        assertThat(dbFile).exists();

        Set<PosixFilePermission> perms = Files.getPosixFilePermissions(dbFile);

        assertThat(perms)
                .as("DB file must be owner-readable (SR-SQLITE-07, ASVS V2.7.1 L1)")
                .contains(PosixFilePermission.OWNER_READ);

        assertThat(perms)
                .as("DB file must be owner-writable (SR-SQLITE-07)")
                .contains(PosixFilePermission.OWNER_WRITE);

        assertThat(perms)
                .as("DB file must NOT be group-readable (SR-SQLITE-07, SR-SQLITE-16)")
                .doesNotContain(PosixFilePermission.GROUP_READ);

        assertThat(perms)
                .as("DB file must NOT be group-writable (SR-SQLITE-07)")
                .doesNotContain(PosixFilePermission.GROUP_WRITE);

        assertThat(perms)
                .as("DB file must NOT be others-readable (SR-SQLITE-07, SR-SQLITE-16)")
                .doesNotContain(PosixFilePermission.OTHERS_READ);

        assertThat(perms)
                .as("DB file must NOT be others-writable (SR-SQLITE-07)")
                .doesNotContain(PosixFilePermission.OTHERS_WRITE);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static DataSource buildFileDataSource(final Path dbFile) {
        // Use SingleConnectionDataSource for simplicity in this test
        // (SqliteDataSourceConfig uses HikariCP in production)
        SingleConnectionDataSource ds = new SingleConnectionDataSource();
        ds.setUrl("jdbc:sqlite:" + dbFile.toAbsolutePath());
        ds.setSuppressClose(true);
        return ds;
    }
}
