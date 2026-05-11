package de.seism0saurus.glacier;

import de.seism0saurus.glacier.util.LogScrubber;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Validator for {@link SafeOperatorString}.
 *
 * <p>Rejects strings containing:
 * <ul>
 *   <li>{@code \r}, {@code \n} — log injection / HTTP header injection (CWE-117)</li>
 *   <li>{@code \t} — tab character</li>
 *   <li>{@code \0} — null byte (CWE-626)</li>
 *   <li>U+202E (RTL override) — text spoofing</li>
 *   <li>U+200B (ZWSP) — hidden content</li>
 *   <li>U+FEFF (BOM) — encoding confusion</li>
 *   <li>U+2028 (line separator) — JS newline</li>
 *   <li>U+2029 (paragraph separator) — JS newline</li>
 * </ul>
 *
 * <p>Null values are accepted — combine with {@code @NotBlank} where required.
 *
 * <p>Violation messages are STATIC strings only — no value interpolation
 * (SR-P3A-01; ADR-P3A-7; ASVS V7.3.1 L1; CWE-532).
 *
 * <p>References: ADR-P3A-9; SR-P3A-12; OWASP A05:2021; ASVS V5.1.3 (L1); C3 — input validation.
 */
public class SafeOperatorStringValidator
        implements ConstraintValidator<SafeOperatorString, String> {

    /** Forbidden code point: U+202E RIGHT-TO-LEFT OVERRIDE */
    private static final char RTL_OVERRIDE = '‮';

    /** Forbidden code point: U+200B ZERO-WIDTH SPACE */
    private static final char ZWSP = '​';

    /** Forbidden code point: U+FEFF BYTE ORDER MARK */
    private static final char BOM = '﻿';

    /** Forbidden code point: U+2028 LINE SEPARATOR */
    private static final char LINE_SEPARATOR = ' ';

    /** Forbidden code point: U+2029 PARAGRAPH SEPARATOR */
    private static final char PARAGRAPH_SEPARATOR = ' ';

    @Override
    public boolean isValid(final String value, final ConstraintValidatorContext context) {
        // Null is permitted — @NotBlank handles null/blank separately
        if (value == null) {
            return true;
        }

        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (isForbidden(c)) {
                // C3 — input validation: use static message only; no value echo
                // SR-P3A-01: LogScrubber.forErrorMessage used for any derived content
                // The constraint violation message must NOT include the rejected value (ADR-P3A-7)
                context.disableDefaultConstraintViolation();
                context.buildConstraintViolationWithTemplate(
                        "must not contain control characters or Unicode direction-override characters")
                        .addConstraintViolation();
                return false;
            }
        }
        return true;
    }

    /**
     * Returns {@code true} if the character is in the forbidden set (ADR-P3A-9).
     *
     * @param c the character to test
     * @return {@code true} if forbidden
     */
    private static boolean isForbidden(final char c) {
        return c == '\r'
                || c == '\n'
                || c == '\t'
                || c == '\0'
                || c == RTL_OVERRIDE
                || c == ZWSP
                || c == BOM
                || c == LINE_SEPARATOR
                || c == PARAGRAPH_SEPARATOR;
    }
}
