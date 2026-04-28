package de.seism0saurus.glacier.webservice;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Package-exhaustive DTO field scan to prevent leakage of internal identifiers
 * and raw URLs that would allow SSRF probing or session correlation.
 *
 * <p>Security requirement (ADR-TEST-02, SR-TEST-05, SR-PT-08, OWASP API3 Excessive
 * Data Exposure): Response/Entry/DTO/Message classes must NEVER expose:
 * <ul>
 *   <li>{@code wallId} — the session identifier used for STOMP topic authorization</li>
 *   <li>{@code sharerWallId} — identifies the share-link creator</li>
 *   <li>{@code principal} — internal Spring Security principal</li>
 *   <li>{@code rawWallId} — an unmasked wallId variant</li>
 *   <li>{@code userId} — generic user identifier</li>
 *   <li>{@code tootUrl} — raw toot URL (SR-PT-08: must not appear in response DTOs;
 *       the validated embed URL is the only safe form to expose)</li>
 *   <li>{@code rawTootUrl} — explicitly marks an unvalidated toot URL variant</li>
 *   <li>{@code embedUrl} — raw embed URL field that would expose unvalidated URL strings</li>
 * </ul>
 *
 * <p>The scan is <strong>package-exhaustive</strong> — it inspects all classes in
 * {@code de.seism0saurus.glacier} whose simple name ends in {@code Response},
 * {@code Entry}, {@code Dto}, or {@code Message}.
 *
 * <p>Known architectural exceptions are recorded in {@link #ALLOWED_EXCEPTIONS}:
 * {@code SubscriptionAckMessage#principal} and {@code TerminationAckMessage#principal}
 * are required so the Angular frontend can construct the STOMP topic destination
 * {@code /topic/hashtags/{wallId}/...}. The {@code principal} field in these messages
 * is the wallId intentionally sent to the browser for that purpose.
 */
class DtoFieldScanTest {

    /**
     * Field names that must NEVER appear in any DTO/Response/Entry/Message class
     * unless explicitly allowlisted in {@link #ALLOWED_EXCEPTIONS}.
     *
     * <p>SR-PT-08: {@code tootUrl}, {@code rawTootUrl}, and {@code embedUrl} are
     * added to the forbidden set to prevent future DTOs from inadvertently exposing
     * raw or unvalidated URL strings. The only safe form for a toot URL in a
     * client-facing DTO is the validated embed URL (the {@code url} field in
     * {@code StatusCreatedMessage} / {@code StatusUpdatedMessage} which is already
     * constrained to the {@code /embed} path suffix by {@code StompCallback}).</p>
     */
    private static final Set<String> FORBIDDEN_FIELD_NAMES = Set.of(
            "wallId",
            "sharerWallId",
            "principal",
            "rawWallId",
            "userId",
            // SR-PT-08: raw toot URL fields must not appear in client-facing DTOs
            "tootUrl",
            "rawTootUrl",
            "embedUrl"
    );

    /**
     * Simple-name suffixes that identify DTO/response/message classes to scan.
     * The "Message" suffix is included because messaging DTOs are serialized and
     * sent to browsers, making data exposure a concrete risk.
     */
    private static final List<String> DTO_SUFFIXES = List.of("Response", "Entry", "Dto", "Message");

    /**
     * Explicit allowlist for fields that are architecturally required to contain
     * principal/wallId values despite the general prohibition.
     *
     * <p>Justification for each entry:
     * <ul>
     *   <li>{@code SubscriptionAckMessage#principal} — sent to the browser so the Angular
     *       frontend can construct {@code /topic/hashtags/{wallId}/...} for live subscription.
     *       This mirrors {@code GET /rest/wall-id} which also returns the wallId verbatim.
     *       The wallId is not a secret per se — it is the subscription namespace.</li>
     *   <li>{@code TerminationAckMessage#principal} — same reason as above, for
     *       subscription termination acknowledgements.</li>
     * </ul>
     */
    private static final Set<String> ALLOWED_EXCEPTIONS = Set.of(
            "de.seism0saurus.glacier.webservice.messaging.messages.SubscriptionAckMessage#principal",
            "de.seism0saurus.glacier.webservice.messaging.messages.TerminationAckMessage#principal"
    );

    /**
     * Root package to scan.
     */
    private static final String ROOT_PACKAGE = "de.seism0saurus.glacier";

    // ---------------------------------------------------------------------------
    // Main scan test
    // ---------------------------------------------------------------------------

    @Test
    void noShareDtoLeaksWallIdAcrossPackage() throws Exception {
        // ARRANGE — discover all DTO/Response/Entry/Message classes in the root package
        List<Class<?>> dtoClasses = findDtoClasses(ROOT_PACKAGE);

        // ASSERT — none of them have forbidden fields (outside the explicit allowlist)
        List<String> violations = new ArrayList<>();

        for (Class<?> clazz : dtoClasses) {
            for (Field field : getAllDeclaredFields(clazz)) {
                if (FORBIDDEN_FIELD_NAMES.contains(field.getName())) {
                    String key = clazz.getName() + "#" + field.getName();
                    if (!ALLOWED_EXCEPTIONS.contains(key)) {
                        violations.add(key + " (not in allowlist)");
                    }
                }
            }
        }

        assertThat(violations)
                .as("No DTO/Response/Entry/Message class should expose forbidden field names "
                        + "unless they are in the ALLOWED_EXCEPTIONS set. "
                        + "Violations found — either remove/rename the fields "
                        + "or add a justified entry to ALLOWED_EXCEPTIONS. "
                        + "Found: " + violations)
                .isEmpty();
    }

    /**
     * Verifies that the scan found at least one class (guards against a misconfigured
     * classpath that would make the scan vacuously pass).
     */
    @Test
    void scan_findsAtLeastOneClassInPackage() throws Exception {
        List<Class<?>> allClasses = findClassesInPackage(ROOT_PACKAGE);
        assertThat(allClasses)
                .as("Package scan must find at least some classes in " + ROOT_PACKAGE)
                .isNotEmpty();
    }

    /**
     * Verifies the allowlist entries are accurate — each entry in ALLOWED_EXCEPTIONS
     * must correspond to a field that actually exists in the named class.
     * This prevents stale allowlist entries after refactoring.
     */
    @Test
    void allowlistEntries_referenceExistingFields() throws Exception {
        for (String entry : ALLOWED_EXCEPTIONS) {
            String[] parts = entry.split("#");
            assertThat(parts).hasSize(2);
            String className = parts[0];
            String fieldName = parts[1];
            try {
                Class<?> clazz = Class.forName(className);
                List<Field> fields = getAllDeclaredFields(clazz);
                boolean fieldExists = fields.stream()
                        .anyMatch(f -> f.getName().equals(fieldName));
                assertThat(fieldExists)
                        .as("Allowlist entry '%s' references non-existent field '%s' in class '%s'",
                                entry, fieldName, className)
                        .isTrue();
            } catch (ClassNotFoundException e) {
                assertThat(false)
                        .as("Allowlist entry '%s' references non-existent class '%s'",
                                entry, className)
                        .isTrue();
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Regression test: named nested class filter (F-5)
    // ---------------------------------------------------------------------------

    /**
     * Verifies that the {@code findClassesInDirectory} filename filter correctly
     * distinguishes between anonymous/synthetic inner classes (which should be excluded)
     * and named nested classes (which must be included in the scan).
     */
    @Test
    void scan_regexExcludesAnonymousButIncludesNamedNestedClasses() {
        assertThat("Outer$BarResponse.class")
                .as("Named nested class 'Outer$BarResponse.class' must NOT match the exclusion regex")
                .doesNotMatch(".*\\$\\d+\\.class");

        assertThat("Outer$1.class")
                .as("Anonymous inner class 'Outer$1.class' must match the exclusion regex")
                .matches(".*\\$\\d+\\.class");
        assertThat("Outer$2.class")
                .as("Anonymous inner class 'Outer$2.class' must match the exclusion regex")
                .matches(".*\\$\\d+\\.class");

        assertThat("Outer$$Lambda$42.class")
                .as("Synthetic lambda class 'Outer$$Lambda$42.class' is excluded by the regex "
                        + "because '$42' matches the \\$\\d+ pattern")
                .matches(".*\\$\\d+\\.class");

        assertThat("PlainClass.class")
                .as("Plain class 'PlainClass.class' must NOT match the exclusion regex")
                .doesNotMatch(".*\\$\\d+\\.class");
    }

    // -------------------------------------------------------------------------
    // SR-PT-08: Verify raw toot URL field names are in the forbidden set
    // -------------------------------------------------------------------------

    /**
     * SR-PT-08: verifies that {@code tootUrl}, {@code rawTootUrl}, and {@code embedUrl}
     * are present in {@link #FORBIDDEN_FIELD_NAMES}.
     *
     * <p>OWASP API3 / SR-PT-08: raw toot URL fields in client-facing DTOs would expose
     * unvalidated URL strings to the browser, undermining the SSRF guard in
     * {@link de.seism0saurus.glacier.mastodon.StompCallback}.</p>
     */
    @Test
    void forbiddenFieldNames_containsRawTootUrlFields() {
        assertThat(FORBIDDEN_FIELD_NAMES)
                .as("SR-PT-08: tootUrl must be in FORBIDDEN_FIELD_NAMES to prevent raw URL exposure")
                .contains("tootUrl");
        assertThat(FORBIDDEN_FIELD_NAMES)
                .as("SR-PT-08: rawTootUrl must be in FORBIDDEN_FIELD_NAMES to prevent raw URL exposure")
                .contains("rawTootUrl");
        assertThat(FORBIDDEN_FIELD_NAMES)
                .as("SR-PT-08: embedUrl must be in FORBIDDEN_FIELD_NAMES to prevent raw URL exposure")
                .contains("embedUrl");
    }

    // ============================================================================
    // Package scanning helpers
    // ============================================================================

    private List<Class<?>> findDtoClasses(String basePackage) throws Exception {
        return findClassesInPackage(basePackage).stream()
                .filter(c -> DTO_SUFFIXES.stream()
                        .anyMatch(suffix -> c.getSimpleName().endsWith(suffix)))
                .collect(Collectors.toList());
    }

    private List<Class<?>> findClassesInPackage(String basePackage) throws Exception {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        String path = basePackage.replace('.', '/');
        URL resource = classLoader.getResource(path);
        if (resource == null) {
            return List.of();
        }
        File directory = new File(resource.toURI());
        return findClassesInDirectory(directory, basePackage);
    }

    private List<Class<?>> findClassesInDirectory(File directory, String packageName) {
        List<Class<?>> classes = new ArrayList<>();
        if (!directory.exists()) {
            return classes;
        }
        File[] files = directory.listFiles();
        if (files == null) {
            return classes;
        }
        for (File file : files) {
            if (file.isDirectory()) {
                classes.addAll(findClassesInDirectory(file, packageName + "." + file.getName()));
            } else if (file.getName().endsWith(".class") && !file.getName().matches(".*\\$\\d+\\.class")) {
                String className = packageName + "." + file.getName().replace(".class", "");
                try {
                    Class<?> clazz = Class.forName(className, false,
                            Thread.currentThread().getContextClassLoader());
                    classes.add(clazz);
                } catch (ClassNotFoundException | NoClassDefFoundError e) {
                    // Skip classes that fail to load (e.g., missing transitive deps)
                }
            }
        }
        return classes;
    }

    private List<Field> getAllDeclaredFields(Class<?> clazz) {
        List<Field> fields = new ArrayList<>();
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            fields.addAll(Arrays.asList(current.getDeclaredFields()));
            current = current.getSuperclass();
        }
        return fields;
    }
}
