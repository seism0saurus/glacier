package de.seism0saurus.glacier.share.infrastructure;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test verifying that {@link SqliteDataSourceConfig} wires the HikariCP
 * connection pool with the correct configuration (SR-SQLITE-13; ADR-SQLITE-09).
 *
 * <p>Verifies:
 * <ul>
 *   <li>{@code maximumPoolSize = 2} — SQLite WAL allows one writer + concurrent readers;
 *       a larger pool causes lock-contention spikes (ADR-SQLITE-09).</li>
 *   <li>{@code connectionTimeout = 2000} — fail fast if a connection cannot be acquired
 *       (SR-SQLITE-13).</li>
 *   <li>{@code leakDetectionThreshold = 10000} — surface connection leaks (SR-SQLITE-24).</li>
 * </ul>
 *
 * <p>Uses the {@link SqliteDataSourceConfig} bean factory directly to avoid requiring a
 * full Spring context — makes the test fast (Failsafe IT scope because it uses HikariCP
 * which establishes real JDBC connections).
 *
 * <p>References: SR-SQLITE-13; SR-SQLITE-24; ADR-SQLITE-09.
 */
class SqliteShareLinkRepositoryConnectionPoolIT {

    private HikariDataSource dataSource;

    @AfterEach
    void closeDataSource() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }

    /**
     * ADR-SQLITE-09 / SR-SQLITE-13: maximum pool size must be exactly 2.
     */
    @Test
    void hikariPoolMaximumPoolSize_isTwo() {
        dataSource = createShareDataSource();

        assertThat(dataSource.getMaximumPoolSize())
                .as("HikariCP maximumPoolSize must be 2 (ADR-SQLITE-09; SR-SQLITE-13)")
                .isEqualTo(2);
    }

    /**
     * SR-SQLITE-13: connection timeout must be 2000 ms.
     */
    @Test
    void hikariConnectionTimeout_is2000ms() {
        dataSource = createShareDataSource();

        assertThat(dataSource.getConnectionTimeout())
                .as("HikariCP connectionTimeout must be 2000 ms (SR-SQLITE-13)")
                .isEqualTo(2000L);
    }

    /**
     * SR-SQLITE-24: leak detection threshold must be 10000 ms.
     */
    @Test
    void hikariLeakDetectionThreshold_is10000ms() {
        dataSource = createShareDataSource();

        assertThat(dataSource.getLeakDetectionThreshold())
                .as("HikariCP leakDetectionThreshold must be 10000 ms (SR-SQLITE-24)")
                .isEqualTo(10000L);
    }

    /**
     * Verifies that the DataSource JDBC URL is correctly prefixed with {@code jdbc:sqlite:}.
     */
    @Test
    void hikariJdbcUrl_startsWithJdbcSqlite() {
        dataSource = createShareDataSource();

        assertThat(dataSource.getJdbcUrl())
                .as("HikariCP JDBC URL must start with jdbc:sqlite:")
                .startsWith("jdbc:sqlite:");
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    /**
     * Creates a {@link HikariDataSource} using the same configuration as
     * {@link SqliteDataSourceConfig#shareDataSource(SharePersistenceProperties)}.
     */
    private HikariDataSource createShareDataSource() {
        SharePersistenceProperties props = new SharePersistenceProperties();
        props.setPath(":memory:");
        props.setIpHmacKey("A".repeat(44));

        SqliteDataSourceConfig config = new SqliteDataSourceConfig();
        return (HikariDataSource) config.shareDataSource(props);
    }
}
