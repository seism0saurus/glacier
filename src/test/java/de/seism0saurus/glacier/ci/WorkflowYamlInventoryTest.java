package de.seism0saurus.glacier.ci;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SR-NA-07: Structural gate for {@code .github/workflows/security.yml}.
 *
 * <p>Guards against silent regression of the npm audit CI job. Three invariants are enforced:
 * <ol>
 *   <li>All three required triggers ({@code push}, {@code pull_request}, {@code schedule})
 *       are present in the {@code on:} block — protecting push, PR merge, and weekly CVE
 *       detection scenarios respectively (SR-NA-01..03).</li>
 *   <li>A step running {@code npm ci} and a step running {@code npm audit} both exist
 *       under the {@code frontend-audit} job, and the working directory is {@code frontend}
 *       (SR-NA-05, SR-NA-09).</li>
 *   <li>The audit step specifies {@code --audit-level=high} or {@code --audit-level=critical},
 *       and {@code continue-on-error} is absent or {@code false} on that step (SR-NA-01,
 *       SR-NA-04).</li>
 * </ol>
 *
 * <p>Parsing strategy: SnakeYAML traverses the YAML object tree — no string grep so that
 * YAML structure changes (reordering steps, changing step names) do not silently pass the
 * gate through textual coincidence.
 *
 * <p>The workflow file is resolved relative to the project root using the same convention
 * as {@link de.seism0saurus.glacier.security.TrivyignoreExpiryTest} so the test works both
 * locally and in CI.
 *
 * <p><b>Mode applicability</b>: mode-agnostic — CI workflow correctness is independent of
 * Glacier's operational mode (live / fallback / killswitch / insecure).
 *
 * <p>OWASP: A06:2021 — Vulnerable and Outdated Components (frontend supply-chain gate).
 * Security requirements: SR-NA-01, SR-NA-02, SR-NA-03, SR-NA-04, SR-NA-05, SR-NA-07, SR-NA-09.
 *
 * @see <a href="https://owasp.org/Top10/A06_2021-Vulnerable_and_Outdated_Components/">OWASP A06:2021</a>
 */
// frontend-audit — npm audit gate for the Angular SPA bundle (SR-FUZZ-07, SR-NA-01..09)
class WorkflowYamlInventoryTest {

    private static final String WORKFLOW_PATH = ".github/workflows/security.yml";

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadWorkflow() throws IOException {
        Path workflowFile = Paths.get(WORKFLOW_PATH);
        try (InputStream in = Files.newInputStream(workflowFile)) {
            Yaml yaml = new Yaml();
            return (Map<String, Object>) yaml.load(in);
        }
    }

    /**
     * SR-NA-02, SR-NA-03: Asserts that the {@code on:} block of {@code security.yml} contains
     * all three required triggers: {@code push}, {@code pull_request}, and {@code schedule}.
     *
     * <p>This traverses the YAML tree — it does not grep the raw text — so YAML aliasing,
     * reordering, or reformatting cannot produce a false positive.
     *
     * <p>Note: SnakeYAML 2.x (YAML 1.1 spec) parses the unquoted key {@code on} as the
     * boolean {@code true}, not as the string {@code "on"}. The lookup must use
     * {@code Boolean.TRUE} as the map key.
     */
    @Test
    void triggersIncludePushPullRequestAndSchedule() throws IOException {
        Map<String, Object> workflow = loadWorkflow();

        // SnakeYAML 2.x (YAML 1.1): unquoted 'on' is parsed as Boolean.TRUE, not String "on"
        Object onBlock = workflow.get(Boolean.TRUE);
        assertThat(onBlock)
                .as("The 'on:' block of security.yml must be a map with trigger keys (SnakeYAML 2.x parses 'on' as Boolean.TRUE)")
                .isInstanceOf(Map.class);

        @SuppressWarnings("unchecked")
        Map<String, Object> triggers = (Map<String, Object>) onBlock;

        assertThat(triggers)
                .as("security.yml must have a 'push' trigger (SR-NA-02)")
                .containsKey("push");

        assertThat(triggers)
                .as("security.yml must have a 'pull_request' trigger (SR-NA-02: gates PR merges against vulnerable deps)")
                .containsKey("pull_request");

        assertThat(triggers)
                .as("security.yml must have a 'schedule' trigger (SR-NA-03: weekly CVE detection after last commit)")
                .containsKey("schedule");
    }

    /**
     * SR-NA-05, SR-NA-09: Asserts that the {@code frontend-audit} job contains a step whose
     * {@code run} script includes {@code npm ci} and a step whose {@code run} script includes
     * {@code npm audit}, and that the working directory is {@code frontend} for each.
     *
     * <p>Both {@code npm ci} and {@code npm audit} may appear in the same step or in separate
     * steps; the assertion covers both cases. {@code npm ci} ensures the exact lockfile is
     * installed (no drift); {@code npm audit} performs the actual vulnerability scan.
     */
    @Test
    void auditStepUsesNpmCiAndNpmAudit() throws IOException {
        Map<String, Object> workflow = loadWorkflow();

        @SuppressWarnings("unchecked")
        Map<String, Object> jobs = (Map<String, Object>) workflow.get("jobs");
        assertThat(jobs)
                .as("security.yml must define a 'frontend-audit' job (SR-NA-07)")
                .containsKey("frontend-audit");

        @SuppressWarnings("unchecked")
        Map<String, Object> frontendAuditJob = (Map<String, Object>) jobs.get("frontend-audit");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) frontendAuditJob.get("steps");
        assertThat(steps)
                .as("frontend-audit job must have at least one step")
                .isNotEmpty();

        boolean foundNpmCi = steps.stream().anyMatch(step -> {
            String run = (String) step.get("run");
            return run != null && run.contains("npm ci");
        });
        assertThat(foundNpmCi)
                .as("frontend-audit job must have a step with 'npm ci' to ensure lockfile fidelity (SR-NA-05)")
                .isTrue();

        boolean foundNpmAudit = steps.stream().anyMatch(step -> {
            String run = (String) step.get("run");
            return run != null && run.contains("npm audit");
        });
        assertThat(foundNpmAudit)
                .as("frontend-audit job must have a step with 'npm audit' (SR-NA-01)")
                .isTrue();

        // SR-NA-09: working-directory must be 'frontend' (or run command uses cd frontend)
        boolean frontendWorkingDir = steps.stream().anyMatch(step -> {
            String run = (String) step.get("run");
            String wd = (String) step.get("working-directory");
            if (run == null) return false;
            if (run.contains("npm ci") || run.contains("npm audit")) {
                return "frontend".equals(wd) || run.contains("cd frontend");
            }
            return false;
        });
        assertThat(frontendWorkingDir)
                .as("npm ci/npm audit steps must specify working-directory: frontend (or cd frontend) so they target the Angular package.json (SR-NA-09)")
                .isTrue();
    }

    /**
     * SR-NA-01, SR-NA-04: Asserts that the npm audit step specifies
     * {@code --audit-level=high} or {@code --audit-level=critical}, that {@code --omit=dev}
     * is present to restrict the scan to production runtime dependencies only, and that
     * {@code continue-on-error} is absent or explicitly {@code false} on the audit step.
     *
     * <p>A missing or {@code true} {@code continue-on-error} would silently swallow audit
     * failures and defeat the purpose of the gate (SR-NA-04).
     *
     * <p>{@code --omit=dev} is required because dev-toolchain packages (e.g. {@code @angular/cli},
     * webpack loaders) are never included in the deployed Angular SPA bundle and routinely carry
     * advisories that are not exploitable in production. Without this flag the gate generates
     * noise and fails on build-time-only vulnerabilities, defeating the signal/noise ratio.
     */
    @Test
    void auditLevelIsHighOrCritical() throws IOException {
        Map<String, Object> workflow = loadWorkflow();

        @SuppressWarnings("unchecked")
        Map<String, Object> jobs = (Map<String, Object>) workflow.get("jobs");
        assertThat(jobs)
                .as("security.yml must define a 'frontend-audit' job")
                .containsKey("frontend-audit");

        @SuppressWarnings("unchecked")
        Map<String, Object> frontendAuditJob = (Map<String, Object>) jobs.get("frontend-audit");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) frontendAuditJob.get("steps");

        // Find the npm audit step
        Map<String, Object> auditStep = steps.stream()
                .filter(step -> {
                    String run = (String) step.get("run");
                    return run != null && run.contains("npm audit");
                })
                .findFirst()
                .orElse(null);

        assertThat(auditStep)
                .as("frontend-audit job must contain a step with 'npm audit'")
                .isNotNull();

        String runScript = (String) auditStep.get("run");
        assertThat(runScript)
                .as("npm audit step must specify --audit-level=high or --audit-level=critical to avoid low/moderate noise (SR-NA-01)")
                .satisfiesAnyOf(
                        s -> assertThat(s).contains("--audit-level=high"),
                        s -> assertThat(s).contains("--audit-level=critical")
                );

        assertThat(runScript)
                .as("npm audit step must include --omit=dev to restrict the scan to production runtime " +
                    "dependencies only — dev-toolchain advisories (e.g. @angular/cli, webpack loaders) are " +
                    "never deployed in the SPA bundle and must not block the gate (SR-NA-01)")
                .contains("--omit=dev");

        // SR-NA-04: continue-on-error must be absent or false
        Object continueOnError = auditStep.get("continue-on-error");
        if (continueOnError != null) {
            assertThat(continueOnError)
                    .as("npm audit step must not have continue-on-error: true — that would swallow failures and defeat the gate (SR-NA-04)")
                    .isEqualTo(Boolean.FALSE);
        }
        // If absent (null), that is acceptable — GitHub Actions defaults to false
    }
}
