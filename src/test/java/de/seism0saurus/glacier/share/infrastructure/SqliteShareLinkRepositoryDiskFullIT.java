package de.seism0saurus.glacier.share.infrastructure;

import de.seism0saurus.glacier.share.application.CapacityExceededException;
import de.seism0saurus.glacier.share.domain.SecureRandomTokenGenerator;
import de.seism0saurus.glacier.share.domain.ShareLink;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test verifying that the {@code PRAGMA max_page_count} cap eventually
 * causes a database-full error when inserting too many rows.
 *
 * <p>SR-SQLITE-09: setting {@code glacier.share.db.maxPageCount=1} limits the database
 * to 1 page. Inserting many rows must eventually fail. The adapter wraps the JDBC
 * exception in a {@link RuntimeException} (the infrastructure-layer error contract).
 *
 * <p>Note: SQLite's minimum page size is 512 bytes; at maxPageCount=1, the DB can hold
 * approximately 512 bytes of data. The DDL itself may consume that single page, so even
 * the first INSERT may fail depending on the SQLite version. The test inserts up to 100
 * rows to guarantee the limit is hit.
 *
 * <p>References: SR-SQLITE-09; ADR-SQLITE-02; OWASP A05:2021 Security Misconfiguration.
 */
class SqliteShareLinkRepositoryDiskFullIT {

    private static final String SHARER_WALL_ID = "sharer-wall-id-disk-full-it-000000000";
    private static final Duration TTL = Duration.ofDays(7);
    private static final Instant T0 = Instant.parse("2025-06-01T00:00:00Z");
    private static final String VALID_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    /**
     * Setting {@code maxPageCount=1} on a fresh in-memory DB causes either:
     * (a) the DDL schema setup itself to fail, or
     * (b) inserts to eventually fail once the page limit is exhausted.
     *
     * <p>Both outcomes satisfy SR-SQLITE-09: the database cap is enforced.
     *
     * <p>Arrange: maxPageCount=1 (1 page = 512 bytes minimum — may not hold the schema+data).
     * <p>Act:     attempt full init + up to 100 inserts.
     * <p>Assert:  a RuntimeException is thrown at some point (init or insert) — never silently succeeds.
     *
     * <p>SR-SQLITE-09: the cap is enforced at the PRAGMA level; the exact failure point
     * (DDL or DML) depends on the SQLite page size and JDBC driver version.
     */
    @Test
    void capacityCapEnforced_withMaxPageCountOne_throwsAtInitOrInsert() {
        SecureRandomTokenGenerator tokenGenerator = new SecureRandomTokenGenerator();
        DataSource ds = buildInMemoryDataSource();
        SharePersistenceProperties props = buildProperties(":memory:", 1); // maxPageCount=1

        // The entire operation (init + inserts) must eventually fail — either at init time
        // (if the schema DDL already fills the 1-page cap) or during inserts.
        // We wrap the whole sequence to catch wherever the failure occurs.
        assertThatThrownBy(() -> {
            SqliteShareLinkRepository repository = new SqliteShareLinkRepository(props, ds);
            repository.init(); // schema DDL — may itself exhaust the 1-page cap

            // If DDL succeeded, inserts must eventually fail
            for (int i = 0; i < 100; i++) {
                ShareLink link = ShareLink.create(tokenGenerator.generateShareLinkId(),
                        SHARER_WALL_ID, T0, TTL);
                repository.save(link);
            }
        })
                .as("Init or insert with maxPageCount=1 must throw RuntimeException somewhere "
                        + "(SR-SQLITE-09: database cap is enforced by PRAGMA max_page_count)")
                .isInstanceOf(RuntimeException.class);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static SharePersistenceProperties buildProperties(final String path, final int maxPageCount) {
        SharePersistenceProperties props = new SharePersistenceProperties();
        props.setPath(path);
        props.setIpHmacKey(VALID_KEY);
        props.setMaxPageCount(maxPageCount);
        return props;
    }

    private static DataSource buildInMemoryDataSource() {
        SingleConnectionDataSource ds = new SingleConnectionDataSource();
        ds.setUrl("jdbc:sqlite::memory:");
        ds.setSuppressClose(true);
        return ds;
    }
}
