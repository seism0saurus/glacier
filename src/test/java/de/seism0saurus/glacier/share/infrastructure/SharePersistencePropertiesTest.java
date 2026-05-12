package de.seism0saurus.glacier.share.infrastructure;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SharePersistenceProperties} field-level validation.
 *
 * <p>These tests verify the JSR-380 constraints applied at the field level —
 * matching what {@code @Validated} triggers at Spring Boot startup. Each test
 * validates the property object directly using a Validator, making the tests
 * independent of the Spring context (Surefire-safe).
 *
 * <p>Coverage:
 * <ul>
 *   <li>Valid binding: all required fields present and valid.</li>
 *   <li>{@code @NotBlank} on {@code ipHmacKey} rejects blank/null values.</li>
 *   <li>{@code @Size(min=44)} on {@code ipHmacKey} rejects keys shorter than 256-bit Base64.</li>
 *   <li>{@code @Min(1)} on {@code maxPageCount} and {@code busyTimeoutMs}.</li>
 *   <li>Default values for {@code maxPageCount} and {@code busyTimeoutMs}.</li>
 * </ul>
 *
 * <p>References: SR-SQLITE-17; SR-SQLITE-22; ADR-SQLITE-01; OWASP A05:2021.
 */
class SharePersistencePropertiesTest {

    private static Validator validator;

    @BeforeAll
    static void buildValidator() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    // -------------------------------------------------------------------------
    // Helper factory
    // -------------------------------------------------------------------------

    private static SharePersistenceProperties validProps() {
        SharePersistenceProperties props = new SharePersistenceProperties();
        props.setPath("/var/glacier/share.db");
        props.setIpHmacKey("A".repeat(44)); // 44 chars = 256-bit Base64 minimum
        return props;
    }

    // -------------------------------------------------------------------------
    // Valid binding
    // -------------------------------------------------------------------------

    @Test
    void validPropertiesProduceNoViolations() {
        Set<ConstraintViolation<SharePersistenceProperties>> violations = validator.validate(validProps());

        assertThat(violations)
                .as("Valid SharePersistenceProperties must produce no constraint violations")
                .isEmpty();
    }

    @Test
    void defaultMaxPageCountIs65536() {
        SharePersistenceProperties props = validProps();
        assertThat(props.getMaxPageCount()).isEqualTo(65536);
    }

    @Test
    void defaultBusyTimeoutMsIs5000() {
        SharePersistenceProperties props = validProps();
        assertThat(props.getBusyTimeoutMs()).isEqualTo(5000);
    }

    // -------------------------------------------------------------------------
    // ipHmacKey — @NotBlank
    // -------------------------------------------------------------------------

    @Test
    void nullIpHmacKey_violatesNotBlank() {
        SharePersistenceProperties props = validProps();
        props.setIpHmacKey(null);

        Set<ConstraintViolation<SharePersistenceProperties>> violations = validator.validate(props);

        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .contains("ipHmacKey");
    }

    @Test
    void blankIpHmacKey_violatesNotBlank() {
        SharePersistenceProperties props = validProps();
        props.setIpHmacKey("   ");

        Set<ConstraintViolation<SharePersistenceProperties>> violations = validator.validate(props);

        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .contains("ipHmacKey");
    }

    // -------------------------------------------------------------------------
    // ipHmacKey — @Size(min=44) — SR-SQLITE-22
    // -------------------------------------------------------------------------

    @ParameterizedTest(name = "ipHmacKey length={0} is too short — rejected (SR-SQLITE-22)")
    @ValueSource(strings = {
            "",                    // 0 chars — also NotBlank
            "A",                   // 1 char
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",  // 42 chars
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", // 43 chars (one below min)
    })
    void shortIpHmacKey_violatesSizeConstraint(final String shortKey) {
        SharePersistenceProperties props = validProps();
        props.setIpHmacKey(shortKey);

        Set<ConstraintViolation<SharePersistenceProperties>> violations = validator.validate(props);

        assertThat(violations)
                .as("Expected @Size(min=44) or @NotBlank to reject key of length %d", shortKey.length())
                .extracting(v -> v.getPropertyPath().toString())
                .contains("ipHmacKey");
    }

    @ParameterizedTest(name = "ipHmacKey length={0} is acceptable")
    @ValueSource(strings = {
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",   // exactly 44
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",  // 45
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", // 64
    })
    void sufficientlyLongIpHmacKey_passesValidation(final String longKey) {
        SharePersistenceProperties props = validProps();
        props.setIpHmacKey(longKey);

        Set<ConstraintViolation<SharePersistenceProperties>> violations = validator.validate(props);

        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .doesNotContain("ipHmacKey");
    }

    // -------------------------------------------------------------------------
    // maxPageCount — @Min(1)
    // -------------------------------------------------------------------------

    @Test
    void zeroMaxPageCount_violatesMinConstraint() {
        SharePersistenceProperties props = validProps();
        props.setMaxPageCount(0);

        Set<ConstraintViolation<SharePersistenceProperties>> violations = validator.validate(props);

        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .contains("maxPageCount");
    }

    @Test
    void oneMaxPageCount_isValid() {
        SharePersistenceProperties props = validProps();
        props.setMaxPageCount(1);

        Set<ConstraintViolation<SharePersistenceProperties>> violations = validator.validate(props);

        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .doesNotContain("maxPageCount");
    }

    // -------------------------------------------------------------------------
    // busyTimeoutMs — @Min(1)
    // -------------------------------------------------------------------------

    @Test
    void zeroBusyTimeoutMs_violatesMinConstraint() {
        SharePersistenceProperties props = validProps();
        props.setBusyTimeoutMs(0);

        Set<ConstraintViolation<SharePersistenceProperties>> violations = validator.validate(props);

        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .contains("busyTimeoutMs");
    }

    @Test
    void oneBusyTimeoutMs_isValid() {
        SharePersistenceProperties props = validProps();
        props.setBusyTimeoutMs(1);

        Set<ConstraintViolation<SharePersistenceProperties>> violations = validator.validate(props);

        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .doesNotContain("busyTimeoutMs");
    }

    // -------------------------------------------------------------------------
    // path field — @SafeFilesystemPath (integration with validator)
    // -------------------------------------------------------------------------

    @Test
    void traversalPath_violatesSafeFilesystemPath() {
        SharePersistenceProperties props = validProps();
        props.setPath("../../etc/passwd");

        Set<ConstraintViolation<SharePersistenceProperties>> violations = validator.validate(props);

        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .contains("path");
    }

    @Test
    void nullPath_doesNotViolateSafeFilesystemPath() {
        // @SafeFilesystemPath accepts null — @NotNull must be applied separately
        SharePersistenceProperties props = validProps();
        props.setPath(null);

        Set<ConstraintViolation<SharePersistenceProperties>> violations = validator.validate(props);

        // No @SafeFilesystemPath violation for null; path field has no @NotNull here
        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .doesNotContain("path");
    }

    // -------------------------------------------------------------------------
    // Getters / setters — round-trip
    // -------------------------------------------------------------------------

    @Test
    void gettersReturnSetValues() {
        SharePersistenceProperties props = new SharePersistenceProperties();
        props.setPath("/opt/glacier.db");
        props.setIpHmacKey("B".repeat(44));
        props.setMaxPageCount(1024);
        props.setBusyTimeoutMs(3000);

        assertThat(props.getPath()).isEqualTo("/opt/glacier.db");
        assertThat(props.getIpHmacKey()).isEqualTo("B".repeat(44));
        assertThat(props.getMaxPageCount()).isEqualTo(1024);
        assertThat(props.getBusyTimeoutMs()).isEqualTo(3000);
    }
}
