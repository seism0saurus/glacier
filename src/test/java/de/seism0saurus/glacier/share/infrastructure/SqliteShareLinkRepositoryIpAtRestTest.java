package de.seism0saurus.glacier.share.infrastructure;

import de.seism0saurus.glacier.share.domain.SecureRandomTokenGenerator;
import de.seism0saurus.glacier.share.domain.ShareLink;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that raw creator IP addresses are never stored in the SQLite database.
 *
 * <p>After {@link SqliteShareLinkRepository#save}, this test queries the raw
 * {@code creator_ip_hmac} column directly via JDBC and asserts:
 * <ol>
 *   <li>The stored value is a 64-character hex string (HMAC-SHA256 output).</li>
 *   <li>The raw IP address does NOT appear in the column value.</li>
 *   <li>When no IP is provided, the column is NULL.</li>
 * </ol>
 *
 * <p>References: ADR-SQLITE-04 (IP pseudonymisation); SR-SQLITE-04 (raw IP never stored);
 * GDPR Art. 25 — Data Protection by Design; OWASP A02:2021 — Cryptographic Failures.
 */
class SqliteShareLinkRepositoryIpAtRestTest {

    private static final String SHARER_WALL_ID = "sharer-wall-id-fixture-value-0000000000";
    private static final String CREATOR_IP = "192.168.1.99";
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
     * When a link is saved with a creator IP, the {@code creator_ip_hmac} column stores
     * HMAC-SHA256(ip), never the raw IP.
     *
     * <p>Arrange: create a link with a known IP.
     * <p>Act:     save it; query the raw {@code creator_ip_hmac} column.
     * <p>Assert:  stored value is a 64-char hex string; raw IP is absent.
     */
    @Test
    void afterSave_creatorIpHmacColumn_doesNotContainRawIp() {
        ShareLink link = ShareLink.create(
                tokenGenerator.generateShareLinkId(), SHARER_WALL_ID, CREATOR_IP, T0, TTL);

        repository.save(link);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT creator_ip_hmac FROM share_links");
        assertThat(rows).hasSize(1);

        String storedValue = (String) rows.get(0).get("creator_ip_hmac");

        assertThat(storedValue)
                .as("creator_ip_hmac must not contain the raw IP (SR-SQLITE-04; ADR-SQLITE-04)")
                .isNotNull()
                .doesNotContain(CREATOR_IP);

        // HMAC-SHA256 output is 32 bytes = 64 hex characters
        assertThat(storedValue)
                .as("creator_ip_hmac must be a 64-char hex HMAC (SR-SQLITE-04)")
                .matches("[0-9a-f]{64}");
    }

    /**
     * When a link has no creator IP (null), the {@code creator_ip_hmac} column is NULL.
     *
     * <p>Arrange: create a link without IP.
     * <p>Act:     save it; query the raw column.
     * <p>Assert:  column value is NULL.
     */
    @Test
    void afterSave_withNoCreatorIp_creatorIpHmacIsNull() {
        ShareLink link = ShareLink.create(
                tokenGenerator.generateShareLinkId(), SHARER_WALL_ID, T0, TTL);

        repository.save(link);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT creator_ip_hmac FROM share_links");
        assertThat(rows).hasSize(1);

        Object storedValue = rows.get(0).get("creator_ip_hmac");

        assertThat(storedValue)
                .as("creator_ip_hmac must be NULL when no creator IP is provided (SR-SQLITE-04)")
                .isNull();
    }

    /**
     * Two links with the same IP produce the same HMAC — deterministic pseudonymisation
     * is required for correct {@code countActiveForIp} behaviour.
     *
     * <p>Arrange: two links with the same IP.
     * <p>Act:     save both; query the {@code creator_ip_hmac} column.
     * <p>Assert:  both HMAC values are identical.
     */
    @Test
    void twoLinksWithSameIp_haveIdenticalCreatorIpHmac() {
        ShareLink link1 = ShareLink.create(
                tokenGenerator.generateShareLinkId(), SHARER_WALL_ID, CREATOR_IP, T0, TTL);
        ShareLink link2 = ShareLink.create(
                tokenGenerator.generateShareLinkId(), SHARER_WALL_ID, CREATOR_IP, T0, TTL);

        repository.save(link1);
        repository.save(link2);

        List<String> hmacs = jdbcTemplate.queryForList(
                "SELECT creator_ip_hmac FROM share_links ORDER BY created_at",
                String.class);
        assertThat(hmacs).hasSize(2);
        assertThat(hmacs.get(0))
                .as("HMAC must be deterministic — same IP must produce same HMAC (SR-SQLITE-04)")
                .isEqualTo(hmacs.get(1));
    }
}
