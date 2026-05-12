package de.seism0saurus.glacier.share.infrastructure;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Validator for {@link SafeFilesystemPath}.
 *
 * <p>Rejects strings that could enable path traversal, home-directory expansion,
 * shell-metacharacter injection, null-byte attacks, or Unicode-direction spoofing
 * when used as a SQLite database file path.
 *
 * <h2>Rejection rules (SR-SQLITE-08)</h2>
 * <ul>
 *   <li>{@code ..} anywhere in the string — directory traversal (CWE-22)</li>
 *   <li>{@code ~} anywhere — shell home-directory expansion</li>
 *   <li>Null byte ({@code \0}) — path truncation (CWE-626)</li>
 *   <li>Shell metacharacters: {@code ; | &amp; ` $ ( ) { } &lt; &gt;}</li>
 *   <li>U+202E (RIGHT-TO-LEFT OVERRIDE) — filename display spoofing</li>
 *   <li>U+200B (ZERO-WIDTH SPACE) — hidden injection</li>
 *   <li>U+FEFF (BYTE ORDER MARK) — encoding confusion</li>
 *   <li>U+2028 (LINE SEPARATOR) — log/JS injection</li>
 *   <li>U+2029 (PARAGRAPH SEPARATOR) — log/JS injection</li>
 *   <li>{@code %00} (URL-encoded null byte) — URL decoding bypass</li>
 * </ul>
 *
 * <h2>Verbatim allowlist (in-memory SQLite test paths)</h2>
 * <ul>
 *   <li>{@code :memory:} — SQLite in-memory database (no file path semantics)</li>
 *   <li>{@code jdbc:sqlite::memory:} — Spring datasource URL for in-memory SQLite</li>
 * </ul>
 *
 * <p>Null values are accepted — pair with {@code @NotNull} where required.
 *
 * <p><strong>Security invariant</strong>: violation messages NEVER echo the input value
 * to prevent path-traversal payloads from appearing in startup logs or error responses
 * (CWE-22; ADR-P3A-7; ASVS V7.3.1 L1).
 *
 * <p>References: SR-SQLITE-08; OWASP A03:2021 — Injection; CWE-22; CWE-626;
 * ASVS V5.1.3 (L1); C3 — input validation at boundary.
 */
public class SafeFilesystemPathValidator
        implements ConstraintValidator<SafeFilesystemPath, String> {

    /** Verbatim-allowed in-memory SQLite path (no file system semantics). */
    private static final String MEMORY_BARE = ":memory:";

    /** Verbatim-allowed in-memory SQLite JDBC URL (used in Spring Boot test contexts). */
    private static final String MEMORY_JDBC = "jdbc:sqlite::memory:";

    /** URL-encoded null byte sequence — must be rejected before any decoding step. */
    private static final String URL_ENCODED_NULL = "%00";

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

    /**
     * Static violation message — NEVER includes the input value (CWE-22 prevention).
     */
    private static final String STATIC_VIOLATION =
            "must be a safe filesystem path: no traversal sequences, metacharacters, or null bytes";

    @Override
    public boolean isValid(final String value, final ConstraintValidatorContext context) {
        // Null accepted — @NotNull / @NotBlank must be applied separately where required
        if (value == null) {
            return true;
        }

        // Verbatim allowlist: in-memory SQLite paths have no filesystem semantics
        if (MEMORY_BARE.equals(value) || MEMORY_JDBC.equals(value)) {
            return true;
        }

        // --- Rejection checks (order: cheapest first) ---

        // URL-encoded null byte bypass (must check before character scan)
        if (value.contains(URL_ENCODED_NULL)) {
            return setStaticViolation(context);
        }

        // Scan each character for forbidden code points and metacharacters
        boolean sawDot = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);

            // Null byte — CWE-626
            if (c == '\0') {
                return setStaticViolation(context);
            }

            // Track consecutive dots for ".." traversal detection
            if (c == '.') {
                if (sawDot) {
                    // Two consecutive dots — directory traversal sequence (CWE-22)
                    return setStaticViolation(context);
                }
                sawDot = true;
            } else {
                sawDot = false;
            }

            // Home-directory expansion character
            if (c == '~') {
                return setStaticViolation(context);
            }

            // Shell metacharacters
            if (isShellMetacharacter(c)) {
                return setStaticViolation(context);
            }

            // Unicode direction-control and invisible characters
            if (isUnicodeDirectionControl(c)) {
                return setStaticViolation(context);
            }
        }

        return true;
    }

    /**
     * Returns {@code true} if the character is a shell metacharacter that could enable
     * command injection or shell-word splitting if the path were passed to a shell.
     *
     * <p>Covered: {@code ; | &amp; ` $ ( ) { } &lt; &gt;}
     *
     * @param c character to test
     * @return {@code true} if forbidden shell metacharacter
     */
    private static boolean isShellMetacharacter(final char c) {
        return c == ';'
                || c == '|'
                || c == '&'
                || c == '`'
                || c == '$'
                || c == '('
                || c == ')'
                || c == '{'
                || c == '}'
                || c == '<'
                || c == '>';
    }

    /**
     * Returns {@code true} if the character is a Unicode direction-control or invisible
     * character that could be used for display spoofing or log injection.
     *
     * @param c character to test
     * @return {@code true} if forbidden Unicode control
     */
    private static boolean isUnicodeDirectionControl(final char c) {
        return c == RTL_OVERRIDE
                || c == ZWSP
                || c == BOM
                || c == LINE_SEPARATOR
                || c == PARAGRAPH_SEPARATOR;
    }

    /**
     * Disables the default constraint violation and registers a static violation message
     * that does NOT echo the submitted value (CWE-22 prevention).
     *
     * @param context the constraint validator context
     * @return {@code false} — always signals invalid
     */
    private static boolean setStaticViolation(final ConstraintValidatorContext context) {
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(STATIC_VIOLATION)
                .addConstraintViolation();
        return false;
    }
}
