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
 * Bidirectional structural ratchet test for the {@code glacier.cookie.secure} migration
 * to {@link GlacierCookieProperties} (Bundle B, ADR-P3B-2).
 *
 * <h2>Two assertions are required (SR-P3B-05)</h2>
 * <ol>
 *   <li><b>Positive</b>: all 7 consumer files reference {@code GlacierCookieProperties}
 *       — confirms the migration actually happened in every consumer.</li>
 *   <li><b>Negative</b>: none of the 7 consumer files still contains
 *       {@code @Value("${glacier.cookie.secure"} } — confirms no file was left on the
 *       old injection mechanism.</li>
 * </ol>
 *
 * <h2>Why bidirectional?</h2>
 * <p>A unidirectional positive-only test would pass even if the bean reference was added
 * AS A COMMENT while the {@code @Value} was kept. The negative assertion guarantees
 * the old injection is fully removed (SR-P3B-05a; ADR-P3B-2 atomic migration).
 *
 * <p>This test replaces {@code CookieSecureSingleSourceOfTruthStructureTest} which
 * asserted the OPPOSITE (all consumers on @Value). That class is deleted in the same
 * commit as this file (ADR-P3B-2).
 *
 * <p>References: ADR-P3B-2; SR-P3B-05; SR-P3B-05a.
 */
class CookieSecureBeanWiringStructureTest {

    /**
     * Pattern asserting that the file references {@link GlacierCookieProperties} by name.
     * Matches any reference: import, field type, constructor parameter type, or JavaDoc.
     */
    private static final Pattern BEAN_REFERENCE_PATTERN =
            Pattern.compile("GlacierCookieProperties");

    /**
     * Pattern asserting that NO legacy {@code @Value("${glacier.cookie.secure")} injection remains.
     * Uses {@code \\s*} to tolerate optional whitespace between {@code @Value} and its argument
     * (e.g., {@code @Value( "${glacier.cookie.secure"}}).
     */
    private static final Pattern LEGACY_VALUE_PATTERN =
            Pattern.compile("@Value\\s*\\(\\s*\"\\$\\{glacier\\.cookie\\.secure");

    /**
     * The 7 known consumer class filenames (simple name only).
     * These are the files that must ALL be migrated atomically (ADR-P3B-2).
     */
    private static final Set<String> CONSUMER_FILENAMES = Set.of(
            "InformationController.java",
            "WebSocketConfiguration.java",
            "CsrfTokenCookieFactory.java",
            "ShareViewerCookieFactory.java",
            "ShareCsrfGuard.java",
            "ImageProxyHmacSecretValidator.java",
            "StartupSanityChecker.java"
    );

    /**
     * Positive assertion: all 7 consumer files must reference {@link GlacierCookieProperties}.
     *
     * <p>Arrange: walk {@code src/main/java} for the 7 known filenames
     * Act: check each file for the {@code GlacierCookieProperties} pattern
     * Assert: no file is missing the reference
     */
    @Test
    void allConsumers_referencesGlacierCookieProperties() throws IOException {
        Path mainRoot = Path.of("src/main/java").toAbsolutePath();
        List<String> missingBeanReference = new ArrayList<>();

        try (Stream<Path> paths = Files.walk(mainRoot)) {
            paths.filter(Files::isRegularFile)
                    .filter(p -> CONSUMER_FILENAMES.contains(p.getFileName().toString()))
                    .forEach(p -> {
                        try {
                            String content = Files.readString(p);
                            if (!BEAN_REFERENCE_PATTERN.matcher(content).find()) {
                                missingBeanReference.add(p.getFileName().toString());
                            }
                        } catch (IOException e) {
                            throw new RuntimeException("Failed to read: " + p, e);
                        }
                    });
        }

        assertThat(missingBeanReference)
                .as("These consumer files do NOT reference GlacierCookieProperties. "
                        + "All 7 consumers must be migrated atomically (ADR-P3B-2, SR-P3B-05). "
                        + "Missing reference: %s", missingBeanReference)
                .isEmpty();
    }

    /**
     * Negative assertion: none of the 7 consumer files may still use
     * {@code @Value("${glacier.cookie.secure"}) injection.
     *
     * <p>Arrange: walk {@code src/main/java} for the 7 known filenames
     * Act: check each file for the legacy {@code @Value} pattern
     * Assert: no file retains the old injection (no partial migration)
     */
    @Test
    void noConsumer_stillUsesAtValueInjection() throws IOException {
        Path mainRoot = Path.of("src/main/java").toAbsolutePath();
        List<String> legacyConsumers = new ArrayList<>();

        try (Stream<Path> paths = Files.walk(mainRoot)) {
            paths.filter(Files::isRegularFile)
                    .filter(p -> CONSUMER_FILENAMES.contains(p.getFileName().toString()))
                    .forEach(p -> {
                        try {
                            String content = Files.readString(p);
                            if (LEGACY_VALUE_PATTERN.matcher(content).find()) {
                                legacyConsumers.add(p.getFileName().toString());
                            }
                        } catch (IOException e) {
                            throw new RuntimeException("Failed to read: " + p, e);
                        }
                    });
        }

        assertThat(legacyConsumers)
                .as("These consumer files still use @Value(\"${glacier.cookie.secure\") injection. "
                        + "The migration must be complete — no @Value injection may remain "
                        + "(ADR-P3B-2 atomic migration; SR-P3B-05; SR-P3B-05a). "
                        + "Remaining legacy consumers: %s", legacyConsumers)
                .isEmpty();
    }
}
