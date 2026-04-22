package de.seism0saurus.glacier.share.domain;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ShareLinkLifetimePolicy} {@code @ConfigurationProperties} bean.
 *
 * <p>Tests the defaults (no property overrides) and an override scenario where the TTL is
 * shortened for testing — verifying that test harnesses can shrink the TTL without reflection.
 */
class ShareLinkLifetimePolicyTest {

    // ---------------------------------------------------------------------------
    // Default values
    // ---------------------------------------------------------------------------

    @Test
    void defaultTtlIsSevenDays() {
        ShareLinkLifetimePolicy policy = new ShareLinkLifetimePolicy();
        assertThat(policy.getTtl()).isEqualTo(Duration.ofDays(7));
    }

    @Test
    void defaultClockSkewToleranceIsZero() {
        ShareLinkLifetimePolicy policy = new ShareLinkLifetimePolicy();
        assertThat(policy.getClockSkewTolerance()).isEqualTo(Duration.ZERO);
    }

    @Test
    void defaultSweepIntervalMsIs300000() {
        ShareLinkLifetimePolicy policy = new ShareLinkLifetimePolicy();
        assertThat(policy.getSweepIntervalMs()).isEqualTo(300_000L);
    }

    // ---------------------------------------------------------------------------
    // Mutability — setters allow test harnesses to override without reflection
    // ---------------------------------------------------------------------------

    @Test
    void ttlCanBeOverriddenForTestDeterminism() {
        ShareLinkLifetimePolicy policy = new ShareLinkLifetimePolicy();
        policy.setTtl(Duration.ofSeconds(30));
        assertThat(policy.getTtl()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void sweepIntervalMsCanBeOverridden() {
        ShareLinkLifetimePolicy policy = new ShareLinkLifetimePolicy();
        policy.setSweepIntervalMs(1_000L);
        assertThat(policy.getSweepIntervalMs()).isEqualTo(1_000L);
    }
}
