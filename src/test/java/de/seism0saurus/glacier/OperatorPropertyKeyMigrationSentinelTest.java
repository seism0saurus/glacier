package de.seism0saurus.glacier;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural ratchet test: no Java or properties file outside the historical ADR docs
 * directory must reference operator properties in the old camelCase form
 * (e.g., {@code glacier.operatorName}) instead of the new nested form
 * ({@code glacier.operator.name}).
 *
 * <p>This sentinel ensures the property-key migration (ADR-P3A-3) is complete and
 * prevents any future code from silently re-introducing the old key.
 *
 * <p>References: ADR-P3A-3; AC-P3A-14.
 */
class OperatorPropertyKeyMigrationSentinelTest {

    /**
     * Pattern matching the old camelCase operator property keys.
     * e.g., glacier.operatorName, glacier.operatorMail, etc.
     */
    private static final Pattern OLD_OPERATOR_KEY_PATTERN =
            Pattern.compile("glacier\\.operator[A-Z]");

    /**
     * Directories or filenames allowlisted for historical references to the old key form.
     * <ul>
     *   <li>ADR files ({@code docs/decisions}) are hand-authored and contain both old and
     *       new keys for documentation.</li>
     *   <li>This sentinel test itself ({@code OperatorPropertyKeyMigrationSentinelTest.java})
     *       necessarily contains the old pattern in Javadoc and its own assertion messages —
     *       it would flag itself without this allowlist entry.</li>
     *   <li>{@code GlacierOperatorProperties.java} contains the old pattern in its Javadoc
     *       as a migration note (ADR-P3A-3).</li>
     * </ul>
     */
    private static final Set<String> ALLOWLISTED_PATH_SEGMENTS = Set.of(
            "docs/decisions",
            "OperatorPropertyKeyMigrationSentinelTest.java",
            "GlacierOperatorProperties.java"
    );

    @Test
    void noJavaOrPropertiesFileContainsCamelCaseOperatorKey() throws IOException {
        Path projectRoot = Path.of("src").toAbsolutePath();
        List<Path> violations = new ArrayList<>();

        try (Stream<Path> paths = Files.walk(projectRoot)) {
            paths.filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.endsWith(".java") || name.endsWith(".properties");
                    })
                    .filter(p -> !isAllowlisted(p))
                    .forEach(p -> {
                        try {
                            String content = Files.readString(p);
                            if (OLD_OPERATOR_KEY_PATTERN.matcher(content).find()) {
                                violations.add(p);
                            }
                        } catch (IOException e) {
                            throw new RuntimeException("Failed to read: " + p, e);
                        }
                    });
        }

        assertThat(violations)
                .as("No .java or .properties file under src/ should contain the old camelCase "
                        + "operator key form (glacier.operatorName, etc.). "
                        + "These files still reference the old form (ADR-P3A-3, AC-P3A-14): %s",
                        violations)
                .isEmpty();
    }

    private boolean isAllowlisted(Path path) {
        String pathStr = path.toString().replace('\\', '/');
        String fileName = path.getFileName().toString();
        return ALLOWLISTED_PATH_SEGMENTS.stream()
                .anyMatch(segment -> pathStr.contains(segment) || fileName.equals(segment));
    }
}
