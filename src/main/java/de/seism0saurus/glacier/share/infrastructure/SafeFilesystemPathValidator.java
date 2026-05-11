package de.seism0saurus.glacier.share.infrastructure;

import de.seism0saurus.glacier.util.LogScrubber;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Validator for {@link SafeFilesystemPath}.
 *
 * <p>Rejects filesystem paths containing:
 * <ul>
 *   <li>{@code ..} — path traversal (CWE-22)</li>
 *   <li>{@code ~} — home-directory expansion</li>
 *   <li>{@code \0} — null byte (CWE-626)</li>
 *   <li>Shell metacharacters: {@code ;}, {@code |}, {@code &}, {@code $}, {@code `},
 *       {@code (}, {@code )}</li>
 *   <li>U+202E RIGHT-TO-LEFT OVERRIDE — text spoofing</li>
 *   <li>U+200B ZERO-WIDTH SPACE — hidden content</li>
 *   <li>U+FEFF BYTE ORDER MARK — encoding confusion</li>
 *   <li>U+2028 LINE SEPARATOR — JS newline</li>
 *   <li>U+2029 PARAGRAPH SEPARATOR — JS newline</li>
 *   <li>Blank strings (empty or whitespace-only)</li>
 * </ul>
 *
 * <p>Explicitly ALLOWED:
 * <ul>
 *   <li>{@code null} — combine with {@code @NotBlank} where null must be rejected</li>
 *   <li>{@code :memory:} — SQLite in-memory specifier (required for test environments)</li>
 *   <li>{@code jdbc:sqlite::memory:} — JDBC URL form of the in-memory specifier</li>
 *   <li>Valid absolute paths ({@code /var/data/glacier/share.db}, etc.)</li>
 * </ul>
 *
 * <p>Violation messages are STATIC — no path value is echoed (ADR-P3A-7; CWE-532;
 * ASVS V7.3.1 L1).  {@link LogScrubber#forErrorMessage(String)} is referenced here
 * as the scrubbing pattern to follow for any derived error context (SR-SQLITE-08).
 *
 * <p>References: SR-SQLITE-08; CWE-22; CWE-626; OWASP A05:2021;
 * ASVS V5.1.3 (L1); C3 — Input Validation.
 */
public class SafeFilesystemPathValidator
        implements ConstraintValidator<SafeFilesystemPath, String> {

    // -------------------------------------------------------------------------
    // Forbidden Unicode code points (matching SafeOperatorStringValidator conventions)
    // -------------------------------------------------------------------------

    /** U+202E RIGHT-TO-LEFT OVERRIDE — text spoofing */
    private static final char RTL_OVERRIDE = '‮';

    /** U+200B ZERO-WIDTH SPACE — hidden content injection */
    private static final char ZWSP = '​';

    /** U+FEFF BYTE ORDER MARK — encoding confusion */
    private static final char BOM = '﻿';

    /** U+2028 LINE SEPARATOR — newline in JavaScript engines */
    private static final char LINE_SEP = ' ';

    /** U+2029 PARAGRAPH SEPARATOR — newline in JavaScript engines */
    private static final char PARA_SEP = ' ';

    // -------------------------------------------------------------------------
    // Allowed special paths
    // -------------------------------------------------------------------------

    /** SQLite in-memory specifier — always allowed (ADR-SQLITE-02, test environments) */
    private static final String SQLITE_MEMORY = ":memory:";

    /** JDBC URL form of the in-memory SQLite specifier */
    private static final String JDBC_SQLITE_MEMORY = "jdbc:sqlite::memory:";

    @Override
    public boolean isValid(final String value, final ConstraintValidatorContext context) {
        // Null is permitted — combine with @NotBlank where null/blank must be rejected
        // C3 — input validation: null-permissive to allow composition with @NotBlank
        if (value == null) {
            return true;
        }

        // Blank paths are rejected (CWE-20: improper input validation)
        if (value.isBlank()) {
            setStaticViolation(context);
            return false;
        }

        // Explicitly allow the SQLite in-memory specifiers (ADR-SQLITE-02)
        if (SQLITE_MEMORY.equals(value) || JDBC_SQLITE_MEMORY.equals(value)) {
            return true;
        }

        // CWE-22: reject any .. component (path traversal)
        if (value.contains("..")) {
            setStaticViolation(context);
            return false;
        }

        // Home-directory expansion via tilde
        if (value.contains("~")) {
            setStaticViolation(context);
            return false;
        }

        // CWE-626: null byte injection
        if (value.indexOf('\0') >= 0) {
            setStaticViolation(context);
            return false;
        }

        // Shell metacharacters — prevent command injection if path is ever passed to a shell
        // SR-SQLITE-08: ;, |, &, $, `, (, ) are all banned
        if (containsShellMetacharacter(value)) {
            setStaticViolation(context);
            return false;
        }

        // Unicode direction controls — prevent filename spoofing (CWE-116)
        if (containsForbiddenUnicodeControl(value)) {
            setStaticViolation(context);
            return false;
        }

        return true;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} if the value contains any shell metacharacter.
     *
     * <p>Checked characters: {@code ;}, {@code |}, {@code &}, {@code $}, {@code `},
     * {@code (}, {@code )} — all enable code execution or injection if the path
     * is ever passed to a shell (SR-SQLITE-08).
     */
    private static boolean containsShellMetacharacter(final String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == ';' || c == '|' || c == '&' || c == '$'
                    || c == '`' || c == '(' || c == ')') {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns {@code true} if the value contains any forbidden Unicode direction control.
     *
     * <p>Forbidden code points: U+202E (RTL override), U+200B (ZWSP), U+FEFF (BOM),
     * U+2028 (line separator), U+2029 (paragraph separator) — same set as
     * {@code SafeOperatorStringValidator} for consistency (ADR-P3A-9; SR-SQLITE-08).
     */
    private static boolean containsForbiddenUnicodeControl(final String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == RTL_OVERRIDE || c == ZWSP || c == BOM
                    || c == LINE_SEP || c == PARA_SEP) {
                return true;
            }
        }
        return false;
    }

    /**
     * Disables the default constraint violation and sets a static message.
     *
     * <p>The message must NOT include the rejected path value — echoing the value
     * in violation messages risks path disclosure in logs and SIEM
     * (ADR-P3A-7; CWE-532; ASVS V7.3.1 L1; SR-SQLITE-08).
     * Use {@link LogScrubber#forErrorMessage(String)} if a scrubbed representation
     * is ever needed for diagnostic purposes.
     */
    private static void setStaticViolation(final ConstraintValidatorContext context) {
        // C3 — input validation: use static message only; no value echo
        // SR-SQLITE-08: LogScrubber.forErrorMessage(value) is available if needed
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(
                        "must not contain path traversal, shell metacharacters, "
                                + "Unicode direction controls, or null bytes (SR-SQLITE-08)")
                .addConstraintViolation();
    }
}
