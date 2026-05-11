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
 * Verifies token-at-rest protection for {@link SqliteShareLinkRepository} (SR-SQLITE-01;
 * ADR-SQLITE-04).
 *
 * <p>The raw bearer token must NEVER be stored in the database — only its SHA-256 hex
 * digest is stored as the primary key. This test reads the raw {@code id} column directly
 * via SQL to assert the invariant at the storage layer.
 *
 * <p>Uses {@link SingleConnectionDataSource} to ensure schema and data are visible
 * in the same in-memory connection.
 *
 * <p>References: SR-SQLITE-01; ADR-SQLITE-04; OWASP A02:2021 Cryptographic Failures;
 * NIST SP 800-53 SC-28.
 */
class SqliteShareLinkRepositoryTokenAtRestTest {

    private static final String SHARER_WALL_ID = "sharer-wall-id-token-at-rest-000000000";
    private static final Duration TTL = Duration.ofDays(7);
    private static final Instant T0 = Instant.parse("2025-06-01T00:00:00Z");
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
     * SR-SQLITE-01 / ADR-SQLITE-04: the raw token must NOT appear in the {@code id} column.
     *
     * <p>Arrange: save a link with a known raw token.
     * <p>Act:     query the {@code id} column directly via JdbcTemplate.
     * <p>Assert:  stored value != raw token; stored value == SHA-256(raw token).
     */
    @Test
    void storedIdColumn_containsSha256OfToken_notRawToken() {
        ShareLink link = ShareLink.create(tokenGenerator.generateShareLinkId(), SHARER_WALL_ID, T0, TTL);
        String rawToken = link.id().value();
        repository.save(link);

        // Read raw id column from DB
        org.springframework.jdbc.core.JdbcTemplate jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id FROM share_links");

        assertThat(rows).hasSize(1);
        String storedId = (String) rows.get(0).get("id");

        assertThat(storedId)
                .as("SR-SQLITE-01: raw token must never be stored in the id column "
                        + "(ADR-SQLITE-04: adapter-local token hashing)")
                .isNotEqualTo(rawToken);

        // The stored id must equal SHA-256(token)
        String expectedHash = SqliteShareLinkRepository.sha256Hex(rawToken);
        assertThat(storedId)
                .as("SR-SQLITE-01: the stored id must equal SHA-256(token) hex (ADR-SQLITE-04)")
                .isEqualTo(expectedHash);
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
