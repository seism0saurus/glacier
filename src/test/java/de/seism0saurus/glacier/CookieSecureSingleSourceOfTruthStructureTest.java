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
 * Structural ratchet test: ALL production consumers of {@code glacier.cookie.secure}
 * must remain on {@code @Value} injection — NOT wired through any
 * {@code @ConfigurationProperties} bean like {@code GlacierDevmodeProperties}.
 *
 * <p>The migration of {@code glacier.cookie.secure} to a typed bean has been
 * DEFERRED to Bundle B (ADR-P3A-1; Conflict #1 resolution; SR-P3A-05).
 * Partial wiring — where some consumers use {@code @Value} and others use the
 * bean — is a security misconfiguration window: two binding mechanisms are
 * simultaneously authoritative for one boolean, creating an inconsistency window
 * if one path is updated and the other is not.
 *
 * <p>This test fails if ANY of the known production consumer classes is changed
 * to use a {@code GlacierDevmodeProperties} reference (or any other property-bean)
 * for the cookie-secure flag before the atomic full migration has been approved.
 *
 * <p>Expected {@code @Value} consumers (AC-P3A-01):
 * <ol>
 *   <li>{@code InformationController}</li>
 *   <li>{@code CsrfTokenCookieFactory}</li>
 *   <li>{@code ShareViewerCookieFactory}</li>
 *   <li>{@code ShareCsrfGuard}</li>
 *   <li>{@code StartupSanityChecker}</li>
 *   <li>{@code ImageProxyHmacSecretValidator}</li>
 *   <li>{@code WebSocketConfiguration}</li>
 * </ol>
 *
 * <p>References: ADR-P3A-1; SR-P3A-05; AC-P3A-01.
 */
class CookieSecureSingleSourceOfTruthStructureTest {

    /**
     * Pattern that detects {@code @Value("${glacier.cookie.secure...}")} usage.
     * All known consumers must still have this pattern.
     */
    private static final Pattern VALUE_ANNOTATION_PATTERN =
            Pattern.compile("@Value\\([^)]*glacier\\.cookie\\.secure");

    /**
     * Pattern that would indicate migration to a bean reference has happened prematurely.
     * e.g., "glacierDevmodeProperties.isCookieSecure()" or injection of the bean
     * where the field is cookieSecure.
     */
    private static final Pattern PREMATURE_BEAN_PATTERN =
            Pattern.compile("GlacierDevmodeProperties");

    /**
     * The 7 known production consumer class simple names and their relative paths.
     * Updated here when Bundle B formally migrates them atomically.
     */
    private static final Set<String> EXPECTED_VALUE_CONSUMERS = Set.of(
            "InformationController.java",
            "CsrfTokenCookieFactory.java",
            "ShareViewerCookieFactory.java",
            "ShareCsrfGuard.java",
            "StartupSanityChecker.java",
            "ImageProxyHmacSecretValidator.java",
            "WebSocketConfiguration.java"
    );

    @Test
    void allCookieSecureConsumers_stillUseAtValue() throws IOException {
        Path mainRoot = Path.of("src/main/java").toAbsolutePath();
        List<String> missingValueAnnotation = new ArrayList<>();

        try (Stream<Path> paths = Files.walk(mainRoot)) {
            paths.filter(Files::isRegularFile)
                    .filter(p -> EXPECTED_VALUE_CONSUMERS.contains(p.getFileName().toString()))
                    .forEach(p -> {
                        try {
                            String content = Files.readString(p);
                            if (!VALUE_ANNOTATION_PATTERN.matcher(content).find()) {
                                missingValueAnnotation.add(p.getFileName().toString());
                            }
                        } catch (IOException e) {
                            throw new RuntimeException("Failed to read: " + p, e);
                        }
                    });
        }

        assertThat(missingValueAnnotation)
                .as("These known glacier.cookie.secure consumers no longer use @Value. "
                        + "If the migration to GlacierDevmodeProperties is intended, "
                        + "it must be done atomically across ALL consumers in Bundle B (ADR-P3A-1, SR-P3A-05). "
                        + "Affected files: %s", missingValueAnnotation)
                .isEmpty();
    }

    @Test
    void noCookieSecureConsumer_usesPrematureGlacierDevmodePropertiesBean() throws IOException {
        Path mainRoot = Path.of("src/main/java").toAbsolutePath();
        List<String> prematureConsumers = new ArrayList<>();

        try (Stream<Path> paths = Files.walk(mainRoot)) {
            paths.filter(Files::isRegularFile)
                    .filter(p -> EXPECTED_VALUE_CONSUMERS.contains(p.getFileName().toString()))
                    .forEach(p -> {
                        try {
                            String content = Files.readString(p);
                            if (PREMATURE_BEAN_PATTERN.matcher(content).find()) {
                                prematureConsumers.add(p.getFileName().toString());
                            }
                        } catch (IOException e) {
                            throw new RuntimeException("Failed to read: " + p, e);
                        }
                    });
        }

        assertThat(prematureConsumers)
                .as("These consumer files already reference GlacierDevmodeProperties, "
                        + "indicating partial wiring. This is forbidden until all consumers "
                        + "are migrated atomically (ADR-P3A-1, SR-P3A-05, AC-P3A-01): %s",
                        prematureConsumers)
                .isEmpty();
    }
}
