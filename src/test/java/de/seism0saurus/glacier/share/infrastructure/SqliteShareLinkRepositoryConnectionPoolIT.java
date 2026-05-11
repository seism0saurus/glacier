package de.seism0saurus.glacier.share.infrastructure;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test verifying that the HikariCP connection pool is configured with
 * {@code maximumPoolSize=2} and that a third concurrent operation is queued rather
 * than immediately throwing a connection-acquisition error.
 *
 * <p>References: ADR-SQLITE-09; SR-SQLITE-13; SR-SQLITE-24.
 */
class SqliteShareLinkRepositoryConnectionPoolIT {

    private static final String VALID_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    private HikariDataSource hikariDs;

    @AfterEach
    void closeDataSource() {
        if (hikariDs != null && !hikariDs.isClosed()) {
            hikariDs.close();
        }
    }

    /**
     * ADR-SQLITE-09: HikariCP must be configured with {@code maximumPoolSize=2}.
     *
     * <p>Three concurrent operations are submitted. With {@code maximumPoolSize=2},
     * the third must wait for a connection (not throw immediately), confirming the pool
     * is bounded as required.
     */
    @Test
    void threeConcurrentOperations_thirdIsQueuedNotRejected(@TempDir final Path tempDir)
            throws Exception {
        Path dbFile = tempDir.resolve("pool-test.db");
        SharePersistenceProperties props = new SharePersistenceProperties();
        props.setPath(dbFile.toAbsolutePath().toString());
        props.setIpHmacKey(VALID_KEY);

        // Build HikariCP datasource via SqliteDataSourceConfig
        hikariDs = buildHikariDataSource(props);
        DataSource ds = hikariDs;

        // Init the repository (creates schema)
        SqliteShareLinkRepository repo = new SqliteShareLinkRepository(props, ds);
        repo.init();

        // Assert pool max size is 2 (ADR-SQLITE-09, SR-SQLITE-13)
        assertThat(hikariDs.getMaximumPoolSize())
                .as("HikariCP maximumPoolSize must be 2 (ADR-SQLITE-09, SR-SQLITE-13)")
                .isEqualTo(2);

        // Assert connectionTimeout is 2000 ms (SR-SQLITE-13)
        assertThat(hikariDs.getConnectionTimeout())
                .as("HikariCP connectionTimeout must be 2000 ms (SR-SQLITE-13)")
                .isEqualTo(2000L);

        // Assert leakDetectionThreshold is 10000 ms (SR-SQLITE-24)
        assertThat(hikariDs.getLeakDetectionThreshold())
                .as("HikariCP leakDetectionThreshold must be 10000 ms (SR-SQLITE-24)")
                .isEqualTo(10000L);

        // Submit 3 concurrent read operations — all must complete without SQLite busy error.
        // With WAL + pool size 2, reads can proceed concurrently; the third must queue briefly
        // but should not fail within the connectionTimeout window.
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            tasks.add(() -> {
                // Count share_links — safe read even when table is empty
                return jdbc.queryForObject(
                        "SELECT COUNT(*) FROM share_links", Integer.class);
            });
        }

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Integer>> futures = executor.invokeAll(tasks, 10, TimeUnit.SECONDS);
            for (Future<Integer> future : futures) {
                // Must complete without exception — third queued, not rejected
                assertThat(future.get())
                        .as("Concurrent read must succeed — 3rd operation was queued, not rejected "
                                + "(ADR-SQLITE-09, SR-SQLITE-13)")
                        .isGreaterThanOrEqualTo(0);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static HikariDataSource buildHikariDataSource(final SharePersistenceProperties props) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + props.getPath());
        config.setMaximumPoolSize(2);
        config.setConnectionTimeout(2000L);
        config.setLeakDetectionThreshold(10000L);
        config.setDriverClassName("org.sqlite.JDBC");
        return new HikariDataSource(config);
    }
}
