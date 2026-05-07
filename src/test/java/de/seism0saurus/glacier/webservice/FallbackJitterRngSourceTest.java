package de.seism0saurus.glacier.webservice;

import de.seism0saurus.glacier.webservice.cache.FallbackRateLimiter;
import de.seism0saurus.glacier.webservice.cache.MessageCache;
import de.seism0saurus.glacier.webservice.security.ClientIpResolver;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.security.SecureRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Sec-13/P2-15: verifies that {@link FallbackController}'s jitter source is
 * {@link SecureRandom}, not {@code ThreadLocalRandom} or {@code Math.random()}.
 *
 * <p>Rationale (resolved conflict): predictable jitter from {@code ThreadLocalRandom}
 * or {@code Math.random()} defeats the timing-channel mitigation. An attacker who can
 * predict the jitter can subtract it and recover the true response time. {@link SecureRandom}
 * is required per ASVS V6.3.1 (L2).
 *
 * <p>Test uses reflection to verify the field type, not behaviour — this is a structural
 * assertion that the right class is used. Behaviour is tested in {@code FallbackTimingIT}.
 *
 * <p>Security: Sec-13/P2-15; ASVS V6.3.1 (L2) — CSPRNG required for security-relevant
 * randomness; OWASP A02:2021 Cryptographic Failures.
 */
class FallbackJitterRngSourceTest {

    /**
     * Verifies the {@code SECURE_RANDOM} field in {@link FallbackController} is of type
     * {@link SecureRandom}.
     *
     * <p>Arrange: use reflection to access the {@code SECURE_RANDOM} static field.
     * Act: retrieve the field value.
     * Assert: the value is an instance of {@link SecureRandom} (not {@code Random},
     * {@code ThreadLocalRandom}, or any other weaker source).
     */
    @Test
    void fallbackController_jitterField_isSecureRandom() throws Exception {
        Field rngField = FallbackController.class.getDeclaredField("SECURE_RANDOM");
        rngField.setAccessible(true);
        Object rng = rngField.get(null); // static field
        assertThat(rng)
                .as("FallbackController.SECURE_RANDOM must be a SecureRandom instance "
                        + "(Sec-13, ASVS V6.3.1 L2 — not ThreadLocalRandom or Math.random)")
                .isInstanceOf(SecureRandom.class);
    }

    /**
     * Verifies the field name is exactly {@code SECURE_RANDOM} (constant naming).
     * This guards against a refactor that renames the field without updating the test.
     */
    @Test
    void fallbackController_jitterFieldName_isSecureRandom() {
        boolean found = false;
        for (Field field : FallbackController.class.getDeclaredFields()) {
            if ("SECURE_RANDOM".equals(field.getName())) {
                found = true;
                break;
            }
        }
        assertThat(found)
                .as("FallbackController must declare a static field named SECURE_RANDOM "
                        + "(naming convention for security-critical constants — Sec-13)")
                .isTrue();
    }

    /**
     * Verifies that jitterMsMax=0 disables jitter (no sleep applied).
     * This tests the escape hatch used in latency-sensitive tests.
     *
     * <p>Arrange: build controller with jitterMsMax=0.
     * Act: call {@code applyJitter()} 100 times and measure total wall time.
     * Assert: total time is under 10 ms (much less than 100 × 1 ms).
     */
    @Test
    void applyJitter_jitterMsMaxZero_noSleepApplied() {
        MessageCache cache = mock(MessageCache.class);
        FallbackAuthGuard guard = mock(FallbackAuthGuard.class);
        FallbackRateLimiter rl = mock(FallbackRateLimiter.class);
        ClientIpResolver ipResolver = new ClientIpResolver(0);
        FallbackController controller = new FallbackController(cache, guard, rl, true, 0, ipResolver);

        long start = System.nanoTime();
        for (int i = 0; i < 100; i++) {
            controller.applyJitter();
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        assertThat(elapsedMs)
                .as("jitterMsMax=0 must not add any sleep — 100 calls should complete in < 10 ms")
                .isLessThan(10);
    }
}
