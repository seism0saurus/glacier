package de.seism0saurus.glacier;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test verifying that {@link GlacierCookieProperties} binds correctly
 * for valid inputs and fails-fast (via {@link GlacierBindHandler}) for missing,
 * empty, or non-boolean values of {@code glacier.cookie.secure}.
 *
 * <h2>Security scope (ADR-P3B-1, SR-P3B-07)</h2>
 * <p>{@code glacier.cookie.secure} is now a {@code @NotNull Boolean} field. A missing
 * or unconvertible value must cause context startup failure — not silently default to
 * {@code false} (= no Secure flag = session hijackable over HTTP). CWE-1188, ASVS V14.1.1.
 *
 * <h2>Testing approach</h2>
 * <p>Uses {@link ApplicationContextRunner} with only
 * {@link ConfigurationPropertiesAutoConfiguration} and {@link ValidationAutoConfiguration}
 * loaded — no full Spring Boot context. This isolates the binding and validation behavior
 * without starting the entire application.
 *
 * <p>References: ADR-P3B-1; ADR-P3B-3; SR-P3B-07; CWE-1188; OWASP A02:2021.
 */
class GlacierCookiePropertiesBindIT {

    /**
     * Reusable base runner configured with only the beans needed to test
     * {@link GlacierCookieProperties} binding in isolation.
     */
    private ApplicationContextRunner baseRunner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        ValidationAutoConfiguration.class,
                        ConfigurationPropertiesAutoConfiguration.class))
                .withUserConfiguration(GlacierCookieProperties.class, GlacierBindHandler.class);
    }

    // =========================================================================
    // Happy path: valid boolean values bind correctly
    // =========================================================================

    @Nested
    class HappyPaths {

        /**
         * Arrange: {@code glacier.cookie.secure=true}
         * Act: bind into {@link GlacierCookieProperties}
         * Assert: {@code getSecure()} returns {@link Boolean#TRUE}
         */
        @Test
        void cookieSecureTrue_bindsToTrue() {
            baseRunner()
                    .withPropertyValues("glacier.cookie.secure=true")
                    .run(context -> {
                        assertThat(context)
                                .as("Context must start successfully when glacier.cookie.secure=true")
                                .hasNotFailed();
                        GlacierCookieProperties props = context.getBean(GlacierCookieProperties.class);
                        assertThat(props.getSecure())
                                .as("getSecure() must return Boolean.TRUE for glacier.cookie.secure=true")
                                .isEqualTo(Boolean.TRUE);
                    });
        }

        /**
         * Arrange: {@code glacier.cookie.secure=false}
         * Act: bind into {@link GlacierCookieProperties}
         * Assert: {@code getSecure()} returns {@link Boolean#FALSE}
         */
        @Test
        void cookieSecureFalse_bindsToFalse() {
            baseRunner()
                    .withPropertyValues("glacier.cookie.secure=false")
                    .run(context -> {
                        assertThat(context)
                                .as("Context must start successfully when glacier.cookie.secure=false")
                                .hasNotFailed();
                        GlacierCookieProperties props = context.getBean(GlacierCookieProperties.class);
                        assertThat(props.getSecure())
                                .as("getSecure() must return Boolean.FALSE for glacier.cookie.secure=false")
                                .isEqualTo(Boolean.FALSE);
                    });
        }
    }

    // =========================================================================
    // Failure paths: missing / empty / non-boolean values fail-fast
    // =========================================================================

    @Nested
    class FailurePaths {

        /**
         * Arrange: {@code glacier.cookie.secure} property is completely absent
         * Act: attempt to bind {@link GlacierCookieProperties}
         * Assert: context fails to start — {@code @NotNull} rejects null binding
         *
         * <p>This is the primary CWE-1188 guard: no silent {@code false} default.
         */
        @Test
        void missingCookieSecure_contexFailsToStart() {
            baseRunner()
                    // glacier.cookie.secure is NOT set — simulates deployment misconfiguration
                    .run(context -> {
                        assertThat(context)
                                .as("Context must FAIL when glacier.cookie.secure is absent — "
                                        + "@NotNull prevents silent false default (CWE-1188, ADR-P3B-1)")
                                .hasFailed();
                    });
        }

        /**
         * Arrange: {@code glacier.cookie.secure=} (empty string)
         * Act: attempt to bind {@link GlacierCookieProperties}
         * Assert: context fails to start — empty string cannot be converted to {@link Boolean}
         */
        @Test
        void emptyCookieSecure_contextFailsToStart() {
            baseRunner()
                    .withPropertyValues("glacier.cookie.secure=")
                    .run(context -> {
                        assertThat(context)
                                .as("Context must FAIL when glacier.cookie.secure is empty")
                                .hasFailed();
                    });
        }

        /**
         * Arrange: {@code glacier.cookie.secure=notabool}
         * Act: attempt to bind {@link GlacierCookieProperties}
         * Assert: context fails to start — type conversion failure via BRANCH 2 of
         *         {@link GlacierBindHandler.ScrubbingBindHandler}
         *
         * <p>Additionally asserts: the raw value {@code "notabool"} does NOT appear in
         * any exception message in the chain (GlacierBindHandler BRANCH 2 scrubs it).
         */
        @Test
        void nonBooleanCookieSecure_contextFailsAndDoesNotLeakRawValue() {
            baseRunner()
                    .withPropertyValues("glacier.cookie.secure=notabool")
                    .run(context -> {
                        assertThat(context)
                                .as("Context must FAIL when glacier.cookie.secure is not a boolean")
                                .hasFailed();

                        // Walk the entire exception chain: the raw value must not appear anywhere.
                        // GlacierBindHandler BRANCH 2 scrubs type-conversion failures.
                        Throwable cause = context.getStartupFailure();
                        while (cause != null) {
                            String message = cause.getMessage();
                            if (message != null) {
                                assertThat(message)
                                        .as("Raw value 'notabool' must not appear in exception chain — "
                                                + "GlacierBindHandler BRANCH 2 must scrub type-conversion failures "
                                                + "(SR-P3B-07, ADR-P3A-7, CWE-532, ASVS V7.3.1 L1)")
                                        .doesNotContain("notabool");
                            }
                            cause = cause.getCause();
                        }
                    });
        }
    }
}
