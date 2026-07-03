package de.seism0saurus.glacier.ci;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-SEC-02 supply-chain gate: structural check for the {@code bigbone:2.0.0} release
 * checksum verification.
 *
 * <p>Historically {@code bigbone} was the one sanctioned SNAPSHOT dependency (no upstream
 * release existed) and this class guarded a mutable-SNAPSHOT tripwire. Upstream tagged the
 * stable release {@code io.github.pattafeufeu:bigbone:2.0.0} on 2026-06-15 and discontinued
 * SNAPSHOT publishing, so the dependency is now an immutable Maven Central artifact pinned
 * by SHA-256 — the same pattern as {@code sqlite-jdbc}. The CI step resolves the artifact
 * and compares its hash so a supply-chain swap (registry compromise, proxy tampering) fails
 * the workflow loudly (OWASP A06:2021).
 *
 * <h2>ADR-CI-18 relocation (secure CI-pipeline rebuild, 2026-07-03)</h2>
 * The checksum-verification step originally lived, duplicated, in both {@code verify.yml}
 * and {@code pull-request.yml}. The rebuild retires {@code verify.yml} outright
 * ({@link WorkflowYamlInventoryTest#everyAdrGovernedWorkflowFileExistsAndParses}) and folds
 * {@code pull-request.yml}'s build steps into the secret-free reusable core
 * {@code _build.yml}, called via {@code uses:} from every build-triggering workflow
 * (quality.yml, pull-request.yml, full-suite.yml, security.yml). The checksum step therefore
 * now has exactly one home: {@code _build.yml}'s {@code test} job. This test guards three
 * invariants that must not silently regress:
 * <ol>
 *   <li>The checksum file {@code dependency-checksums/bigbone-2.0.0.sha256} exists
 *       and holds a syntactically valid 64-hex-char SHA-256 in {@code sha256sum} format.</li>
 *   <li>{@code _build.yml}'s {@code test} job contains a step that references that checksum
 *       file AND runs {@code sha256sum} — i.e. the gate is wired into every caller that builds
 *       against the artifact, without each caller duplicating the step.</li>
 *   <li>That verification step runs BEFORE the {@code mvnw verify} build step in the same job,
 *       so a mutated dependency is caught before its code is executed.</li>
 * </ol>
 * A companion assertion confirms {@code pull-request.yml} calls the shared core rather than
 * re-declaring its own build steps — otherwise this gate could pass on {@code _build.yml}
 * while a reintroduced duplicate in {@code pull-request.yml} silently drifted out of sync.
 *
 * <p>Parsing strategy uses the shared {@link WorkflowInventory} harness (ADR-CI-07): SnakeYAML
 * traverses the object tree rather than grepping raw text, so reordering or reformatting
 * cannot produce a false positive. Every file lookup is guarded with {@link Files#exists}
 * before parsing, so a missing/relocated file fails with a readable assertion message instead
 * of an unhandled {@link java.io.UncheckedIOException}.
 *
 * <p><b>Mode applicability</b>: mode-agnostic — supply-chain integrity is independent of
 * Glacier's operational mode (live / fallback / killswitch / insecure).
 *
 * @see <a href="https://owasp.org/Top10/A06_2021-Vulnerable_and_Outdated_Components/">OWASP A06:2021</a>
 */
class BigboneChecksumPinTest {

    private static final Path CHECKSUM_FILE =
            Paths.get("dependency-checksums/bigbone-2.0.0.sha256");
    private static final Pattern SHA256_LINE =
            Pattern.compile("^[0-9a-f]{64}\\s+\\S+\\s*$");

    /** The secret-free reusable core where the checksum tripwire now lives (ADR-CI-18 migration). */
    private static final String BUILD_CORE_WORKFLOW = "_build.yml";

    /** The job inside {@link #BUILD_CORE_WORKFLOW} that runs the checksum + Maven verify steps. */
    private static final String TEST_JOB_ID = "test";

    @Test
    void checksumFileExistsAndIsValidSha256() throws IOException {
        assertThat(Files.exists(CHECKSUM_FILE))
                .as("dependency-checksums/bigbone-2.0.0.sha256 must exist (ADR-SEC-02 checksum pin)")
                .isTrue();

        List<String> lines = Files.readAllLines(CHECKSUM_FILE).stream()
                .filter(l -> !l.isBlank())
                .toList();
        assertThat(lines)
                .as("checksum file must contain exactly one sha256sum line")
                .hasSize(1);
        assertThat(lines.get(0))
                .as("checksum line must be in 'sha256sum' format: <64-hex>  bigbone-2.0.0.jar")
                .matches(SHA256_LINE);
        assertThat(lines.get(0))
                .as("checksum line must reference the bigbone release jar")
                .contains("bigbone-2.0.0.jar");
    }

    @Test
    void buildCoreVerifiesBigboneChecksumBeforeBuilding() {
        Path buildCorePath = WorkflowInventory.WORKFLOWS_DIR.resolve(BUILD_CORE_WORKFLOW);
        assertThat(Files.exists(buildCorePath))
                .as("%s must exist -- the bigbone checksum tripwire lives in the secret-free "
                        + "reusable core, not in the retired verify.yml or a duplicated "
                        + "pull-request.yml build step (ADR-CI-18 migration)", BUILD_CORE_WORKFLOW)
                .isTrue();

        WorkflowFile buildCore = WorkflowInventory.loadWorkflow(BUILD_CORE_WORKFLOW);
        WorkflowJob testJob = buildCore.job(TEST_JOB_ID)
                .orElseThrow(() -> new AssertionError(
                        BUILD_CORE_WORKFLOW + " must define a '" + TEST_JOB_ID + "' job"));

        List<WorkflowStep> runSteps = testJob.runSteps();

        int tripwireIdx = indexOfFirstRunContaining(runSteps, "bigbone-2.0.0.sha256", "sha256sum");
        assertThat(tripwireIdx)
                .as("%s's '%s' job must contain a step that runs sha256sum against the bigbone "
                        + "checksum file (ADR-SEC-02)", BUILD_CORE_WORKFLOW, TEST_JOB_ID)
                .isGreaterThanOrEqualTo(0);

        int buildIdx = indexOfFirstRunContainingAny(runSteps,
                "mvnw --batch-mode verify", "mvnw --batch-mode -DskipTests=true package");
        assertThat(buildIdx)
                .as("%s's '%s' job must contain the Maven build step whose ordering relative to "
                        + "the checksum tripwire this gate protects", BUILD_CORE_WORKFLOW, TEST_JOB_ID)
                .isGreaterThanOrEqualTo(0);

        assertThat(tripwireIdx)
                .as("%s: the bigbone checksum tripwire must run BEFORE the Maven build so a mutated "
                        + "dependency is caught before its code is executed", BUILD_CORE_WORKFLOW)
                .isLessThan(buildIdx);
    }

    /**
     * Drift guard for the relocation itself: {@code pull-request.yml} must call the shared
     * {@code _build.yml} core (via {@code uses:}) rather than re-declaring its own build/
     * checksum steps. Without this, {@link #buildCoreVerifiesBigboneChecksumBeforeBuilding()}
     * could stay green forever on {@code _build.yml} while a reintroduced, un-migrated
     * duplicate step in {@code pull-request.yml} silently skipped the checksum entirely.
     */
    @Test
    void pullRequestWorkflowDelegatesBuildToSharedCoreInsteadOfDuplicatingIt() {
        WorkflowFile pullRequest = WorkflowInventory.loadWorkflow("pull-request.yml");
        WorkflowJob buildJob = pullRequest.job("build")
                .orElseThrow(() -> new AssertionError(
                        "pull-request.yml must define a 'build' job that delegates to " + BUILD_CORE_WORKFLOW));

        assertThat(buildJob.callsReusableWorkflow())
                .as("pull-request.yml's 'build' job must call %s via 'uses:' rather than "
                        + "duplicating its own compile/test/checksum steps inline -- a duplicate "
                        + "would let the two copies of the bigbone checksum step drift apart silently",
                        BUILD_CORE_WORKFLOW)
                .isTrue();
        assertThat(buildJob.calledWorkflow())
                .as("pull-request.yml's 'build' job must reference %s specifically", BUILD_CORE_WORKFLOW)
                .contains(BUILD_CORE_WORKFLOW);
    }

    private static int indexOfFirstRunContaining(List<WorkflowStep> steps, String... allOf) {
        for (int i = 0; i < steps.size(); i++) {
            String run = steps.get(i).run();
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

    private static int indexOfFirstRunContainingAny(List<WorkflowStep> steps, String... anyOf) {
        for (int i = 0; i < steps.size(); i++) {
            String run = steps.get(i).run();
            if (run == null) continue;
            for (String needle : anyOf) {
                if (run.contains(needle)) return i;
            }
        }
        return -1;
    }
}
