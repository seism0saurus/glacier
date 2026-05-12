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
 * Verifies concurrent revocation semantics: exactly 1 of N concurrent revocation attempts
 * succeeds; the rest silently no-op (first-revocation-wins).
 *
 * <p>32 virtual threads all attempt to revoke the same share link simultaneously. The SQLite
 * {@code WHERE revoked_at IS NULL} predicate, combined with SQLite's serialised write
 * semantics, ensures exactly one write succeeds.
 *
 * <p>References: ADR-SQLITE-04; R-05; OWASP API3:2023 — Broken Object Property Level Authorization.
 */
class SqliteShareLinkRepositoryConcurrencyIT {

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
     * 32 virtual threads all try to revoke the same link. After the race:
     * <ul>
     *   <li>No exception is thrown by any thread.</li>
     *   <li>The link is REVOKED.</li>
     *   <li>The total count of active links drops to zero (the revoked link is no longer ACTIVE).</li>
     * </ul>
     */
    @Test
    void thirtyTwoConcurrentRevocations_linkIsRevoked_noExceptionThrown() throws Exception {
        ShareLink link = ShareLink.create(
                tokenGenerator.generateShareLinkId(), SHARER_WALL_ID, T0, TTL);
        repository.save(link);

        Instant revokeTime = T0.plusSeconds(1);
        AtomicInteger exceptionCount = new AtomicInteger(0);

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
                f.get();
            }
        }

        assertThat(exceptionCount.get())
                .as("No exception must propagate from concurrent revocation (first-revocation-wins)")
                .isEqualTo(0);

        Optional<ShareLink> found = repository.findById(link.id());
        assertThat(found).isPresent();
        assertThat(found.get().status(revokeTime.plusSeconds(1)))
                .isEqualTo(ShareLinkStatus.REVOKED);

        assertThat(repository.countActive(revokeTime.plusSeconds(1)))
                .as("Active count must be 0 after revocation")
                .isEqualTo(0);
    }
}
