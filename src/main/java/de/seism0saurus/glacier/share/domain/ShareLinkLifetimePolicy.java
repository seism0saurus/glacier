package de.seism0saurus.glacier.share.domain;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * {@code @ConfigurationProperties} bean for share-link lifetime settings.
 *
 * <p>Exposes the following {@code glacier.share.*} properties:
 * <ul>
 *   <li>{@code glacier.share.ttl} — link lifetime (default: 7 days, ISO-8601 duration).</li>
 *   <li>{@code glacier.share.clockSkewTolerance} — grace period at expiry boundary
 *       (default: {@code Duration.ZERO}).</li>
 *   <li>{@code glacier.share.sweepIntervalMs} — how often expired links are swept from
 *       the in-memory repository (default: 300 000 ms / 5 minutes).</li>
 * </ul>
 *
 * <p>Setters allow test harnesses to override TTL values without reflection tricks —
 * simply create an instance and call {@code setTtl(Duration.ofSeconds(30))}.
 */
@Component
@ConfigurationProperties(prefix = "glacier.share")
public class ShareLinkLifetimePolicy {

    /** Default TTL: 7 days. */
    private Duration ttl = Duration.ofDays(7);

    /** Default clock-skew tolerance: zero (no grace period). */
    private Duration clockSkewTolerance = Duration.ZERO;

    /** Sweep interval in milliseconds; matches {@code @Scheduled(fixedDelayString)} usage. */
    private long sweepIntervalMs = 300_000L;

    // -------------------------------------------------------------------------
    // Getters
    // -------------------------------------------------------------------------

    /**
     * Returns the link lifetime.  Defaults to 7 days.
     *
     * @return the TTL duration; never null
     */
    public Duration getTtl() {
        return ttl;
    }

    /**
     * Returns the clock-skew tolerance applied at expiry boundaries.
     * Defaults to {@link Duration#ZERO}.
     *
     * @return the tolerance duration; never null
     */
    public Duration getClockSkewTolerance() {
        return clockSkewTolerance;
    }

    /**
     * Returns the sweep interval in milliseconds.  Defaults to 300 000 (5 minutes).
     *
     * @return sweep interval in ms; positive
     */
    public long getSweepIntervalMs() {
        return sweepIntervalMs;
    }

    // -------------------------------------------------------------------------
    // Setters (required for @ConfigurationProperties binding + test overrides)
    // -------------------------------------------------------------------------

    /** @param ttl the link lifetime; must not be null */
    public void setTtl(final Duration ttl) {
        this.ttl = ttl;
    }

    /** @param clockSkewTolerance tolerance at expiry boundary; must not be null */
    public void setClockSkewTolerance(final Duration clockSkewTolerance) {
        this.clockSkewTolerance = clockSkewTolerance;
    }

    /** @param sweepIntervalMs sweep interval in milliseconds; must be positive */
    public void setSweepIntervalMs(final long sweepIntervalMs) {
        this.sweepIntervalMs = sweepIntervalMs;
    }
}
