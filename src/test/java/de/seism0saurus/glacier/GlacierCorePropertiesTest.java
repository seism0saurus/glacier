package de.seism0saurus.glacier;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link GlacierCoreProperties} startup-validation guard (SR-PQ-12-FU).
 *
 * <p>Uses the Jakarta Validation API directly — no Spring context required — so the
 * {@code @NotBlank} constraint is verified cheaply and diagnostically.
 */
class GlacierCorePropertiesTest {

    private static Validator validator;

    @BeforeAll
    static void buildValidator() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    // -------------------------------------------------------------------------
    // @NotBlank guard
    // -------------------------------------------------------------------------

    @Test
    void nullDomainViolatesNotBlank() {
        GlacierCoreProperties props = new GlacierCoreProperties();
        // domain left null (no setter call)
        Set<ConstraintViolation<GlacierCoreProperties>> violations = validator.validate(props);
        assertThat(violations)
                .as("null domain must trigger @NotBlank")
                .isNotEmpty();
    }

    @Test
    void emptyDomainViolatesNotBlank() {
        GlacierCoreProperties props = new GlacierCoreProperties();
        props.setDomain("");
        Set<ConstraintViolation<GlacierCoreProperties>> violations = validator.validate(props);
        assertThat(violations)
                .as("empty-string domain must trigger @NotBlank")
                .isNotEmpty();
    }

    @Test
    void blankDomainViolatesNotBlank() {
        GlacierCoreProperties props = new GlacierCoreProperties();
        props.setDomain("   ");
        Set<ConstraintViolation<GlacierCoreProperties>> violations = validator.validate(props);
        assertThat(violations)
                .as("whitespace-only domain must trigger @NotBlank")
                .isNotEmpty();
    }

    // -------------------------------------------------------------------------
    // Valid value passes
    // -------------------------------------------------------------------------

    @Test
    void validDomainPassesConstraint() {
        GlacierCoreProperties props = new GlacierCoreProperties();
        props.setDomain("glacier.events");
        Set<ConstraintViolation<GlacierCoreProperties>> violations = validator.validate(props);
        assertThat(violations)
                .as("a real hostname must pass @NotBlank")
                .isEmpty();
    }

    // -------------------------------------------------------------------------
    // Getter / setter round-trip
    // -------------------------------------------------------------------------

    @Test
    void setterAndGetterRoundTrip() {
        GlacierCoreProperties props = new GlacierCoreProperties();
        props.setDomain("example.com");
        assertThat(props.getDomain()).isEqualTo("example.com");
    }
}
