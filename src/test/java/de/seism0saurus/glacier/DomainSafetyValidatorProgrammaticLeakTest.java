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
 * Programmatic leak test: bypasses {@code GlacierBindHandler.ScrubbingBindHandler} entirely
 * by calling {@code validator.validate(bean)} directly with plain Jakarta Validation API.
 *
 * <p>This is the attack path that {@code GlacierBindHandler} does NOT protect:
 * <ul>
 *   <li>Programmatic {@code validator.validate(bean)} calls in application code</li>
 *   <li>REST {@code @Valid} body binding via {@code MethodArgumentNotValidException}</li>
 *   <li>AOP method validation, JMX attribute validation, AOT context introspection</li>
 * </ul>
 *
 * <p>Each prohibited input is validated and every resulting {@code ConstraintViolation.getMessage()}
 * is asserted to NOT contain the raw input value (ADR-P3B-4, SR-P3B-01, CWE-532).
 *
 * <p>References: ADR-P3B-4; SR-P3B-01; SR-P3B-08; CWE-532;
 * ASVS V7.3.1 (L1) — sensitive data must not appear in error messages;
 * OWASP A05:2021.
 */
class DomainSafetyValidatorProgrammaticLeakTest {

    /**
     * Minimal wrapper bean for programmatic validation.
     * Contains only {@code @DomainSafetyValidator} — no other constraints —
     * so that each violation is unambiguously from the PROHIBITED_LITERALS branch.
     * (Constraint: do NOT add @SafeOperatorString per ADR-P3B-4 test-bean rule.)
     */
    static class ProgrammaticDomainBean {
        @DomainSafetyValidator
        final String domain;

        ProgrammaticDomainBean(final String domain) {
            this.domain = domain;
        }
    }

    private static Validator validator;

    @BeforeAll
    static void buildValidator() {
        // Plain Jakarta Validation — intentionally NOT a Spring LocalValidatorFactoryBean
        // so there is zero chance of GlacierBindHandler or any Spring error-mapping
        // intercepting the raw violations before this test can inspect them.
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    /**
     * For each prohibited literal: calls {@code validator.validate(bean)} directly (bypassing
     * GlacierBindHandler) and asserts that no violation message leaks the raw input value.
     *
     * <p>Inputs include the 6 PROHIBITED_LITERALS from the Set.of(...) in the validator,
     * plus range members "127.0.0.1" (covered by 127.x range) and "192.168.1.1"
     * (covered by RFC1918 range) to exercise non-PROHIBITED_LITERALS branches as well,
     * confirming those branches are also static.
     *
     * @param input the prohibited value to validate
     */
    @ParameterizedTest
    @ValueSource(strings = {
            // PROHIBITED_LITERALS set members — these reach the PROHIBITED_LITERALS branch
            "localhost",
            "127.0.0.1",
            "::1",
            "0.0.0.0",
            "[::]",
            "0:0:0:0:0:0:0:1",
            // 127.x range — reaches the 127.* startsWith branch (not PROHIBITED_LITERALS)
            "127.99.99.99",
            // RFC1918 — reaches isPrivateIpv4Range branch
            "192.168.1.1"
    })
    void programmaticValidate_noViolationMessage_containsRawValue(final String input) {
        Set<ConstraintViolation<ProgrammaticDomainBean>> violations =
                validator.validate(new ProgrammaticDomainBean(input));

        // Confirm rejection occurred
        assertThat(violations)
                .as("Programmatic validator.validate() must reject input '%s'", input)
                .isNotEmpty();

        // For every violation: message must NOT contain the raw input (ADR-P3B-4, CWE-532).
        // This is the defense-in-depth assertion — GlacierBindHandler is NOT in the call chain.
        for (ConstraintViolation<ProgrammaticDomainBean> cv : violations) {
            assertThat(cv.getMessage())
                    .as("SR-P3B-01 (programmatic path): cv.getMessage() must not echo "
                            + "raw input '%s' — GlacierBindHandler is not active on this path "
                            + "(ADR-P3B-4, CWE-532, ASVS V7.3.1)", input)
                    .doesNotContain(input);

            // Also check lower-case variant to cover the old "': '" + trimmed pattern
            assertThat(cv.getMessage())
                    .as("SR-P3B-01: cv.getMessage() must not echo lowercased input '%s'", input)
                    .doesNotContain(input.toLowerCase(java.util.Locale.ROOT));
        }
    }
}
