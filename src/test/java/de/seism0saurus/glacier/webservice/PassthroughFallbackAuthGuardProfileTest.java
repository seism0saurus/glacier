package de.seism0saurus.glacier.webservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link PassthroughFallbackAuthGuard} is absent from the default (production)
 * Spring application context (FIX E, D-08, ADR-06, OWASP API2).
 *
 * <p>The passthrough guard is annotated with {@code @Profile("test")}. Without that profile
 * active, it must not be registered — not even via {@code @Qualifier} injection. This test
 * runs the context without any profile and asserts the bean is missing.
 *
 * <p>This is a lightweight {@link ApplicationContextRunner} test — no full Spring Boot
 * context required, so Surefire processes it as a unit test.
 */
class PassthroughFallbackAuthGuardProfileTest {

    /**
     * In the default profile (no "test"), {@link PassthroughFallbackAuthGuard} must not
     * be present in the application context.
     */
    @Test
    void passthroughGuard_defaultProfile_beanIsAbsent() {
        new ApplicationContextRunner()
                .withUserConfiguration(PassthroughFallbackAuthGuard.class)
                // No profile set — simulates production context
                .run(context -> assertThat(context)
                        .doesNotHaveBean(PassthroughFallbackAuthGuard.class));
    }

    /**
     * With the "test" profile active, {@link PassthroughFallbackAuthGuard} IS present
     * (so tests that need a passthrough guard can opt into it).
     */
    @Test
    void passthroughGuard_testProfile_beanIsPresent() {
        new ApplicationContextRunner()
                .withUserConfiguration(PassthroughFallbackAuthGuard.class)
                .withPropertyValues("spring.profiles.active=test")
                .run(context -> assertThat(context)
                        .hasSingleBean(PassthroughFallbackAuthGuard.class));
    }
}
