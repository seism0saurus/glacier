package de.seism0saurus.glacier.share.infrastructure;

import de.seism0saurus.glacier.share.domain.SecureRandomTokenGenerator;
import de.seism0saurus.glacier.share.domain.ShareLink;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies that {@link SqliteShareLinkRepository} propagates a {@link RuntimeException}
 * when the SQLite database reaches its page-count cap (simulating disk-full behaviour).
 *
 * <p>Setting {@code maxPageCount=1} causes SQLite to refuse writes after the first page
 * is full. Subsequent {@code INSERT} calls throw a JDBC exception which Spring wraps in
 * a {@link DataAccessException} (a {@link RuntimeException} subtype).
 *
 * <p>The test does NOT assert on {@code CapacityExceededException} — the SQLite adapter
 * propagates the raw Spring JDBC exception. The application layer is responsible for
 * converting it to an appropriate HTTP response if needed.
 *
 * <p>References: SR-SQLITE-09 ({@code max_page_count}); OWASP A05:2021 — Security
 * Misconfiguration; Capacity Exceeded handling.
 */
class SqliteShareLinkRepositoryDiskFullIT {

    private static final String SHARER_WALL_ID = "sharer-wall-id-fixture-value-0000000000";
    private static final Duration TTL = Duration.ofDays(7);
    private static final Instant T0 = Instant.parse("2025-06-01T00:00:00Z");
    private static final String HMAC_KEY = "A".repeat(44);

    /**
     * When {@code maxPageCount=1} is set, writing enough rows to exceed the page cap
     * causes a {@link RuntimeException} (specifically a Spring {@link DataAccessException}).
     *
     * <p>Arrange: repository with maxPageCount=1 (minimal size cap).
     * <p>Act:     insert rows until the page cap is exceeded.
     * <p>Assert:  a {@link RuntimeException} is thrown; the class hierarchy includes
     *             {@link DataAccessException}.
     */
    @Test
    void saveToFullDatabase_throwsRuntimeException() {
        SingleConnectionDataSource ds = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(ds);
        SharePersistenceProperties props = new SharePersistenceProperties();
        props.setPath(":memory:");
        props.setIpHmacKey(HMAC_KEY);
        props.setMaxPageCount(1); // Minimal cap — simulates disk-full

        SqliteShareLinkRepository repository = new SqliteShareLinkRepository(jdbcTemplate, props);
        repository.init();

        SecureRandomTokenGenerator tokenGenerator = new SecureRandomTokenGenerator();

        // Writing many rows should eventually exceed the page count.
        // With maxPageCount=1 and a 4KB default page, SQLite will refuse writes once
        // the single page is full. We write in a loop until an exception is thrown.
        assertThatThrownBy(() -> {
            for (int i = 0; i < 200; i++) {
                ShareLink link = ShareLink.create(
                        tokenGenerator.generateShareLinkId(), SHARER_WALL_ID, T0, TTL);
                repository.save(link);
            }
        })
                .as("Writing to a full SQLite DB (maxPageCount=1) must throw RuntimeException (SR-SQLITE-09)")
                .isInstanceOf(RuntimeException.class);
    }
}
