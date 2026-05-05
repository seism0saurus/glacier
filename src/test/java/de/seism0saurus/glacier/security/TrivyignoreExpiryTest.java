package de.seism0saurus.glacier.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * SR-CI-03: Validates that every CVE suppression in the Trivy ignore files carries a
 * non-expired {@code # expires: YYYY-MM-DD} trailing comment.
 *
 * <p>Two files are checked:
 * <ul>
 *   <li>{@code infrastructure/security/.trivyignore} — image-layer suppressions</li>
 *   <li>{@code infrastructure/security/.trivyignore-fs} — jar-dependency suppressions</li>
 * </ul>
 *
 * <p>Rules enforced:
 * <ol>
 *   <li>Every non-comment, non-blank line must contain a trailing {@code # expires: YYYY-MM-DD}
 *       comment (format must be parseable by {@link LocalDate#parse}).</li>
 *   <li>The expiry date must be &gt;= today (past expiries cause build failure so suppressions
 *       are revisited on schedule rather than silently accumulated).</li>
 * </ol>
 *
 * <p>Paths are relative to the project root so the test works both locally and in CI.
 *
 * <p><b>Mode applicability</b>: mode-agnostic — this is a CI-level concern that applies
 * regardless of the operational mode (live / fallback / killswitch / insecure).
 *
 * <p>OWASP: A06:2021 — Vulnerable and Outdated Components.
 *
 * @see <a href="https://owasp.org/Top10/A06_2021-Vulnerable_and_Outdated_Components/">OWASP A06:2021</a>
 */
class TrivyignoreExpiryTest {

    /**
     * Regex to extract an expiry date from a suppression line.
     *
     * <p>A valid suppression line looks like one of:
     * <pre>
     *   CVE-2024-1234 # expires: 2026-12-31
     *   CVE-2024-1234 # some justification; expires: 2026-12-31
     * </pre>
     *
     * <p>The regex captures the ISO-8601 date in group 1.
     */
    private static final Pattern EXPIRY_PATTERN =
            Pattern.compile(".*#\\s*(?:.*[;,]\\s*)?expires:\\s*(\\d{4}-\\d{2}-\\d{2})\\s*$",
                    Pattern.CASE_INSENSITIVE);

    /**
     * Paths to both Trivy ignore files, relative to the project root.
     */
    private static final String TRIVYIGNORE_IMAGE = "infrastructure/security/.trivyignore";
    private static final String TRIVYIGNORE_FS    = "infrastructure/security/.trivyignore-fs";

    /**
     * Validate the image-layer Trivy ignore file.
     *
     * <p>SR-CI-03: every suppression in {@code .trivyignore} must have a non-expired
     * {@code # expires: YYYY-MM-DD} comment.
     */
    @Test
    void imageLayerTrivyignore_allSuppressionsHaveNonExpiredExpiryDate() {
        validateFile(Paths.get(TRIVYIGNORE_IMAGE));
    }

    /**
     * Validate the jar-dependency Trivy ignore file.
     *
     * <p>SR-CI-03: every suppression in {@code .trivyignore-fs} must have a non-expired
     * {@code # expires: YYYY-MM-DD} comment.
     */
    @Test
    void jarDependencyTrivyignore_allSuppressionsHaveNonExpiredExpiryDate() {
        validateFile(Paths.get(TRIVYIGNORE_FS));
    }

    /**
     * SR-F9-01: Assert that {@code .trivyignore-fs} contains an active sentinel line.
     *
     * <p>The sentinel proves that:
     * <ol>
     *   <li>The {@code trivyignores} CI parameter in {@code security.yml} successfully targets
     *       this file (rather than being silently misconfigured).</li>
     *   <li>The file follows the {@code # expires:} discipline — the sentinel itself carries an
     *       expiry date that forces a yearly refresh.</li>
     * </ol>
     *
     * <p>OWASP: A06:2021 — Vulnerable and Outdated Components.
     */
    @Test
    void trivyignoreFs_containsActiveSentinel() throws IOException {
        Path ignoreFile = Path.of(TRIVYIGNORE_FS);
        List<String> lines = Files.readAllLines(ignoreFile);

        boolean sentinelFound = lines.stream()
                .anyMatch(l -> l.startsWith("# SENTINEL: trivyignores parameter active # expires: "));

        assertThat(sentinelFound)
                .as("SR-F9-01: .trivyignore-fs must contain an active sentinel line " +
                        "'# SENTINEL: trivyignores parameter active # expires: YYYY-MM-DD' " +
                        "to prove the trivyignores CI parameter is wired and the file is actively maintained. " +
                        "Add the sentinel line or renew it if expired.")
                .isTrue();

        // Also verify the sentinel's expiry date is in the future
        lines.stream()
                .filter(l -> l.startsWith("# SENTINEL: trivyignores parameter active # expires: "))
                .forEach(l -> {
                    String dateStr = l.substring(l.lastIndexOf("expires: ") + "expires: ".length()).trim();
                    LocalDate expiry = LocalDate.parse(dateStr);
                    assertThat(expiry)
                            .as("SR-F9-01: Sentinel expiry date %s is in the past — renew the sentinel " +
                                    "to confirm the trivyignores parameter is still active", dateStr)
                            .isAfter(LocalDate.now());
                });
    }

    /**
     * Core validation logic for a single Trivy ignore file.
     *
     * <p>For each non-comment, non-blank line:
     * <ol>
     *   <li>Assert a trailing {@code # expires: YYYY-MM-DD} comment is present.</li>
     *   <li>Parse the date with {@link LocalDate#parse}; fail with a clear message if the format
     *       is wrong.</li>
     *   <li>Assert the date is &gt;= today.</li>
     * </ol>
     *
     * @param filePath relative path from the project root
     */
    private void validateFile(Path filePath) {
        assertThat(filePath.toFile())
                .as("Trivy ignore file must exist: %s", filePath)
                .exists()
                .isReadable();

        List<String> lines;
        try {
            lines = Files.readAllLines(filePath);
        } catch (IOException e) {
            fail("Failed to read Trivy ignore file '%s': %s", filePath, e.getMessage());
            return; // unreachable — fail() throws
        }

        LocalDate today = LocalDate.now();
        List<String> violations = new ArrayList<>();

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();

            // Skip comment lines (starting with #) and blank lines
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }

            int lineNumber = i + 1;

            // Every non-blank, non-comment line is a CVE suppression — it must have an expiry
            Matcher matcher = EXPIRY_PATTERN.matcher(line);
            if (!matcher.matches()) {
                violations.add(String.format(
                        "  Line %d in %s: missing '# expires: YYYY-MM-DD' comment.%n    Line content: %s",
                        lineNumber, filePath, line));
                continue;
            }

            // Parse the date — format must be ISO-8601 (YYYY-MM-DD)
            String dateStr = matcher.group(1);
            LocalDate expiryDate;
            try {
                expiryDate = LocalDate.parse(dateStr);
            } catch (DateTimeParseException e) {
                violations.add(String.format(
                        "  Line %d in %s: unparseable expiry date '%s' (must be YYYY-MM-DD).%n    Line content: %s",
                        lineNumber, filePath, dateStr, line));
                continue;
            }

            // The expiry date must not be in the past
            if (expiryDate.isBefore(today)) {
                violations.add(String.format(
                        "  Line %d in %s: suppression expired on %s (today is %s). Re-triage or remove.%n    Line content: %s",
                        lineNumber, filePath, expiryDate, today, line));
            }
        }

        assertThat(violations)
                .as("SR-CI-03 (A06:2021): Trivy suppressions in '%s' have expiry violations:%n%s%n%n"
                        + "Fix: add '# expires: YYYY-MM-DD' to every suppression, or remove/re-triage expired ones.",
                        filePath, String.join("\n", violations))
                .isEmpty();
    }
}
