package de.seism0saurus.glacier.share.infrastructure;

import de.seism0saurus.glacier.share.domain.SecureRandomTokenGenerator;
import de.seism0saurus.glacier.share.domain.ShareLink;
import de.seism0saurus.glacier.util.LogScrubber;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the raw bearer token is never stored in the SQLite database.
 *
 * <p>After {@link SqliteShareLinkRepository#save}, this test queries the raw {@code id}
 * column directly via JDBC and asserts:
 * <ol>
 *   <li>The stored value is the SHA-256 hex digest of the token — not the token itself.</li>
 *   <li>The raw token string does NOT appear anywhere in the database rows.</li>
 * </ol>
 *
 * <p>References: ADR-SQLITE-04 (token at rest); SR-SQLITE-01 (raw token never stored);
 * OWASP A02:2021 — Cryptographic Failures; NIST SP 800-132.
 */
class SqliteShareLinkRepositoryTokenAtRestTest {

    private static final String SHARER_WALL_ID = "sharer-wall-id-fixture-value-0000000000";
    private static final Duration TTL = Duration.ofDays(7);
    private static final Instant T0 = Instant.parse("2025-06-01T00:00:00Z");
    private static final String HMAC_KEY = "A".repeat(44);

    private SqliteShareLinkRepository repository;
    private JdbcTemplate jdbcTemplate;
    private SecureRandomTokenGenerator tokenGenerator;

    @BeforeEach
    void setUp() {
        SingleConnectionDataSource ds = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        jdbcTemplate = new JdbcTemplate(ds);
        SharePersistenceProperties props = new SharePersistenceProperties();
        props.setPath(":memory:");
        props.setIpHmacKey(HMAC_KEY);
        repository = new SqliteShareLinkRepository(jdbcTemplate, props);
        repository.init();
        tokenGenerator = new SecureRandomTokenGenerator();
    }

    /**
     * After save, the {@code id} column contains SHA-256(token) — the raw token is absent.
     *
     * <p>Arrange: create a link with a known token.
     * <p>Act:     save it; query the raw {@code id} column.
     * <p>Assert:  stored value equals sha256Hex(token); raw token is absent from the DB.
     */
    @Test
    void afterSave_idColumn_containsHashNotRawToken() throws Exception {
        ShareLink link = ShareLink.create(
                tokenGenerator.generateShareLinkId(), SHARER_WALL_ID, T0, TTL);
        String rawToken = link.id().value();

        repository.save(link);

        // Query raw id column
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id FROM share_links");
        assertThat(rows).hasSize(1);

        String storedId = (String) rows.get(0).get("id");

        // Stored id must be exactly SHA-256(token) — 64 lowercase hex chars
        String expectedHash = sha256Hex(rawToken);
        assertThat(storedId)
                .as("id column must contain SHA-256(token), not the raw token (ADR-SQLITE-04; SR-SQLITE-01)")
                .isEqualTo(expectedHash);

        // Raw token must NOT appear anywhere in the stored id
        assertThat(storedId)
                .as("Raw token must NOT appear in the stored id column (SR-SQLITE-01)")
                .doesNotContain(rawToken);

        // Cross-check: hash8 of the stored id's first 8 chars equals LogScrubber.hash8 of the token
        // (idHash8 = storedId.substring(0,8))
        String idHash8 = storedId.substring(0, 8);
        assertThat(idHash8)
                .as("idHash8 must be first 8 chars of SHA-256(token)")
                .isEqualTo(LogScrubber.hash8(rawToken).substring(0, 8));
    }

    /**
     * The raw token does not appear in ANY column of the share_links table.
     *
     * <p>Arrange: create and save a link.
     * <p>Act:     query all columns; stringify them.
     * <p>Assert:  raw token does not appear in any column value.
     */
    @Test
    void afterSave_rawToken_doesNotAppearInAnyColumn() {
        ShareLink link = ShareLink.create(
                tokenGenerator.generateShareLinkId(), SHARER_WALL_ID, T0, TTL);
        String rawToken = link.id().value();

        repository.save(link);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, sharer_wall_id, creator_ip_hmac, created_at, expires_at, revoked_at "
                        + "FROM share_links");
        assertThat(rows).hasSize(1);

        for (Map.Entry<String, Object> entry : rows.get(0).entrySet()) {
            String colValue = entry.getValue() != null ? entry.getValue().toString() : "";
            assertThat(colValue)
                    .as("Column '%s' must not contain the raw token (SR-SQLITE-01; ADR-SQLITE-04)",
                            entry.getKey())
                    .doesNotContain(rawToken);
        }
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private static String sha256Hex(final String value) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] hash = md.digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
