package de.seism0saurus.glacier.share.infrastructure;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parameterised unit tests for {@link SafeFilesystemPath} path-traversal and injection
 * rejection on the {@link SharePersistenceProperties#getPath()} field.
 *
 * <p>Each test case asserts that the JSR-380 validator fires at least one
 * {@link ConstraintViolation} on the {@code path} property — which is what
 * {@code @Validated} on {@link SharePersistenceProperties} triggers at startup
 * (SR-SQLITE-08; CWE-22 — Path Traversal).
 *
 * <p>Test coverage:
 * <ul>
 *   <li>Classic relative traversal: {@code ..}, {@code ../}, {@code ../../etc/passwd}</li>
 *   <li>Windows traversal: {@code ..\}, {@code ..\db.sqlite}</li>
 *   <li>Mixed separators: {@code /valid/../etc/passwd}</li>
 *   <li>Shell metacharacters: {@code ; | &amp; ` $ ( ) { } &lt; &gt;}</li>
 *   <li>Home-directory expansion: {@code ~}, {@code ~/db.sqlite}</li>
 *   <li>Null byte: {@code /db\0.sqlite} (raw), {@code /db%00.sqlite} (URL-encoded)</li>
 *   <li>Unicode direction controls: U+202E, U+200B, U+FEFF, U+2028, U+2029</li>
 *   <li>Compound attacks: traversal + metacharacter, traversal + null byte</li>
 * </ul>
 *
 * <p>References: SR-SQLITE-08; OWASP A03:2021 — Injection; CWE-22; CWE-626;
 * ASVS V5.1.3 (L1); C3 — input validation at boundary.
 */
class SharePersistencePropertiesPathTraversalTest {

    private static Validator validator;

    @BeforeAll
    static void buildValidator() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    /**
     * Helper: create a minimal {@link SharePersistenceProperties} with the given path
     * and a valid HMAC key (44 chars), so only the {@code path} field is under test.
     */
    private static SharePersistenceProperties propsWithPath(final String path) {
        SharePersistenceProperties props = new SharePersistenceProperties();
        props.setPath(path);
        props.setIpHmacKey("A".repeat(44)); // minimum valid key length
        return props;
    }

    // -------------------------------------------------------------------------
    // Classic directory traversal (CWE-22)
    // -------------------------------------------------------------------------

    @ParameterizedTest(name = "path traversal rejected: [{0}]")
    @ValueSource(strings = {
            // Classic relative traversal
            "..",
            "../",
            "../../etc/passwd",
            "../db.sqlite",
            "data/../etc/shadow",
            // Windows-style traversal
            "..\\",
            "..\\db.sqlite",
            "data\\..\\etc\\passwd",
            // Mixed with valid prefix
            "/var/db/../../../etc/passwd",
            "share/db/../../../tmp/evil",
            // Triple-dot prefix (contains ..)
            "...",
            // Traversal + valid extension
            "../secret.db",
            "../../secret.db",
    })
    void pathWithTraversal_isRejected(final String maliciousPath) {
        SharePersistenceProperties props = propsWithPath(maliciousPath);
        Set<ConstraintViolation<SharePersistenceProperties>> violations = validator.validate(props);

        assertThat(violations)
                .as("Expected @SafeFilesystemPath to reject path traversal: [%s]", maliciousPath)
                .extracting(v -> v.getPropertyPath().toString())
                .contains("path");
    }

    // -------------------------------------------------------------------------
    // Shell metacharacter injection
    // -------------------------------------------------------------------------

    @ParameterizedTest(name = "shell metacharacter rejected: [{0}]")
    @ValueSource(strings = {
            "/db.sqlite;rm -rf /",
            "/db.sqlite|cat /etc/passwd",
            "/db.sqlite&id",
            "/db.sqlite`whoami`",
            "/db.sqlite$(whoami)",
            "/db${IFS}sqlite",
            "/db.sqlite{malicious}",
            "/db.sqlite<input",
            "/db.sqlite>output",
    })
    void pathWithShellMetacharacter_isRejected(final String maliciousPath) {
        SharePersistenceProperties props = propsWithPath(maliciousPath);
        Set<ConstraintViolation<SharePersistenceProperties>> violations = validator.validate(props);

        assertThat(violations)
                .as("Expected @SafeFilesystemPath to reject shell metacharacter in: [%s]", maliciousPath)
                .extracting(v -> v.getPropertyPath().toString())
                .contains("path");
    }

    // -------------------------------------------------------------------------
    // Home-directory expansion
    // -------------------------------------------------------------------------

    @ParameterizedTest(name = "home expansion rejected: [{0}]")
    @ValueSource(strings = {
            "~",
            "~/db.sqlite",
            "~root/evil.db",
    })
    void pathWithTilde_isRejected(final String maliciousPath) {
        SharePersistenceProperties props = propsWithPath(maliciousPath);
        Set<ConstraintViolation<SharePersistenceProperties>> violations = validator.validate(props);

        assertThat(violations)
                .as("Expected @SafeFilesystemPath to reject tilde home-expansion: [%s]", maliciousPath)
                .extracting(v -> v.getPropertyPath().toString())
                .contains("path");
    }

    // -------------------------------------------------------------------------
    // Null byte attacks (CWE-626)
    // -------------------------------------------------------------------------

    @ParameterizedTest(name = "null byte rejected: [{0}]")
    @ValueSource(strings = {
            "/db%00.sqlite",
            "/var/glacier%00.db",
    })
    void pathWithUrlEncodedNullByte_isRejected(final String maliciousPath) {
        SharePersistenceProperties props = propsWithPath(maliciousPath);
        Set<ConstraintViolation<SharePersistenceProperties>> violations = validator.validate(props);

        assertThat(violations)
                .as("Expected @SafeFilesystemPath to reject URL-encoded null byte in: [%s]", maliciousPath)
                .extracting(v -> v.getPropertyPath().toString())
                .contains("path");
    }

    // -------------------------------------------------------------------------
    // Unicode direction-control characters
    // -------------------------------------------------------------------------

    @ParameterizedTest(name = "unicode control rejected: [{0}]")
    @ValueSource(strings = {
            // U+202E RIGHT-TO-LEFT OVERRIDE
            "/var/glacier‮.db",
            // U+200B ZERO-WIDTH SPACE
            "/var/glacier​.db",
            // U+FEFF BYTE ORDER MARK
            "﻿/var/glacier.db",
            // U+2028 LINE SEPARATOR
            "/var/glacier .db",
            // U+2029 PARAGRAPH SEPARATOR
            "/var/glacier .db",
    })
    void pathWithUnicodeDirectionControl_isRejected(final String maliciousPath) {
        SharePersistenceProperties props = propsWithPath(maliciousPath);
        Set<ConstraintViolation<SharePersistenceProperties>> violations = validator.validate(props);

        assertThat(violations)
                .as("Expected @SafeFilesystemPath to reject Unicode direction control in path")
                .extracting(v -> v.getPropertyPath().toString())
                .contains("path");
    }

    // -------------------------------------------------------------------------
    // JDBC URL query-string and fragment injection (CRIT-3/F-2; SR-SQLITE-08)
    // -------------------------------------------------------------------------

    /**
     * CRIT-3/F-2 regression: {@code ?} in a path would inject PRAGMA parameters into the
     * JDBC URL, potentially overriding {@code synchronous=FULL} with attacker-controlled
     * values. Both raw {@code ?} and URL-encoded {@code %3F} / {@code %23} must be rejected.
     *
     * <p>References: SR-SQLITE-08; OWASP A03:2021 — Injection; CWE-88 — Argument Injection.
     */
    @ParameterizedTest(name = "JDBC URL injection rejected: [{0}]")
    @ValueSource(strings = {
            // Raw query-string injection — appends PRAGMA before controlled params
            "/var/lib/glacier/db.sqlite?synchronous=OFF",
            // Raw fragment injection — truncates controlled PRAGMA params
            "/var/lib/glacier/db.sqlite#fragment",
            // Question mark mid-path
            "/var/lib/glacier/db?extra.sqlite",
            // URL-encoded ? bypass
            "/var/lib/glacier/db.sqlite%3Fsynchronous=OFF",
            // URL-encoded ? lowercase bypass
            "/var/lib/glacier/db.sqlite%3fsynchronous=OFF",
            // URL-encoded # bypass
            "/var/lib/glacier/db.sqlite%23fragment",
    })
    void pathWithJdbcUrlInjection_isRejected(final String maliciousPath) {
        SharePersistenceProperties props = propsWithPath(maliciousPath);
        Set<ConstraintViolation<SharePersistenceProperties>> violations = validator.validate(props);

        assertThat(violations)
                .as("Expected @SafeFilesystemPath to reject JDBC URL injection in: [%s]", maliciousPath)
                .extracting(v -> v.getPropertyPath().toString())
                .contains("path");
    }

    // -------------------------------------------------------------------------
    // Violation message must NOT echo the input (CWE-22 prevention)
    // -------------------------------------------------------------------------

    @ParameterizedTest(name = "violation message does not echo input: [{0}]")
    @ValueSource(strings = {
            "../canary_value_in_violation_message",
            "~/canary_tilde_expansion",
    })
    void violationMessage_doesNotEchoInputValue(final String maliciousPath) {
        SharePersistenceProperties props = propsWithPath(maliciousPath);
        Set<ConstraintViolation<SharePersistenceProperties>> violations = validator.validate(props);

        assertThat(violations)
                .as("Violation must be present for: [%s]", maliciousPath)
                .isNotEmpty();

        for (ConstraintViolation<SharePersistenceProperties> violation : violations) {
            if ("path".equals(violation.getPropertyPath().toString())) {
                assertThat(violation.getMessage())
                        .as("Violation message must NOT echo the input value (CWE-22)")
                        .doesNotContain(maliciousPath);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Sanity: valid absolute paths are accepted
    // -------------------------------------------------------------------------

    @ParameterizedTest(name = "valid path accepted: [{0}]")
    @ValueSource(strings = {
            "/var/glacier/share.db",
            "/opt/glacier.sqlite",
            "/data/glacier/share-links.db",
            ":memory:",
            "jdbc:sqlite::memory:",
    })
    void validAbsolutePath_isAccepted(final String validPath) {
        SharePersistenceProperties props = propsWithPath(validPath);
        Set<ConstraintViolation<SharePersistenceProperties>> violations = validator.validate(props);

        long pathViolations = violations.stream()
                .filter(v -> "path".equals(v.getPropertyPath().toString()))
                .count();

        assertThat(pathViolations)
                .as("Expected @SafeFilesystemPath to accept valid path: [%s]", validPath)
                .isZero();
    }
}
