package de.seism0saurus.glacier.share.infrastructure;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * Spring {@code @Configuration} that wires the HikariCP-wrapped SQLite datasource and
 * its associated {@link JdbcTemplate} bean.
 *
 * <p>Both beans are only created when {@code glacier.share.db.path} is set in the
 * application configuration (ADR-SQLITE-01).
 *
 * <h3>Pool settings (ADR-SQLITE-09; SR-SQLITE-13)</h3>
 * <ul>
 *   <li>{@code maximumPoolSize = 2} — SQLite WAL allows concurrent readers plus one writer;
 *       a larger pool causes lock-contention spikes without throughput gain.</li>
 *   <li>{@code connectionTimeout = 2000 ms} — fail fast for operations that cannot
 *       acquire a connection; avoids thread pile-up under transient SQLite contention.</li>
 *   <li>{@code leakDetectionThreshold = 10000 ms} — logs a warning (via AUDIT logger
 *       surface) when a connection is not returned within 10 s (SR-SQLITE-24).</li>
 * </ul>
 *
 * <p>References: ADR-SQLITE-01; ADR-SQLITE-09; SR-SQLITE-13; SR-SQLITE-24;
 * OWASP A05:2021 Security Misconfiguration.
 */
@Configuration
@ConditionalOnProperty(name = "glacier.share.db.path")
public class SqliteDataSourceConfig {

    /**
     * HikariCP-wrapped SQLite datasource.
     *
     * <p>The JDBC URL is constructed from {@code SharePersistenceProperties.getPath()},
     * which has already been validated by {@link SafeFilesystemPath} at startup
     * (SR-SQLITE-08; CWE-22).
     *
     * <p>Pool settings are per ADR-SQLITE-09 and SR-SQLITE-13:
     * <ul>
     *   <li>maximumPoolSize = 2 (SQLite WAL: one writer + one reader max concurrent)</li>
     *   <li>connectionTimeout = 2000 ms</li>
     *   <li>leakDetectionThreshold = 10000 ms (SR-SQLITE-24)</li>
     * </ul>
     *
     * @param props the validated persistence configuration
     * @return the configured {@link HikariDataSource}
     */
    @Bean(name = "sqliteDataSource", destroyMethod = "close")
    public DataSource sqliteDataSource(final SharePersistenceProperties props) {
        HikariConfig config = new HikariConfig();
        // C2 — data protection: path has been validated by @SafeFilesystemPath (SR-SQLITE-08)
        config.setJdbcUrl("jdbc:sqlite:" + props.getPath());
        // ADR-SQLITE-09: pool size 2 matches SQLite WAL concurrency model
        config.setMaximumPoolSize(2);
        // SR-SQLITE-13: fail-fast on connection acquisition
        config.setConnectionTimeout(2000L);
        // SR-SQLITE-24: log warning when connection is held longer than 10 s
        config.setLeakDetectionThreshold(10000L);
        config.setDriverClassName("org.sqlite.JDBC");
        return new HikariDataSource(config);
    }

    /**
     * {@link JdbcTemplate} backed by the SQLite datasource.
     *
     * <p>Qualified with {@code "sqliteDataSource"} to avoid collision with any future
     * primary datasource (ADR-SQLITE-01).
     *
     * @param sqliteDataSource the SQLite HikariCP datasource
     * @return the configured {@link JdbcTemplate}
     */
    @Bean
    public JdbcTemplate shareJdbcTemplate(
            @Qualifier("sqliteDataSource") final DataSource sqliteDataSource) {
        return new JdbcTemplate(sqliteDataSource);
    }
}
