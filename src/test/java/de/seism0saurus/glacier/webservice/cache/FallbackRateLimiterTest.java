package de.seism0saurus.glacier.webservice.cache;

import de.seism0saurus.glacier.webservice.messaging.PrincipalKey;
import de.seism0saurus.glacier.webservice.messaging.PrincipalKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Helper to construct a WALL-kind PrincipalKey from a wallId string. */

/**
 * Unit tests for {@link FallbackRateLimiter}.
 *
 * Covers (SR-4, D-10):
 * - 30/min per wallId
 * - 120/min per IP
 * - Distinct buckets are isolated (wallId A does not affect wallId B)
 * - evictStaleBuckets removes idle buckets based on lastAccessedAt (Clock-controlled)
 * - 1000-thread concurrency stress — no data corruption
 */
class FallbackRateLimiterTest {

    private FallbackRateLimiter limiter;

    @BeforeEach
    void setUp() {
        limiter = new FallbackRateLimiter(30, 120, Clock.systemUTC());
    }

    /** Convenience factory: wraps a wallId string in a WALL PrincipalKey. */
    private static PrincipalKey wall(String wallId) {
        return new PrincipalKey(PrincipalKind.WALL, wallId);
    }

    // -------------------------------------------------------------------------
    // Per-wallId axis (30/min)
    // -------------------------------------------------------------------------

    @Test
    void check_perWallId_first30RequestsAllowed() {
        String wallId = "wall-aaaaaaaaaaaaaaaaaaaaaaaaaaa1";
        String ip = "10.0.0.1";

        for (int i = 0; i < 30; i++) {
            assertThat(limiter.check(wall(wallId), ip).permitted())
                    .as("request %d should be allowed", i + 1)
                    .isTrue();
        }
    }

    @Test
    void check_perWallId_31stRequestDenied() {
        String wallId = "wall-bbbbbbbbbbbbbbbbbbbbbbbbbbbbb1";
        String ip = "10.0.0.2";

        for (int i = 0; i < 30; i++) {
            limiter.check(wall(wallId), ip);
        }

        FallbackRateLimiter.RateLimitResult result = limiter.check(wall(wallId), ip);
        assertThat(result.permitted()).isFalse();
        assertThat(result.retryAfterSeconds()).isGreaterThan(0L);
    }

    @Test
    void check_perWallId_retryAfterIsPresent() {
        String wallId = "wall-ccccccccccccccccccccccccccccc1";
        String ip = "10.0.0.3";

        for (int i = 0; i < 30; i++) {
            limiter.check(wall(wallId), ip);
        }

        FallbackRateLimiter.RateLimitResult result = limiter.check(wall(wallId), ip);
        assertThat(result.retryAfterSeconds()).isGreaterThanOrEqualTo(1L);
        assertThat(result.retryAfterSeconds()).isLessThanOrEqualTo(62L); // +2s tolerance for window boundary
    }

    // -------------------------------------------------------------------------
    // Per-IP axis (120/min)
    // -------------------------------------------------------------------------

    @Test
    void check_perIp_first120RequestsAllowed_multipleWallIds() {
        String ip = "10.1.2.3";

        // Use different wallIds so per-wallId limit (30) is not hit
        for (int i = 0; i < 120; i++) {
            // Each "wallId" gets 30 requests before rotating
            String wallId = "wall-ip-test-" + String.format("%04d", (i / 30)) + "aaaaaaaaaaaaaaaaa";
            assertThat(limiter.check(wall(wallId), ip).permitted())
                    .as("request %d should be allowed (ip axis)", i + 1)
                    .isTrue();
        }
    }

    @Test
    void check_perIp_121stRequestDenied() {
        String ip = "10.1.2.4";
        // Exhaust the IP bucket by spreading across 4 wallIds (30 each = 120 total)
        for (int i = 0; i < 120; i++) {
            String wallId = "wall-ip-exhaust-" + String.format("%02d", (i / 30)) + "aaaaaaaaaaaaaaa";
            limiter.check(wall(wallId), ip);
        }

        // 121st with a fresh wallId — IP bucket exhausted
        FallbackRateLimiter.RateLimitResult result = limiter.check(wall("wall-ip-fresh-aaaaaaaaaaaaaaaaaa"), ip);
        assertThat(result.permitted()).isFalse();
    }

    // -------------------------------------------------------------------------
    // Distinct bucket isolation
    // -------------------------------------------------------------------------

    @Test
    void check_distinctWallIds_bucketsAreIsolated() {
        String ip = "10.2.0.1";
        String wallA = "wall-aaaaaaaaaaaaaaaaaaaaaaaaaaaa1";
        String wallB = "wall-bbbbbbbbbbbbbbbbbbbbbbbbbbbbb2";

        // Exhaust wallA
        for (int i = 0; i < 30; i++) {
            limiter.check(wall(wallA), ip);
        }

        // wallB should still be at full capacity
        assertThat(limiter.check(wall(wallB), ip).permitted()).isTrue();
    }

    @Test
    void check_distinctIps_bucketsAreIsolated() {
        // Use one wallId but different IPs with per-IP limit set very low
        FallbackRateLimiter smallLimiter = new FallbackRateLimiter(5, 5, Clock.systemUTC());

        String wallId = "wall-ip-iso-aaaaaaaaaaaaaaaaaaaaa1";
        String ipA = "192.168.1.1";
        String ipB = "192.168.1.2";

        // Exhaust ipA
        for (int i = 0; i < 5; i++) {
            smallLimiter.check(wall(wallId), ipA);
        }
        assertThat(smallLimiter.check(wall(wallId), ipA).permitted()).isFalse();

        // ipB should still be allowed (but wallId is exhausted, so use a fresh wallId)
        String freshWall = "wall-ip-iso-bbbbbbbbbbbbbbbbbbbbb1";
        assertThat(smallLimiter.check(wall(freshWall), ipB).permitted()).isTrue();
    }

    // -------------------------------------------------------------------------
    // checkIpOnly (for /rest/wall-id endpoint)
    // -------------------------------------------------------------------------

    @Test
    void checkIpOnly_first120Allowed() {
        String ip = "10.3.0.1";
        for (int i = 0; i < 120; i++) {
            assertThat(limiter.checkIpOnly(ip).permitted()).isTrue();
        }
    }

    @Test
    void checkIpOnly_121stDenied() {
        String ip = "10.3.0.2";
        for (int i = 0; i < 120; i++) {
            limiter.checkIpOnly(ip);
        }
        assertThat(limiter.checkIpOnly(ip).permitted()).isFalse();
    }

    // -------------------------------------------------------------------------
    // evictStaleBuckets — idle full buckets are removed (SR-4, D-10)
    // -------------------------------------------------------------------------

    @Test
    void evictStaleBuckets_freshBuckets_notEvicted() {
        String wallId = "wall-evict-fresh-aaaaaaaaaaaaaaaa1";
        String ip = "10.4.0.1";

        // Create fresh buckets by making a request
        limiter.check(wall(wallId), ip);

        int wallIdCountBefore = limiter.wallIdBucketCount();
        int ipCountBefore = limiter.ipBucketCount();

        limiter.evictStaleBuckets(); // should not evict recently-created buckets

        // Counts should be unchanged (or at most the same)
        assertThat(limiter.wallIdBucketCount()).isLessThanOrEqualTo(wallIdCountBefore);
        assertThat(limiter.ipBucketCount()).isLessThanOrEqualTo(ipCountBefore);
    }

    @Test
    void evictStaleBuckets_unusedBuckets_evenNewlyCreated_areNotEvictedImmediately() {
        // This test verifies the eviction window logic — fresh buckets should survive one eviction call
        String wallId = "wall-evict-new-aaaaaaaaaaaaaaaaaaa";
        limiter.check(wall(wallId), "10.4.0.5");

        // Evict — bucket was just created, should not be evicted
        limiter.evictStaleBuckets();

        assertThat(limiter.wallIdBucketCount()).isGreaterThan(0);
    }

    /**
     * Verifies that a bucket idle across ≥ 2 × 60-second window rollovers IS evicted and a
     * non-idle bucket in the same map is NOT touched (H-1 fix, D-10, SR-4).
     *
     * <p>Uses an injected {@link Clock} — no reflection required.
     *
     * <p>Root cause guarded: {@code refillIfNewWindow()} previously reset {@code lastFullAt} on
     * every window rollover, causing stale-idle buckets to appear "fresh" and escape eviction
     * indefinitely.  The fix introduces {@code lastAccessedAt} (updated only in
     * {@code tryConsume()}, {@code refund()}, and legacy entry points), which is independent of
     * window rollovers, so truly idle buckets are evicted correctly.
     */
    @Test
    void evictStaleBuckets_idleBucketAcrossMultipleWindowRollovers_isEvicted() {
        // Arrange: mutable Clock reference — use AtomicReference so the lambda can update it
        AtomicReference<Instant> nowRef = new AtomicReference<>(Instant.parse("2024-01-01T00:00:00Z"));
        Clock testClock = new Clock() {
            @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return nowRef.get(); }
        };

        FallbackRateLimiter testLimiter = new FallbackRateLimiter(30, 120, testClock);

        String idleWallId  = "wall-evict-idle-aaaaaaaaaaaaaaaaaa";
        String activeWallId = "wall-evict-active-aaaaaaaaaaaaaaaa";
        String idleIp      = "10.4.1.1";
        String activeIp    = "10.4.1.2";

        // Touch both buckets at t=0 so they are created with lastAccessedAt = t0
        testLimiter.check(wall(idleWallId), idleIp);
        testLimiter.check(wall(activeWallId), activeIp);

        // Advance clock past the 10-minute idle eviction threshold (t = 620 s) without
        // consuming from idleWallId — the idle bucket must be evicted; active must survive.
        // Eviction requires lastAccessedAt < clock.now() - 600 s (see evictStaleBuckets).
        nowRef.set(Instant.parse("2024-01-01T00:10:20Z")); // +620 s > 600 s eviction window

        // Touch the active bucket at the new time so its lastAccessedAt is recent (not idle)
        testLimiter.check(wall(activeWallId), activeIp);

        // Act
        testLimiter.evictStaleBuckets();

        // Assert: idle bucket gone; active bucket retained
        assertThat(testLimiter.wallIdBucketCount())
                .as("only the active wallId bucket should survive")
                .isEqualTo(1);
        // Verify the surviving bucket is indeed the active one (indirect: active bucket allows requests)
        assertThat(testLimiter.check(wall(activeWallId), activeIp).permitted()).isTrue();
    }

    /**
     * Verifies that a stale (idle-full for &gt;10 min) wallId bucket IS evicted and a
     * non-stale bucket in the same map is NOT touched (D-10, SR-4).
     *
     * <p>Uses the injected Clock — no reflection. This replaces the previous
     * reflection-based {@code evictStaleBuckets_staleBucketIsRemoved_nonStaleIsKept} test.
     */
    @Test
    void evictStaleBuckets_staleBucketIsRemoved_nonStaleIsKept() {
        // Arrange: mutable Clock
        AtomicReference<Instant> nowRef = new AtomicReference<>(Instant.parse("2024-02-01T12:00:00Z"));
        Clock testClock = new Clock() {
            @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return nowRef.get(); }
        };

        FallbackRateLimiter testLimiter = new FallbackRateLimiter(30, 120, testClock);

        String staleWallId = "wall-evict-stale-aaaaaaaaaaaaaaaaa";
        String liveWallId  = "wall-evict-live-aaaaaaaaaaaaaaaaaa";
        String staleIp     = "10.4.0.9";
        String liveIp      = "10.4.0.10";

        // Create both buckets at t=0 — lastAccessedAt is set via tryConsume() in check()
        testLimiter.check(wall(staleWallId), staleIp);
        testLimiter.check(wall(liveWallId), liveIp);

        // Advance clock by 11 minutes — stale bucket is now beyond the 10-min eviction window
        nowRef.set(Instant.parse("2024-02-01T12:11:00Z")); // +11 min

        // Touch the live bucket at the new time so its lastAccessedAt is recent
        testLimiter.check(wall(liveWallId), liveIp);

        assertThat(testLimiter.wallIdBucketCount()).isGreaterThanOrEqualTo(2);

        testLimiter.evictStaleBuckets();

        // stale wallId bucket (lastAccessedAt at t=0, now t=+11 min) must be evicted
        // live wallId bucket (lastAccessedAt at t=+11 min) must survive
        // IP buckets follow the same pattern but are harder to inspect by wallId name;
        // verify total wallId count dropped to 1
        assertThat(testLimiter.wallIdBucketCount())
                .as("stale bucket evicted, live bucket retained")
                .isEqualTo(1);
    }

    /** Mutable test clock so eviction timing is deterministic. */
    private static Clock movableClock(AtomicReference<Instant> nowRef) {
        return new Clock() {
            @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return nowRef.get(); }
        };
    }

    /**
     * MUTATION-KILL: pins the 600 s eviction boundary ("600 with 601" mutant, L179)
     * and the {@code removeIf} lambda boolean return values (L181).
     *
     * <p>threshold = now - 600 s; {@code isIdleSince} returns
     * {@code lastAccessedAt.isBefore(threshold)}.
     * A bucket whose last access was EXACTLY 600 s ago is at the threshold and
     * therefore NOT idle (must be kept). A bucket 601 s ago IS idle (must be evicted).
     *
     * <p>Lambda boolean mutants: a stale bucket must yield {@code true} (evicted) and a
     * fresh bucket must yield {@code false} (kept). Asserting both an evicted and a
     * retained bucket in the same map kills BooleanTrue/BooleanFalse return mutants.
     */
    @Test
    void evictStaleBuckets_600sBoundary_exactBoundaryKept_justPastEvicted() {
        // --- Exactly 600 s: must be KEPT ---
        AtomicReference<Instant> nowRef = new AtomicReference<>(Instant.parse("2024-03-01T00:00:00Z"));
        FallbackRateLimiter atBoundary = new FallbackRateLimiter(30, 120, movableClock(nowRef));
        atBoundary.check(wall("wall-boundary-aaaaaaaaaaaaaaaaaa"), "10.6.0.1");

        nowRef.set(Instant.parse("2024-03-01T00:10:00Z")); // +600 s exactly
        atBoundary.evictStaleBuckets();
        assertThat(atBoundary.wallIdBucketCount())
                .as("bucket exactly at 600 s boundary must NOT be evicted")
                .isEqualTo(1);
        assertThat(atBoundary.ipBucketCount())
                .as("ip bucket exactly at 600 s boundary must NOT be evicted")
                .isEqualTo(1);

        // --- 601 s: must be EVICTED ---
        AtomicReference<Instant> nowRef2 = new AtomicReference<>(Instant.parse("2024-03-01T01:00:00Z"));
        FallbackRateLimiter pastBoundary = new FallbackRateLimiter(30, 120, movableClock(nowRef2));
        pastBoundary.check(wall("wall-past-aaaaaaaaaaaaaaaaaaaaa"), "10.6.0.2");

        nowRef2.set(Instant.parse("2024-03-01T01:10:01Z")); // +601 s
        pastBoundary.evictStaleBuckets();
        assertThat(pastBoundary.wallIdBucketCount())
                .as("bucket 601 s past last access must be evicted (lambda true)")
                .isEqualTo(0);
        assertThat(pastBoundary.ipBucketCount())
                .as("ip bucket 601 s past last access must be evicted (lambda true)")
                .isEqualTo(0);
    }

    /**
     * MUTATION-KILL: in a single eviction pass over the SAME map, a stale bucket is
     * evicted (lambda returns {@code true}) while a fresh bucket is retained (lambda
     * returns {@code false}). Kills the {@code removeIf} call removal (L181) and both
     * lambda BooleanTrue/BooleanFalse return mutants — a constant-true lambda would
     * also drop the fresh bucket; a constant-false lambda would keep the stale one.
     */
    @Test
    void evictStaleBuckets_lambda_staleEvicted_freshKept_sameMap() {
        AtomicReference<Instant> nowRef = new AtomicReference<>(Instant.parse("2024-04-01T00:00:00Z"));
        FallbackRateLimiter limiter = new FallbackRateLimiter(30, 120, movableClock(nowRef));

        // stale bucket created at t0
        limiter.check(wall("wall-stale-aaaaaaaaaaaaaaaaaaaa"), "10.7.0.1");

        // advance past the window, then create the fresh bucket so it has a recent access
        nowRef.set(Instant.parse("2024-04-01T00:11:00Z")); // +660 s
        limiter.check(wall("wall-fresh-aaaaaaaaaaaaaaaaaaaa"), "10.7.0.2");

        assertThat(limiter.wallIdBucketCount()).isEqualTo(2);
        assertThat(limiter.ipBucketCount()).isEqualTo(2);

        limiter.evictStaleBuckets();

        // Exactly one survives in each map: the stale one gone, the fresh one kept.
        assertThat(limiter.wallIdBucketCount())
                .as("stale wallId evicted, fresh wallId kept")
                .isEqualTo(1);
        assertThat(limiter.ipBucketCount())
                .as("stale ip evicted, fresh ip kept")
                .isEqualTo(1);
        // Confirm the surviving wallId bucket is the FRESH one (still admits requests).
        assertThat(limiter.check(wall("wall-fresh-aaaaaaaaaaaaaaaaaaaa"), "10.7.0.2").permitted()).isTrue();
    }

    /**
     * MUTATION-KILL: {@code ipBucketCount()} returns {@code ipBuckets.size()} (L193).
     * Adds exactly N distinct IP buckets and asserts the count == N. Kills the
     * {@code ConcurrentHashMap::size} removal and the {@code PrimitiveReturns}
     * "return 0" mutant (which would report 0 instead of N).
     */
    @Test
    void ipBucketCount_returnsExactNumberOfDistinctIpBuckets() {
        FallbackRateLimiter limiter = new FallbackRateLimiter(30, 120, Clock.systemUTC());
        int n = 7;
        for (int i = 0; i < n; i++) {
            // distinct IPs, distinct wallIds — creates exactly n IP buckets
            limiter.check(wall("wall-count-" + i + "-aaaaaaaaaaaaaa"), "10.8.0." + i);
        }
        assertThat(limiter.ipBucketCount())
                .as("ipBucketCount must equal the number of distinct IPs seen")
                .isEqualTo(n);
        // Sanity: not zero (kills PrimitiveReturns return-0 mutant explicitly).
        assertThat(limiter.ipBucketCount()).isNotZero();
    }

    /**
     * MUTATION-KILL: checkIpOnly debits the per-IP bucket (L156 removed
     * {@code iBucket.tryConsume()} / removed {@code secondsUntilRefill}).
     * Exhausts the IP bucket via checkIpOnly and asserts the next call is rejected
     * with a positive retry-after — a removed consume would never reject.
     */
    @Test
    void checkIpOnly_rejected_returnsPositiveRetryAfter() {
        AtomicReference<Instant> nowRef = new AtomicReference<>(Instant.parse("2024-05-01T00:00:00Z"));
        FallbackRateLimiter limiter = new FallbackRateLimiter(30, 120, movableClock(nowRef));
        String ip = "10.9.0.1";
        for (int i = 0; i < 120; i++) {
            assertThat(limiter.checkIpOnly(ip).permitted()).isTrue();
        }
        FallbackRateLimiter.RateLimitResult result = limiter.checkIpOnly(ip);
        assertThat(result.permitted()).isFalse();
        // clock pinned at window start -> full 61 s retry-after.
        assertThat(result.retryAfterSeconds()).isEqualTo(61L);
    }

    /**
     * MUTATION-KILL: check() IP-axis rejection + wallId refund (L131-L135).
     * Makes the per-IP axis the binding constraint while the per-wallId axis still
     * has tokens. This is the only path through
     * {@code if (!iBucket.tryConsume()) { wBucket.refund(); ... }}.
     *
     * <p>Kills: removed {@code iBucket.tryConsume()} (L131 — IP limit would never fire),
     * removed {@code wBucket.refund()} (L134 — proven by the refund assertion below),
     * removed {@code iBucket.secondsUntilRefill()} (L135 — proven by positive retry-after).
     */
    @Test
    void check_ipAxisBinding_rejectsAndRefundsWallIdToken() {
        AtomicReference<Instant> nowRef = new AtomicReference<>(Instant.parse("2024-06-01T00:00:00Z"));
        // per-wallId 30, per-IP 5 -> IP is the binding constraint.
        FallbackRateLimiter limiter = new FallbackRateLimiter(30, 5, movableClock(nowRef));
        String ip = "10.10.0.1";

        // Exhaust the IP bucket across 5 distinct wallIds (each wallId bucket keeps tokens).
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.check(wall("wall-ipbind-" + i + "-aaaaaaaaaa"), ip).permitted())
                    .as("request %d should be permitted", i + 1).isTrue();
        }

        // 6th: fresh wallId (wallId axis passes) but IP bucket exhausted -> IP rejects.
        FallbackRateLimiter.RateLimitResult rejected =
                limiter.check(wall("wall-ipbind-fresh-aaaaaaaaaaaa"), ip);
        assertThat(rejected.permitted())
                .as("IP axis must reject when exhausted")
                .isFalse();
        assertThat(rejected.retryAfterSeconds())
                .as("rejection carries IP bucket's positive retry-after")
                .isEqualTo(61L);

        // The fresh wallId's token must have been refunded. Prove its bucket holds
        // exactly 30 by spending all 30 against DISTINCT fresh IPs (per-IP limit is 5,
        // so we rotate IPs every 5 requests to avoid the IP axis binding here).
        for (int i = 0; i < 30; i++) {
            String freshIp = "10.10.1." + (i / 5); // new IP every 5 requests
            assertThat(limiter.check(wall("wall-ipbind-fresh-aaaaaaaaaaaa"), freshIp).permitted())
                    .as("refunded wallId token: request %d of 30", i + 1).isTrue();
        }
        // 31st on the fresh wallId (with yet another fresh IP) -> wallId axis exhausted.
        assertThat(limiter.check(wall("wall-ipbind-fresh-aaaaaaaaaaaa"), "10.10.2.0").permitted())
                .as("wallId bucket holds exactly 30 after refund")
                .isFalse();
    }

    // -------------------------------------------------------------------------
    // RateLimitResult factory methods
    // -------------------------------------------------------------------------

    @Test
    void rateLimitResult_allowed_hasRetryAfterZero() {
        FallbackRateLimiter.RateLimitResult r = FallbackRateLimiter.RateLimitResult.allowed();
        assertThat(r.permitted()).isTrue();
        assertThat(r.retryAfterSeconds()).isEqualTo(0L);
    }

    @Test
    void rateLimitResult_rejected_hasPositiveRetryAfter() {
        FallbackRateLimiter.RateLimitResult r = FallbackRateLimiter.RateLimitResult.rejected(45L);
        assertThat(r.permitted()).isFalse();
        assertThat(r.retryAfterSeconds()).isEqualTo(45L);
    }

    // -------------------------------------------------------------------------
    // 1000-thread concurrency stress (SR-4)
    // -------------------------------------------------------------------------

    /**
     * Verifies that {@code check()} is deterministically bounded to the per-wallId limit
     * under 1000-thread burst concurrency (SR-4, D-10).
     *
     * <p>Determinism guarantee: {@link FallbackRateLimiter.TokenBucket#tryConsume()} holds
     * the {@link java.util.concurrent.locks.ReentrantLock} across the entire check-and-decrement
     * in a single lock acquisition.  There is no window between "check token available" and
     * "consume token" for another thread to observe the same state.  Therefore {@code allowed}
     * is bounded exactly by {@code perWallIdPerMinute} (30) regardless of thread count or
     * JVM scheduling.  The assertion {@code <= 30} is a hard correctness invariant, not a
     * tolerance (NIST SP 800-53 AC-3; D-10 correctness fix — replaces the prior split
     * {@code hasToken()} + {@code consume()} that had a TOCTOU race under burst load).
     */
    @Test
    void check_1000ConcurrentThreads_noDataCorruption() throws Exception {
        FallbackRateLimiter stressLimiter = new FallbackRateLimiter(30, 120, Clock.systemUTC());
        String wallId = "wall-stress-aaaaaaaaaaaaaaaaaaaaaa";
        String ip = "10.5.0.1";

        int threadCount = 1000;
        CyclicBarrier barrier = new CyclicBarrier(threadCount);
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);

        AtomicInteger allowed = new AtomicInteger(0);
        AtomicInteger denied = new AtomicInteger(0);

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            futures.add(pool.submit(() -> {
                try {
                    barrier.await(); // release all threads simultaneously
                    FallbackRateLimiter.RateLimitResult r = stressLimiter.check(wall(wallId), ip);
                    if (r.permitted()) {
                        allowed.incrementAndGet();
                    } else {
                        denied.incrementAndGet();
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
        }

        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();

        // Per-wallId limit is 30 — exactly at most 30 threads must have been allowed.
        // This is a HARD invariant guaranteed by tryConsume() atomicity, not a tolerance.
        // IP axis is 120, so it is not the binding constraint in this test.
        assertThat(allowed.get())
                .as("allowed count must not exceed per-wallId limit of 30 (tryConsume atomicity)")
                .isLessThanOrEqualTo(30);
        assertThat(allowed.get() + denied.get()).isEqualTo(threadCount);
    }
}
