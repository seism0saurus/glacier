package de.seism0saurus.glacier.share.infrastructure;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Jakarta Validation constraint that guards filesystem path fields against
 * path-traversal, home-directory expansion, shell metacharacters, null bytes,
 * and Unicode direction-control characters.
 *
 * <p>Rejected inputs (SR-SQLITE-08; CWE-22; OWASP A03:2021 — Injection):
 * <ul>
 *   <li>{@code ..} — directory traversal sequences</li>
 *   <li>{@code ~} — shell home-directory expansion</li>
 *   <li>{@code \0} — null byte (path truncation, CWE-626)</li>
 *   <li>Shell metacharacters: {@code ; | &amp; ` $ ( ) { } &lt; &gt;}</li>
 *   <li>U+202E (RIGHT-TO-LEFT OVERRIDE) — filename spoofing</li>
 *   <li>U+200B (ZERO-WIDTH SPACE) — hidden injection</li>
 *   <li>U+FEFF (BYTE ORDER MARK) — encoding confusion</li>
 *   <li>U+2028 (LINE SEPARATOR) — JS/log newline</li>
 *   <li>U+2029 (PARAGRAPH SEPARATOR) — JS/log newline</li>
 *   <li>{@code %00} — URL-encoded null byte</li>
 * </ul>
 *
 * <p>Accepted verbatim (needed for in-memory SQLite in tests):
 * <ul>
 *   <li>{@code :memory:}</li>
 *   <li>{@code jdbc:sqlite::memory:}</li>
 * </ul>
 *
 * <p>Null values are accepted — combine with {@code @NotNull} / {@code @NotBlank}
 * where null/blank must be rejected.
 *
 * <p>Violation messages NEVER echo the supplied value (CWE-22; ADR-P3A-7).
 *
 * <p>References: SR-SQLITE-08; OWASP A03:2021 — Injection; CWE-22 — Path Traversal;
 * CWE-626 — Null Byte Interaction; ASVS V5.1.3 (L1); C3 — input validation.
 */
@Documented
@Constraint(validatedBy = SafeFilesystemPathValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface SafeFilesystemPath {

    String message() default "must be a safe filesystem path: no traversal sequences, metacharacters, or null bytes";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
