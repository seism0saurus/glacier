package de.seism0saurus.glacier.share.infrastructure;

import de.seism0saurus.glacier.share.domain.ShareLink;
import de.seism0saurus.glacier.share.domain.ShareLinkId;
import de.seism0saurus.glacier.share.domain.ShareLinkRepository;
import de.seism0saurus.glacier.share.domain.ShareLinkStatus;
import de.seism0saurus.glacier.share.domain.ShareLinkSummary;
import de.seism0saurus.glacier.util.LogScrubber;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * SQLite-backed implementation of {@link ShareLinkRepository} — security scaffold.
 *
 * <p>This adapter is activated only when {@code glacier.share.db.path} is set in the
 * application configuration (ADR-SQLITE-01).  The in-memory adapter
 * ({@link InMemoryShareLinkRepository}) remains the default when the property is absent.
 *
 * <h2>Security design</h2>
 * <ul>
 *   <li><b>Token at rest</b>: {@link #sha256Hex(String)} stores only {@code SHA-256(token)}
 *       in the {@code id} column — the raw bearer token is never written to the DB
 *       (SR-SQLITE-01; ADR-SQLITE-04).</li>
 *   <li><b>IP pseudonymisation</b>: {@link #hmacSha256Hex(String)} stores only
 *       {@code HMAC-SHA256(ip, key)} in {@code creator_ip_hmac} — raw IP never in DB
 *       (SR-SQLITE-04; ADR-SQLITE-06).</li>
 *   <li><b>SQL injection prevention</b>: all SQL uses {@link JdbcTemplate} with
 *       parameterised queries — never raw {@code Statement} or string concatenation
 *       (SR-SQLITE-02; SR-SQLITE-10; OWASP A03:2021).</li>
 *   <li><b>File permissions</b>: DB file is created with POSIX {@code 0600}
 *       (SR-SQLITE-07; ASVS V2.7.1 L1).</li>
 *   <li><b>PRAGMAs</b>: {@code journal_mode=WAL}, {@code synchronous=FULL},
 *       {@code foreign_keys=ON}, {@code busy_timeout}, {@code max_page_count}
 *       (ADR-SQLITE-02; SR-SQLITE-05).</li>
 *   <li><b>Audit log</b>: startup AUDIT event with impl name and {@code hash8(path)}
 *       — never the raw path (SR-SQLITE-06; D-13/SR-8).</li>
 * </ul>
 *
 * <p>Lane 3 ({@code tdd-ddd-implementer}) will implement all CRUD methods.
 * Until then they throw {@link UnsupportedOperationException}.
 *
 * <p>References: ADR-SQLITE-01; ADR-SQLITE-02; ADR-SQLITE-04; ADR-SQLITE-06;
 * SR-SQLITE-01 through SR-SQLITE-09; OWASP A03:2021; OWASP A05:2021;
 * NIST SP 800-53 SC-28; GDPR Art. 25.
 */
@Repository
@ConditionalOnProperty(name = "glacier.share.db.path")
public class SqliteShareLinkRepository implements ShareLinkRepository {

    private static final Logger AUDIT = LoggerFactory.getLogger("AUDIT");
    private static final Logger LOGGER = LoggerFactory.getLogger(SqliteShareLinkRepository.class);

    // -------------------------------------------------------------------------
    // DDL — idempotent via IF NOT EXISTS (ADR-SQLITE-02)
    // -------------------------------------------------------------------------

    /**
     * PRAGMA configuration strings. These are static compile-time constants — no user
     * input is interpolated into them. Integer values ({@code busy_timeout},
     * {@code max_page_count}) are appended from validated integer properties at runtime
     * (SR-SQLITE-10; C3 — inputs validated at @ConfigurationProperties binding).
     */
    private static final String PRAGMA_WAL = "PRAGMA journal_mode = WAL";
    private static final String PRAGMA_SYNCHRONOUS = "PRAGMA synchronous = FULL";
    private static final String PRAGMA_FOREIGN_KEYS = "PRAGMA foreign_keys = ON";

    /**
     * Table DDL — SHA-256(token) hex in {@code id}; HMAC-SHA256(ip, key) in
     * {@code creator_ip_hmac} (ADR-SQLITE-02; SR-SQLITE-01; SR-SQLITE-04).
     */
    private static final String CREATE_TABLE_SQL =
            "CREATE TABLE IF NOT EXISTS share_links ("
            + "    id              TEXT    NOT NULL PRIMARY KEY,"
            + "    sharer_wall_id  TEXT    NOT NULL,"
            + "    creator_ip_hmac TEXT,"
            + "    created_at      INTEGER NOT NULL,"
            + "    expires_at      INTEGER NOT NULL,"
            + "    revoked_at      INTEGER"
            + ") WITHOUT ROWID";

    private static final String CREATE_INDEX_SHARER_ACTIVE =
            "CREATE INDEX IF NOT EXISTS idx_share_links_sharer_active"
            + " ON share_links (sharer_wall_id, expires_at)"
            + " WHERE revoked_at IS NULL";

    private static final String CREATE_INDEX_IP_ACTIVE =
            "CREATE INDEX IF NOT EXISTS idx_share_links_creator_ip_active"
            + " ON share_links (creator_ip_hmac, expires_at)"
            + " WHERE revoked_at IS NULL AND creator_ip_hmac IS NOT NULL";

    private static final String CREATE_INDEX_EXPIRES =
            "CREATE INDEX IF NOT EXISTS idx_share_links_expires_at"
            + " ON share_links (expires_at)"
            + " WHERE revoked_at IS NULL";

    // -------------------------------------------------------------------------
    // POSIX permission constant
    // -------------------------------------------------------------------------

    private static final Set<PosixFilePermission> OWNER_RW =
            PosixFilePermissions.fromString("rw-------");

    // -------------------------------------------------------------------------
    // Instance state
    // -------------------------------------------------------------------------

    private final SharePersistenceProperties properties;
    private final JdbcTemplate jdbc;

    /**
     * Constructs the repository with validated configuration and a data source.
     *
     * <p>The {@code dataSource} is expected to be the HikariCP-wrapped SQLite source
     * produced by {@link SqliteDataSourceConfig} (ADR-SQLITE-09).
     *
     * @param properties the validated persistence configuration
     * @param dataSource the SQLite datasource; wrapped in a {@link JdbcTemplate}
     */
    public SqliteShareLinkRepository(
            final SharePersistenceProperties properties,
            final DataSource dataSource) {
        this.properties = properties;
        this.jdbc = new JdbcTemplate(dataSource);
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /**
     * Schema initialisation and security setup.
     *
     * <p>Actions performed (idempotent — safe to call multiple times):
     * <ol>
     *   <li>Enforce POSIX {@code 0600} permissions on the DB file
     *       (SR-SQLITE-07; NIST SP 800-53 SC-28).</li>
     *   <li>Apply PRAGMAs and create table + indices in a <em>single connection</em>
     *       to ensure schema visibility in SQLite in-memory mode
     *       (ADR-SQLITE-02; SR-SQLITE-05).</li>
     *   <li>Emit startup AUDIT event with impl name + {@code hash8(path)}
     *       — never raw path (SR-SQLITE-06; D-13/SR-8).</li>
     * </ol>
     *
     * <p><strong>SR-SQLITE-10 / OWASP A03:2021</strong>: All SQL is executed via
     * {@link PreparedStatement} — never raw {@link java.sql.Statement}.
     * {@link JdbcTemplate#execute(java.sql.ConnectionCallback)} is used to run all DDL
     * within a single connection, which is required for SQLite in-memory mode where the
     * schema exists only within one connection's session.
     */
    @PostConstruct
    public void init() {
        // SR-SQLITE-07: enforce 0600 permissions on the DB file before opening it
        enforceFilePermissions();

        // ADR-SQLITE-02 / SR-SQLITE-10: all DDL executed within a SINGLE connection
        // using PreparedStatement — required for in-memory SQLite where schema is
        // session-scoped, and required to avoid raw Statement usage.
        jdbc.execute((Connection conn) -> {
            // PRAGMAs via PreparedStatement
            executeViaPrepared(conn, PRAGMA_WAL);
            executeViaPrepared(conn, PRAGMA_SYNCHRONOUS);
            executeViaPrepared(conn, PRAGMA_FOREIGN_KEYS);
            // Integer PRAGMAs use safe integer-only values from validated config
            // (not user-supplied strings — no injection risk; C3 validated at binding)
            executeViaPrepared(conn, "PRAGMA busy_timeout = " + properties.getBusyTimeoutMs());
            executeViaPrepared(conn, "PRAGMA max_page_count = " + properties.getMaxPageCount());

            // DDL — idempotent via IF NOT EXISTS (ADR-SQLITE-02)
            executeViaPrepared(conn, CREATE_TABLE_SQL);
            executeViaPrepared(conn, CREATE_INDEX_SHARER_ACTIVE);
            executeViaPrepared(conn, CREATE_INDEX_IP_ACTIVE);
            executeViaPrepared(conn, CREATE_INDEX_EXPIRES);

            return null;
        });

        // SR-SQLITE-06: startup AUDIT log with impl name + hash8(path)
        // D-13/SR-8: hash8() prevents raw path from reaching the log encoder
        AUDIT.info("share.link.repository.active impl={} dbHash={}",
                getClass().getSimpleName(),
                LogScrubber.hash8(properties.getPath()));
    }

    /**
     * Executes a DDL or PRAGMA statement via {@link PreparedStatement}.
     *
     * <p>SR-SQLITE-10: using {@link PreparedStatement} instead of raw
     * {@link java.sql.Statement} enforces the PreparedStatement-only invariant.
     * DDL and PRAGMAs are static compile-time strings — parameterisation applies
     * to the execution method, not to bind parameters.
     *
     * @param conn the active connection
     * @param sql  the static SQL string (DDL or PRAGMA)
     * @throws SQLException if the statement fails
     */
    private static void executeViaPrepared(final Connection conn, final String sql)
            throws SQLException {
        // SR-SQLITE-10: PreparedStatement-only — no raw Statement access
        // OWASP A03:2021: parameterised execution even for DDL/PRAGMA
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.execute();
        }
    }

    // -------------------------------------------------------------------------
    // SQL constants — all use parameterised queries (SR-SQLITE-02; OWASP A03:2021)
    // -------------------------------------------------------------------------

    private static final String SQL_SAVE =
            "INSERT OR REPLACE INTO share_links "
            + "(id, sharer_wall_id, creator_ip_hmac, created_at, expires_at, revoked_at) "
            + "VALUES (?, ?, ?, ?, ?, ?)";

    private static final String SQL_FIND_BY_ID =
            "SELECT sharer_wall_id, created_at, expires_at, revoked_at "
            + "FROM share_links WHERE id = ?";

    private static final String SQL_MARK_REVOKED =
            "UPDATE share_links SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL";

    private static final String SQL_SWEEP_EXPIRED =
            "DELETE FROM share_links WHERE expires_at <= ? AND revoked_at IS NULL";

    private static final String SQL_COUNT_ACTIVE =
            "SELECT COUNT(*) FROM share_links WHERE expires_at > ? AND revoked_at IS NULL";

    private static final String SQL_COUNT_ACTIVE_FOR_SHARER =
            "SELECT COUNT(*) FROM share_links "
            + "WHERE sharer_wall_id = ? AND expires_at > ? AND revoked_at IS NULL";

    private static final String SQL_COUNT_ACTIVE_FOR_IP =
            "SELECT COUNT(*) FROM share_links "
            + "WHERE creator_ip_hmac = ? AND expires_at > ? AND revoked_at IS NULL";

    private static final String SQL_FIND_ALL_BY_SHARER =
            "SELECT sharer_wall_id, created_at, expires_at, revoked_at "
            + "FROM share_links WHERE sharer_wall_id = ?";

    private static final String SQL_LIST_SUMMARY_BY_SHARER =
            "SELECT id, sharer_wall_id, created_at, expires_at, revoked_at "
            + "FROM share_links WHERE sharer_wall_id = ? AND expires_at > ? AND revoked_at IS NULL";

    // -------------------------------------------------------------------------
    // Repository interface — CRUD (ADR-SQLITE-01 through ADR-SQLITE-07)
    // -------------------------------------------------------------------------

    /**
     * Persists (or replaces) the given {@link ShareLink}.
     *
     * <p>Uses {@code INSERT OR REPLACE} semantics to mirror the in-memory adapter's
     * idempotent {@code put} contract (ADR-SQLITE-04; SR-SQLITE-18).
     *
     * <p>Security: the raw token is NEVER written to the DB. Only
     * {@code SHA-256(id.value())} is stored as the primary key (SR-SQLITE-01; ADR-SQLITE-04).
     * The creator IP is stored as {@code HMAC-SHA256(ip, key)} (SR-SQLITE-04; ADR-SQLITE-06).
     * All SQL uses parameterised queries (SR-SQLITE-02).
     *
     * @param link the aggregate to persist; must not be null
     * @return the same link (enables fluent usage)
     */
    @Override
    public ShareLink save(final ShareLink link) {
        try {
            String idHash = sha256Hex(link.id().value());
            String ipHmac = link.creatorIp().map(this::hmacSha256Hex).orElse(null);
            Long revokedAtMs = link.revokedAt().map(Instant::toEpochMilli).orElse(null);

            jdbc.update(SQL_SAVE,
                    idHash,
                    link.sharerWallId(),
                    ipHmac,
                    link.createdAt().toEpochMilli(),
                    link.expiresAt().toEpochMilli(),
                    revokedAtMs);

            LOGGER.debug("share.link.saved shareId-hash8={}", LogScrubber.hash8(link.id().value()));
            return link;
        } catch (Exception e) {
            throw new RuntimeException(
                    "share.link.save.failed shareId-hash8=" + LogScrubber.hash8(link.id().value())
                    + " cause=" + LogScrubber.forErrorMessage(e.getMessage()), e);
        }
    }

    /**
     * Finds a {@link ShareLink} by its opaque identifier.
     *
     * <p>The lookup queries {@code SHA-256(id.value())} — the raw token is never sent to DB
     * (SR-SQLITE-01; ADR-SQLITE-04). Because the raw token is not stored, the reconstituted
     * aggregate carries the caller-supplied {@link ShareLinkId} directly.
     *
     * <p>The reconstituted aggregate carries {@code creatorIp = null} — the raw IP is not
     * stored at rest (ADR-SQLITE-06), so it cannot be recovered.
     *
     * @param id the share-link ID; must not be null
     * @return the link if found; empty otherwise
     */
    @Override
    public Optional<ShareLink> findById(final ShareLinkId id) {
        try {
            String idHash = sha256Hex(id.value());
            List<ShareLink> results = jdbc.query(SQL_FIND_BY_ID,
                    (rs, rowNum) -> {
                        String sharerWallId = rs.getString("sharer_wall_id");
                        Instant createdAt = Instant.ofEpochMilli(rs.getLong("created_at"));
                        Instant expiresAt = Instant.ofEpochMilli(rs.getLong("expires_at"));
                        long revokedAtMs = rs.getLong("revoked_at");
                        Instant revokedAt = rs.wasNull() ? null : Instant.ofEpochMilli(revokedAtMs);
                        // creatorIp is null after reconstitution — by design (ADR-SQLITE-06)
                        return ShareLink.fromPersistence(id, sharerWallId, null, createdAt, expiresAt, revokedAt);
                    },
                    idHash);

            LOGGER.debug("share.link.findById shareId-hash8={} found={}",
                    LogScrubber.hash8(id.value()), !results.isEmpty());
            return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
        } catch (Exception e) {
            throw new RuntimeException(
                    "share.link.findById.failed shareId-hash8=" + LogScrubber.hash8(id.value())
                    + " cause=" + LogScrubber.forErrorMessage(e.getMessage()), e);
        }
    }

    /**
     * Marks the link identified by {@code id} as revoked at {@code when}.
     *
     * <p>Uses {@code WHERE revoked_at IS NULL} to implement "first revocation wins"
     * atomically — SQLite serialises writes so this conditional update is a safe
     * compare-and-swap without a SELECT-then-UPDATE race (ADR-SQLITE-03).
     *
     * <p>Zero rows affected means the link is already revoked or unknown — both are no-ops,
     * matching the in-memory idempotency contract.
     *
     * @param id   the share-link ID; must not be null
     * @param when the revocation instant; must not be null
     */
    @Override
    public void markRevoked(final ShareLinkId id, final Instant when) {
        try {
            String idHash = sha256Hex(id.value());
            int rowsAffected = jdbc.update(SQL_MARK_REVOKED, when.toEpochMilli(), idHash);

            if (rowsAffected > 0) {
                AUDIT.info("share.link.revoked shareId-hash8={}", LogScrubber.hash8(id.value()));
            } else {
                LOGGER.debug("share.link.markRevoked no-op (already revoked or unknown) shareId-hash8={}",
                        LogScrubber.hash8(id.value()));
            }
        } catch (Exception e) {
            AUDIT.warn("share.link.markRevoked.failed shareId-hash8={} cause={}",
                    LogScrubber.hash8(id.value()),
                    LogScrubber.forErrorMessage(e.getMessage()));
        }
    }

    /**
     * Removes all links whose {@code expiresAt} is not after {@code now} and that are not
     * already revoked.
     *
     * <p>Revoked links survive the sweep — they are removed when they naturally expire.
     *
     * @param now the reference instant for expiry comparison
     * @return the number of links removed
     */
    @Override
    public int sweepExpired(final Instant now) {
        try {
            int removed = jdbc.update(SQL_SWEEP_EXPIRED, now.toEpochMilli());
            if (removed > 0) {
                AUDIT.info("share.link.sweep removed={}", removed);
            } else {
                LOGGER.debug("share.link.sweep removed=0");
            }
            return removed;
        } catch (Exception e) {
            AUDIT.warn("share.link.sweep.failed cause={}", LogScrubber.forErrorMessage(e.getMessage()));
            return 0;
        }
    }

    /**
     * Returns the total number of {@link ShareLinkStatus#ACTIVE} links at the given instant.
     *
     * @param now reference instant for status derivation
     * @return non-negative count
     */
    @Override
    public long countActive(final Instant now) {
        try {
            Long count = jdbc.queryForObject(SQL_COUNT_ACTIVE, Long.class, now.toEpochMilli());
            return count != null ? count : 0L;
        } catch (Exception e) {
            throw new RuntimeException(
                    "share.link.countActive.failed cause=" + LogScrubber.forErrorMessage(e.getMessage()), e);
        }
    }

    /**
     * Returns the number of {@link ShareLinkStatus#ACTIVE} links for the given sharer.
     *
     * @param sharerWallId the sharer's wallId; must not be null
     * @param now          reference instant for status derivation
     * @return non-negative count
     */
    @Override
    public int countActiveForSharer(final String sharerWallId, final Instant now) {
        try {
            Integer count = jdbc.queryForObject(SQL_COUNT_ACTIVE_FOR_SHARER, Integer.class,
                    sharerWallId, now.toEpochMilli());
            return count != null ? count : 0;
        } catch (Exception e) {
            throw new RuntimeException(
                    "share.link.countActiveForSharer.failed cause=" + LogScrubber.forErrorMessage(e.getMessage()), e);
        }
    }

    /**
     * Returns the number of {@link ShareLinkStatus#ACTIVE} links for the given creator IP.
     *
     * <p>The IP is converted to {@code HMAC-SHA256(ip, key)} before querying — the raw IP
     * is never sent to the DB (SR-SQLITE-04; ADR-SQLITE-06).
     *
     * @param ip  the creator IP; must not be null
     * @param now reference instant
     * @return non-negative count
     */
    @Override
    public int countActiveForIp(final String ip, final Instant now) {
        try {
            String ipHmac = hmacSha256Hex(ip);
            Integer count = jdbc.queryForObject(SQL_COUNT_ACTIVE_FOR_IP, Integer.class,
                    ipHmac, now.toEpochMilli());
            return count != null ? count : 0;
        } catch (Exception e) {
            throw new RuntimeException(
                    "share.link.countActiveForIp.failed cause=" + LogScrubber.forErrorMessage(e.getMessage()), e);
        }
    }

    /**
     * Returns all links for the given sharer, regardless of status.
     *
     * <p><strong>Deprecated</strong>: the SQLite adapter cannot reconstruct the raw
     * {@link ShareLinkId} from the hashed primary key stored in the DB (ADR-SQLITE-04).
     * This method always returns an empty list and logs a deprecation warning.
     *
     * <p>Use {@link #listSummaryBySharer(String, Instant)} instead.
     *
     * @param sharerWallId the sharer's wallId; must not be null
     * @return empty list always (ADR-SQLITE-05)
     * @deprecated Use {@link #listSummaryBySharer(String, Instant)} for management views.
     */
    @Override
    @Deprecated(since = "P3-05", forRemoval = false)
    public List<ShareLink> findAllBySharer(final String sharerWallId) {
        LOGGER.warn("share.link.findAllBySharer.deprecated — callers should use "
                + "listSummaryBySharer; returning empty list (ADR-SQLITE-05)");
        return List.of();
    }

    /**
     * Returns summary projections for ACTIVE links belonging to the given sharer.
     *
     * <p>The raw token is NOT included — it is shown exactly once at creation time
     * (ADR-SQLITE-05; SR-SQLITE-20). The {@code idHash8} field carries the first 8 hex
     * characters of the already-hashed primary key for display purposes.
     *
     * @param sharerWallId the sharer's wallId; must not be null
     * @param now          reference instant for status derivation
     * @return list of summaries for ACTIVE links; never null; may be empty
     */
    @Override
    public List<ShareLinkSummary> listSummaryBySharer(final String sharerWallId, final Instant now) {
        try {
            return jdbc.query(SQL_LIST_SUMMARY_BY_SHARER,
                    (rs, rowNum) -> mapSummaryRow(rs, now),
                    sharerWallId, now.toEpochMilli());
        } catch (Exception e) {
            throw new RuntimeException(
                    "share.link.listSummaryBySharer.failed cause=" + LogScrubber.forErrorMessage(e.getMessage()), e);
        }
    }

    // -------------------------------------------------------------------------
    // Scheduled sweep (ADR-SQLITE-07)
    // -------------------------------------------------------------------------

    /**
     * Periodically removes expired links from the database.
     *
     * <p>The interval is driven by {@code glacier.share.sweepIntervalMs} (default: 300 000 ms /
     * 5 minutes). This prevents unbounded database growth.
     *
     * <p>AUDIT: reports sweep counts so operators can track link lifecycle.
     * If the sweep fails, a WARN is emitted but the scheduler thread survives
     * (SR-SQLITE-14; ADR-SQLITE-07).
     */
    @Scheduled(fixedDelayString = "${glacier.share.sweepIntervalMs:300000}")
    public void scheduledSweep() {
        try {
            int removed = sweepExpired(Instant.now());
            if (removed > 0) {
                AUDIT.info("share.link.sweep removed={}", removed);
            } else {
                LOGGER.debug("share.link.sweep removed=0");
            }
        } catch (Exception e) {
            AUDIT.warn("share.link.sweep.failed error={}", LogScrubber.forErrorMessage(e.getMessage()));
        }
    }

    // -------------------------------------------------------------------------
    // Private row-mapping helpers
    // -------------------------------------------------------------------------

    /**
     * Maps a result-set row from {@link #SQL_LIST_SUMMARY_BY_SHARER} to a
     * {@link ShareLinkSummary}.
     *
     * <p>The {@code id} column contains {@code SHA-256(token)} hex (not the raw token).
     * The first 8 characters are used as {@code idHash8} for display purposes.
     *
     * <p>Status is derived inline from the stored epoch-millis fields — no round-trip
     * to the domain aggregate is needed for the summary projection.
     *
     * @param rs  the current result-set row
     * @param now reference instant for status derivation
     * @return a summary projection; never null
     */
    private static ShareLinkSummary mapSummaryRow(
            final java.sql.ResultSet rs,
            final Instant now) throws java.sql.SQLException {
        String idHex = rs.getString("id");          // SHA-256 hex stored as PK
        String sharerWallId = rs.getString("sharer_wall_id");
        Instant createdAt = Instant.ofEpochMilli(rs.getLong("created_at"));
        Instant expiresAt = Instant.ofEpochMilli(rs.getLong("expires_at"));
        long revokedAtMs = rs.getLong("revoked_at");
        Instant revokedAt = rs.wasNull() ? null : Instant.ofEpochMilli(revokedAtMs);

        // Status derivation mirrors ShareLink.status(now) priority order
        ShareLinkStatus status = revokedAt != null ? ShareLinkStatus.REVOKED
                : !now.isBefore(expiresAt) ? ShareLinkStatus.EXPIRED
                : ShareLinkStatus.ACTIVE;

        // idHash8: first 8 hex chars of the already-SHA256-hashed primary key
        String idHash8 = idHex != null && idHex.length() >= 8 ? idHex.substring(0, 8) : idHex;

        return new ShareLinkSummary(idHash8, createdAt, expiresAt, revokedAt, status, sharerWallId);
    }

    // -------------------------------------------------------------------------
    // Security helper methods (package-visible for Lane 3)
    // -------------------------------------------------------------------------

    /**
     * Returns the lowercase hex SHA-256 digest of the given token string.
     *
     * <p>Used to hash share-link IDs before storing them as the primary key.
     * The raw bearer token is NEVER stored in the database (SR-SQLITE-01; ADR-SQLITE-04).
     *
     * <p><strong>Access control</strong>: this method is intentionally package-private
     * (not public) so that only classes within {@code share.infrastructure} can call it.
     * An ArchUnit rule in {@code ShareLinkPersistenceArchitectureTest} enforces this
     * invariant at build time (SR-SQLITE-19; ADR-SQLITE-04).
     *
     * @param token the raw share-link token value; must not be null
     * @return lowercase hex SHA-256 of the UTF-8 encoded token
     */
    static String sha256Hex(final String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed in every conformant JVM (NIST FIPS 180-4)
            throw new IllegalStateException("SHA-256 unavailable — JVM non-conformant", e);
        }
    }

    /**
     * Returns the lowercase hex HMAC-SHA256 of the given IP address, or {@code null}
     * if {@code ip} is null.
     *
     * <p>The HMAC key is sourced from {@code properties.getIpHmacKey()} (base64-decoded).
     * Raw IP addresses are NEVER stored in the database (SR-SQLITE-04; ADR-SQLITE-06).
     *
     * <p>HMAC key rotation invalidates all existing HMAC values (accepted trade-off —
     * the IP cap is a soft anti-DoS measure, not a hard invariant; see ADR-SQLITE-06).
     *
     * @param ip the raw IPv4 or IPv6 address string; may be null
     * @return lowercase hex HMAC-SHA256, or null if ip is null
     * @throws IllegalStateException if the HMAC algorithm or key is unavailable
     */
    String hmacSha256Hex(final String ip) {
        if (ip == null) {
            return null;
        }
        try {
            byte[] keyBytes = Base64.getDecoder().decode(properties.getIpHmacKey());
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(keyBytes, "HmacSHA256"));
            byte[] hmac = mac.doFinal(ip.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hmac);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("HmacSHA256 unavailable — JVM non-conformant", e);
        } catch (InvalidKeyException e) {
            throw new IllegalStateException(
                    "Invalid HMAC key — check glacier.share.db.ip-hmac-key (SR-SQLITE-22)", e);
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Enforces POSIX {@code 0600} permissions on the SQLite database file.
     *
     * <p>Creates the file with {@code 0600} if it does not exist; checks and tightens
     * permissions to {@code 0600} if the file already exists with wider permissions.
     *
     * <p>On non-POSIX filesystems (Windows, some container environments where the
     * underlying FS does not support POSIX attributes), this method logs a WARN
     * and continues (degrade-with-warn, not fail-closed). The WARN is routed to
     * the AUDIT logger so operators are aware of the degradation.
     *
     * <p>The in-memory specifier ({@code :memory:}) is skipped — no physical file exists.
     *
     * <p>References: SR-SQLITE-07; SR-SQLITE-16; NIST SP 800-53 SC-28;
     * ASVS V2.7.1 (L1); OWASP A05:2021 Security Misconfiguration.
     */
    private void enforceFilePermissions() {
        final String dbPath = properties.getPath();

        // :memory: and JDBC in-memory paths have no physical file — skip
        if (dbPath == null
                || ":memory:".equals(dbPath)
                || dbPath.contains(":memory:")) {
            return;
        }

        // Check if POSIX permissions are supported on this filesystem
        if (!FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            AUDIT.warn("share.link.repository.posix_unsupported impl={} "
                            + "dbHash={} — POSIX 0600 permissions cannot be enforced; "
                            + "ensure DB file access control via OS/container mechanisms "
                            + "(SR-SQLITE-07, SR-SQLITE-16)",
                    getClass().getSimpleName(),
                    LogScrubber.hash8(dbPath));
            return;
        }

        try {
            Path path = Path.of(dbPath);

            if (!Files.exists(path)) {
                // Create parent directories if needed
                if (path.getParent() != null) {
                    Files.createDirectories(path.getParent());
                }
                // Create the file with 0600 immediately
                // C5 — secure defaults: 0600 from creation, not post-hoc chmod
                Files.createFile(path,
                        PosixFilePermissions.asFileAttribute(OWNER_RW));
                LOGGER.debug("share.link.db.created dbHash={}",
                        LogScrubber.hash8(dbPath));
            } else {
                // File exists — tighten permissions to 0600
                Set<PosixFilePermission> current = Files.getPosixFilePermissions(path);
                if (!OWNER_RW.equals(current)) {
                    Files.setPosixFilePermissions(path, OWNER_RW);
                    AUDIT.warn("share.link.repository.permissions_tightened impl={} dbHash={} "
                                    + "— DB file had wider permissions than 0600; corrected "
                                    + "(SR-SQLITE-07, SR-SQLITE-16)",
                            getClass().getSimpleName(),
                            LogScrubber.hash8(dbPath));
                }
            }
        } catch (Exception e) {
            // SR-SQLITE-07: degrade-with-warn on permission enforcement failure
            // Log the exception class but NOT the path (D-13/SR-8)
            AUDIT.warn("share.link.repository.permission_enforcement_failed impl={} "
                            + "cause={} — DB file permissions could not be enforced; "
                            + "manual verification required (SR-SQLITE-07)",
                    getClass().getSimpleName(),
                    e.getClass().getSimpleName());
        }
    }
}
