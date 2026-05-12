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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Repository;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * Durable SQLite-backed {@link ShareLinkRepository} for the share-link bounded context.
 *
 * <p>This adapter persists share-link aggregates to a SQLite database file, enabling
 * durability across JVM restarts (R-05; ADR-SQLITE-01).
 *
 * <h2>At-rest security properties</h2>
 * <ul>
 *   <li><strong>Token at rest</strong>: only {@code SHA-256(token)} is stored in the
 *       {@code id} column — the raw bearer token cannot be reconstructed from the DB
 *       (ADR-SQLITE-04; SR-SQLITE-01; SR-SQLITE-03).</li>
 *   <li><strong>IP at rest</strong>: creator IPs are stored as {@code HMAC-SHA256(ip, key)},
 *       never as raw strings (ADR-SQLITE-04; SR-SQLITE-04; GDPR Art. 25).</li>
 *   <li><strong>File permissions</strong>: the DB file is created and chmod'd to
 *       {@code 0600} (owner r/w only) at {@code @PostConstruct} time (SR-SQLITE-07).</li>
 * </ul>
 *
 * <h2>Schema</h2>
 * The {@code share_links} table is created via {@code CREATE TABLE IF NOT EXISTS} (idempotent).
 * The schema is documented in {@link #init()}.
 *
 * <h2>Revocation semantics</h2>
 * {@link #markRevoked} uses {@code UPDATE ... WHERE revoked_at IS NULL}: first-revocation-wins.
 * Concurrent revocations of the same link are safe — exactly one write succeeds;
 * subsequent calls silently no-op (zero rows affected).
 *
 * <h2>Conditional activation</h2>
 * Active only when {@code glacier.share.db.path} is present in the environment
 * (ADR-SQLITE-01). When the property is absent the {@link InMemoryShareLinkRepository}
 * remains active instead.
 *
 * <h2>References</h2>
 * <ul>
 *   <li>ADR-SQLITE-01: conditional opt-in wiring</li>
 *   <li>ADR-SQLITE-04: token + IP at rest hashing</li>
 *   <li>SR-SQLITE-01..24: complete security requirements list</li>
 *   <li>R-05: durability acceptance criterion (restart persistence)</li>
 *   <li>OWASP A02:2021 — Cryptographic Failures; A03:2021 — Injection; A05:2021 — Misconfiguration</li>
 * </ul>
 */
@Repository
@ConditionalOnProperty(name = "glacier.share.db.path")
public class SqliteShareLinkRepository implements ShareLinkRepository {

    private static final Logger AUDIT = LoggerFactory.getLogger("AUDIT");
    private static final Logger log = LoggerFactory.getLogger(SqliteShareLinkRepository.class);

    private final JdbcTemplate jdbcTemplate;
    private final SharePersistenceProperties props;
    private final SecretKeySpec hmacKey;

    /**
     * Constructs the repository with the share-specific JDBC template and persistence properties.
     *
     * <p>The HMAC key is decoded from Base64 once at construction time; any failure here
     * (malformed Base64) propagates as an unchecked exception, preventing context startup
     * with an invalid key (fail-closed, SR-SQLITE-17).
     *
     * @param jdbcTemplate the share-scoped JdbcTemplate (qualifier {@code "shareJdbcTemplate"})
     * @param props        validated share persistence configuration (never null when active)
     */
    public SqliteShareLinkRepository(
            @Qualifier("shareJdbcTemplate") final JdbcTemplate jdbcTemplate,
            final SharePersistenceProperties props) {
        this.jdbcTemplate = jdbcTemplate;
        this.props = props;
        byte[] keyBytes = Base64.getDecoder().decode(props.getIpHmacKey());
        this.hmacKey = new SecretKeySpec(keyBytes, "HmacSHA256");
    }

    // -------------------------------------------------------------------------
    // Initialisation (@PostConstruct)
    // -------------------------------------------------------------------------

    /**
     * Creates the database schema and applies security settings at context startup.
     *
     * <p>Performed in order:
     * <ol>
     *   <li>POSIX 0600 file permissions (SR-SQLITE-07) — enforced before the connection pool
     *       makes its first write, preventing transient window where the file is world-readable.</li>
     *   <li>{@code CREATE TABLE IF NOT EXISTS share_links} — idempotent DDL.</li>
     *   <li>Three partial indexes: by sharer+expiry (active links), creator_ip+expiry
     *       (IP cap), and expiry alone (sweep efficiency).</li>
     *   <li>{@code PRAGMA max_page_count} — caps DB size (SR-SQLITE-09).</li>
     *   <li>Startup AUDIT log line: {@code share-link-repository=sqlite db-hash8=...}
     *       (SR-SQLITE-06).</li>
     * </ol>
     *
     * <p>Table schema:
     * <pre>
     * CREATE TABLE IF NOT EXISTS share_links (
     *     id              TEXT    NOT NULL PRIMARY KEY,   -- SHA-256(token), 64 hex chars
     *     sharer_wall_id  TEXT    NOT NULL,               -- raw wallId (UUID)
     *     creator_ip_hmac TEXT,                           -- HMAC-SHA256(ip), nullable
     *     created_at      INTEGER NOT NULL,               -- epoch millis
     *     expires_at      INTEGER NOT NULL,               -- epoch millis
     *     revoked_at      INTEGER                         -- epoch millis; NULL = not revoked
     * ) WITHOUT ROWID
     * </pre>
     *
     * <p>{@code WITHOUT ROWID} avoids the hidden rowid B-tree, giving slightly smaller
     * storage for text-keyed tables (SQLite documentation §4.1).
     */
    @PostConstruct
    void init() {
        enforceFilePermissions();

        // Schema DDL — idempotent; safe to re-run on restart
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS share_links (
                    id              TEXT    NOT NULL PRIMARY KEY,
                    sharer_wall_id  TEXT    NOT NULL,
                    creator_ip_hmac TEXT,
                    created_at      INTEGER NOT NULL,
                    expires_at      INTEGER NOT NULL,
                    revoked_at      INTEGER
                ) WITHOUT ROWID
                """);

        // Partial index: sharer self-management list + per-sharer cap query
        jdbcTemplate.execute("""
                CREATE INDEX IF NOT EXISTS idx_share_links_sharer_active
                    ON share_links (sharer_wall_id, expires_at)
                    WHERE revoked_at IS NULL
                """);

        // Partial index: per-IP cap query (nullable column — index only covers non-null rows)
        jdbcTemplate.execute("""
                CREATE INDEX IF NOT EXISTS idx_share_links_creator_ip_active
                    ON share_links (creator_ip_hmac, expires_at)
                    WHERE revoked_at IS NULL AND creator_ip_hmac IS NOT NULL
                """);

        // Partial index: sweep efficiency — only scans non-revoked rows
        jdbcTemplate.execute("""
                CREATE INDEX IF NOT EXISTS idx_share_links_expires_at
                    ON share_links (expires_at)
                    WHERE revoked_at IS NULL
                """);

        // Cap DB file size to prevent unbounded growth (SR-SQLITE-09)
        jdbcTemplate.execute("PRAGMA max_page_count = " + props.getMaxPageCount());

        // SR-SQLITE-06: startup AUDIT log — path hashed, never raw
        String path = props.getPath();
        String dbHash8 = isInMemoryPath(path) ? "memory" : sha256Hex(path).substring(0, 8);
        AUDIT.info("share-link-repository=sqlite db-hash8={}", dbHash8);
    }

    // -------------------------------------------------------------------------
    // ShareLinkRepository implementation
    // -------------------------------------------------------------------------

    /**
     * Persists (or replaces) the given {@link ShareLink} in the database.
     *
     * <p>At-rest security:
     * <ul>
     *   <li>{@code id} column: {@code SHA-256(token)} — raw token not written.</li>
     *   <li>{@code creator_ip_hmac} column: {@code HMAC-SHA256(ip, key)} — raw IP not written.</li>
     * </ul>
     *
     * @param link the aggregate to persist; must not be null
     * @return the same link (fluent usage)
     */
    @Override
    public ShareLink save(final ShareLink link) {
        String idHash = sha256Hex(link.id().value());
        String ipHmac = link.creatorIp().map(this::hmacSha256Hex).orElse(null);
        Long revokedAtMillis = link.revokedAt().map(Instant::toEpochMilli).orElse(null);

        jdbcTemplate.update(
                "INSERT OR REPLACE INTO share_links "
                        + "(id, sharer_wall_id, creator_ip_hmac, created_at, expires_at, revoked_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                idHash,
                link.sharerWallId(),
                ipHmac,
                link.createdAt().toEpochMilli(),
                link.expiresAt().toEpochMilli(),
                revokedAtMillis);

        log.debug("share.link.saved id-hash8={}", link.id().hash8());
        return link;
    }

    /**
     * Finds a {@link ShareLink} by its opaque identifier.
     *
     * <p>The lookup hashes the provided ID (SHA-256) before querying — the raw token is
     * never sent to the database (ADR-SQLITE-04). The returned aggregate has
     * {@code creatorIp = null} because the raw IP is not recoverable from the HMAC
     * stored in the database.
     *
     * @param id the share-link ID; must not be null
     * @return the link if found; empty otherwise
     */
    @Override
    public Optional<ShareLink> findById(final ShareLinkId id) {
        String idHash = sha256Hex(id.value());
        List<ShareLink> results = jdbcTemplate.query(
                "SELECT sharer_wall_id, created_at, expires_at, revoked_at "
                        + "FROM share_links WHERE id = ?",
                (rs, rowNum) -> {
                    String sharerWallId = rs.getString("sharer_wall_id");
                    Instant createdAt = Instant.ofEpochMilli(rs.getLong("created_at"));
                    Instant expiresAt = Instant.ofEpochMilli(rs.getLong("expires_at"));
                    long revokedAtRaw = rs.getLong("revoked_at");
                    Instant revokedAt = rs.wasNull() ? null : Instant.ofEpochMilli(revokedAtRaw);
                    // creatorIp is null — raw IP cannot be recovered from the HMAC (ADR-SQLITE-04)
                    return ShareLink.fromPersistence(id, sharerWallId, null, createdAt, expiresAt, revokedAt);
                },
                idHash);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * Marks the link identified by {@code id} as revoked at {@code when}.
     *
     * <p>Uses {@code WHERE revoked_at IS NULL} to enforce first-revocation-wins semantics
     * (concurrent revocations of the same link are safe — exactly one succeeds, others
     * silently no-op with zero rows affected).
     *
     * @param id   the share-link ID; must not be null
     * @param when the revocation instant; must not be null
     */
    @Override
    public void markRevoked(final ShareLinkId id, final Instant when) {
        String idHash = sha256Hex(id.value());
        int rowsUpdated = jdbcTemplate.update(
                "UPDATE share_links SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL",
                when.toEpochMilli(),
                idHash);
        if (rowsUpdated == 0) {
            log.debug("markRevoked: id-hash8={} — already revoked or not found (no-op)", id.hash8());
        }
    }

    /**
     * Removes all non-revoked links whose {@code expires_at} is not after {@code now}.
     *
     * <p>Revoked links survive the sweep regardless of their expiry time — the
     * {@code WHERE revoked_at IS NULL} predicate preserves them for audit purposes
     * until they have been explicitly fetched.
     *
     * @param now the reference instant for expiry comparison
     * @return the number of rows deleted
     */
    @Override
    public int sweepExpired(final Instant now) {
        int removed = jdbcTemplate.update(
                "DELETE FROM share_links WHERE expires_at <= ? AND revoked_at IS NULL",
                now.toEpochMilli());
        if (removed > 0) {
            AUDIT.info("share.link.sweep removed={}", removed);
        } else {
            log.debug("share.link.sweep removed=0");
        }
        return removed;
    }

    /**
     * Returns the total count of non-expired, non-revoked links at the given instant.
     *
     * @param now reference instant for status derivation
     * @return non-negative count
     */
    @Override
    public long countActive(final Instant now) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM share_links WHERE expires_at > ? AND revoked_at IS NULL",
                Long.class,
                now.toEpochMilli());
        return count != null ? count : 0L;
    }

    /**
     * Returns the count of active links for the given sharer.
     *
     * @param sharerWallId the sharer's wallId; must not be null
     * @param now          reference instant for status derivation
     * @return non-negative count
     */
    @Override
    public int countActiveForSharer(final String sharerWallId, final Instant now) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM share_links "
                        + "WHERE sharer_wall_id = ? AND expires_at > ? AND revoked_at IS NULL",
                Integer.class,
                sharerWallId,
                now.toEpochMilli());
        return count != null ? count : 0;
    }

    /**
     * Returns the count of active links created by the given IP address.
     *
     * <p>The IP is hashed with HMAC-SHA256 before querying — the raw IP is never sent
     * to the database (ADR-SQLITE-04; GDPR Art. 25).
     *
     * @param ip  the source IP at creation; must not be null
     * @param now reference instant for status derivation
     * @return non-negative count
     */
    @Override
    public int countActiveForIp(final String ip, final Instant now) {
        String ipHmac = hmacSha256Hex(ip);
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM share_links "
                        + "WHERE creator_ip_hmac = ? AND expires_at > ? AND revoked_at IS NULL",
                Integer.class,
                ipHmac,
                now.toEpochMilli());
        return count != null ? count : 0;
    }

    /**
     * Returns summary projections for all links belonging to the given sharer,
     * ordered by creation time descending.
     *
     * <p>The {@code id} column stored in the database is already {@code SHA-256(token)};
     * the first 8 hex characters serve as the visual {@code idHash8} identifier.
     *
     * @param sharerWallId the sharer's wallId; must not be null
     * @param now          reference instant for {@link ShareLinkStatus} computation
     * @return list of summaries; never null; may be empty
     */
    @Override
    public List<ShareLinkSummary> listSummaryBySharer(final String sharerWallId, final Instant now) {
        return jdbcTemplate.query(
                "SELECT id, created_at, expires_at, revoked_at "
                        + "FROM share_links WHERE sharer_wall_id = ? "
                        + "ORDER BY created_at DESC",
                (rs, rowNum) -> {
                    // id column is already SHA-256(token); first 8 chars = idHash8
                    String idHash8 = rs.getString("id").substring(0, 8);
                    Instant createdAt = Instant.ofEpochMilli(rs.getLong("created_at"));
                    Instant expiresAt = Instant.ofEpochMilli(rs.getLong("expires_at"));
                    long revokedAtRaw = rs.getLong("revoked_at");
                    Instant revokedAt = rs.wasNull() ? null : Instant.ofEpochMilli(revokedAtRaw);

                    // Derive status from the stored timestamps at the reference instant
                    ShareLinkStatus status;
                    if (revokedAt != null) {
                        status = ShareLinkStatus.REVOKED;
                    } else if (!now.isBefore(expiresAt)) {
                        status = ShareLinkStatus.EXPIRED;
                    } else {
                        status = ShareLinkStatus.ACTIVE;
                    }

                    return new ShareLinkSummary(idHash8, createdAt, expiresAt, revokedAt, status, sharerWallId);
                },
                sharerWallId);
    }

    /**
     * Returns an empty list and logs a deprecation warning.
     *
     * <p>The SQLite adapter cannot implement this method correctly because the raw token
     * is not stored — only {@code SHA-256(token)} is persisted. Callers must migrate to
     * {@link #listSummaryBySharer(String, Instant)} (ADR-SQLITE-04).
     *
     * @param sharerWallId the sharer's wallId (ignored)
     * @return always an empty list
     * @deprecated Use {@link #listSummaryBySharer(String, Instant)} instead.
     */
    @Override
    @Deprecated
    public List<ShareLink> findAllBySharer(final String sharerWallId) {
        log.warn("findAllBySharer called on SQLite adapter — deprecated; use listSummaryBySharer. "
                + "Returning empty list (raw token not recoverable from DB, ADR-SQLITE-04).");
        return List.of();
    }

    // -------------------------------------------------------------------------
    // Scheduled maintenance
    // -------------------------------------------------------------------------

    /**
     * Periodically removes expired links from the SQLite database.
     *
     * <p>The interval is driven by {@code glacier.share.sweepIntervalMs} (default: 300 000 ms /
     * 5 minutes). Exceptions are caught and logged to the AUDIT logger — the scheduler thread
     * must not be killed by a transient DB error (e.g., disk full mid-test, SR-SQLITE-14).
     */
    @Scheduled(fixedDelayString = "${glacier.share.sweepIntervalMs:300000}")
    public void scheduledSweep() {
        try {
            sweepExpired(Instant.now());
        } catch (Exception e) {
            AUDIT.warn("share.link.sweep.failed error={}", LogScrubber.forErrorMessage(e.getMessage()));
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Returns the full lowercase hex SHA-256 digest of the given value.
     *
     * <p>Used to derive the at-rest token hash ({@code id} column) and to compute the
     * startup path hash for the AUDIT log. SHA-256 is guaranteed by every JVM (NIST FIPS 180-4).
     *
     * <p>This method is {@code private static} — callers outside {@code share.infrastructure}
     * may not invoke it (SR-SQLITE-19; ADR-SQLITE-04).
     *
     * @param value the input string; must not be null
     * @return lowercase hex-encoded SHA-256 digest; always 64 characters
     */
    private static String sha256Hex(final String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed in every JVM (NIST FIPS 180-4)
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Returns the lowercase hex HMAC-SHA256 of the given value using the configured key.
     *
     * <p>Used to pseudonymise creator IPs before writing to the {@code creator_ip_hmac} column
     * (ADR-SQLITE-04; SR-SQLITE-04; GDPR Art. 25 — Data Protection by Design).
     *
     * @param value the plaintext input (IP address); must not be null
     * @return lowercase hex-encoded HMAC-SHA256; always 64 characters
     */
    private String hmacSha256Hex(final String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(hmacKey);
            byte[] result = mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(result.length * 2);
            for (byte b : result) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 unavailable or key invalid", e);
        }
    }

    /**
     * Enforces POSIX {@code 0600} permissions on the SQLite database file (SR-SQLITE-07).
     *
     * <p>If the file does not exist, it is created with {@code 0600} permissions before
     * the connection pool opens it — preventing a transient window where the file is
     * world-readable (TOCTOU mitigation).
     *
     * <p>On non-POSIX filesystems (e.g., Windows), a WARN is logged to the AUDIT logger
     * advising the operator to enforce permissions manually.
     *
     * <p>In-memory paths ({@code :memory:}) are skipped entirely.
     */
    private void enforceFilePermissions() {
        String path = props.getPath();
        if (isInMemoryPath(path)) {
            return;
        }

        Path filePath = Path.of(path);
        try {
            if (!Files.exists(filePath)) {
                Path parent = filePath.getParent();
                if (parent != null && !Files.exists(parent)) {
                    Files.createDirectories(parent);
                }
                Files.createFile(filePath,
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            } else {
                try {
                    Files.setPosixFilePermissions(filePath, PosixFilePermissions.fromString("rw-------"));
                } catch (UnsupportedOperationException e) {
                    AUDIT.warn("share.link.db.permissions.unavailable: non-POSIX filesystem detected; "
                            + "cannot enforce 0600 permissions on share-link DB. "
                            + "Operator must enforce file permissions manually (SR-SQLITE-07).");
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException(
                    LogScrubber.forErrorMessage("Failed to create/chmod share-link DB file"), e);
        }
    }

    /**
     * Returns {@code true} when the given path refers to an in-memory SQLite database.
     *
     * <p>In-memory databases ({@code :memory:} and {@code jdbc:sqlite::memory:}) do not
     * correspond to a real filesystem path, so file-permission enforcement is skipped.
     *
     * @param path the configured DB path; may be null
     * @return {@code true} for in-memory paths; {@code false} otherwise
     */
    private static boolean isInMemoryPath(final String path) {
        return path == null
                || path.equals(":memory:")
                || path.startsWith("jdbc:sqlite::memory:");
    }
}
