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
 * <h2>Connection PRAGMAs via JDBC URL parameters (ADR-SQLITE-02; SR-SQLITE-05)</h2>
 * <ul>
 *   <li>{@code journal_mode=WAL} — enables WAL for concurrent read/write.</li>
 *   <li>{@code synchronous=FULL} — required for revocation durability: a revocation
 *       write is flushed to the OS before the JDBC call returns, preventing rollback on
 *       power failure (SR-SQLITE-05).</li>
 *   <li>{@code foreign_keys=ON} — enforce referential integrity.</li>
 *   <li>{@code busy_timeout=N} — SQLite wait time in ms before returning SQLITE_BUSY;
 *       value from {@link SharePersistenceProperties#getBusyTimeoutMs()}.</li>
 * </ul>
 *
 * <p>These settings are encoded as JDBC URL query parameters rather than
 * {@code connectionInitSql}. The sqlite-jdbc driver's {@code Statement.execute()} calls
 * {@code sqlite3_prepare_v2()} internally, which compiles only the <em>first</em>
 * statement in a semicolon-separated string — subsequent statements are silently dropped.
 * URL parameters are applied atomically by the driver before any SQL executes, so all
 * four PRAGMA settings are guaranteed to take effect on every pool-vended connection.
 *
 * <h2>References</h2>
 * <ul>
 *   <li>ADR-SQLITE-01: conditional wiring</li>
 *   <li>ADR-SQLITE-02: PRAGMA settings — all applied via JDBC URL query parameters</li>
 *   <li>ADR-SQLITE-09: pool size = 2</li>
 *   <li>SR-SQLITE-05: {@code synchronous=FULL} via URL parameter</li>
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
        // Encode all PRAGMA settings as JDBC URL query parameters.
        // sqlite-jdbc applies these atomically before any SQL executes, guaranteeing
        // that journal_mode, synchronous, foreign_keys, and busy_timeout are all in
        // effect on every connection the pool vends.
        // Using connectionInitSql is insufficient: sqlite3_prepare_v2() compiles only
        // the first statement in a semicolon-separated string, silently dropping the rest.
        String url = "jdbc:sqlite:" + props.getPath()
                + "?journal_mode=WAL"
                + "&synchronous=FULL"         // SR-SQLITE-05: revocation durability
                + "&foreign_keys=ON"
                + "&busy_timeout=" + props.getBusyTimeoutMs(); // validated int — injection-safe

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setMaximumPoolSize(2);            // SR-SQLITE-13, ADR-SQLITE-09: SQLite WAL allows 1 writer + concurrent readers
        config.setConnectionTimeout(2000);       // SR-SQLITE-13: fail fast; prevents unbounded request queuing
        config.setLeakDetectionThreshold(10000); // SR-SQLITE-24: surface connection leaks early
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
