package de.seism0saurus.glacier;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural ratchet test: every {@code ConstraintValidator} in this codebase that calls
 * {@code context.buildConstraintViolationWithTemplate(...)} must pass ONLY string literals
 * (or literal-to-literal concatenation). Raw identifier concatenation is forbidden.
 *
 * <p>Enforcement rule (ADR-P3B-4): after the opening parenthesis of
 * {@code buildConstraintViolationWithTemplate(}, the argument must consist ONLY of:
 * <ul>
 *   <li>String literals (sequences starting with {@code "})</li>
 *   <li>String-to-string concatenation operators ({@code + "})</li>
 * </ul>
 * Any {@code + someIdentifier} (a {@code +} followed by a Java identifier token, not a quote)
 * is a violation of this invariant and must fail the test.
 *
 * <p>This test scans only files ending in {@code *Validator.java} under
 * {@code src/main/java/}. It does NOT scan test-only validators or infrastructure classes.
 *
 * <p>The three files currently subject to this rule are:
 * <ol>
 *   <li>{@code DomainSafetyValidator.java} — fixed by ADR-P3B-4 (Lane A, Bundle B)</li>
 *   <li>{@code MastodonInstanceValidator.java} — already compliant (all static messages)</li>
 *   <li>{@code SafeOperatorStringValidator.java} — already compliant (all static messages)</li>
 * </ol>
 *
 * <p>Adding a new {@code *Validator.java} that uses identifier concatenation in a template
 * argument will fail this test automatically — ratcheting the invariant forward.
 *
 * <p>References: ADR-P3B-4; SR-P3B-01a; SR-P3B-02; SR-P3B-10; CWE-532;
 * ASVS V7.3.1 (L1) — error messages must not reflect untrusted input;
 * OWASP A05:2021 Security Misconfiguration.
 */
class ConstraintViolationMessageStaticOnlyStructureTest {

    /**
     * Pattern to locate any occurrence of {@code buildConstraintViolationWithTemplate(}
     * in source text. Group 1 captures the content from the opening paren to end of line.
     */
    private static final Pattern BUILD_TEMPLATE_CALL =
            Pattern.compile("buildConstraintViolationWithTemplate\\((.*)");

    /**
     * Pattern that detects identifier concatenation inside a template argument.
     *
     * <p>Matches: {@code + someIdentifier} where {@code someIdentifier} starts with a letter,
     * {@code $}, or {@code _} (Java identifier start chars). This is the forbidden pattern
     * that would echo a raw runtime value into the violation message.
     *
     * <p>The pattern is deliberately simple — it does NOT try to parse Java fully.
     * A false positive (flagging something that is actually a valid string literal) is
     * acceptable as a conservative ratchet; a false negative (missing real identifier
     * concatenation) would be a security gap.
     *
     * <p>Specifically: matches {@code +} (optionally preceded by whitespace) followed by
     * whitespace and then a Java identifier start character. The sequence {@code + "} is
     * explicitly excluded because {@code + "literal"} is a safe literal concatenation.
     */
    private static final Pattern IDENTIFIER_CONCAT_PATTERN =
            Pattern.compile("\\+\\s+[A-Za-z_$]");

    /**
     * Scans all {@code *Validator.java} files in {@code src/main/java/} and fails if any
     * {@code buildConstraintViolationWithTemplate(} call site has identifier concatenation
     * in its argument.
     *
     * <p>Algorithm:
     * <ol>
     *   <li>Walk {@code src/main/java/} recursively for files matching {@code *Validator.java}</li>
     *   <li>For each file, find every occurrence of {@code buildConstraintViolationWithTemplate(}</li>
     *   <li>Collect the remainder of that line plus the next 4 lines (to cover multi-line template strings)</li>
     *   <li>If the collected window contains {@code + someIdentifier} (IDENTIFIER_CONCAT_PATTERN), record a violation</li>
     * </ol>
     */
    @Test
    void allConstraintValidators_buildConstraintViolationWithTemplate_usesStaticLiteralsOnly()
            throws IOException {

        Path mainRoot = Path.of("src/main/java").toAbsolutePath();
        List<String> violations = new ArrayList<>();

        try (Stream<Path> paths = Files.walk(mainRoot)) {
            paths.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith("Validator.java"))
                    .forEach(filePath -> checkFile(filePath, violations));
        }

        assertThat(violations)
                .as("ADR-P3B-4: The following buildConstraintViolationWithTemplate() call sites "
                        + "contain identifier concatenation (raw value echo). "
                        + "All arguments must be string literals only — no '+ someVariable'. "
                        + "Affected locations:\n%s", String.join("\n", violations))
                .isEmpty();
    }

    /**
     * Checks a single {@code *Validator.java} file for identifier concatenation in
     * {@code buildConstraintViolationWithTemplate()} arguments.
     *
     * @param filePath   the file to check
     * @param violations accumulator for violation descriptions
     */
    private static void checkFile(final Path filePath, final List<String> violations) {
        List<String> lines;
        try {
            lines = Files.readAllLines(filePath);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read validator file: " + filePath, e);
        }

        String fileName = filePath.getFileName().toString();

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            Matcher callMatcher = BUILD_TEMPLATE_CALL.matcher(line);
            if (!callMatcher.find()) {
                continue;
            }

            // Collect the tail of the current line plus up to 4 following lines
            // to cover multi-line template string arguments.
            StringBuilder window = new StringBuilder();
            window.append(callMatcher.group(1)); // tail of the line after '('
            for (int j = i + 1; j <= i + 4 && j < lines.size(); j++) {
                window.append('\n').append(lines.get(j));
                // Stop collecting once we see the closing .addConstraintViolation() — the
                // argument is complete and any further content belongs to the next statement.
                if (lines.get(j).contains(".addConstraintViolation()")) {
                    break;
                }
            }

            String windowStr = window.toString();

            // Check if the window contains '+ someIdentifier' (forbidden pattern)
            if (IDENTIFIER_CONCAT_PATTERN.matcher(windowStr).find()) {
                int lineNo = i + 1; // 1-based
                violations.add(String.format(
                        "  %s (line %d): buildConstraintViolationWithTemplate() argument contains "
                                + "identifier concatenation (ADR-P3B-4, CWE-532): %s",
                        fileName, lineNo, line.strip()));
            }
        }
    }
}
