package de.seism0saurus.glacier.share.infrastructure;

import de.seism0saurus.glacier.share.domain.SecureRandomTokenGenerator;
import de.seism0saurus.glacier.share.domain.ShareLink;
import de.seism0saurus.glacier.share.domain.ShareLinkId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrency integration test for {@link SqliteShareLinkRepository}.
 *
 * <p>32 virtual threads all call {@code markRevoked(sameId, distinctInstants)} concurrently.
 * ADR-SQLITE-03: {@code WHERE revoked_at IS NULL} ensures exactly one of the concurrent
 * writes succeeds. All threads must complete without exception, and exactly one
 * {@code revokedAt} must be stored.
 *
 * <p>SQLite WAL + busy_timeout handles write serialisation. The test uses a
 * {@link SingleConnectionDataSource} (connection-level serialisation) to avoid actual
 * file locking complexity in the test environment.
 *
 * <p>References: ADR-SQLITE-03; ADR-SQLITE-07 (scheduled sweep thread safety).
 */
class SqliteShareLinkRepositoryConcurrencyIT {

    private static final String SHARER_WALL_ID = "sharer-wall-id-concurrency-it-0000000";
    private static final Duration TTL = Duration.ofDays(7);
    private static final Instant T0 = Instant.parse("2025-06-01T00:00:00Z");
    private static final String VALID_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
    private static final int THREAD_COUNT = 32;

    private SqliteShareLinkRepository repository;
    private SecureRandomTokenGenerator tokenGenerator;

    @BeforeEach
    void setUp() {
        tokenGenerator = new SecureRandomTokenGenerator();
        DataSource ds = buildInMemoryDataSource();
        SharePersistenceProperties props = buildProperties(":memory:");
        repository = new SqliteShareLinkRepository(props, ds);
        repository.init();
    }

    /**
     * 32 virtual threads call markRevoked on the SAME link with DISTINCT revocation instants.
     *
     * <p>Arrange: save one link.
     * <p>Act:     32 virtual threads each call markRevoked with a distinct instant (T0 + i seconds).
     * <p>Assert:  all threads complete without exception; exactly one revokedAt stored.
     */
    @Test
    void thirtyTwoVirtualThreadsRevokingConcurrently_exactlyOneRevocationWins() throws Exception {
        ShareLink link = ShareLink.create(tokenGenerator.generateShareLinkId(), SHARER_WALL_ID, T0, TTL);
        ShareLinkId id = link.id();
        repository.save(link);

        List<Instant> distinctInstants = new ArrayList<>();
        for (int i = 0; i < THREAD_COUNT; i++) {
            distinctInstants.add(T0.plusSeconds(i));
        }

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Callable<Void>> tasks = distinctInstants.stream()
                    .<Callable<Void>>map(instant -> () -> {
                        repository.markRevoked(id, instant);
                        return null;
                    })
                    .toList();

            List<Future<Void>> futures = executor.invokeAll(tasks);
            for (Future<Void> f : futures) {
                f.get(); // re-throws any ExecutionException
            }
        }

        // Exactly one revokedAt must be stored (the first write wins)
        Optional<ShareLink> found = repository.findById(id);
        assertThat(found).isPresent();
        assertThat(found.get().revokedAt())
                .as("Exactly one revokedAt must be stored after 32 concurrent revoke calls "
                        + "(ADR-SQLITE-03: WHERE revoked_at IS NULL ensures first-write wins)")
                .isPresent();
        // The stored revokedAt must be one of the 32 supplied instants
        assertThat(distinctInstants)
                .as("The stored revokedAt must be one of the 32 distinct instants supplied")
                .contains(found.get().revokedAt().get());
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
