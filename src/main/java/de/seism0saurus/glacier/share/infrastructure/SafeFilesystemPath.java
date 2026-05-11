package de.seism0saurus.glacier.share.infrastructure;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Jakarta Validation constraint that rejects filesystem paths containing path-traversal
 * sequences ({@code ..}), shell metacharacters, null bytes, Unicode direction controls,
 * and home-directory expansion ({@code ~}).
 *
 * <p>Rejected input (SR-SQLITE-08; CWE-22; CWE-626):
 * <ul>
 *   <li>{@code ..} path traversal components (CWE-22)</li>
 *   <li>{@code ~} home-directory expansion</li>
 *   <li>{@code \0} null byte (CWE-626)</li>
 *   <li>Shell metacharacters: {@code ;}, {@code |}, {@code &}, {@code $}, {@code `},
 *       {@code (}, {@code )}</li>
 *   <li>Unicode direction controls: U+202E (RTL override), U+200B (ZWSP),
 *       U+FEFF (BOM), U+2028 (line separator), U+2029 (paragraph separator)</li>
 *   <li>Blank/null strings (use {@code @NotBlank} in combination to reject those)</li>
 * </ul>
 *
 * <p>Explicitly ALLOWED:
 * <ul>
 *   <li>{@code null} — permitted so this constraint can be combined with {@code @NotBlank}
 *       where null must be rejected separately</li>
 *   <li>{@code :memory:} — SQLite in-memory specifier (required for test environments)</li>
 *   <li>{@code jdbc:sqlite::memory:} — full JDBC URL for in-memory SQLite</li>
 *   <li>Valid absolute paths ({@code /var/data/glacier/share.db}, etc.)</li>
 * </ul>
 *
 * <p>References: SR-SQLITE-08; CWE-22 Path Traversal; CWE-626 Null Byte Injection;
 * OWASP A05:2021 Security Misconfiguration; ASVS V5.1.3 (L1); C3 — Input Validation.
 */
@Documented
@Constraint(validatedBy = SafeFilesystemPathValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface SafeFilesystemPath {

    /**
     * Violation message — static, no path value echo (CWE-532; ADR-P3A-7).
     */
    String message() default "must not contain path traversal, shell metacharacters, "
            + "Unicode direction controls, or null bytes (SR-SQLITE-08)";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
