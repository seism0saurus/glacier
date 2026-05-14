package de.seism0saurus.glacier.share.infrastructure;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.sqlite.SQLiteDataSource;
import social.bigbone.MastodonClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Base64;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests verifying that a new SQLite database file is created and
 * {@code chmod}'d to POSIX permissions {@code 0600} (owner read/write only) as part
 * of the full Spring bean lifecycle.
 *
 * <p><strong>CRIT-2/F-1 fix</strong>: the {@link SqliteDbFileInitializer} BFP creates
 * and chmods the file <em>before</em> {@link SqliteDataSourceConfig} constructs the
 * {@link com.zaxxer.hikari.HikariDataSource}, closing the TOCTOU window where HikariCP's
 * {@code checkFailFast()} call would have created the file with the JVM's default umask
 * (typically {@code 0644}).
 *
 * <h2>Test coverage</h2>
 * <ul>
 *   <li>Full Spring context startup — {@code afterContextStartup_dbFile_hasOwnerReadWriteOnlyPermissions}:
 *       verifies the file is {@code 0600} after the full bean lifecycle completes.</li>
 *   <li>BFP presence check — {@code afterContextStartup_sqliteDbInitializerBean_isPresent}:
 *       confirms the {@link SqliteDbFileInitializer} BFP is registered in the context.</li>
 *   <li>Isolated mechanism test — {@code newDbFile_hasOwnerReadWriteOnlyPermissions}:
 *       verifies the OS-level create + chmod primitive works correctly on the platform.</li>
 *   <li>Functional test — {@code dbFileWith0600Permissions_isReadableAndWritableByOwner}:
 *       confirms a {@code 0600} file remains readable/writable by the owning process.</li>
 * </ul>
 *
 * <p>Tests are disabled on Windows where POSIX permissions do not apply.
 *
 * <p>References: CRIT-2/F-1; SR-SQLITE-07; NIST SP 800-53 AC-3 (Access Enforcement);
 * ISO/IEC 27002 — 8.3 Information access restriction; OWASP A05:2021 — Security Misconfiguration.
 */
@DisabledOnOs(OS.WINDOWS)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "glacier.domain=glacier.example.com",
                "glacier.cookie.secure=false",
                "glacier.fallback.enabled=true",
                "mastodon.instance=mastodon.social",
                "mastodon.handle=@glacier@mastodon.social",
                "mastodon.accessToken=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                "glacier.share.maxActivePerSharer=10",
                "glacier.share.maxActivePerIp=20",
                "glacier.share.globalMax=1000",
                "glacier.share.maxViewersPerLink=100",
                "glacier.share.sweepIntervalMs=999999999",
        }
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SqliteShareLinkRepositoryFilePermissionsIT {

    /**
     * Shared temp directory — must be {@code static} so it is created before
     * {@link DynamicPropertySource} registration (which happens before instance fields).
     */
    @TempDir
    static Path tempDir;

    @MockitoBean
    MastodonClient mastodonClient;

    @Autowired
    ApplicationContext applicationContext;

    /**
     * Registers the SQLite DB path and HMAC key as dynamic Spring properties so the
     * full {@link SqliteDbFileInitializer} → {@link SqliteDataSourceConfig} →
     * {@link SqliteShareLinkRepository} bean lifecycle is exercised.
     *
     * <p>The DB file does NOT exist when this method runs — {@link SqliteDbFileInitializer}
     * must create it with {@code 0600} permissions before HikariCP connects.
     */
    @DynamicPropertySource
    static void sqliteProperties(final DynamicPropertyRegistry registry) {
        Path dbFile = tempDir.resolve("spring-context-share-links.db");
        registry.add("glacier.share.db.path", dbFile::toString);
        // 256-bit all-zero key in Base64 — synthetic test key, not a secret (SR-SQLITE-22)
        registry.add("glacier.share.db.ip-hmac-key",
                () -> Base64.getEncoder().encodeToString(new byte[32]));
    }

    // -------------------------------------------------------------------------
    // Full Spring context tests — verify CRIT-2/F-1 fix
    // -------------------------------------------------------------------------

    /**
     * CRIT-2/F-1 / SR-SQLITE-07: after a full Spring context startup with
     * {@code glacier.share.db.path} pointing to a non-existent file in a temp dir,
     * the file must exist and have exactly {@code 0600} permissions.
     *
     * <p>This verifies that {@link SqliteDbFileInitializer} (BFP) creates and chmods the
     * file <em>before</em> HikariCP opens its first connection — not merely that
     * {@code @PostConstruct} sets permissions after the JDBC connection is already open.
     */
    @Test
    void afterContextStartup_dbFile_hasOwnerReadWriteOnlyPermissions() throws IOException {
        Path dbFile = tempDir.resolve("spring-context-share-links.db");

        assertThat(dbFile)
                .as("DB file must have been created by SqliteDbFileInitializer BFP before "
                        + "HikariCP opened a connection (CRIT-2/F-1; SR-SQLITE-07)")
                .exists();

        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(dbFile);

        assertThat(permissions)
                .as("DB file must have POSIX 0600 permissions after full Spring context startup "
                        + "(CRIT-2/F-1; SR-SQLITE-07)")
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
     * CRIT-2/F-1: the {@link SqliteDbFileInitializer} bean must be present in the
     * application context when {@code glacier.share.db.path} is set.
     */
    @Test
    void afterContextStartup_sqliteDbInitializerBean_isPresent() {
        assertThat(applicationContext.getBeansOfType(SqliteDbFileInitializer.class))
                .as("SqliteDbFileInitializer BFP must be registered when glacier.share.db.path is set "
                        + "(CRIT-2/F-1)")
                .isNotEmpty();
    }

    // -------------------------------------------------------------------------
    // Isolated mechanism tests (no Spring context required)
    // -------------------------------------------------------------------------

    @TempDir
    Path isolatedTempDir;

    /**
     * SR-SQLITE-07: a new SQLite file created with {@link PosixFilePermissions#asFileAttribute}
     * must have exactly {@code 0600} permissions on the target platform.
     *
     * <p>This isolated test verifies the OS-level primitive independently of the Spring
     * bean lifecycle.
     */
    @Test
    void newDbFile_hasOwnerReadWriteOnlyPermissions() throws IOException {
        Path dbFile = isolatedTempDir.resolve("share-links.db");

        createAndApplySecurePermissions(dbFile);

        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(dbFile);

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
     * Verifies that a SQLite database created with {@code 0600} permissions remains
     * functional — the owning process can read and write it.
     */
    @Test
    void dbFileWith0600Permissions_isReadableAndWritableByOwner() throws Exception {
        Path dbFile = isolatedTempDir.resolve("share-links-rw.db");
        createAndApplySecurePermissions(dbFile);

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
     * Creates {@code path} with {@code 0600} permissions if it does not exist, or
     * {@code chmod}s it to {@code 0600} if it does.
     */
    private static void createAndApplySecurePermissions(final Path path) throws IOException {
        if (!Files.exists(path)) {
            Files.createFile(path,
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } else {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        }
    }
}
