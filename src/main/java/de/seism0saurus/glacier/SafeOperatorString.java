package de.seism0saurus.glacier;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Jakarta Validation constraint that guards operator string fields against
 * control characters and Unicode direction-override characters.
 *
 * <p>Rejected characters (ADR-P3A-9):
 * <ul>
 *   <li>{@code \r} (carriage return) — log injection / HTTP header injection (CWE-117)</li>
 *   <li>{@code \n} (line feed) — log injection / HTTP header injection (CWE-117)</li>
 *   <li>{@code \t} (tab) — potential CSV/TSV injection</li>
 *   <li>{@code \0} (null byte) — path truncation attacks (CWE-626)</li>
 *   <li>U+202E (RIGHT-TO-LEFT OVERRIDE) — text spoofing / phishing</li>
 *   <li>U+200B (ZERO-WIDTH SPACE) — hidden content injection</li>
 *   <li>U+FEFF (BYTE ORDER MARK) — encoding confusion</li>
 *   <li>U+2028 (LINE SEPARATOR) — newline treated by JavaScript engines</li>
 *   <li>U+2029 (PARAGRAPH SEPARATOR) — newline treated by JavaScript engines</li>
 * </ul>
 *
 * <p>Applied to all 8 {@link GlacierOperatorProperties} string fields
 * (SR-P3A-12; ADR-P3A-9; OWASP A05:2021; ASVS V5.1.3 L1).
 *
 * <p>Null values are permitted — combine with {@code @NotBlank} where null/blank
 * must be rejected (e.g., the {@code name} field).
 */
@Documented
@Constraint(validatedBy = SafeOperatorStringValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface SafeOperatorString {

    String message() default "must not contain control characters or Unicode direction-override characters";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
