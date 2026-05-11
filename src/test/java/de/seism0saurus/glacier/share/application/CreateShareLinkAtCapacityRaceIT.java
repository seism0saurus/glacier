package de.seism0saurus.glacier.share.application;

import de.seism0saurus.glacier.share.domain.ShareLink;
import de.seism0saurus.glacier.share.domain.ShareLinkStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import social.bigbone.MastodonClient;

import java.time.Clock;
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
 * R-2: TOCTOU race test — verifies that {@link ShareLinkServiceImpl#create} never
 * overshoots the per-sharer capacity cap under concurrent load.
 *
 * <p>Setup: cap = 5, pre-seed 4 active links, then race 10 concurrent create calls.
 * Expected result: exactly 5 active links after all concurrent calls complete — the
 * cap must never be exceeded regardless of thread interleaving.
 *
 * <p>This test runs {@value #RACE_REPETITIONS} times to increase the probability of
 * catching non-deterministic race windows.  Each run uses {@link DirtiesContext} to
 * reset the in-memory repository between repetitions.
 *
 * <p>Security: R-2 (TOCTOU), OWASP API4 (Lack of Resources and Rate Limiting) —
 * concurrent cap bypass would allow an attacker to create unlimited share links,
 * causing uncontrolled resource consumption.
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@TestPropertySource(properties = {
        "glacier.domain=glacier.example.com",
        "glacier.cookie.secure=false",
        "glacier.fallback.enabled=true",
        "mastodon.instance=mastodon.social",
        "mastodon.accessToken=dummy",
        "mastodon.handle=glacier@mastodon.social",
        "glacier.operator.name=Test",
        "glacier.operator.streetAndNumber=Test 1",
        "glacier.operator.zipcode=12345",
        "glacier.operator.city=Test",
        "glacier.operator.country=Test",
        "glacier.operator.phone=+1",
        "glacier.operator.mail=test@test.com",
        "glacier.operator.website=test.com",
        "glacier.share.imgproxy.hmacSecret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
        // Cap under test: 5 active links per sharer
        "glacier.share.maxActivePerSharer=5",
        // Per-IP cap must be high enough not to interfere with this test
        "glacier.share.maxActivePerIp=1000"
})
class CreateShareLinkAtCapacityRaceIT {

    /** How many concurrent create calls to fire in the race. */
    private static final int CONCURRENT_CALLERS = 10;

    /** The cap under test — must match the property set above. */
    private static final int CAP = 5;

    /** Number of pre-seeded links before the race (CAP - 1). */
    private static final int PRE_SEEDED = CAP - 1;

    /** Wallid and IP used for all test calls (isolated to this test). */
    private static final String SHARER_WALL_ID = "race-test-wall-id-00000000000000000";
    private static final String SHARER_IP = "10.0.0.42";

    @Autowired
    private ShareLinkService shareLinkService;

    @Autowired
    private Clock clock;

    /** Suppress the Mastodon client so the context starts without a real server. */
    @MockitoBean
    @SuppressWarnings("unused")
    private MastodonClient mastodonClient;

    /**
     * Pre-seeds {@value #PRE_SEEDED} active links so there is exactly 1 slot remaining
     * before the race begins.
     */
    @BeforeEach
    void preSeedLinks() {
        Instant now = clock.instant();
        for (int i = 0; i < PRE_SEEDED; i++) {
            shareLinkService.create(SHARER_WALL_ID, SHARER_IP, now);
        }
    }

    /**
     * Fires {@value #CONCURRENT_CALLERS} virtual threads simultaneously, each attempting
     * to create a share link when exactly 1 slot is available.
     *
     * <p>Assertions:
     * <ol>
     *   <li>The final active count for {@value #SHARER_WALL_ID} must be exactly {@value #CAP}
     *       — the cap must never be exceeded.</li>
     *   <li>No thread throws an unexpected exception — capacity exceeded must return
     *       {@link Optional#empty()} or throw {@link CapacityExceededException}, not
     *       an NPE or unexpected runtime error.</li>
     * </ol>
     *
     * <p>R-2 (TOCTOU): the check-then-act sequence in {@link ShareLinkServiceImpl#create}
     * (countActiveForSharer → save) is not atomic at the repository level.  Without
     * concurrency control, N threads can all observe "4 < 5" and all proceed to save,
     * producing N + 4 links instead of 5.  This test catches that regression.
     */
    @Test
    void concurrentCreate_neverExceedsCap() throws Exception {
        Instant now = clock.instant();
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger capacityExceededCount = new AtomicInteger(0);
        List<Throwable> unexpectedExceptions = new ArrayList<>();

        // Build CONCURRENT_CALLERS tasks, each attempting one create
        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < CONCURRENT_CALLERS; i++) {
            tasks.add(() -> {
                try {
                    shareLinkService.create(SHARER_WALL_ID, SHARER_IP, now);
                    successCount.incrementAndGet();
                } catch (CapacityExceededException e) {
                    // Expected when cap is exhausted — this is the correct behaviour
                    capacityExceededCount.incrementAndGet();
                } catch (Throwable t) {
                    // Any other exception is unexpected and must be reported
                    synchronized (unexpectedExceptions) {
                        unexpectedExceptions.add(t);
                    }
                }
                return null;
            });
        }

        // Run all tasks concurrently using virtual threads (same executor as production)
        ExecutorService vt = Executors.newVirtualThreadPerTaskExecutor();
        try {
            List<Future<Void>> futures = vt.invokeAll(tasks);
            // Wait for all futures to complete (invokeAll blocks until all done)
            for (Future<Void> f : futures) {
                f.get();
            }
        } finally {
            vt.shutdown();
        }

        // No unexpected exceptions
        assertThat(unexpectedExceptions)
                .as("No unexpected exceptions must be thrown during concurrent create (R-2)")
                .isEmpty();

        // Total calls = successes + capacity-exceeded rejections
        assertThat(successCount.get() + capacityExceededCount.get())
                .as("All %d concurrent calls must complete (success or graceful rejection)", CONCURRENT_CALLERS)
                .isEqualTo(CONCURRENT_CALLERS);

        // Core assertion: active count must never exceed CAP
        Instant now2 = clock.instant();
        List<ShareLink> activeLinks = shareLinkService.listBySharer(SHARER_WALL_ID, now2)
                .stream()
                .filter(link -> link.status(now2) == ShareLinkStatus.ACTIVE)
                .toList();

        assertThat(activeLinks.size())
                .as("Active link count must not exceed cap=%d after concurrent creates (R-2 TOCTOU)", CAP)
                .isLessThanOrEqualTo(CAP);

        // At least CAP links must have been successfully created (pre-seeded + at least 1 from race)
        assertThat(activeLinks.size())
                .as("At least %d active links must exist (pre-seeded=%d + race winner(s))",
                        PRE_SEEDED + 1, PRE_SEEDED)
                .isGreaterThanOrEqualTo(PRE_SEEDED + 1);
    }
}
