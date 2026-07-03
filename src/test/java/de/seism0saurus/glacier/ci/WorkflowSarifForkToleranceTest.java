package de.seism0saurus.glacier.ci;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gate (WorkflowSarifForkTolerance): a fork PR run must never hard-fail purely because it
 * cannot write to the base repository's code-scanning "Security" tab (which GitHub restricts
 * for fork PRs regardless of token scope) — but the underlying scanner finding must still fail
 * the run, so a real vulnerability is never silently waved through just because the SARIF
 * upload step itself was made lenient.
 *
 * <h2>Rules enforced</h2>
 * <ol>
 *   <li>Every {@code github/codeql-action/upload-sarif} step carries either
 *       {@code continue-on-error: true} or an explicit fork guard
 *       ({@code if:} referencing both {@code head.repo.full_name} and
 *       {@code github.repository}) — RR-1's accepted trade-off: fork-PR SARIF does not reach
 *       the Security tab, but the run itself does not spuriously fail on that alone
 *       (ADR-CI-15).</li>
 *   <li>Every Trivy scan step (the {@code aquasecurity/trivy-action} action, or the
 *       {@code aquasec/trivy} CLI invoked via {@code docker run}) sets {@code exit-code: 1} /
 *       {@code --exit-code 1} — the actual merge-blocking finding gate lives on the scanner's
 *       own exit code, which is completely independent of, and unaffected by, the upload step's
 *       fork-tolerance (ADR-CI-15: "the finding gate stays on the scanner exit-code, not the
 *       upload").</li>
 * </ol>
 *
 * <h2>Deliberate RED state (before Lane C)</h2>
 * Rule 1 is RED today: none of the current {@code upload-sarif} steps (in
 * {@code build-and-deploy.yml}'s {@code trivy-fs}/{@code trivy-image} jobs, or
 * {@code security.yml}'s {@code baseline}/{@code full-scan} jobs) declare
 * {@code continue-on-error} or a fork guard. Rule 2 is expected GREEN already: every current
 * Trivy invocation sets {@code exit-code: '1'} (trivy-action) or {@code --exit-code 1} (CLI).
 *
 * <p>Mode applicability: mode-agnostic.
 *
 * <p>OWASP: A05:2021 — Security Misconfiguration. Security requirement: SR-CI-15; ADR-CI-15.
 */
class WorkflowSarifForkToleranceTest {

    @Test
    void everyUploadSarifStepIsForkTolerant() {
        List<WorkflowFile> all = WorkflowInventory.loadAllWorkflows();
        SoftAssertions softly = new SoftAssertions();

        for (WorkflowFile wf : all) {
            for (WorkflowStep step : wf.allSteps()) {
                if (!(step.hasUses() && step.usesContains("upload-sarif"))) {
                    continue;
                }
                boolean continueOnError = Boolean.TRUE.equals(step.continueOnError());
                boolean forkGuard = step.ifCondition() != null
                        && String.valueOf(step.ifCondition()).contains("repo.full_name")
                        && String.valueOf(step.ifCondition()).contains("github.repository");

                softly.assertThat(continueOnError || forkGuard)
                        .as("%s upload-sarif step '%s' must have continue-on-error: true or a "
                                + "fork guard (if: referencing head.repo.full_name == "
                                + "github.repository) -- a fork PR cannot write to the base "
                                + "repo's Security tab and must not hard-fail on that alone "
                                + "(SR-CI-15/ADR-CI-15, RR-1 accepted trade-off)",
                                wf.relativePath(), step.name())
                        .isTrue();
            }
        }

        softly.assertAll();
    }

    @Test
    void everyTrivyScanStepGatesOnFindingsViaExitCode() {
        List<WorkflowFile> all = WorkflowInventory.loadAllWorkflows();
        SoftAssertions softly = new SoftAssertions();

        for (WorkflowFile wf : all) {
            for (WorkflowStep step : wf.allSteps()) {
                if (step.hasUses() && step.usesContains("trivy-action")) {
                    Object exitCode = step.with().get("exit-code");
                    softly.assertThat(String.valueOf(exitCode))
                            .as("%s trivy-action step '%s' must set exit-code: '1' so findings "
                                    + "fail the run independently of the SARIF upload's "
                                    + "fork-tolerance (ADR-CI-15)", wf.relativePath(), step.name())
                            .isEqualTo("1");
                } else if (step.hasRun() && step.run().contains("aquasec/trivy")) {
                    softly.assertThat(step.run())
                            .as("%s Trivy CLI step '%s' must pass --exit-code 1 so findings fail "
                                    + "the run independently of the SARIF upload's fork-tolerance "
                                    + "(ADR-CI-15)", wf.relativePath(), step.name())
                            .contains("--exit-code 1");
                }
            }
        }

        softly.assertAll();
    }
}
