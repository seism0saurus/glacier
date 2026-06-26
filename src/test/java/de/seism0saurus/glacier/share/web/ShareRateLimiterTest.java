package de.seism0saurus.glacier.share.web;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ShareRateLimiter}.
 *
 * <p>Verifies the six share-specific rate-limit axes:
 * <ul>
 *   <li>share.create.perMinutePerWallId = 5</li>
 *   <li>share.create.perMinutePerIp = 20</li>
 *   <li>share.csrf.perMinutePerIp = 60</li>
 *   <li>share.fallback.perMinutePerViewer = 20</li>
 *   <li>share.fallback.perMinutePerIp = 120 (shared)</li>
 *   <li>share.imgproxy.perMinutePerIp = 300</li>
 * </ul>
 *
 * <p>Security: OWASP API4, SR-SHARE-12.
 */
class ShareRateLimiterTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-04-22T10:00:00Z"), ZoneOffset.UTC);

    // -----------------------------------------------------------------------
    // share.create axes (SR-SHARE-12 — brute-force share creation prevention)
    // -----------------------------------------------------------------------

    @Test
    void createShare_perWallId_allowedUntilLimit() {
        ShareRateLimiter limiter = new ShareRateLimiter(FIXED_CLOCK);
        String wallId = "wall-1";

        for (int i = 0; i < 5; i++) {
            assertThat(limiter.checkShareCreate(wallId, "1.2.3.4").permitted())
                    .as("Request %d should be permitted", i + 1).isTrue();
        }
        // 6th request exceeds per-wallId limit of 5
        assertThat(limiter.checkShareCreate(wallId, "1.2.3.4").permitted()).isFalse();
    }

    @Test
    void createShare_perIp_allowedUntilLimit() {
        ShareRateLimiter limiter = new ShareRateLimiter(FIXED_CLOCK);
        // 20 different wallIds, same IP — per-IP limit is 20
        for (int i = 0; i < 20; i++) {
            assertThat(limiter.checkShareCreate("wall-" + i, "5.5.5.5").permitted())
                    .as("Request %d should be permitted", i + 1).isTrue();
        }
        // 21st from same IP is rejected even with a new wallId
        assertThat(limiter.checkShareCreate("wall-new", "5.5.5.5").permitted()).isFalse();
    }

    @Test
    void createShare_rejected_returnsPositiveRetryAfter() {
        ShareRateLimiter limiter = new ShareRateLimiter(FIXED_CLOCK);
        for (int i = 0; i < 5; i++) limiter.checkShareCreate("wall-a", "1.1.1.1");
        var result = limiter.checkShareCreate("wall-a", "1.1.1.1");
        assertThat(result.permitted()).isFalse();
        assertThat(result.retryAfterSeconds()).isGreaterThan(0);
    }

    // -----------------------------------------------------------------------
    // share.csrf axis
    // -----------------------------------------------------------------------

    @Test
    void csrfToken_perIp_allowedUntilLimit() {
        ShareRateLimiter limiter = new ShareRateLimiter(FIXED_CLOCK);
        for (int i = 0; i < 60; i++) {
            assertThat(limiter.checkCsrfIssuance("2.2.2.2").permitted()).isTrue();
        }
        assertThat(limiter.checkCsrfIssuance("2.2.2.2").permitted()).isFalse();
    }

    // -----------------------------------------------------------------------
    // share.fallback axes
    // -----------------------------------------------------------------------

    @Test
    void fallbackShare_perViewer_allowedUntilLimit() {
        ShareRateLimiter limiter = new ShareRateLimiter(FIXED_CLOCK);
        for (int i = 0; i < 20; i++) {
            assertThat(limiter.checkShareFallback("sv_viewer-1", "3.3.3.3").permitted()).isTrue();
        }
        assertThat(limiter.checkShareFallback("sv_viewer-1", "3.3.3.3").permitted()).isFalse();
    }

    @Test
    void fallbackShare_perIp_sharedAcrossViewers() {
        ShareRateLimiter limiter = new ShareRateLimiter(FIXED_CLOCK);
        // 120 different viewers from same IP — per-IP limit is 120
        for (int i = 0; i < 120; i++) {
            assertThat(limiter.checkShareFallback("sv_v-" + i, "4.4.4.4").permitted()).isTrue();
        }
        // 121st viewer from same IP is rejected
        assertThat(limiter.checkShareFallback("sv_v-new", "4.4.4.4").permitted()).isFalse();
    }

    // -----------------------------------------------------------------------
    // share.imgproxy axis
    // -----------------------------------------------------------------------

    @Test
    void imgProxy_perIp_allowedUntilLimit() {
        ShareRateLimiter limiter = new ShareRateLimiter(FIXED_CLOCK);
        for (int i = 0; i < 300; i++) {
            assertThat(limiter.checkImgProxy("6.6.6.6").permitted()).isTrue();
        }
        assertThat(limiter.checkImgProxy("6.6.6.6").permitted()).isFalse();
    }

    // -----------------------------------------------------------------------
    // Axis isolation: different axes use separate buckets
    // -----------------------------------------------------------------------

    @Test
    void axes_areIsolated_exhaustingOneDoesNotAffectAnother() {
        ShareRateLimiter limiter = new ShareRateLimiter(FIXED_CLOCK);
        // Exhaust share.create for this IP
        for (int i = 0; i < 5; i++) limiter.checkShareCreate("wall-x", "7.7.7.7");
        limiter.checkShareCreate("wall-x", "7.7.7.7"); // rejected

        // csrf axis is still independent
        assertThat(limiter.checkCsrfIssuance("7.7.7.7").permitted()).isTrue();
    }

    // -----------------------------------------------------------------------
    // MUTATION-KILL: IP-axis rejection + refund of the wallId/viewer token
    // (checkShareCreate L128-L130, checkShareFallback L169-L171)
    // -----------------------------------------------------------------------

    /**
     * Drives checkShareCreate so the IP axis is the binding constraint while the
     * wallId axis still has tokens. This is the only path that hits the
     * {@code if (!ib.tryConsume()) { wb.refund(); ... }} branch (L128-L130).
     *
     * <p>Kills: removed {@code ib.tryConsume()} call (L128 — would make the IP
     * limit never fire so the 21st request stays permitted), removed
     * {@code wb.refund()} (L129 — proven below by the refund-restores-token
     * assertion), and removed {@code ib.secondsUntilRefill()} (L130 — proven by
     * the positive retry-after assertion).
     */
    @Test
    void createShare_ipAxisBinding_rejectsAndRefundsWallIdToken() {
        ShareRateLimiter limiter = new ShareRateLimiter(FIXED_CLOCK);
        // per-IP limit is 20; spread across 20 distinct wallIds so no single
        // wallId bucket (limit 5) becomes the binding constraint.
        for (int i = 0; i < 20; i++) {
            assertThat(limiter.checkShareCreate("wall-c-" + i, "9.9.9.9").permitted())
                    .as("request %d should be permitted", i + 1).isTrue();
        }
        // 21st request: a FRESH wallId (its own bucket is full, so the wallId
        // axis passes) but the shared IP bucket is exhausted -> IP axis rejects.
        var rejected = limiter.checkShareCreate("wall-c-fresh", "9.9.9.9");
        assertThat(rejected.permitted())
                .as("IP axis must reject the 21st request")
                .isFalse();
        assertThat(rejected.retryAfterSeconds())
                .as("rejection must carry the IP bucket's positive retry-after")
                .isGreaterThan(0L);

        // The fresh wallId bucket consumed one token during the rejected call,
        // then it MUST have been refunded (wb.refund()). Proof: that wallId can
        // still make its full 5 allowed requests against a DIFFERENT (fresh) IP.
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.checkShareCreate("wall-c-fresh", "9.9.9.99").permitted())
                    .as("refunded wallId token: request %d of 5 must be permitted", i + 1)
                    .isTrue();
        }
        // 6th on the fresh wallId is rejected -> confirms exactly 5 were available
        // (i.e. the token taken during the IP-reject was refunded, not lost).
        assertThat(limiter.checkShareCreate("wall-c-fresh", "9.9.9.99").permitted())
                .as("wallId bucket holds exactly 5 after refund")
                .isFalse();
    }

    /**
     * Same pattern for checkShareFallback: per-IP (120) is the binding constraint
     * while the per-viewer (20) bucket still has tokens — the only path through
     * {@code if (!ib.tryConsume()) { vb.refund(); ... }} (L169-L171).
     *
     * <p>Kills: removed {@code ib.tryConsume()} (L169), removed {@code vb.refund()}
     * (L170 — proven by refund assertion), removed {@code ib.secondsUntilRefill()}
     * (L171 — proven by positive retry-after).
     */
    @Test
    void fallbackShare_ipAxisBinding_rejectsAndRefundsViewerToken() {
        ShareRateLimiter limiter = new ShareRateLimiter(FIXED_CLOCK);
        // per-IP limit is 120; spread across 120 distinct viewers (each viewer
        // limit is 20) so no single viewer bucket is the binding constraint.
        for (int i = 0; i < 120; i++) {
            assertThat(limiter.checkShareFallback("sv_c-" + i, "8.8.8.8").permitted())
                    .as("request %d should be permitted", i + 1).isTrue();
        }
        // 121st: fresh viewer (viewer axis passes) but shared IP bucket exhausted.
        var rejected = limiter.checkShareFallback("sv_c-fresh", "8.8.8.8");
        assertThat(rejected.permitted())
                .as("IP axis must reject the 121st fallback request")
                .isFalse();
        assertThat(rejected.retryAfterSeconds())
                .as("rejection must carry the IP bucket's positive retry-after")
                .isGreaterThan(0L);

        // The fresh viewer's token must have been refunded: it can still make its
        // full 20 allowed requests against a different (fresh) IP.
        for (int i = 0; i < 20; i++) {
            assertThat(limiter.checkShareFallback("sv_c-fresh", "8.8.8.88").permitted())
                    .as("refunded viewer token: request %d of 20 must be permitted", i + 1)
                    .isTrue();
        }
        assertThat(limiter.checkShareFallback("sv_c-fresh", "8.8.8.88").permitted())
                .as("viewer bucket holds exactly 20 after refund")
                .isFalse();
    }

    // -----------------------------------------------------------------------
    // MUTATION-KILL: positive retry-after on single-axis rejections
    // (checkCsrfIssuance L146, checkImgProxy L187)
    // -----------------------------------------------------------------------

    /**
     * Kills the removed {@code ib.secondsUntilRefill()} call on L146: with the
     * clock fixed at the start of a window, the retry-after must be the full
     * window (61 s). A removed/zeroed call would surface a non-positive value.
     */
    @Test
    void csrfIssuance_rejected_returnsPositiveRetryAfter() {
        ShareRateLimiter limiter = new ShareRateLimiter(FIXED_CLOCK);
        for (int i = 0; i < 60; i++) limiter.checkCsrfIssuance("2.2.2.20");
        var result = limiter.checkCsrfIssuance("2.2.2.20");
        assertThat(result.permitted()).isFalse();
        assertThat(result.retryAfterSeconds()).isEqualTo(61L);
    }

    /**
     * Kills the removed {@code ib.secondsUntilRefill()} call on L187 for the
     * image-proxy axis.
     */
    @Test
    void imgProxy_rejected_returnsPositiveRetryAfter() {
        ShareRateLimiter limiter = new ShareRateLimiter(FIXED_CLOCK);
        for (int i = 0; i < 300; i++) limiter.checkImgProxy("6.6.6.60");
        var result = limiter.checkImgProxy("6.6.6.60");
        assertThat(result.permitted()).isFalse();
        assertThat(result.retryAfterSeconds()).isEqualTo(61L);
    }

    // -----------------------------------------------------------------------
    // MUTATION-KILL: evictStaleBuckets across all six bucket maps + 600s boundary
    // (L200 Clock::instant + Instant::minusSeconds, L201-L206 removeIf per map)
    // -----------------------------------------------------------------------

    /** Mutable test clock so eviction timing is deterministic. */
    private static Clock movableClock(AtomicReference<Instant> nowRef) {
        return new Clock() {
            @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return nowRef.get(); }
        };
    }

    // Reflection accessor for a bucket map's size. Token-count assertions cannot prove eviction
    // here: advancing the clock past the idle window also REFILLS a surviving bucket, so
    // "evicted+recreated" and "survived+refilled" are indistinguishable by token behavior. The
    // only sound observable for eviction is the bucket-map entry count. ShareRateLimiter exposes
    // no count accessor, so we read the private map directly (classpath app, no modules).
    @SuppressWarnings("unchecked")
    private static int mapSize(ShareRateLimiter limiter, String fieldName) {
        try {
            java.lang.reflect.Field f = ShareRateLimiter.class.getDeclaredField(fieldName);
            f.setAccessible(true);
            return ((java.util.Map<String, ?>) f.get(limiter)).size();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("reflective map size for " + fieldName + " failed", e);
        }
    }

    private static final String[] ALL_BUCKET_MAPS = {
            "createWallIdBuckets", "createIpBuckets", "csrfIpBuckets",
            "fallbackViewerBuckets", "fallbackIpBuckets", "imgProxyIpBuckets"
    };

    /**
     * Touches every one of the six bucket maps, advances the clock past the 600 s idle window,
     * and asserts ALL six maps are emptied by eviction — observed via map size, not token counts.
     *
     * <p>Kills the per-map {@code removeIf} mutants (L201-L206): if any single {@code removeIf}
     * (or its {@code entrySet()}) is removed, that map keeps its entry and its size assertion
     * fails. Also kills {@code clock.instant()} / {@code minusSeconds(600)} removal on L200 (no
     * threshold → no "idle" verdict → nothing removed → sizes stay 1).
     */
    @Test
    void evictStaleBuckets_allSixMaps_staleEntriesRemoved() {
        AtomicReference<Instant> nowRef = new AtomicReference<>(Instant.parse("2026-04-22T10:00:00Z"));
        ShareRateLimiter limiter = new ShareRateLimiter(movableClock(nowRef));

        // Populate one bucket in each of the six maps at t0.
        limiter.checkShareCreate("wall-e", "ip-create");      // createWallId + createIp
        limiter.checkCsrfIssuance("ip-csrf");                 // csrfIp
        limiter.checkShareFallback("sv_e", "ip-fb");          // fallbackViewer + fallbackIp
        limiter.checkImgProxy("ip-img");                      // imgProxyIp

        for (String map : ALL_BUCKET_MAPS) {
            assertThat(mapSize(limiter, map)).as("%s has one bucket before eviction", map).isEqualTo(1);
        }

        // Advance well past the 600 s idle window so EVERY bucket is stale.
        nowRef.set(Instant.parse("2026-04-22T10:11:00Z")); // +660 s
        limiter.evictStaleBuckets();

        for (String map : ALL_BUCKET_MAPS) {
            assertThat(mapSize(limiter, map))
                    .as("%s must be emptied by eviction (removed removeIf/entrySet would leave it at 1)", map)
                    .isZero();
        }
    }

    /**
     * Kills the per-map eviction-predicate lambdas (L201-L205 {@code BooleanTrueReturnVals}): a
     * predicate forced to always-return-true would evict FRESH buckets too. Populate one fresh
     * bucket in every map, run eviction with NO clock advance (nothing is stale), and assert every
     * map RETAINS its bucket. Under an always-true predicate on map X, map X empties → size 0 →
     * the per-map assertion fails. (The imgProxy lambda L206 is already pinned by the boundary
     * test's "kept at exactly 600 s" case; this test covers the other five maps' fresh-keep path.)
     */
    @Test
    void evictStaleBuckets_freshBucketsInEveryMap_areAllRetained() {
        AtomicReference<Instant> nowRef = new AtomicReference<>(Instant.parse("2026-04-22T10:00:00Z"));
        ShareRateLimiter limiter = new ShareRateLimiter(movableClock(nowRef));

        limiter.checkShareCreate("wall-fresh", "ip-create-fresh"); // createWallId + createIp
        limiter.checkCsrfIssuance("ip-csrf-fresh");                // csrfIp
        limiter.checkShareFallback("sv_fresh", "ip-fb-fresh");     // fallbackViewer + fallbackIp
        limiter.checkImgProxy("ip-img-fresh");                     // imgProxyIp

        // No clock advance: every bucket was just touched, so none is idle past 600 s.
        limiter.evictStaleBuckets();

        for (String map : ALL_BUCKET_MAPS) {
            assertThat(mapSize(limiter, map))
                    .as("%s holds a FRESH bucket that eviction must keep (always-true predicate would drop it)", map)
                    .isEqualTo(1);
        }
    }

    /**
     * Pins the 600 s eviction boundary ("600 with 601" mutant on L200), observed via map size: a
     * bucket whose last access is EXACTLY 600 s before "now" must NOT be evicted (threshold =
     * now-600 = t0; idle requires lastAccessedAt.isBefore(threshold), and t0 is not before t0). A
     * bucket 601 s before "now" MUST be evicted. The +601 case is what kills the "600 with 601"
     * mutant (under minusSeconds(601) the threshold would be t0 and the bucket would survive).
     */
    @Test
    void evictStaleBuckets_600sBoundary_exactlyAtBoundaryKept_justPastEvicted() {
        AtomicReference<Instant> nowRef = new AtomicReference<>(Instant.parse("2026-04-22T10:00:00Z"));
        ShareRateLimiter limiter = new ShareRateLimiter(movableClock(nowRef));
        limiter.checkImgProxy("ip-boundary"); // bucket at t0
        assertThat(mapSize(limiter, "imgProxyIpBuckets")).as("one bucket at t0").isEqualTo(1);

        nowRef.set(Instant.parse("2026-04-22T10:10:00Z")); // +600 s exactly
        limiter.evictStaleBuckets();
        assertThat(mapSize(limiter, "imgProxyIpBuckets"))
                .as("bucket idle exactly 600 s is on the boundary (not strictly before threshold) → kept")
                .isEqualTo(1);

        ShareRateLimiter limiter2 = new ShareRateLimiter(movableClock(nowRef));
        nowRef.set(Instant.parse("2026-04-22T11:00:00Z")); // reset frame for limiter2
        limiter2.checkImgProxy("ip-past");
        assertThat(mapSize(limiter2, "imgProxyIpBuckets")).as("one bucket at t0'").isEqualTo(1);
        nowRef.set(Instant.parse("2026-04-22T11:10:01Z")); // +601 s
        limiter2.evictStaleBuckets();
        assertThat(mapSize(limiter2, "imgProxyIpBuckets"))
                .as("bucket idle 601 s (> 600 s) must be evicted")
                .isZero();
    }
}
