package de.seism0saurus.glacier.share.infrastructure;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SharePersistenceProperties} binding and validation.
 *
 * <p>Validates:
 * <ul>
 *   <li>Defaults are applied when values are absent ({@code maxPageCount}, {@code busyTimeoutMs}).</li>
 *   <li>{@code @NotBlank} + {@code @Size(min=44)} on {@code ipHmacKey} — blank value → violation.</li>
 *   <li>{@code @Size(min=44)} on {@code ipHmacKey} — too-short value → violation.</li>
 *   <li>{@code @SafeFilesystemPath} on {@code path} — traversal value → violation.</li>
 *   <li>Valid property set produces no violations.</li>
 * </ul>
 *
 * <p>References: SR-SQLITE-08; SR-SQLITE-17; SR-SQLITE-22;
 * ASVS V5.1.3 (L1); ADR-SQLITE-01.
 */
class SharePersistencePropertiesTest {

    // Valid 44-char base64 key (256 bits; minimum length per SR-SQLITE-22)
    private static final String VALID_HMAC_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
    private static final String VALID_PATH = "/var/data/glacier/share.db";

    private static Validator validator;

    @BeforeAll
    static void initValidator() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    // -------------------------------------------------------------------------
    // Defaults
    // -------------------------------------------------------------------------

    @Test
    void defaultMaxPageCount_is65536() {
        SharePersistenceProperties props = new SharePersistenceProperties();
        assertThat(props.getMaxPageCount())
                .as("maxPageCount default must be 65536 (~256 MB at default SQLite page size)")
                .isEqualTo(65536);
    }

    @Test
    void defaultBusyTimeoutMs_is5000() {
        SharePersistenceProperties props = new SharePersistenceProperties();
        assertThat(props.getBusyTimeoutMs())
                .as("busyTimeoutMs default must be 5000 ms (SR-SQLITE-13)")
                .isEqualTo(5000);
    }

    // -------------------------------------------------------------------------
    // Valid property set — no violations
    // -------------------------------------------------------------------------

    @Test
    void validPropertiesProduceNoViolations() {
        SharePersistenceProperties props = buildValid();
        Set<ConstraintViolation<SharePersistenceProperties>> violations = validator.validate(props);
        assertThat(violations)
                .as("Valid SharePersistenceProperties must produce zero constraint violations")
                .isEmpty();
    }

    // -------------------------------------------------------------------------
    // ipHmacKey validation (SR-SQLITE-22)
    // -------------------------------------------------------------------------

    @Test
    void blankIpHmacKey_producesViolation() {
        SharePersistenceProperties props = buildValid();
        props.setIpHmacKey("   ");

        Set<ConstraintViolation<SharePersistenceProperties>> violations = validator.validate(props);

        assertThat(violations)
                .as("Blank ipHmacKey must fail @NotBlank (SR-SQLITE-22)")
                .isNotEmpty();
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("ipHmacKey"));
    }

    @Test
    void nullIpHmacKey_producesViolation() {
        SharePersistenceProperties props = buildValid();
        props.setIpHmacKey(null);

        Set<ConstraintViolation<SharePersistenceProperties>> violations = validator.validate(props);

        assertThat(violations)
                .as("Null ipHmacKey must fail @NotBlank (SR-SQLITE-22)")
                .isNotEmpty();
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("ipHmacKey"));
    }

    @Test
    void tooShortIpHmacKey_producesViolation() {
        SharePersistenceProperties props = buildValid();
        // 43 chars — one below the @Size(min=44) floor (SR-SQLITE-22: base64-256-bit minimum)
        props.setIpHmacKey("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");

        Set<ConstraintViolation<SharePersistenceProperties>> violations = validator.validate(props);

        assertThat(violations)
                .as("ipHmacKey shorter than 44 chars must fail @Size(min=44) (SR-SQLITE-22)")
                .isNotEmpty();
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("ipHmacKey"));
    }

    @Test
    void exactlyMinLengthIpHmacKey_passes() {
        SharePersistenceProperties props = buildValid();
        // 44 chars — exactly at the @Size(min=44) floor
        props.setIpHmacKey(VALID_HMAC_KEY); // 44 chars

        Set<ConstraintViolation<SharePersistenceProperties>> violations = validator.validate(props);

        assertThat(violations)
                .as("ipHmacKey of exactly 44 chars must pass @Size(min=44) (SR-SQLITE-22)")
                .isEmpty();
    }

    // -------------------------------------------------------------------------
    // path validation (SR-SQLITE-08)
    // -------------------------------------------------------------------------

    @Test
    void pathTraversal_producesViolation() {
        SharePersistenceProperties props = buildValid();
        props.setPath("../etc/passwd");

        Set<ConstraintViolation<SharePersistenceProperties>> violations = validator.validate(props);

        assertThat(violations)
                .as("Path traversal '../etc/passwd' must fail @SafeFilesystemPath (SR-SQLITE-08)")
                .isNotEmpty();
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("path"));
    }

    @Test
    void shellMetacharacterInPath_producesViolation() {
        SharePersistenceProperties props = buildValid();
        props.setPath("/data/db;rm -rf /");

        Set<ConstraintViolation<SharePersistenceProperties>> violations = validator.validate(props);

        assertThat(violations)
                .as("Shell metacharacter ';' in path must fail @SafeFilesystemPath (SR-SQLITE-08)")
                .isNotEmpty();
    }

    @Test
    void inMemoryPath_passes() {
        SharePersistenceProperties props = buildValid();
        props.setPath(":memory:");

        Set<ConstraintViolation<SharePersistenceProperties>> violations = validator.validate(props);

        assertThat(violations)
                .as(":memory: must pass @SafeFilesystemPath (needed for SQLite in-memory tests)")
                .isEmpty();
    }

    // -------------------------------------------------------------------------
    // maxPageCount and busyTimeoutMs @Min(1) validation
    // -------------------------------------------------------------------------

    @Test
    void zeroMaxPageCount_producesViolation() {
        SharePersistenceProperties props = buildValid();
        props.setMaxPageCount(0);

        Set<ConstraintViolation<SharePersistenceProperties>> violations = validator.validate(props);

        assertThat(violations)
                .as("maxPageCount=0 must fail @Min(1)")
                .isNotEmpty();
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("maxPageCount"));
    }

    @Test
    void zeroBusyTimeoutMs_producesViolation() {
        SharePersistenceProperties props = buildValid();
        props.setBusyTimeoutMs(0);

        Set<ConstraintViolation<SharePersistenceProperties>> violations = validator.validate(props);

        assertThat(violations)
                .as("busyTimeoutMs=0 must fail @Min(1)")
                .isNotEmpty();
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("busyTimeoutMs"));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static SharePersistenceProperties buildValid() {
        SharePersistenceProperties props = new SharePersistenceProperties();
        props.setPath(VALID_PATH);
        props.setIpHmacKey(VALID_HMAC_KEY);
        return props;
    }
}
