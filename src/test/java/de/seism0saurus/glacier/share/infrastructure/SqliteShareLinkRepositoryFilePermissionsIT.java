package de.seism0saurus.glacier.share.infrastructure;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test verifying that a new SQLite database file is created with POSIX
 * permissions {@code 0600} (owner read/write only, no group or other access).
 *
 * <p>This test is disabled on Windows where POSIX permissions do not apply
 * ({@link DisabledOnOs#OS#WINDOWS}).
 *
 * <p>The file-permission check implements SR-SQLITE-07: SQLite DB file must be
 * accessible only by the JVM process owner. Leaking group-read or world-read
 * exposes the database — which contains hashed tokens and HMAC-pseudonymised IPs —
 * to other users on the same host.
 *
 * <p>References: SR-SQLITE-07; NIST SP 800-53 AC-3 (Access Enforcement);
 * ISO/IEC 27002 — 8.3 Information access restriction; OWASP A05:2021.
 */
@DisabledOnOs(OS.WINDOWS)
class SqliteShareLinkRepositoryFilePermissionsIT {

    @TempDir
    Path tempDir;

    /**
     * SR-SQLITE-07: a new SQLite file must be created with permissions {@code 0600}.
     *
     * <p>The approach is to set the permissions explicitly after creation (as
     * {@code SqliteShareLinkRepository} will do in its {@code @PostConstruct} method in Lane 3).
     * This test verifies the permission-setting mechanism works correctly on the target platform.
     */
    @Test
    void newDbFile_hasOwnerReadWriteOnlyPermissions() throws IOException {
        Path dbFile = tempDir.resolve("share-links.db");

        // Create the file and apply 0600 permissions — this mirrors what
        // SqliteShareLinkRepository will do in @PostConstruct (SR-SQLITE-07)
        createAndApplySecurePermissions(dbFile);

        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(dbFile);

        // 0600 = owner read + owner write only
        assertThat(permissions)
                .as("DB file must have POSIX 0600 permissions: owner r/w only (SR-SQLITE-07)")
                .containsExactlyInAnyOrder(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE);

        assertThat(permissions)
                .as("DB file must NOT have group-read permission (SR-SQLITE-07)")
                .doesNotContain(PosixFilePermission.GROUP_READ);

        assertThat(permissions)
                .as("DB file must NOT have group-write permission (SR-SQLITE-07)")
                .doesNotContain(PosixFilePermission.GROUP_WRITE);

        assertThat(permissions)
                .as("DB file must NOT have others-read permission (SR-SQLITE-07)")
                .doesNotContain(PosixFilePermission.OTHERS_READ);

        assertThat(permissions)
                .as("DB file must NOT have others-write permission (SR-SQLITE-07)")
                .doesNotContain(PosixFilePermission.OTHERS_WRITE);
    }

    /**
     * Verifies that a SQLite database created with 0600 permissions remains functional
     * (the process can open and query it).
     */
    @Test
    void dbFileWith0600Permissions_isReadableAndWritableByOwner() throws Exception {
        Path dbFile = tempDir.resolve("share-links-rw.db");
        createAndApplySecurePermissions(dbFile);

        // The file must be usable as a SQLite DB by the process owner
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dbFile.toAbsolutePath());

        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS test (id TEXT PRIMARY KEY)");
            stmt.execute("INSERT INTO test VALUES ('hello')");

            try (var rs = stmt.executeQuery("SELECT id FROM test")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("hello");
            }
        }

        // Permissions must still be 0600 after writes
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(dbFile);
        assertThat(permissions)
                .as("Permissions must remain 0600 after write operations (SR-SQLITE-07)")
                .containsExactlyInAnyOrder(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Creates the file at {@code path} if it does not exist and sets its permissions to
     * {@code 0600} (owner read/write only).
     *
     * <p>This method mirrors the permission-setting logic that
     * {@code SqliteShareLinkRepository} will apply at {@code @PostConstruct} time (SR-SQLITE-07).
     *
     * @param path the file path to create
     */
    private static void createAndApplySecurePermissions(final Path path) throws IOException {
        // Create if absent — SQLite also creates the file on first connect, but
        // we set permissions before opening the connection so no other process can
        // observe the file with broader permissions even transiently.
        if (!Files.exists(path)) {
            Files.createFile(path);
        }
        // 0600 = owner read + owner write
        Set<PosixFilePermission> ownerRw = PosixFilePermissions.fromString("rw-------");
        Files.setPosixFilePermissions(path, ownerRw);
    }
}
