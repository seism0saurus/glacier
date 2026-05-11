package de.seism0saurus.glacier;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test verifying that {@link GlacierCookieProperties} carries a functional
 * {@code @NotNull} constraint on the {@code secure} field.
 *
 * <h2>Testing approach</h2>
 * <p>Uses the Jakarta Validation API directly ({@link Validation#buildDefaultValidatorFactory()})
 * to bypass Spring Boot's binding infrastructure. This proves the constraint is enforced
 * by the bean itself — not only when Spring is involved.
 *
 * <h2>Security scope (ADR-P3B-3, SR-P3B-03)</h2>
 * <p>{@code @NotNull} on {@code Boolean secure} prevents a missing property from silently
 * defaulting to {@code false} (= no Secure flag = session hijackable over HTTP).
 * CWE-1188; ASVS V14.1.1 (L1).
 *
 * <p>References: ADR-P3B-1; ADR-P3B-3; SR-P3B-03; CWE-1188; ASVS V14.1.1.
 */
class GlacierCookiePropertiesNotNullValidationTest {

    private static Validator validator;

    @BeforeAll
    static void buildValidator() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    /**
     * Arrange: a {@link GlacierCookieProperties} instance with {@code secure = null}
     * Act: {@code Validator.validate(bean)}
     * Assert: exactly one {@code @NotNull} constraint violation is reported
     *
     * <p>This test is independent of Spring Boot. It proves that the Jakarta Validation
     * constraint is active on the bean regardless of how it is instantiated.
     */
    @Test
    void secureNull_producesNotNullConstraintViolation() {
        GlacierCookieProperties props = new GlacierCookieProperties();
        props.setSecure(null);

        Set<ConstraintViolation<GlacierCookieProperties>> violations = validator.validate(props);

        assertThat(violations)
                .as("A null 'secure' field must produce at least one @NotNull constraint violation "
                        + "(ADR-P3B-3, SR-P3B-03, CWE-1188, ASVS V14.1.1 L1)")
                .isNotEmpty();

        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .as("The constraint violation must be on the 'secure' property")
                .contains("secure");
    }

    /**
     * Arrange: {@link GlacierCookieProperties} with {@code secure = Boolean.TRUE}
     * Act: {@code Validator.validate(bean)}
     * Assert: no constraint violations
     *
     * <p>Negative control: confirms that a non-null value passes validation cleanly.
     */
    @Test
    void secureTrue_noConstraintViolations() {
        GlacierCookieProperties props = new GlacierCookieProperties();
        props.setSecure(Boolean.TRUE);

        Set<ConstraintViolation<GlacierCookieProperties>> violations = validator.validate(props);

        assertThat(violations)
                .as("Boolean.TRUE must not produce any constraint violations")
                .isEmpty();
    }

    /**
     * Arrange: {@link GlacierCookieProperties} with {@code secure = Boolean.FALSE}
     * Act: {@code Validator.validate(bean)}
     * Assert: no constraint violations
     *
     * <p>Negative control: confirms that {@code false} (explicit dev-mode setting)
     * passes validation cleanly — only null is rejected.
     */
    @Test
    void secureFalse_noConstraintViolations() {
        GlacierCookieProperties props = new GlacierCookieProperties();
        props.setSecure(Boolean.FALSE);

        Set<ConstraintViolation<GlacierCookieProperties>> violations = validator.validate(props);

        assertThat(violations)
                .as("Boolean.FALSE must not produce any constraint violations")
                .isEmpty();
    }
}
