package de.seism0saurus.glacier.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural regression gate: enforces that all case-folding calls in production
 * code use {@code Locale.ROOT} — never the no-argument form or {@code Locale.ENGLISH}.
 *
 * <p><b>Why this test exists (ADR-LR-01 / ADR-LR-02)</b>:
 * On a JVM started with Turkish locale ({@code -Duser.language=tr}), bare
 * {@code toUpperCase()} / {@code toLowerCase()} and their {@code Locale.ENGLISH}
 * counterparts produce wrong results for characters like 'i', 'I', 'ı', 'İ'.
 * For protocol-token comparisons (HTTP methods, URL schemes, CSP directives) this
 * is a security defect (CWE-176 / CWE-178). {@code Locale.ROOT} is the correct choice
 * for protocol tokens because they have no natural language.
 *
 * <p><b>Why {@code equalsIgnoreCase} is NOT scanned (ADR-LR-03)</b>:
 * JDK 23 {@link String#equalsIgnoreCase(String)} uses
 * {@link Character#toUpperCase(int)} which is locale-independent by JDK specification.
 * Scanning it would produce false positives without adding security value.
 * See JDK source: String.java, equalsIgnoreCase() delegates to Character.toUpperCase(int),
 * not to String.toUpperCase(Locale), so no locale context is consulted.
 *
 * <p><b>Opt-out mechanism (for legitimate future exceptions)</b>:
 * If a line has a legitimate need for locale-sensitive case folding, append
 * {@code // LOCALE-OK: <reason>} to that line. The structural gate will skip it.
 * Currently, no exceptions are expected or registered.
 *
 * <p>Security requirements: SR-LR-04 (CWE-176, CWE-178), SR-LR-07 (self-exclusion).
 */
class LocaleRootDisciplineTest {

    /**
     * Regex matching the no-argument bare form of toUpperCase/toLowerCase.
     * Forbidden — must always specify Locale.ROOT for protocol tokens.
     * Example match: {@code scheme.toLowerCase()} or {@code method.toUpperCase( )}.
     */
    private static final Pattern BARE_CASE_FOLD =
            Pattern.compile("\\.to(?:Upper|Lower)Case\\s*\\(\\s*\\)");

    /**
     * Regex matching the Locale.ENGLISH form of toUpperCase.
     * Forbidden — ENGLISH carries language semantics inappropriate for protocol tokens.
     * ADR-LR-01: only Locale.ROOT is correct for HTTP methods, schemes, CSP directives.
     */
    private static final Pattern ENGLISH_UPPER =
            Pattern.compile("\\.toUpperCase\\(Locale\\.ENGLISH\\)");

    /**
     * Regex matching the Locale.ENGLISH form of toLowerCase.
     * Forbidden — same rationale as {@link #ENGLISH_UPPER}.
     */
    private static final Pattern ENGLISH_LOWER =
            Pattern.compile("\\.toLowerCase\\(Locale\\.ENGLISH\\)");

    /**
     * Opt-out marker: a line containing this comment is excluded from all three
     * pattern checks. Use only for documented, reviewed exceptions.
     */
    private static final String LOCALE_OK_MARKER = "// LOCALE-OK:";

    /**
     * SR-LR-07: the structural gate must exclude its own source file to avoid
     * false-positive matches on the pattern strings within this very class.
     */
    private static final String THIS_FILE_SUFFIX =
            "LocaleRootDisciplineTest.java";

    /**
     * SR-LR-04: Walk all {@code .java} files under {@code src/main/java} and assert
     * that none contains a bare {@code toUpperCase()} / {@code toLowerCase()} call,
     * a {@code toUpperCase(Locale.ENGLISH)} call, or a {@code toLowerCase(Locale.ENGLISH)}
     * call.
     *
     * <p>Violations are collected before failing so all offending lines are reported
     * in a single assertion, not just the first match.
     */
    @Test
    void noBareCaseFoldingInProductionCode() throws IOException {
        Path srcMain = resolveSourceRoot();
        List<String> violations = collectViolations(srcMain, BARE_CASE_FOLD, "bare");

        assertThat(violations)
                .as("Found bare toUpperCase()/toLowerCase() calls in production code. "
                        + "Replace all occurrences with Locale.ROOT form. "
                        + "CWE-178: locale-sensitive case folding on protocol tokens is a security defect.")
                .isEmpty();
    }

    /**
     * SR-LR-04: Assert zero {@code toUpperCase(Locale.ENGLISH)} calls in production code.
     */
    @Test
    void noEnglishLocaleUpperCaseInProductionCode() throws IOException {
        Path srcMain = resolveSourceRoot();
        List<String> violations = collectViolations(srcMain, ENGLISH_UPPER, "Locale.ENGLISH toUpperCase");

        assertThat(violations)
                .as("Found toUpperCase(Locale.ENGLISH) in production code. "
                        + "Use toUpperCase(Locale.ROOT) instead. "
                        + "Locale.ENGLISH carries English-specific semantics; Locale.ROOT is correct "
                        + "for protocol tokens (ADR-LR-01).")
                .isEmpty();
    }

    /**
     * SR-LR-04: Assert zero {@code toLowerCase(Locale.ENGLISH)} calls in production code.
     */
    @Test
    void noEnglishLocaleLowerCaseInProductionCode() throws IOException {
        Path srcMain = resolveSourceRoot();
        List<String> violations = collectViolations(srcMain, ENGLISH_LOWER, "Locale.ENGLISH toLowerCase");

        assertThat(violations)
                .as("Found toLowerCase(Locale.ENGLISH) in production code. "
                        + "Use toLowerCase(Locale.ROOT) instead. "
                        + "Locale.ENGLISH carries English-specific semantics; Locale.ROOT is correct "
                        + "for protocol tokens (ADR-LR-01).")
                .isEmpty();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Resolves the {@code src/main/java} directory relative to the project root.
     *
     * <p>Tries the working directory first (Maven Surefire sets cwd to the module root),
     * then walks up the directory tree looking for {@code pom.xml} as the project root marker.
     */
    private static Path resolveSourceRoot() throws IOException {
        Path cwd = Paths.get(System.getProperty("user.dir", "."));

        // Try cwd/src/main/java (standard Maven layout when cwd == module root)
        Path candidate = cwd.resolve("src/main/java");
        if (Files.isDirectory(candidate)) {
            return candidate;
        }

        // Walk up until we find a pom.xml — the Maven module root
        Path current = cwd;
        for (int i = 0; i < 5; i++) {
            if (Files.exists(current.resolve("pom.xml"))) {
                Path resolved = current.resolve("src/main/java");
                if (Files.isDirectory(resolved)) {
                    return resolved;
                }
            }
            Path parent = current.getParent();
            if (parent == null) break;
            current = parent;
        }

        throw new IOException(
                "Cannot locate src/main/java. cwd=" + cwd
                        + ". Ensure the test runs from the Maven module root.");
    }

    /**
     * Scans all {@code .java} files under {@code root} for lines matching {@code pattern}.
     * Lines containing {@link #LOCALE_OK_MARKER} are skipped (opt-out mechanism).
     * The file {@link #THIS_FILE_SUFFIX} is excluded (SR-LR-07 self-exclusion).
     *
     * @param root        directory to walk recursively
     * @param pattern     the pattern to detect as a violation
     * @param description human-readable name of the pattern (used in violation messages)
     * @return list of violation messages (empty if none found)
     */
    private static List<String> collectViolations(
            final Path root,
            final Pattern pattern,
            final String description) throws IOException {

        List<String> violations = new ArrayList<>();

        try (Stream<Path> files = Files.walk(root)) {
            files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.toString().endsWith(THIS_FILE_SUFFIX))
                    .forEach(file -> {
                        try {
                            List<String> lines = Files.readAllLines(file);
                            for (int i = 0; i < lines.size(); i++) {
                                String line = lines.get(i);
                                // Skip lines with the explicit opt-out marker
                                if (line.contains(LOCALE_OK_MARKER)) {
                                    continue;
                                }
                                if (pattern.matcher(line).find()) {
                                    violations.add(String.format(
                                            "[%s] %s:%d — %s",
                                            description,
                                            root.relativize(file),
                                            i + 1,
                                            line.stripLeading()));
                                }
                            }
                        } catch (IOException e) {
                            violations.add(String.format(
                                    "[%s] Cannot read file: %s — %s",
                                    description, file, e.getMessage()));
                        }
                    });
        }

        return violations;
    }
}
