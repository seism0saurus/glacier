package de.seism0saurus.glacier.ci;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gate 5.6 (SecurityDastSelfContained): the dynamic-scan reusable core
 * ({@code _security-dast.yml}) never assumes a GHCR image already exists — it builds (or
 * receives) everything it scans within its own run.
 *
 * <p>The pre-rebuild {@code security.yml} has a live
 * {@code docker manifest inspect ghcr.io/seism0saurus/glacier:${{ github.ref_name }}}
 * precondition step: it fails loudly if the image was never published, but it also means the
 * scan job is not self-contained — it depends on push-order and registry state instead of
 * building what it scans. ADR-CI-06 removes that precondition entirely: the DAST core must
 * build the Docker image (and the jar it packages) itself, so it can run identically from
 * {@code quality.yml}, {@code pull-request.yml} (fork PRs included), {@code full-suite.yml},
 * and the scheduled {@code security.yml}, without any of them needing to have already pushed
 * an image to GHCR first.
 *
 * <h2>Rules enforced</h2>
 * <ol>
 *   <li>No step anywhere in the repository's workflows runs
 *       {@code docker manifest inspect ghcr.io/...} — the removed precondition, checked
 *       repository-wide so it cannot silently resurface in another file.</li>
 *   <li>{@code _security-dast.yml} exists and builds the Docker image it scans in-run (a step
 *       whose {@code run:} contains a Docker build invocation).</li>
 *   <li>{@code _security-dast.yml} either builds the jar it packages in-run (a {@code ./mvnw}
 *       package step) or receives it as a {@code workflow_call} input (e.g. downloading a
 *       caller-produced jar artifact by a caller-supplied name/run) — either way, it must not
 *       assume a specific external artifact already exists in the registry.</li>
 * </ol>
 *
 * <h2>Deliberate RED state (before Lane C)</h2>
 * Rule 1 is RED today: {@code security.yml}'s {@code baseline} job contains the literal
 * {@code docker manifest inspect ghcr.io/seism0saurus/glacier:...} step. Rules 2–3 are RED
 * because {@code _security-dast.yml} does not exist yet.
 *
 * <p>Mode applicability: mode-agnostic.
 *
 * <p>OWASP: A05:2021 — Security Misconfiguration; A08:2021 — Software and Data Integrity
 * Failures. Security requirement: SR-CI-14; ADR-CI-06.
 */
class SecurityDastSelfContainedTest {

    private static final String SECURITY_DAST = "_security-dast.yml";

    @Test
    void noWorkflowPreconditionsOnAGhcrManifestAlreadyExisting() {
        List<WorkflowFile> all = WorkflowInventory.loadAllWorkflows();
        SoftAssertions softly = new SoftAssertions();

        for (WorkflowFile wf : all) {
            for (WorkflowStep step : wf.runSteps()) {
                softly.assertThat(step.run())
                        .as("%s step '%s' must not run 'docker manifest inspect ghcr.io/...' -- "
                                + "the DAST core must be self-contained and build what it scans "
                                + "in-run, not assume a prior push to the registry (ADR-CI-06)",
                                wf.relativePath(), step.name())
                        .doesNotContain("docker manifest inspect ghcr.io/");
            }
        }
        softly.assertAll();
    }

    @Test
    void securityDastCoreBuildsTheDockerImageInRun() {
        assumeSecurityDastExists();
        WorkflowFile dast = WorkflowInventory.loadWorkflow(SECURITY_DAST);

        boolean buildsImage = dast.runSteps().stream().anyMatch(s ->
                s.runContains("docker build") || s.runContains("docker compose") && s.runContains("build"));

        assertThat(buildsImage)
                .as("%s must build the Docker image it scans in-run (no reliance on a "
                        + "pre-existing GHCR image) -- ADR-CI-06", SECURITY_DAST)
                .isTrue();
    }

    @Test
    void securityDastCoreBuildsOrReceivesTheJarWithoutRegistryAssumption() {
        assumeSecurityDastExists();
        WorkflowFile dast = WorkflowInventory.loadWorkflow(SECURITY_DAST);

        boolean buildsJarInRun = dast.runSteps().stream().anyMatch(s ->
                (s.runContains("./mvnw") || s.runContains("mvnw ")) && s.runContains("package"));

        boolean receivesJarAsInput = receivesJarAsWorkflowCallInput(dast)
                || dast.allSteps().stream().anyMatch(s -> s.hasUses() && s.usesContains("actions/download-artifact"));

        assertThat(buildsJarInRun || receivesJarAsInput)
                .as("%s must either build the jar in-run (./mvnw package) or receive it as a "
                        + "workflow_call input / caller-produced artifact download -- it must "
                        + "never assume a specific external jar/image already exists (ADR-CI-06)",
                        SECURITY_DAST)
                .isTrue();
    }

    @SuppressWarnings("unchecked")
    private static boolean receivesJarAsWorkflowCallInput(WorkflowFile dast) {
        Object workflowCall = WorkflowInventory.triggersOf(dast).get("workflow_call");
        if (!(workflowCall instanceof Map<?, ?> wc)) {
            return false;
        }
        Object inputs = wc.get("inputs");
        return inputs instanceof Map<?, ?> inputMap && !inputMap.isEmpty();
    }

    private static void assumeSecurityDastExists() {
        assertThat(Files.exists(WorkflowInventory.WORKFLOWS_DIR.resolve(SECURITY_DAST)))
                .as("%s must exist -- expected to be RED until Lane C creates the reusable core "
                        + "(ADR-CI-06)", SECURITY_DAST)
                .isTrue();
    }
}
