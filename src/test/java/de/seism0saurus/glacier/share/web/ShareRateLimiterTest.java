package de.seism0saurus.glacier.share.web;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

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
}
