package de.seism0saurus.glacier;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P3-03 structural sentinel: asserts that every {@code .md} file under
 * {@code docs/decisions/} (except {@code README.md} itself) is referenced
 * in {@code docs/decisions/README.md}.
 *
 * <p>This prevents the ADR index from going stale when new decision records
 * are added without a corresponding index row. The check is filename-based:
 * the file's simple name (e.g. {@code 2026-05-10-planning-p3-backlog-bundle.md})
 * must appear as a substring of the index content.
 *
 * <p>Mode applicability: mode-agnostic — this is a static documentation-quality
 * gate independent of Glacier's operational mode (live / fallback / killswitch / insecure).
 */
class AdrIndexCompletenessSentinelTest {

    private static final String DECISIONS_DIR = "docs/decisions";
    private static final String INDEX_FILE = "docs/decisions/README.md";

    /**
     * Walks {@code docs/decisions/} and asserts that every {@code .md} file
     * (except {@code README.md}) is referenced by name in {@code docs/decisions/README.md}.
     */
    @Test
    void adrIndexReferencesAllDecisionFiles() throws IOException {
        Path indexPath = Path.of(INDEX_FILE);
        assertThat(indexPath).as("ADR index file must exist at %s", INDEX_FILE).exists();

        String indexContent = Files.readString(indexPath);

        try (Stream<Path> walk = Files.walk(Path.of(DECISIONS_DIR))) {
            List<Path> unreferenced = walk
                    .filter(p -> p.toString().endsWith(".md"))
                    .filter(p -> !p.getFileName().toString().equals("README.md"))
                    .filter(p -> {
                        String filename = p.getFileName().toString();
                        return !indexContent.contains(filename);
                    })
                    .toList();

            assertThat(unreferenced)
                    .as("All .md files under docs/decisions/ must be referenced in docs/decisions/README.md. "
                            + "Missing from index: %s", unreferenced)
                    .isEmpty();
        }
    }
}
