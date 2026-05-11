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
 * Unit tests for {@link SafeOperatorStringValidator} and the {@link SafeOperatorString} annotation.
 *
 * <p>Uses the Jakarta Validation API directly — no Spring context required.
 * Tests all forbidden control characters and Unicode direction-override characters
 * defined in ADR-P3A-9.
 *
 * <p>Security requirements:
 * <ul>
 *   <li>ADR-P3A-9: reject \r, \n, \t, \0, U+202E, U+200B, U+FEFF, U+2028, U+2029</li>
 *   <li>SR-P3A-12: all operator string fields must use @SafeOperatorString</li>
 *   <li>CWE-117: log injection prevention</li>
 * </ul>
 *
 * <p>Tested via a minimal holder class that applies {@code @SafeOperatorString}.
 */
class SafeOperatorStringValidatorTest {

    private static Validator validator;

    @BeforeAll
    static void buildValidator() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    // -------------------------------------------------------------------------
    // Test holder — minimal bean with @SafeOperatorString
    // -------------------------------------------------------------------------

    /** Minimal bean for testing the annotation in isolation. */
    static class Holder {
        @SafeOperatorString
        String value;

        Holder(String value) {
            this.value = value;
        }
    }

    private Set<ConstraintViolation<Holder>> violationsFor(String value) {
        return validator.validate(new Holder(value));
    }

    // -------------------------------------------------------------------------
    // Forbidden characters (ADR-P3A-9)
    // -------------------------------------------------------------------------

    @Test
    void crlf_isRejected() {
        assertThat(violationsFor("line1\r\nline2"))
                .as("CR+LF must be rejected (log injection, CWE-117)")
                .isNotEmpty();
    }

    @Test
    void carriageReturn_isRejected() {
        assertThat(violationsFor("value\r"))
                .as("carriage return \\r must be rejected")
                .isNotEmpty();
    }

    @Test
    void lineFeed_isRejected() {
        assertThat(violationsFor("line1\nline2"))
                .as("line feed \\n must be rejected")
                .isNotEmpty();
    }

    @Test
    void nullByte_isRejected() {
        assertThat(violationsFor("evil\0value"))
                .as("null byte \\0 must be rejected (path truncation, CWE-626)")
                .isNotEmpty();
    }

    @Test
    void tab_isRejected() {
        assertThat(violationsFor("col1\tcol2"))
                .as("tab \\t must be rejected")
                .isNotEmpty();
    }

    @Test
    void rtlOverride_isRejected() {
        // U+202E — used to reverse text display (spoofing attacks)
        assertThat(violationsFor("name‮spoof"))
                .as("U+202E RTL override must be rejected (unicode spoofing)")
                .isNotEmpty();
    }

    @Test
    void zwsp_isRejected() {
        // U+200B — zero-width space, can hide content
        assertThat(violationsFor("name​"))
                .as("U+200B zero-width space must be rejected")
                .isNotEmpty();
    }

    @Test
    void bom_isRejected() {
        // U+FEFF — byte order mark, can cause rendering issues
        assertThat(violationsFor("﻿value"))
                .as("U+FEFF BOM must be rejected")
                .isNotEmpty();
    }

    @Test
    void lineSeparator_U2028_isRejected() {
        // U+2028 — Unicode line separator, treated as newline by JavaScript
        assertThat(violationsFor("line end"))
                .as("U+2028 Unicode line separator must be rejected (JS injection vector)")
                .isNotEmpty();
    }

    @Test
    void paragraphSeparator_U2029_isRejected() {
        // U+2029 — Unicode paragraph separator, treated as newline by JavaScript
        assertThat(violationsFor("para end"))
                .as("U+2029 Unicode paragraph separator must be rejected (JS injection vector)")
                .isNotEmpty();
    }

    // -------------------------------------------------------------------------
    // Null and blank — should be accepted (null/blank validation is separate)
    // -------------------------------------------------------------------------

    @Test
    void nullValue_isAccepted() {
        // @SafeOperatorString validates non-null values only; null is allowed here
        // (combined with @NotBlank elsewhere for the name field)
        assertThat(violationsFor(null))
                .as("null must be accepted — @NotBlank handles null separately")
                .isEmpty();
    }

    // -------------------------------------------------------------------------
    // Normal strings — must be accepted
    // -------------------------------------------------------------------------

    @Test
    void normalString_isAccepted() {
        assertThat(violationsFor("Jon Doe"))
                .as("normal ASCII name must be accepted")
                .isEmpty();
    }

    @Test
    void stringWithUmlaut_isAccepted() {
        assertThat(violationsFor("München"))
                .as("string with umlauts must be accepted")
                .isEmpty();
    }

    @Test
    void stringWithHyphenAndNumber_isAccepted() {
        assertThat(violationsFor("Main-St 42"))
                .as("string with hyphens and numbers must be accepted")
                .isEmpty();
    }

    @Test
    void emailAddress_isAccepted() {
        assertThat(violationsFor("user@example.com"))
                .as("email address must be accepted")
                .isEmpty();
    }
}
