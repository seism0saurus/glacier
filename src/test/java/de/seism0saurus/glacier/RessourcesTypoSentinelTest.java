package de.seism0saurus.glacier;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P3-08 structural sentinel: asserts that no Java source file under {@code src/main/java}
 * contains the misspelling {@code ressources} (double-s French variant).
 *
 * <p>The correct spelling is {@code resources}. All occurrences were fixed as part of P3-08;
 * this test prevents re-introduction. It performs a case-sensitive file scan so that
 * camelCase identifiers or foreign-language comments that use the correct single-s form
 * are not flagged.
 *
 * <p>Mode applicability: mode-agnostic — this is a static source-quality gate independent
 * of Glacier's operational mode (live / fallback / killswitch / insecure).
 */
class RessourcesTypoSentinelTest {

    /**
     * Walks {@code src/main/java} and asserts that no {@code .java} file contains
     * the string {@code ressources} (case-sensitive).
     *
     * <p>The path is resolved relative to the working directory, matching the convention
     * used by other structural tests in this package (e.g. {@code WorkflowYamlInventoryTest}).
     */
    @Test
    void noJavaFileContainsRessourcesTypo() throws IOException {
        Path sourceRoot = Path.of("src/main/java");

        try (Stream<Path> walk = Files.walk(sourceRoot)) {
            List<Path> offending = walk
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> {
                        try {
                            String content = Files.readString(p);
                            return content.contains("ressources");
                        } catch (IOException e) {
                            throw new RuntimeException("Failed to read " + p, e);
                        }
                    })
                    .toList();

            assertThat(offending)
                    .as("No .java file under src/main/java should contain 'ressources' (use 'resources' instead). "
                            + "Offending files: %s", offending)
                    .isEmpty();
        }
    }
}
