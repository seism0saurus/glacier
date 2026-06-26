package de.seism0saurus.glacier.ci;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-SEC-02 supply-chain tripwire: structural gate for the {@code bigbone:2.0.0-SNAPSHOT}
 * checksum verification.
 *
 * <p>{@code bigbone} is the one sanctioned SNAPSHOT dependency (no upstream release exists).
 * A SNAPSHOT is mutable: any upstream republish silently changes the served artifact, which
 * is a supply-chain attack surface (OWASP A06:2021). {@code maven-enforcer}'s
 * {@code requireReleaseDependencies} cannot pin a SNAPSHOT, so the pin is enforced at the CI
 * layer instead: a recorded SHA-256 of the known-good resolved jar is compared against the
 * artifact CI actually resolves. A mismatch fails the workflow loudly so a human consciously
 * re-pins after reviewing the upstream change — turning a silent swap into a gated event.
 *
 * <p>This test guards three invariants that must not silently regress:
 * <ol>
 *   <li>The checksum file {@code dependency-checksums/bigbone-2.0.0-SNAPSHOT.sha256} exists
 *       and holds a syntactically valid 64-hex-char SHA-256 in {@code sha256sum} format.</li>
 *   <li>Both build workflows ({@code verify.yml}, {@code pull-request.yml}) contain a step
 *       that references that checksum file AND runs {@code sha256sum} — i.e. the tripwire is
 *       wired into every path that builds against the SNAPSHOT.</li>
 *   <li>That verification step runs BEFORE the {@code mvnw verify}/{@code package} build step
 *       in each workflow, so a mutated dependency is caught before its code is executed.</li>
 * </ol>
 *
 * <p>Parsing strategy mirrors {@link WorkflowYamlInventoryTest}: SnakeYAML traverses the
 * object tree rather than grepping raw text, so reordering or reformatting cannot produce a
 * false positive.
 *
 * <p><b>Mode applicability</b>: mode-agnostic — supply-chain integrity is independent of
 * Glacier's operational mode (live / fallback / killswitch / insecure).
 *
 * @see <a href="https://owasp.org/Top10/A06_2021-Vulnerable_and_Outdated_Components/">OWASP A06:2021</a>
 */
class BigboneSnapshotTripwireTest {

    private static final Path CHECKSUM_FILE =
            Paths.get("dependency-checksums/bigbone-2.0.0-SNAPSHOT.sha256");
    private static final Pattern SHA256_LINE =
            Pattern.compile("^[0-9a-f]{64}\\s+\\S+\\s*$");
    private static final List<String> BUILD_WORKFLOWS =
            List.of(".github/workflows/verify.yml", ".github/workflows/pull-request.yml");

    @Test
    void checksumFileExistsAndIsValidSha256() throws IOException {
        assertThat(Files.exists(CHECKSUM_FILE))
                .as("dependency-checksums/bigbone-2.0.0-SNAPSHOT.sha256 must exist (ADR-SEC-02 tripwire)")
                .isTrue();

        List<String> lines = Files.readAllLines(CHECKSUM_FILE).stream()
                .filter(l -> !l.isBlank())
                .toList();
        assertThat(lines)
                .as("checksum file must contain exactly one sha256sum line")
                .hasSize(1);
        assertThat(lines.get(0))
                .as("checksum line must be in 'sha256sum' format: <64-hex>  bigbone-2.0.0-SNAPSHOT.jar")
                .matches(SHA256_LINE);
        assertThat(lines.get(0))
                .as("checksum line must reference the bigbone snapshot jar")
                .contains("bigbone-2.0.0-SNAPSHOT.jar");
    }

    @Test
    void bothBuildWorkflowsVerifyBigboneChecksumBeforeBuilding() throws IOException {
        for (String workflowPath : BUILD_WORKFLOWS) {
            List<Map<String, Object>> runSteps = allRunSteps(workflowPath);

            int tripwireIdx = indexOfFirstRunContaining(runSteps,
                    "bigbone-2.0.0-SNAPSHOT.sha256", "sha256sum");
            assertThat(tripwireIdx)
                    .as("%s must contain a step that runs sha256sum against the bigbone checksum file (ADR-SEC-02)",
                            workflowPath)
                    .isGreaterThanOrEqualTo(0);

            int buildIdx = indexOfFirstRunContainingAny(runSteps,
                    "mvnw --batch-mode verify", "mvnw --batch-mode -DskipTests=true package");
            // Only assert ordering when this workflow actually has a Maven build step.
            if (buildIdx >= 0) {
                assertThat(tripwireIdx)
                        .as("%s: the bigbone checksum tripwire must run BEFORE the Maven build so a mutated "
                                + "dependency is caught before its code is executed", workflowPath)
                        .isLessThan(buildIdx);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> allRunSteps(String workflowPath) throws IOException {
        try (InputStream in = Files.newInputStream(Paths.get(workflowPath))) {
            Map<String, Object> workflow = new Yaml().load(in);
            Map<String, Object> jobs = (Map<String, Object>) workflow.get("jobs");
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object job : jobs.values()) {
                Object steps = ((Map<String, Object>) job).get("steps");
                if (steps instanceof List<?> stepList) {
                    for (Object step : stepList) {
                        if (step instanceof Map<?, ?> m && m.get("run") != null) {
                            result.add((Map<String, Object>) m);
                        }
                    }
                }
            }
            return result;
        }
    }

    private static int indexOfFirstRunContaining(List<Map<String, Object>> steps, String... allOf) {
        for (int i = 0; i < steps.size(); i++) {
            String run = (String) steps.get(i).get("run");
            boolean all = true;
            for (String needle : allOf) {
                if (run == null || !run.contains(needle)) {
                    all = false;
                    break;
                }
            }
            if (all) return i;
        }
        return -1;
    }

    private static int indexOfFirstRunContainingAny(List<Map<String, Object>> steps, String... anyOf) {
        for (int i = 0; i < steps.size(); i++) {
            String run = (String) steps.get(i).get("run");
            if (run == null) continue;
            for (String needle : anyOf) {
                if (run.contains(needle)) return i;
            }
        }
        return -1;
    }
}
