package de.seism0saurus.glacier.share.infrastructure;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parameterised tests for {@link SafeFilesystemPath} constraint annotation.
 *
 * <p>Verifies that path traversal, shell metacharacters, Unicode direction controls,
 * null bytes, and home-directory expansion are all rejected by
 * {@link SafeFilesystemPathValidator}.
 *
 * <p>The SQLite in-memory path {@code :memory:} and valid absolute paths must PASS
 * (needed for tests and standard operation).
 *
 * <p>References: SR-SQLITE-08; OWASP A05:2021 Security Misconfiguration;
 * ASVS V5.1.3 (L1); CWE-22 Path Traversal; CWE-626 Null Byte Injection.
 */
class SharePersistencePropertiesPathTraversalTest {

    /** Minimal holder bean used to exercise the {@link SafeFilesystemPath} annotation. */
    private static class PathHolder {
        @SafeFilesystemPath
        String path;

        PathHolder(final String path) {
            this.path = path;
        }
    }

    private static Validator validator;

    @BeforeAll
    static void initValidator() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    // -------------------------------------------------------------------------
    // Parameterised REJECT cases
    // -------------------------------------------------------------------------

    static Stream<Arguments> rejectedPaths() {
        return Stream.of(
                // CWE-22: path traversal via ..
                Arguments.of("../etc/passwd", "path traversal via .."),
                Arguments.of("/var/data/../../../etc/shadow", "multi-segment traversal"),
                Arguments.of("data/../../secret", "relative traversal"),

                // Home-directory expansion
                Arguments.of("~/.ssh/id_rsa", "tilde home expansion"),
                Arguments.of("~/glacier.db", "tilde home shorthand"),

                // CWE-626: null byte injection
                Arguments.of("path\0null", "null byte in path"),
                Arguments.of("/data/db\0.txt", "null byte disguised as extension"),

                // Shell metacharacters (SR-SQLITE-08)
                Arguments.of(";rm -rf /", "shell semicolon"),
                Arguments.of("path|cat /etc/passwd", "shell pipe"),
                Arguments.of("path&background", "shell ampersand"),
                Arguments.of("$HOME/db", "shell dollar"),
                Arguments.of("`whoami`", "backtick command substitution"),
                Arguments.of("path(sub)", "shell open paren"),
                Arguments.of("path)end", "shell close paren"),

                // Unicode direction controls (SR-SQLITE-08; ADR-P3A-9)
                Arguments.of("‮evil", "U+202E RIGHT-TO-LEFT OVERRIDE"),
                Arguments.of("/data/​db", "U+200B ZERO-WIDTH SPACE"),
                Arguments.of("/data/﻿db", "U+FEFF BYTE ORDER MARK"),
                Arguments.of("/data/ db", "U+2028 LINE SEPARATOR"),
                Arguments.of("/data/ db", "U+2029 PARAGRAPH SEPARATOR"),

                // Blank / empty
                Arguments.of("", "blank path"),
                Arguments.of("   ", "whitespace-only path")
        );
    }

    @ParameterizedTest(name = "rejects [{index}] {1}: ''{0}''")
    @MethodSource("rejectedPaths")
    void rejectsUnsafePath(final String path, final String description) {
        Set<ConstraintViolation<PathHolder>> violations = validator.validate(new PathHolder(path));
        assertThat(violations)
                .as("@SafeFilesystemPath must reject path '%s' (%s) — SR-SQLITE-08", path, description)
                .isNotEmpty();
    }

    // -------------------------------------------------------------------------
    // Parameterised ALLOW cases
    // -------------------------------------------------------------------------

    static Stream<Arguments> allowedPaths() {
        return Stream.of(
                // SQLite in-memory path — MUST be allowed for tests (ADR-SQLITE-02)
                Arguments.of("jdbc:sqlite::memory:", "SQLite in-memory test path"),
                Arguments.of(":memory:", "bare SQLite in-memory specifier"),

                // Valid absolute paths on Linux/macOS
                Arguments.of("/var/data/glacier/share.db", "standard absolute path"),
                Arguments.of("/opt/glacier/db/links.db", "deep absolute path"),
                Arguments.of("/tmp/glacier-test.db", "tmp dir path"),

                // Null value — accepted by the constraint (combine with @NotBlank where null
                // must be rejected; @SafeFilesystemPath is null-permissive to allow opt-in)
                Arguments.of(null, "null is permitted — @NotBlank handles null separately")
        );
    }

    @ParameterizedTest(name = "allows [{index}] {1}: ''{0}''")
    @MethodSource("allowedPaths")
    void allowsSafePath(final String path, final String description) {
        Set<ConstraintViolation<PathHolder>> violations = validator.validate(new PathHolder(path));
        assertThat(violations)
                .as("@SafeFilesystemPath must accept path '%s' (%s) — SR-SQLITE-08", path, description)
                .isEmpty();
    }
}
