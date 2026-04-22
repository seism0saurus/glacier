package de.seism0saurus.glacier.util;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Codebase constraint tests (SR-9, D-14).
 *
 * <p>Asserts that {@code glacier.devmode} is never read outside the {@code mastodon/*} package.
 * This property controls TLS/protocol selection — if it leaked into other packages, an attacker
 * could observe environment-derived behaviour through non-mastodon endpoints (D-14, SR-9).
 *
 * <p>Test strategy: use {@link Files#walk} + {@link Files#readString} to grep all {@code .java}
 * files under the guarded directories.  If a violation is found, the test reports the file path
 * and line number so it can be fixed immediately.
 */
class CodebaseConstraintTest {

    /**
     * The property name that must NOT appear outside {@code mastodon/*}.
     * (D-14, SR-9)
     */
    private static final String FORBIDDEN_PROPERTY = "glacier.devmode";

    /**
     * Directories that must contain zero references to {@link #FORBIDDEN_PROPERTY}.
     */
    private static final List<String> GUARDED_PACKAGE_PATHS = List.of(
            "src/main/java/de/seism0saurus/glacier/webservice",
            "src/main/java/de/seism0saurus/glacier/util"
    );

    /**
     * Asserts that {@code glacier.devmode} does not appear in any {@code .java} file
     * under the guarded packages.
     *
     * <p>Only the {@code mastodon/*} package is allowed to reference this property (D-14).
     * Any violation indicates a developer has leaked TLS-mode logic into an unintended layer.
     *
     * <p>If a violation is found this test fails with: the offending file path and line number.
     */
    @Test
    void glacierDevmode_notReferencedOutsideMastodon() throws IOException {
        Path projectRoot = findProjectRoot();
        List<String> violations = new ArrayList<>();

        for (String relPath : GUARDED_PACKAGE_PATHS) {
            Path dir = projectRoot.resolve(relPath);
            if (!Files.exists(dir)) {
                // Directory doesn't exist yet — not a violation
                continue;
            }

            try (Stream<Path> walk = Files.walk(dir)) {
                walk.filter(p -> p.toString().endsWith(".java"))
                        .forEach(file -> {
                            try {
                                List<String> lines = Files.readAllLines(file);
                                for (int i = 0; i < lines.size(); i++) {
                                    if (lines.get(i).contains(FORBIDDEN_PROPERTY)) {
                                        violations.add(
                                                file + ":" + (i + 1)
                                                + " → contains \"" + FORBIDDEN_PROPERTY + "\""
                                        );
                                    }
                                }
                            } catch (IOException e) {
                                violations.add(file + ":? → could not read file: " + e.getMessage());
                            }
                        });
            }
        }

        if (!violations.isEmpty()) {
            String report = String.join("\n  ", violations);
            fail("SR-9 / D-14 violation: \"" + FORBIDDEN_PROPERTY + "\" found outside mastodon/*:\n  " + report);
        }
    }

    /**
     * Asserts that {@code application.properties} contains no hardcoded password literals.
     *
     * <p>A "hardcoded password" is any line matching {@code password=<value>} where the value
     * is non-empty and does NOT use environment-variable expansion ({@code ${...}}) or a
     * property-reference form.  Empty passwords ({@code password=}) and env-var references
     * ({@code password=${ENV_VAR:default}}) are allowed.
     *
     * <p>Security rationale (F-05): hardcoded credentials in application.properties are visible
     * to anyone with repo read access, get committed to version control, and appear in Docker
     * image layers.  Detected lines must be either deleted (if dead config) or replaced with
     * {@code ${ENV_VAR}} references before merging.
     *
     * <p>If this test fails you will see the exact line number so the violation can be fixed
     * immediately.
     */
    @Test
    void assertNoHardcodedPasswords_inApplicationProperties() throws IOException {
        Path projectRoot = findProjectRoot();
        Path propsFile = projectRoot.resolve("src/main/resources/application.properties");

        assertThat(propsFile).as("application.properties must exist").exists();

        List<String> lines = Files.readAllLines(propsFile);
        List<String> violations = new ArrayList<>();

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            // Skip comments and blank lines
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            // Match lines of the form  password=<something>
            // where <something> is present, non-empty, and does NOT start with '${'
            if (line.matches("(?i).*[._-]?password=.+") && !line.matches("(?i).*[._-]?password=\\$\\{.*")) {
                violations.add("application.properties:" + (i + 1) + " → hardcoded password: " + line);
            }
        }

        if (!violations.isEmpty()) {
            String report = String.join("\n  ", violations);
            fail("F-05: hardcoded password(s) found in application.properties.\n"
                    + "Use ${ENV_VAR} references or delete dead configuration blocks:\n  "
                    + report);
        }
    }

    /**
     * Locates the Maven project root by walking up from the current working directory
     * until a {@code pom.xml} is found.  Failsafe: caps at 5 levels.
     */
    private static Path findProjectRoot() {
        // Try the current directory and parents
        Path candidate = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 5; i++) {
            if (Files.exists(candidate.resolve("pom.xml"))) {
                return candidate;
            }
            Path parent = candidate.getParent();
            if (parent == null) break;
            candidate = parent;
        }
        // Fallback: assume tests run from repo root (Maven Surefire default)
        return Paths.get("").toAbsolutePath();
    }
}
