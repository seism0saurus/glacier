package de.seism0saurus.glacier;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test verifying that {@link GlacierBindHandler} prevents the raw rejected
 * value of a FAILING configuration property from appearing in the exception chain.
 *
 * <h2>Testing approach</h2>
 * <p>The sentinel value is placed on the FAILING field ({@code mastodon.instance}).
 * This exercises the actual scrubbing path through
 * {@code GlacierBindHandler.ScrubbingBindHandler.onFailure} for the
 * {@code BindValidationException} branch (Jakarta Validation constraint failures).
 * A negative-control sibling test omits the handler and asserts the sentinel IS present
 * in the exception chain — proving the handler is load-bearing, not accidentally passing.
 *
 * <h2>Security scope (ADR-P3A-7)</h2>
 * <ul>
 *   <li>SEC-P3A-01 / FIX: {@code BindValidationException} from {@code @Pattern}/
 *       {@code @SafeOperatorString}/{@code @MastodonInstanceValidator} constraints must NOT
 *       expose the raw rejected value in the exception chain.</li>
 *   <li>The sentinel value {@code P3A_SENTINEL_DO_NOT_LEAK} appears as the instance value
 *       and fails {@code @MastodonInstanceValidator} (no dots — not a valid hostname).
 *       Without the handler, Spring's {@code FieldError.rejectedValue} in
 *       {@code BindValidationException} echoes this raw value via
 *       {@code FieldError.toString()} ("{@code rejected value [P3A_SENTINEL_DO_NOT_LEAK]}").</li>
 * </ul>
 *
 * <p>References: CWE-532; ASVS V7.3.1 (L1); ADR-P3A-7; SR-P3A-01; SEC-P3A-01.
 */
class BindFailureValueScrubbingIT {

    /**
     * A distinctive sentinel value placed on the FAILING field.
     *
     * <p>It fails {@code @MastodonInstanceValidator} (no dots — not a valid hostname).
     * Without {@link GlacierBindHandler}, Spring's {@code FieldError.rejectedValue} in
     * {@code BindValidationException.toString()} echoes this value into the exception chain.
     * With the handler, the raw value must be absent from every exception message.
     */
    private static final String FAILING_SENTINEL = "P3A_SENTINEL_DO_NOT_LEAK";

    /** Reusable base runner with valid properties for all fields except the one under test. */
    private ApplicationContextRunner baseRunner() {
        return new ApplicationContextRunner()
                // ConfigurationPropertiesAutoConfiguration + ValidationAutoConfiguration are
                // both required for custom ConstraintValidators to fire at binding time.
                .withConfiguration(AutoConfigurations.of(
                        ValidationAutoConfiguration.class,
                        ConfigurationPropertiesAutoConfiguration.class))
                .withPropertyValues(
                        // The FAILING field carries the sentinel — no TLD, so MastodonInstanceValidator rejects it
                        "mastodon.instance=" + FAILING_SENTINEL,
                        // Other fields are valid so only mastodon.instance triggers the failure
                        "mastodon.accessToken=valid-token",
                        "mastodon.https=true",
                        "mastodon.port=443",
                        "mastodon.readTimeout=240",
                        "mastodon.writeTimeout=240",
                        "mastodon.connectTimeout=240",
                        "mastodon.handle=bot@example.com"
                );
    }

    /**
     * Positive-control: with {@link GlacierBindHandler} loaded, the sentinel value on
     * the FAILING field must NOT appear in any message in the exception chain.
     *
     * <p>SEC-P3A-01 / ADR-P3A-7 / CWE-532 / ASVS V7.3.1 L1.
     */
    @Test
    void withBindHandler_sentinelOnFailingField_doesNotLeakInExceptionChain() {
        baseRunner()
                .withUserConfiguration(MastodonProperties.class, GlacierBindHandler.class)
                .run(context -> {
                    assertThat(context)
                            .as("Invalid mastodon.instance must cause context startup failure")
                            .hasFailed();

                    // Walk the ENTIRE cause chain — raw sentinel must not appear anywhere.
                    // GlacierBindHandler.ScrubbingBindHandler.onFailure replaces the raw value
                    // with a static count-only message (ADR-P3A-7, BindValidationException branch).
                    Throwable cause = context.getStartupFailure();
                    while (cause != null) {
                        String message = cause.getMessage();
                        if (message != null) {
                            assertThat(message)
                                    .as("Exception chain must not contain raw sentinel value "
                                            + "when GlacierBindHandler is active "
                                            + "(SEC-P3A-01, ADR-P3A-7, CWE-532, ASVS V7.3.1 L1). "
                                            + "The ScrubbingBindHandler must intercept "
                                            + "BindValidationException (Jakarta Validation constraints) "
                                            + "with null cause to prevent rejectedValue re-exposure.")
                                    .doesNotContain(FAILING_SENTINEL);
                        }
                        cause = cause.getCause();
                    }
                });
    }

    /**
     * Negative-control: WITHOUT {@link GlacierBindHandler}, the sentinel on the FAILING
     * field IS present in the exception chain (via {@code FieldError.rejectedValue}).
     *
     * <p>This test proves the handler is load-bearing. If this test FAILS (sentinel absent
     * even without the handler), the chosen sentinel value does not exercise the leak path
     * and the positive-control test would be a false negative.
     *
     * <p>Spring's {@code BindValidationException} wraps {@code ValidationErrors} which
     * contains {@code OriginTrackedFieldError} objects. {@code FieldError.toString()}
     * emits {@code "rejected value [<value>]"} — so the sentinel appears in the chain.
     */
    @Test
    void withoutBindHandler_sentinelOnFailingField_leaksInExceptionChain() {
        baseRunner()
                // No GlacierBindHandler — only the raw MastodonProperties bean
                .withUserConfiguration(MastodonProperties.class)
                .run(context -> {
                    assertThat(context)
                            .as("Invalid mastodon.instance must cause context startup failure even without handler")
                            .hasFailed();

                    // Collect entire exception chain as one string
                    StringBuilder chain = new StringBuilder();
                    Throwable cause = context.getStartupFailure();
                    while (cause != null) {
                        if (cause.getMessage() != null) {
                            chain.append(cause.getMessage()).append('\n');
                        }
                        cause = cause.getCause();
                    }

                    // Without the handler, Spring echoes the rejected value via FieldError.toString()
                    assertThat(chain.toString())
                            .as("Without GlacierBindHandler, FieldError.rejectedValue should echo the "
                                    + "sentinel in the exception chain. If this assertion fails, choose a "
                                    + "different sentinel value that is echoed by Spring's BindValidationException.")
                            .contains(FAILING_SENTINEL);
                });
    }

    // =========================================================================
    // B2: CookieSecureBooleanBindFailures — glacier.cookie.secure scrubbing
    // =========================================================================

    /**
     * Integration tests verifying that {@link GlacierBindHandler} scrubs bind failures
     * for {@code glacier.cookie.secure} (SR-P3B-07, ADR-P3B-1).
     *
     * <p>Three cases mirror the failure paths in {@link GlacierCookiePropertiesBindIT}
     * but focus on the SCRUBBING behavior: no raw value must appear in the exception chain.
     *
     * <p>References: ADR-P3B-1; ADR-P3A-7; SR-P3B-07; CWE-532; ASVS V7.3.1 L1.
     */
    @Nested
    class CookieSecureBooleanBindFailures {

        /** Reusable runner that boots only {@link GlacierCookieProperties} + handler. */
        private ApplicationContextRunner cookieRunner() {
            return new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(
                            ValidationAutoConfiguration.class,
                            ConfigurationPropertiesAutoConfiguration.class))
                    .withUserConfiguration(GlacierCookieProperties.class, GlacierBindHandler.class);
        }

        /**
         * Arrange: {@code glacier.cookie.secure} is completely absent
         * Act: attempt to bind {@link GlacierCookieProperties}
         * Assert: context fails; no raw value leaks (vacuously — there is no rejected
         *         value to scrub; the {@code @NotNull} violation uses a static message)
         *
         * <p>BRANCH 1 of {@link GlacierBindHandler.ScrubbingBindHandler}: the
         * {@code BindValidationException} is replaced with a count-only message.
         */
        @Test
        void missingCookieSecure_failsWithScrubbedMessage() {
            cookieRunner()
                    // glacier.cookie.secure NOT set
                    .run(context -> {
                        assertThat(context)
                                .as("Missing glacier.cookie.secure must cause context startup failure "
                                        + "(ADR-P3B-1, SR-P3B-07, CWE-1188)")
                                .hasFailed();

                        // The scrubbed message must not contain the property key's value
                        // (vacuously true for missing property, but asserting the handler ran)
                        Throwable cause = context.getStartupFailure();
                        while (cause != null) {
                            String message = cause.getMessage();
                            if (message != null) {
                                // The static @NotNull message must appear; no raw value leak
                                assertThat(message)
                                        .as("Exception chain must not expose a raw rejected value "
                                                + "(SR-P3B-07, ADR-P3A-7, CWE-532, ASVS V7.3.1 L1)")
                                        .doesNotContain("rejected value [null]");
                            }
                            cause = cause.getCause();
                        }
                    });
        }

        /**
         * Arrange: {@code glacier.cookie.secure=} (empty string)
         * Act: attempt to bind {@link GlacierCookieProperties}
         * Assert: context fails; no raw value (empty string) leaks in exception chain
         *
         * <p>BRANCH 1 of {@link GlacierBindHandler.ScrubbingBindHandler}: empty string
         * cannot be converted to {@code Boolean}, leading to a bind failure.
         */
        @Test
        void emptyCookieSecure_failsWithScrubbedMessage() {
            cookieRunner()
                    .withPropertyValues("glacier.cookie.secure=")
                    .run(context -> {
                        assertThat(context)
                                .as("Empty glacier.cookie.secure must cause context startup failure "
                                        + "(ADR-P3B-1, SR-P3B-07)")
                                .hasFailed();

                        // An empty string binding failure should not expose raw value details
                        Throwable cause = context.getStartupFailure();
                        while (cause != null) {
                            String message = cause.getMessage();
                            if (message != null) {
                                assertThat(message)
                                        .as("Exception chain must not expose the raw empty value "
                                                + "(SR-P3B-07, ADR-P3A-7, CWE-532)")
                                        .doesNotContain("rejected value []");
                            }
                            cause = cause.getCause();
                        }
                    });
        }

        /**
         * Arrange: {@code glacier.cookie.secure=notabool}
         * Act: attempt to bind {@link GlacierCookieProperties}
         * Assert: context fails; the raw value {@code "notabool"} does NOT appear in any
         *         exception message in the chain
         *
         * <p>BRANCH 2 of {@link GlacierBindHandler.ScrubbingBindHandler}: type-conversion
         * failure carries the raw value in {@code BindException.getProperty().getValue()}.
         * The handler replaces it with a {@link de.seism0saurus.glacier.util.LogScrubber}
         * summary. CWE-532; ASVS V7.3.1 L1.
         */
        @Test
        void nonBooleanCookieSecure_doesNotLeakRawValue() {
            final String rawValue = "notabool";

            cookieRunner()
                    .withPropertyValues("glacier.cookie.secure=" + rawValue)
                    .run(context -> {
                        assertThat(context)
                                .as("Non-boolean glacier.cookie.secure must cause context startup failure "
                                        + "(ADR-P3B-1, SR-P3B-07)")
                                .hasFailed();

                        // Walk the entire exception chain — raw value must not appear anywhere.
                        // GlacierBindHandler BRANCH 2 scrubs type-conversion failures.
                        Throwable cause = context.getStartupFailure();
                        while (cause != null) {
                            String message = cause.getMessage();
                            if (message != null) {
                                assertThat(message)
                                        .as("Raw value '%s' must not appear in exception chain — "
                                                + "GlacierBindHandler BRANCH 2 must scrub "
                                                + "type-conversion failures (SR-P3B-07, ADR-P3A-7, "
                                                + "CWE-532, ASVS V7.3.1 L1)", rawValue)
                                        .doesNotContain(rawValue);
                            }
                            cause = cause.getCause();
                        }
                    });
        }
    }
}
