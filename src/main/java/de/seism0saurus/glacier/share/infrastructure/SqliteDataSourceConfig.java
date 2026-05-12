package de.seism0saurus.glacier.share.infrastructure;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * Spring configuration that wires the SQLite {@link DataSource} and {@link JdbcTemplate}
 * beans for the share-link persistence adapter.
 *
 * <p>Active only when {@code glacier.share.db.path} is present in the environment
 * (ADR-SQLITE-01). Both bean names use the {@code "share"} prefix to avoid collision with
 * any future auto-configured default DataSource or JdbcTemplate beans.
 *
 * <h2>HikariCP settings (ADR-SQLITE-09; SR-SQLITE-13; SR-SQLITE-24)</h2>
 * <ul>
 *   <li>{@code maximumPoolSize=2} — SQLite WAL allows one writer and concurrent readers;
 *       a larger pool causes lock-contention spikes on write-heavy paths.</li>
 *   <li>{@code connectionTimeout=2000} — fail fast if a connection cannot be acquired
 *       within 2 s, preventing unbounded request queuing under load.</li>
 *   <li>{@code leakDetectionThreshold=10000} — log a WARN via HikariCP if a connection
 *       is held for more than 10 s without being returned to the pool, surfacing leaks
 *       early (SR-SQLITE-24).</li>
 * </ul>
 *
 * <h2>Connection-init PRAGMAs (ADR-SQLITE-02; SR-SQLITE-05)</h2>
 * <ul>
 *   <li>{@code PRAGMA journal_mode=WAL} — enables WAL for concurrent read/write.</li>
 *   <li>{@code PRAGMA synchronous=FULL} — required for revocation durability: a revocation
 *       write is flushed to the OS before the JDBC call returns, preventing rollback on
 *       power failure (SR-SQLITE-05).</li>
 *   <li>{@code PRAGMA foreign_keys=ON} — enforce referential integrity.</li>
 * </ul>
 *
 * <p>Note: {@code PRAGMA busy_timeout} is set per-connection via {@code connectionInitSql}.
 * The value from {@link SharePersistenceProperties#getBusyTimeoutMs()} is inlined
 * into the init SQL string (this is safe because the value is a validated integer, not
 * user-supplied free text).
 *
 * <h2>References</h2>
 * <ul>
 *   <li>ADR-SQLITE-01: conditional wiring</li>
 *   <li>ADR-SQLITE-02: PRAGMA settings</li>
 *   <li>ADR-SQLITE-09: pool size = 2</li>
 *   <li>SR-SQLITE-05: {@code synchronous=FULL}</li>
 *   <li>SR-SQLITE-13: pool + timeout settings</li>
 *   <li>SR-SQLITE-24: leak detection threshold</li>
 * </ul>
 */
@Configuration
@ConditionalOnProperty(name = "glacier.share.db.path")
public class SqliteDataSourceConfig {

    /**
     * Creates the HikariCP-backed SQLite {@link DataSource} for the share-link adapter.
     *
     * <p>Uses a named bean ({@code "shareDataSource"}) to avoid ambiguity with any
     * auto-configured default DataSource (ADR-SQLITE-01).
     *
     * @param props validated share persistence configuration; never null when this bean is active
     * @return a fully-configured {@link HikariDataSource}
     */
    @Bean
    public DataSource shareDataSource(final SharePersistenceProperties props) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + props.getPath());
        config.setMaximumPoolSize(2);            // SR-SQLITE-13, ADR-SQLITE-09: SQLite WAL allows 1 writer + concurrent readers
        config.setConnectionTimeout(2000);       // SR-SQLITE-13: fail fast; prevents unbounded request queuing
        config.setLeakDetectionThreshold(10000); // SR-SQLITE-24: surface connection leaks early
        // SR-SQLITE-05: synchronous=FULL required for revocation durability.
        // busy_timeout from validated int (not raw user string — injection-safe).
        config.setConnectionInitSql(
                "PRAGMA journal_mode=WAL; PRAGMA synchronous=FULL; PRAGMA foreign_keys=ON; PRAGMA busy_timeout="
                        + props.getBusyTimeoutMs());
        return new HikariDataSource(config);
    }

    /**
     * Creates the {@link JdbcTemplate} scoped to the SQLite share DataSource.
     *
     * <p>Named {@code "shareJdbcTemplate"} to avoid collision with any default
     * auto-configured JdbcTemplate bean.
     *
     * @param shareDataSource the SQLite DataSource; injected by name
     * @return a JdbcTemplate backed by the share DataSource
     */
    @Bean
    public JdbcTemplate shareJdbcTemplate(final DataSource shareDataSource) {
        return new JdbcTemplate(shareDataSource);
    }
}
