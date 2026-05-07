package de.seism0saurus.glacier.architecture;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * P2-11 / Sec-08 lockstep gate: verifies that the CI mutation-trigger regex in
 * {@code .github/workflows/build-and-deploy.yml} covers every package represented
 * by the {@code <targetClasses>} FQNs listed in {@code pom.xml}.
 *
 * <h2>Why this test exists</h2>
 * <p>PITest is only <em>triggered</em> in CI when changed files match the workflow regex.
 * If a new FQN is added to {@code <targetClasses>} but its package path is not in the
 * regex, mutations in that class will never be tested in CI — a silent coverage gap.
 * This test prevents that gap by asserting the two artefacts remain in sync.
 *
 * <h2>How the check works</h2>
 * <ol>
 *   <li>Parse {@code pom.xml} and extract every {@code <param>} inside {@code <targetClasses>}.</li>
 *   <li>Convert each FQN to its source-path prefix:<br>
 *       {@code de.seism0saurus.glacier.util.IpAddressClassifier}
 *       → {@code src/main/java/de/seism0saurus/glacier/util/}</li>
 *   <li>Deduplicate to one entry per package (not per class).</li>
 *   <li>Read the workflow YAML and extract the mutation trigger regex.</li>
 *   <li>Assert each deduced path prefix matches the extracted regex.</li>
 * </ol>
 *
 * <p>Security: ADR-FUZZ-01 / SR-FUZZ-15 — no wildcards; every security-critical class
 * must be explicitly listed in {@code <targetClasses>}.
 * OWASP C1 — access control on mutation coverage: ensure every security class is tested.
 */
class MutationRegexLockstepTest {

    /**
     * Pattern to extract the mutation trigger regex from the workflow YAML.
     * Matches the {@code grep -qE} argument on the single relevant line.
     */
    private static final Pattern WORKFLOW_REGEX_LINE = Pattern.compile(
            "grep\\s+-qE\\s+\\\\?\\s*\"([^\"]+)\"");

    /**
     * Expected root package prefix — used to validate extracted FQNs look sane.
     */
    private static final String GLACIER_PACKAGE_PREFIX = "de.seism0saurus.glacier.";

    @Test
    void pitest_targetClasses_allPackagesCoveredByCiMutationTriggerRegex() throws Exception {
        Path repoRoot = findRepoRoot();
        List<String> targetFqns = extractTargetClassFqns(repoRoot.resolve("pom.xml").toFile());
        String ciRegex = extractCiMutationRegex(repoRoot.resolve(".github/workflows/build-and-deploy.yml"));

        assertThat(targetFqns)
                .as("pom.xml <targetClasses> must not be empty — at least the baseline 10 FQNs are expected")
                .isNotEmpty();

        assertThat(ciRegex)
                .as("CI mutation trigger regex must not be blank — check .github/workflows/build-and-deploy.yml")
                .isNotBlank();

        Pattern compiled = Pattern.compile(ciRegex);

        List<String> uncovered = new ArrayList<>();
        for (String fqn : targetFqns) {
            // Convert FQN to source path: de.a.b.C → src/main/java/de/a/b/
            String pathPrefix = fqnToSourcePathPrefix(fqn);
            Matcher m = compiled.matcher(pathPrefix);
            if (!m.find()) {
                uncovered.add(fqn + " (path: " + pathPrefix + ")");
            }
        }

        if (!uncovered.isEmpty()) {
            fail("The following PITest <targetClasses> entries are NOT covered by the CI mutation "
                    + "trigger regex in build-and-deploy.yml. CI will never run PITest when these "
                    + "files change — add their package paths to the regex.\n\nUncovered:\n"
                    + String.join("\n", uncovered)
                    + "\n\nCI regex: " + ciRegex);
        }
    }

    @Test
    void pitest_targetClasses_countMatchesExpected() throws Exception {
        Path repoRoot = findRepoRoot();
        List<String> targetFqns = extractTargetClassFqns(repoRoot.resolve("pom.xml").toFile());

        // Sec-18/P2-17: 10 baseline + 4 new (HandshakeRateLimitInterceptor,
        // SubscribeRateLimitInterceptor, ShareSecurityHeadersFilter, IpAddressClassifier)
        assertThat(targetFqns)
                .as("Expected exactly 14 PITest target FQNs after Sec-18/P2-17 expansion. "
                        + "Update this assertion if you intentionally add/remove targets, "
                        + "and verify the CI regex is also updated (P2-11).")
                .hasSize(14);
    }

    @Test
    void pitest_targetClasses_allBelongToGlacierPackage() throws Exception {
        Path repoRoot = findRepoRoot();
        List<String> targetFqns = extractTargetClassFqns(repoRoot.resolve("pom.xml").toFile());

        for (String fqn : targetFqns) {
            assertThat(fqn)
                    .as("PITest target FQN must be in the glacier package namespace (no wildcards, "
                            + "no external classes): " + fqn)
                    .startsWith(GLACIER_PACKAGE_PREFIX)
                    .doesNotContain("*");
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Walks up from the test's working directory to the repo root (directory
     * containing {@code pom.xml}).
     */
    private Path findRepoRoot() throws IOException {
        // When run by Maven, the working directory is the project root.
        Path candidate = Paths.get("").toAbsolutePath();
        while (candidate != null) {
            if (Files.exists(candidate.resolve("pom.xml"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IOException("Could not locate pom.xml above: " + Paths.get("").toAbsolutePath());
    }

    /**
     * Parses {@code pom.xml} as XML and extracts every {@code <param>} text node
     * that is a direct child of the single {@code <targetClasses>} element.
     */
    private List<String> extractTargetClassFqns(File pomFile) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        // Disable XXE (OWASP A05 / SSRF mitigation — no external entity resolution needed here).
        // Use the portable JAXP approach: disable external entities and DTD processing
        // without relying on parser-vendor-specific feature URIs (avoids ParserConfigurationException
        // on JVMs without Apache Xerces as the named implementation).
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        // Portable: block all external entity resolution via JAXP attributes
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        } catch (javax.xml.parsers.ParserConfigurationException ignore) {
            // Parser doesn't support the Apache feature — fall back to attribute-based blocking
            factory.setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "");
            factory.setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "");
        }

        Document doc = factory.newDocumentBuilder().parse(pomFile);
        doc.getDocumentElement().normalize();

        NodeList targetClassesNodes = doc.getElementsByTagName("targetClasses");
        assertThat(targetClassesNodes.getLength())
                .as("pom.xml must contain exactly one <targetClasses> element (in the PITest config)")
                .isGreaterThanOrEqualTo(1);

        // Use the first <targetClasses> element — there should be only one.
        NodeList params = targetClassesNodes.item(0).getChildNodes();
        List<String> fqns = new ArrayList<>();
        for (int i = 0; i < params.getLength(); i++) {
            var node = params.item(i);
            if ("param".equals(node.getNodeName())) {
                String text = node.getTextContent().trim();
                if (!text.isBlank()) {
                    fqns.add(text);
                }
            }
        }
        return fqns;
    }

    /**
     * Reads the workflow YAML and extracts the mutation trigger regex from the
     * {@code grep -qE "..."} line inside the "Detect relevant Java source changes" step.
     */
    private String extractCiMutationRegex(Path workflowPath) throws IOException {
        String content = Files.readString(workflowPath);
        Matcher m = WORKFLOW_REGEX_LINE.matcher(content);
        Optional<String> found = Optional.empty();
        while (m.find()) {
            found = Optional.of(m.group(1));
        }
        return found.orElseThrow(() -> new AssertionError(
                "Could not extract mutation trigger regex from " + workflowPath
                        + ". Expected a line matching: grep -qE \"...\""));
    }

    /**
     * Converts a fully-qualified class name to the source-path prefix that the
     * CI regex must match. The class simple name is stripped — only the package path
     * is checked, since the regex operates on directory paths.
     *
     * <p>Example: {@code de.seism0saurus.glacier.util.IpAddressClassifier}
     * → {@code src/main/java/de/seism0saurus/glacier/util/}
     */
    private String fqnToSourcePathPrefix(String fqn) {
        int lastDot = fqn.lastIndexOf('.');
        String packageName = lastDot >= 0 ? fqn.substring(0, lastDot) : fqn;
        return "src/main/java/" + packageName.replace('.', '/') + "/";
    }
}
