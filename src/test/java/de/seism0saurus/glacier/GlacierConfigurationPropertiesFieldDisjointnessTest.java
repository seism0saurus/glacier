package de.seism0saurus.glacier;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural test enforcing field-key disjointness across all
 * {@link ConfigurationProperties}-annotated beans in the Glacier codebase (ADR-P3B-5).
 *
 * <h2>What is verified</h2>
 * <p>For every {@code @ConfigurationProperties} class in {@code src/main/java}, this test
 * computes the set of fully-qualified property keys owned by that class as:
 * <pre>
 *     {prefix} + "." + kebab-case(fieldName)
 * </pre>
 * The union of all key sets across all beans must contain no duplicates.
 *
 * <h2>Why this matters (ADR-P3B-5)</h2>
 * <p>Two {@code @ConfigurationProperties} beans with the same prefix (e.g.,
 * {@code glacier.share}) are legitimate and necessary for separation of concerns.
 * However, two beans owning the SAME fully-qualified key (same prefix + same field name)
 * means both beans receive the same property value, creating silent coupling and potential
 * security misconfiguration (T-P3B-09 / OWASP A05:2021).
 *
 * <h2>Known co-prefix beans (both legitimately use {@code glacier.share.*})</h2>
 * <ul>
 *   <li>{@link de.seism0saurus.glacier.share.domain.ShareLinkLifetimePolicy} —
 *       owns {@code glacier.share.ttl}, {@code .clock-skew-tolerance},
 *       {@code .sweep-interval-ms}</li>
 *   <li>{@link de.seism0saurus.glacier.share.domain.ShareLinkCapPolicy} —
 *       owns {@code glacier.share.max-active-per-sharer}, {@code .max-active-per-ip},
 *       {@code .max-viewers-per-link}, {@code .global-max}</li>
 * </ul>
 * <p>These are allowed because their field names are disjoint.
 *
 * <h2>Implementation approach</h2>
 * <p>The test uses source-file scanning (not classpath reflection) to remain resilient
 * to build-order issues in the test runner. It:
 * <ol>
 *   <li>Walks all {@code *.java} files in {@code src/main/java}</li>
 *   <li>Identifies files with {@code @ConfigurationProperties(prefix = "...")} annotations</li>
 *   <li>Loads those classes via reflection to enumerate their declared fields</li>
 *   <li>Converts each field name to kebab-case and appends to the prefix</li>
 *   <li>Asserts no two beans produce the same fully-qualified key</li>
 * </ol>
 *
 * <p>References: ADR-P3B-5; T-P3B-09; OWASP A05:2021.
 */
class GlacierConfigurationPropertiesFieldDisjointnessTest {

    /**
     * Pattern to extract the prefix value from a {@code @ConfigurationProperties(prefix = "...")}
     * annotation line in a Java source file.
     */
    private static final Pattern PREFIX_PATTERN =
            Pattern.compile("@ConfigurationProperties\\s*\\(\\s*prefix\\s*=\\s*\"([^\"]+)\"");

    /**
     * Pattern to extract the package declaration from a Java source file.
     */
    private static final Pattern PACKAGE_PATTERN =
            Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);

    /**
     * Pattern to extract the class name from a Java source file.
     */
    private static final Pattern CLASS_NAME_PATTERN =
            Pattern.compile("(?:public\\s+)?(?:final\\s+)?class\\s+(\\w+)");

    /**
     * Arrange: scan all {@code *.java} source files for {@code @ConfigurationProperties} beans
     * Act: compute the fully-qualified key for each declared field of each bean
     * Assert: no two beans own the same fully-qualified key
     */
    @Test
    void allConfigurationPropertiesBeans_ownDisjointPropertyKeys() throws IOException {
        Path mainRoot = Path.of("src/main/java").toAbsolutePath();

        // Map from fully-qualified key → list of bean class names that claim it
        Map<String, List<String>> keyToOwners = new HashMap<>();

        try (Stream<Path> paths = Files.walk(mainRoot)) {
            paths.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .forEach(p -> {
                        try {
                            String source = Files.readString(p);

                            // Skip files that do not carry @ConfigurationProperties on the class
                            Matcher prefixMatcher = PREFIX_PATTERN.matcher(source);
                            if (!prefixMatcher.find()) {
                                return;
                            }
                            String prefix = prefixMatcher.group(1);

                            // Extract package and class name to load via reflection
                            Matcher pkgMatcher = PACKAGE_PATTERN.matcher(source);
                            if (!pkgMatcher.find()) {
                                return;
                            }
                            String pkg = pkgMatcher.group(1);

                            Matcher classMatcher = CLASS_NAME_PATTERN.matcher(source);
                            if (!classMatcher.find()) {
                                return;
                            }
                            String simpleName = classMatcher.group(1);
                            String fqcn = pkg + "." + simpleName;

                            // Load class and enumerate its declared fields
                            Class<?> beanClass;
                            try {
                                beanClass = Class.forName(fqcn);
                            } catch (ClassNotFoundException e) {
                                // Class not yet compiled (e.g., new bean in B-RED phase) — skip
                                return;
                            }

                            // Verify the loaded class actually carries @ConfigurationProperties
                            // (the source scan may match inner classes or comments — the annotation
                            // on the loaded class is the authoritative check)
                            if (!beanClass.isAnnotationPresent(ConfigurationProperties.class)) {
                                return;
                            }

                            for (Field field : beanClass.getDeclaredFields()) {
                                // Skip static, synthetic, and serialVersionUID fields
                                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                                    continue;
                                }
                                if (field.isSynthetic()) {
                                    continue;
                                }

                                String kebabFieldName = toKebabCase(field.getName());
                                String fullyQualifiedKey = prefix + "." + kebabFieldName;

                                keyToOwners
                                        .computeIfAbsent(fullyQualifiedKey, k -> new ArrayList<>())
                                        .add(simpleName);
                            }

                        } catch (IOException e) {
                            throw new RuntimeException("Failed to read: " + p, e);
                        }
                    });
        }

        // Collect all keys claimed by more than one bean
        List<String> collisions = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : keyToOwners.entrySet()) {
            if (entry.getValue().size() > 1) {
                collisions.add(entry.getKey() + " (claimed by: " + entry.getValue() + ")");
            }
        }

        assertThat(collisions)
                .as("Two or more @ConfigurationProperties beans own the same fully-qualified "
                        + "property key. This creates silent coupling: both beans receive the "
                        + "same value, making it impossible to reason about which bean is "
                        + "authoritative (ADR-P3B-5, T-P3B-09, OWASP A05:2021). "
                        + "Colliding keys: %s", collisions)
                .isEmpty();
    }

    /**
     * Converts a Java camelCase field name to Spring's kebab-case property key segment.
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code secure} → {@code secure}</li>
     *   <li>{@code accessToken} → {@code access-token}</li>
     *   <li>{@code readTimeout} → {@code read-timeout}</li>
     *   <li>{@code maxActivePerSharer} → {@code max-active-per-sharer}</li>
     *   <li>{@code sweepIntervalMs} → {@code sweep-interval-ms}</li>
     *   <li>{@code clockSkewTolerance} → {@code clock-skew-tolerance}</li>
     * </ul>
     *
     * @param camelCase the Java field name in camelCase
     * @return the kebab-case equivalent used by Spring's relaxed binding
     */
    static String toKebabCase(final String camelCase) {
        if (camelCase == null || camelCase.isEmpty()) {
            return camelCase;
        }
        // Insert hyphen before each uppercase letter that follows a lowercase letter or digit
        return camelCase
                .replaceAll("([a-z0-9])([A-Z])", "$1-$2")
                .toLowerCase();
    }
}
