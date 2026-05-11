package de.seism0saurus.glacier;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link DomainSafetyValidator} constraint-violation messages are static literals
 * — no raw domain value echoed back — for all prohibited-literal inputs.
 *
 * <p>This is a defense-in-depth guard (ADR-P3B-4, SR-P3B-01, CWE-532).
 * {@code GlacierBindHandler.ScrubbingBindHandler.onFailure} already scrubs the violation chain
 * for the {@code @ConfigurationProperties} bind path. This test guards the programmatic
 * {@code validator.validate(bean)} path that bypasses the handler entirely
 * (REST {@code @Valid}, AOP method validation, JMX, AOT introspection).
 *
 * <p>Tested inputs: the six prohibited literals plus two mixed-case variants of "localhost"
 * that reach the same PROHIBITED_LITERALS branch.
 *
 * <p>References: ADR-P3B-4; SR-P3B-01; SR-P3B-08; CWE-532;
 * ASVS V7.3.1 (L1) — sensitive data must not be echoed in error messages;
 * OWASP A05:2021 Security Misconfiguration.
 */
class DomainSafetyValidatorStaticMessageTest {

    /**
     * Minimal bean wrapper used to trigger the PROHIBITED_LITERALS branch of
     * {@link DomainSafetyValidator.DomainSafetyConstraintValidator}.
     *
     * <p>Intentionally uses ONLY {@code @DomainSafetyValidator}, not {@code @SafeOperatorString}
     * or any other constraint, so that each violation is unambiguously from the domain validator.
     * (ADR-P3B-4: do NOT add @SafeOperatorString to the test bean.)
     */
    static class DomainBean {
        @DomainSafetyValidator
        final String domain;

        DomainBean(final String domain) {
            this.domain = domain;
        }
    }

    private static Validator validator;

    @BeforeAll
    static void buildValidator() {
        // Use plain Jakarta Validation — no Spring context — to bypass GlacierBindHandler.
        // This matches the programmatic validator.validate(bean) path that could expose
        // raw values in violation messages (ADR-P3B-4, SR-P3B-01).
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    /**
     * For each prohibited literal: verifies that the constraint-violation message is
     * a static string that does NOT contain the raw input value.
     *
     * <p>SR-P3B-08: asserts {@code getMessage()}, {@code getMessageTemplate()} (regression guard).
     * SR-P3B-01: asserts the message keyword "loopback" is present (confirms the right branch fired).
     *
     * @param input one of the six PROHIBITED_LITERALS plus mixed-case variants
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "localhost",
            "LocalHost",
            "LOCALHOST",
            "127.0.0.1",
            "::1",
            "0.0.0.0",
            "[::]",
            "0:0:0:0:0:0:0:1"
    })
    void prohibitedLiteral_violationMessage_doesNotEchoRawValue(final String input) {
        Set<ConstraintViolation<DomainBean>> violations = validator.validate(new DomainBean(input));

        // Confirm the validator rejected the input (sanity check — not the main assertion)
        assertThat(violations)
                .as("Input '%s' must produce at least one constraint violation", input)
                .isNotEmpty();

        for (ConstraintViolation<DomainBean> cv : violations) {

            // Primary: getMessage() must NOT contain the raw input (ADR-P3B-4, CWE-532).
            // ASVS V7.3.1 (L1): sensitive configuration values must not appear in error messages.
            assertThat(cv.getMessage())
                    .as("SR-P3B-01: cv.getMessage() must not echo the raw input value '%s' "
                            + "(ADR-P3B-4, CWE-532, ASVS V7.3.1)", input)
                    .doesNotContain(input);

            // getMessage() must also not contain a lower-case version of the input
            // (guards against the previous pattern "': '" + trimmed + "' is prohibited")
            assertThat(cv.getMessage())
                    .as("SR-P3B-01: cv.getMessage() must not echo lowercased input '%s'", input)
                    .doesNotContain(input.toLowerCase(java.util.Locale.ROOT));

            // Sanity: message must not be blank — confirms a real message was set
            assertThat(cv.getMessage())
                    .as("Violation message for input '%s' must not be blank", input)
                    .isNotBlank();

            // Canary: the PROHIBITED_LITERALS branch message must contain "loopback"
            // so we know this is the right branch and not a false-green from an early exit
            assertThat(cv.getMessage())
                    .as("SR-P3B-09: violation message must contain keyword 'loopback' "
                            + "to confirm PROHIBITED_LITERALS branch fired for input '%s'", input)
                    .contains("loopback");

            // Regression guard: getMessageTemplate() must also not contain the raw value
            // (SR-P3B-08 — template is the source; if template leaks, every render leaks)
            assertThat(cv.getMessageTemplate())
                    .as("SR-P3B-08: cv.getMessageTemplate() must not echo the raw input value '%s' "
                            + "— if the template leaks the value, every rendered message leaks it too",
                            input)
                    .doesNotContain(input);
        }
    }
}
