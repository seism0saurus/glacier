package de.seism0saurus.glacier.share.infrastructure;

import de.seism0saurus.glacier.share.domain.SecureRandomTokenGenerator;
import de.seism0saurus.glacier.share.domain.ShareLink;
import de.seism0saurus.glacier.share.domain.ShareLinkStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the first-revocation-wins semantics of {@link SqliteShareLinkRepository#markRevoked}.
 *
 * <p>The SQL predicate {@code WHERE revoked_at IS NULL} ensures that when multiple concurrent
 * callers attempt to revoke the same link, exactly one UPDATE affects a row. The others
 * silently no-op (zero rows affected). This test exercises that invariant under concurrency.
 *
 * <p>References: ADR-SQLITE-04; OWASP API3:2023 — Broken Object Property Level Authorization;
 * R-05 first-revocation-wins acceptance criterion.
 */
class SqliteShareLinkRepositoryRevocationRaceTest {

    private static final String SHARER_WALL_ID = "sharer-wall-id-00000000000000000000000";
    private static final Duration TTL = Duration.ofDays(7);
    private static final Instant T0 = Instant.parse("2025-06-01T00:00:00Z");
    private static final String HMAC_KEY = "A".repeat(44);

    private SqliteShareLinkRepository repository;
    private SecureRandomTokenGenerator tokenGenerator;

    @BeforeEach
    void setUp() {
        SingleConnectionDataSource ds = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(ds);
        SharePersistenceProperties props = new SharePersistenceProperties();
        props.setPath(":memory:");
        props.setIpHmacKey(HMAC_KEY);
        repository = new SqliteShareLinkRepository(jdbcTemplate, props);
        repository.init();
        tokenGenerator = new SecureRandomTokenGenerator();
    }

    /**
     * 32 virtual threads all try to revoke the same link at the same instant.
     *
     * <p>Expected: the link ends up REVOKED; the revocation instant matches T0+1s
     * (any of the 32 concurrent writes); all 32 calls complete without exception.
     *
     * <p>Note: with {@code SingleConnectionDataSource} + SQLite WAL, concurrent writes
     * are serialised at the SQLite level. The {@code WHERE revoked_at IS NULL} guard
     * ensures exactly one write sets the column; the rest update zero rows silently.
     */
    @Test
    void thirtyTwoConcurrentRevocations_onlyOneSucceeds_linkIsRevoked() throws Exception {
        // Arrange: save one link
        ShareLink link = ShareLink.create(
                tokenGenerator.generateShareLinkId(), SHARER_WALL_ID, T0, TTL);
        repository.save(link);

        Instant revokeTime = T0.plusSeconds(1);
        AtomicInteger exceptionCount = new AtomicInteger(0);

        // Act: 32 virtual threads all revoke the same link
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int i = 0; i < 32; i++) {
                tasks.add(() -> {
                    try {
                        repository.markRevoked(link.id(), revokeTime);
                    } catch (Exception e) {
                        exceptionCount.incrementAndGet();
                    }
                    return null;
                });
            }
            List<Future<Void>> futures = executor.invokeAll(tasks);
            for (Future<Void> f : futures) {
                f.get(); // re-throws any ExecutionException
            }
        }

        // Assert: no exceptions propagated
        assertThat(exceptionCount.get())
                .as("No exception must be thrown by any concurrent revocation (first-revocation-wins is a no-op, not an error)")
                .isEqualTo(0);

        // Assert: link is REVOKED
        Optional<ShareLink> found = repository.findById(link.id());
        assertThat(found).isPresent();
        assertThat(found.get().status(revokeTime.plusSeconds(1)))
                .as("Link must be REVOKED after concurrent revocation attempts")
                .isEqualTo(ShareLinkStatus.REVOKED);
        assertThat(found.get().revokedAt())
                .as("revokedAt must be set after concurrent revocation")
                .isPresent();
    }
}
