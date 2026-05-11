package de.seism0saurus.glacier.share.infrastructure;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Unit test verifying that calling {@link SqliteShareLinkRepository}'s schema-init
 * logic twice on the same in-memory database does not throw an exception.
 *
 * <p>The DDL uses {@code CREATE TABLE IF NOT EXISTS} and {@code CREATE INDEX IF NOT EXISTS}
 * (ADR-SQLITE-02), so repeated {@code @PostConstruct init()} invocations must be idempotent.
 *
 * <p>This test uses a raw in-memory SQLite datasource to simulate double-initialisation
 * without starting a Spring context.
 *
 * <p>References: ADR-SQLITE-02; SR-SQLITE-05; OWASP A05:2021.
 */
class SqliteSchemaIdempotencyTest {

    // 44-char base64 key (valid for SR-SQLITE-22)
    private static final String VALID_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    /**
     * Calling init() twice on the same in-memory DB must not throw.
     *
     * <p>ADR-SQLITE-02: {@code CREATE TABLE IF NOT EXISTS} and
     * {@code CREATE INDEX IF NOT EXISTS} guarantee idempotency — this test confirms
     * the guarantee holds at the repository level.
     */
    @Test
    void initCalledTwice_doesNotThrow() {
        SharePersistenceProperties props = buildProperties(":memory:");
        DataSource ds = buildInMemoryDataSource();

        SqliteShareLinkRepository repo = new SqliteShareLinkRepository(props, ds);

        assertThatCode(repo::init)
                .as("First init() call must not throw (ADR-SQLITE-02)")
                .doesNotThrowAnyException();

        assertThatCode(repo::init)
                .as("Second init() on the same DB must not throw — CREATE IF NOT EXISTS is idempotent")
                .doesNotThrowAnyException();
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

    /**
     * Returns a {@link SingleConnectionDataSource} so that both init() calls share
     * the same in-memory SQLite connection (in-memory schema is connection-scoped).
     */
    private static DataSource buildInMemoryDataSource() {
        SingleConnectionDataSource ds = new SingleConnectionDataSource();
        ds.setUrl("jdbc:sqlite::memory:");
        ds.setSuppressClose(true);
        return ds;
    }
}
