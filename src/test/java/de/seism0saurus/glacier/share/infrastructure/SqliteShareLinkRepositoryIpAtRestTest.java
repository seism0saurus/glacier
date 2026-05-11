package de.seism0saurus.glacier.share.infrastructure;

import de.seism0saurus.glacier.share.domain.SecureRandomTokenGenerator;
import de.seism0saurus.glacier.share.domain.ShareLink;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies IP pseudonymisation at rest for {@link SqliteShareLinkRepository} (SR-SQLITE-04;
 * ADR-SQLITE-06).
 *
 * <p>The raw creator IP must NEVER be stored in the {@code creator_ip_hmac} column.
 * Only {@code HMAC-SHA256(ip, key)} is stored. This test reads the column directly via
 * SQL to assert the invariant at the storage layer.
 *
 * <p>References: SR-SQLITE-04; ADR-SQLITE-06; GDPR Art. 25 (Data Protection by Design);
 * OWASP A02:2021 Cryptographic Failures.
 */
class SqliteShareLinkRepositoryIpAtRestTest {

    private static final String SHARER_WALL_ID = "sharer-wall-id-ip-at-rest-0000000000000";
    private static final String CREATOR_IP = "192.168.42.100";
    private static final Duration TTL = Duration.ofDays(7);
    private static final Instant T0 = Instant.parse("2025-06-01T00:00:00Z");
    // Use a distinctive key to ensure the HMAC output is verifiable
    private static final String VALID_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    private SqliteShareLinkRepository repository;
    private SecureRandomTokenGenerator tokenGenerator;
    private DataSource dataSource;

    @BeforeEach
    void setUp() {
        tokenGenerator = new SecureRandomTokenGenerator();
        dataSource = buildInMemoryDataSource();
        SharePersistenceProperties props = buildProperties(":memory:");
        repository = new SqliteShareLinkRepository(props, dataSource);
        repository.init();
    }

    /**
     * SR-SQLITE-04 / ADR-SQLITE-06: the raw IP must NOT appear in {@code creator_ip_hmac}.
     *
     * <p>Arrange: save a link with a known creator IP.
     * <p>Act:     query the {@code creator_ip_hmac} column directly via JdbcTemplate.
     * <p>Assert:  stored value != raw IP; stored value is a hex HMAC string (not blank).
     */
    @Test
    void storedCreatorIpHmacColumn_containsHmacNotRawIp() {
        ShareLink link = ShareLink.create(tokenGenerator.generateShareLinkId(), SHARER_WALL_ID,
                CREATOR_IP, T0, TTL);
        repository.save(link);

        org.springframework.jdbc.core.JdbcTemplate jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT creator_ip_hmac FROM share_links");

        assertThat(rows).hasSize(1);
        String storedHmac = (String) rows.get(0).get("creator_ip_hmac");

        assertThat(storedHmac)
                .as("SR-SQLITE-04: raw IP must never be stored in creator_ip_hmac column "
                        + "(ADR-SQLITE-06: HMAC-SHA256 pseudonymisation)")
                .isNotNull()
                .isNotEqualTo(CREATOR_IP);

        // Should be a 64-character hex string (SHA-256 → 32 bytes → 64 hex chars)
        assertThat(storedHmac)
                .as("creator_ip_hmac must be a 64-character hex string (HMAC-SHA256 output)")
                .matches("[0-9a-f]{64}");
    }

    /**
     * When {@code creatorIp} is null, the {@code creator_ip_hmac} column must be NULL.
     *
     * <p>Arrange: save a link without a creator IP.
     * <p>Act:     query the {@code creator_ip_hmac} column directly.
     * <p>Assert:  stored value is NULL.
     */
    @Test
    void storedCreatorIpHmacColumn_isNullWhenNoCreatorIpSupplied() {
        // ShareLink.create without IP uses null creatorIp
        ShareLink link = ShareLink.create(tokenGenerator.generateShareLinkId(), SHARER_WALL_ID, T0, TTL);
        repository.save(link);

        org.springframework.jdbc.core.JdbcTemplate jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT creator_ip_hmac FROM share_links");

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("creator_ip_hmac"))
                .as("creator_ip_hmac must be NULL when no creator IP was provided at creation")
                .isNull();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static SharePersistenceProperties buildProperties(final String path) {
        SharePersistenceProperties props = new SharePersistenceProperties();
        props.setPath(path);
        props.setIpHmacKey(VALID_KEY);
        return props;
    }

    private static DataSource buildInMemoryDataSource() {
        SingleConnectionDataSource ds = new SingleConnectionDataSource();
        ds.setUrl("jdbc:sqlite::memory:");
        ds.setSuppressClose(true);
        return ds;
    }
}
