package de.seism0saurus.glacier.ci;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gate 5.4 (WorkflowRunPrCommentSafety): {@code pr-comment.yml} — the one privileged workflow
 * that runs on {@code workflow_run} to post the coverage summary onto a PR — never builds or
 * executes PR-controlled code, scopes its artifact download to the exact triggering run, and
 * treats the PR number it reads back as untrusted input requiring fail-closed validation
 * before use (ADR-CI-14, which supersedes an earlier proposal to read
 * {@code github.event.workflow_run.pull_requests[0].number} directly — that field is empty
 * for fork PRs and was never validated).
 *
 * <h2>Rules enforced</h2>
 * <ol>
 *   <li>{@code on:} is {@code workflow_run} only — no {@code pull_request_target}, no other
 *       trigger alongside it.</li>
 *   <li>No step builds or runs PR-controlled code (no {@code mvnw}, {@code npm ci/install/run},
 *       {@code docker build/compose build}) — the job only ever consumes already-produced
 *       artifacts and static repo scripts.</li>
 *   <li>Every {@code download-artifact} step is scoped to the exact triggering run via
 *       {@code run-id: ${{ github.event.workflow_run.id }}} — never the ambient/default
 *       "latest artifact matching this name" resolution, which could otherwise be poisoned by
 *       an unrelated run (SR-CI-11 partial mitigation of RR-2's artifact-poisoning surface).</li>
 *   <li>The PR number used in the {@code gh pr comment} call is read from an artifact file,
 *       validated with a {@code ^[0-9]+$} regex (fail-closed) in a step that runs strictly
 *       before the comment step, and passed to the comment step only via a shell environment
 *       variable (never a direct {@code ${{ github.event.* }}} interpolation inside the
 *       {@code run:} text of the comment step).</li>
 *   <li>Top-level {@code permissions:} is exactly {@code {}}, and the job holds exactly
 *       {@code pull-requests: write} — nothing else.</li>
 * </ol>
 *
 * <h2>Deliberate RED state (before Lane C)</h2>
 * {@code pr-comment.yml} does not exist yet — every assertion below is RED until Lane C
 * creates it. The current {@code coverage-aggregation} job (living in {@code pull-request.yml}
 * today) already demonstrates the anti-pattern this gate replaces: it runs
 * {@code gh pr comment $PR_NUMBER ...} with {@code PR_NUMBER: ${{ github.event.number }}} taken
 * directly from the pull_request event context of the *same* (untrusted) workflow run — safe
 * only because {@code pull-request.yml} does not currently hold {@code pull-requests: write}
 * broadly and posts from the trusted numeric event field of its own trigger, but architecturally
 * it is exactly the shape ADR-CI-14 replaces with an artifact-mediated, validated hand-off.
 *
 * <p>Mode applicability: mode-agnostic.
 *
 * <p>OWASP: A08:2021 — Software and Data Integrity Failures; A03:2021 — Injection (fail-closed
 * validation of externally-influenced numeric input before use in a privileged API call).
 * Security requirements: SR-CI-10, SR-CI-11; ADR-CI-14.
 */
class WorkflowRunPrCommentSafetyTest {

    private static final String PR_COMMENT_WORKFLOW = "pr-comment.yml";

    private static final List<String> FORBIDDEN_BUILD_PATTERNS = List.of(
            "./mvnw", "mvnw ", "npm ci", "npm install", "npm run", "docker build", "docker compose build"
    );

    @Test
    void triggersOnWorkflowRunOnlyAndNeverPullRequestTarget() {
        assumePrCommentExists();
        WorkflowFile prComment = WorkflowInventory.loadWorkflow(PR_COMMENT_WORKFLOW);
        Map<String, Object> triggers = WorkflowInventory.triggersOf(prComment);

        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(triggers)
                .as("%s must declare a workflow_run trigger", PR_COMMENT_WORKFLOW)
                .containsKey("workflow_run");
        softly.assertThat(triggers)
                .as("%s must never accept pull_request_target", PR_COMMENT_WORKFLOW)
                .doesNotContainKey("pull_request_target");
        softly.assertThat(triggers.keySet())
                .as("%s must be triggered by workflow_run only -- no other trigger alongside it",
                        PR_COMMENT_WORKFLOW)
                .containsExactly("workflow_run");
        softly.assertAll();
    }

    @Test
    void noStepBuildsOrRunsPrControlledCode() {
        assumePrCommentExists();
        WorkflowFile prComment = WorkflowInventory.loadWorkflow(PR_COMMENT_WORKFLOW);

        SoftAssertions softly = new SoftAssertions();
        for (WorkflowStep step : prComment.runSteps()) {
            for (String forbidden : FORBIDDEN_BUILD_PATTERNS) {
                softly.assertThat(step.run())
                        .as("%s step '%s' must not build/execute PR-controlled code -- found "
                                + "forbidden pattern '%s' (SR-CI-10: pr-comment.yml only "
                                + "consumes already-produced artifacts, never rebuilds anything)",
                                PR_COMMENT_WORKFLOW, step.name(), forbidden)
                        .doesNotContain(forbidden);
            }
        }
        for (WorkflowStep step : prComment.allSteps()) {
            if (step.hasUses() && step.usesContains("actions/checkout")) {
                Object ref = step.with().get("ref");
                if (ref != null) {
                    softly.assertThat(String.valueOf(ref))
                            .as("%s checkout step must not check out an attacker-influenced "
                                    + "ref from the triggering workflow_run event", PR_COMMENT_WORKFLOW)
                            .doesNotContain("github.event.workflow_run");
                }
            }
        }
        softly.assertAll();
    }

    @Test
    void artifactDownloadsAreScopedToTheTriggeringRun() {
        assumePrCommentExists();
        WorkflowFile prComment = WorkflowInventory.loadWorkflow(PR_COMMENT_WORKFLOW);

        List<WorkflowStep> downloads = prComment.allSteps().stream()
                .filter(s -> s.hasUses() && s.usesContains("actions/download-artifact"))
                .toList();

        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(downloads)
                .as("%s must download at least one artifact (the PR-number file, and the "
                        + "coverage report)", PR_COMMENT_WORKFLOW)
                .isNotEmpty();

        for (WorkflowStep step : downloads) {
            Object runId = step.with().get("run-id");
            softly.assertThat(String.valueOf(runId))
                    .as("%s download-artifact step '%s' must scope to run-id: "
                            + "${{ github.event.workflow_run.id }} -- unscoped downloads resolve "
                            + "to the latest artifact of that name from ANY run, which is an "
                            + "artifact-poisoning surface (SR-CI-11)", PR_COMMENT_WORKFLOW, step.name())
                    .contains("github.event.workflow_run.id");

            Object name = step.with().get("name");
            softly.assertThat(name)
                    .as("%s download-artifact step '%s' must use a fixed, literal artifact name "
                            + "(ADR-CI-14: fixed artifact name)", PR_COMMENT_WORKFLOW, step.name())
                    .isInstanceOf(String.class);
            if (name instanceof String s) {
                softly.assertThat(s)
                        .as("%s download-artifact step '%s' artifact name must be a literal, "
                                + "not a template expression", PR_COMMENT_WORKFLOW, step.name())
                        .doesNotContain("${{");
            }
        }
        softly.assertAll();
    }

    @Test
    void prNumberIsArtifactSourcedRegexValidatedBeforeUseAndNeverDirectlyInterpolated() {
        assumePrCommentExists();
        WorkflowFile prComment = WorkflowInventory.loadWorkflow(PR_COMMENT_WORKFLOW);
        List<WorkflowStep> steps = prComment.allSteps();

        int validateIdx = indexOfFirst(steps, s -> s.hasRun() && s.run().contains("^[0-9]+$"));
        int commentIdx = indexOfFirst(steps, s -> s.hasRun() && s.run().contains("gh pr comment"));

        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(validateIdx)
                .as("%s must contain a step that validates the PR number against ^[0-9]+$ "
                        + "(ADR-CI-14: fail-closed validation of untrusted artifact content "
                        + "before it is used in a privileged gh pr comment call)", PR_COMMENT_WORKFLOW)
                .isGreaterThanOrEqualTo(0);
        softly.assertThat(commentIdx)
                .as("%s must contain a 'gh pr comment' step", PR_COMMENT_WORKFLOW)
                .isGreaterThanOrEqualTo(0);

        if (validateIdx >= 0 && commentIdx >= 0) {
            softly.assertThat(commentIdx)
                    .as("the ^[0-9]+$ validation step must run strictly before the gh pr "
                            + "comment step (fail-closed: validate before use)")
                    .isGreaterThan(validateIdx);
        }

        if (commentIdx >= 0) {
            WorkflowStep commentStep = steps.get(commentIdx);
            softly.assertThat(commentStep.run())
                    .as("the gh pr comment step must not directly interpolate "
                            + "${{ github.event.workflow_run... }} into its run: text -- the "
                            + "validated PR number must be passed via a shell environment "
                            + "variable (env: intermediary + \"$VAR\"), not a raw template "
                            + "expression (ADR-CI-14)")
                    .doesNotContain("${{ github.event.workflow_run");
        }

        softly.assertAll();
    }

    @Test
    void topLevelPermissionsAreEmptyAndJobHoldsOnlyPullRequestsWrite() {
        assumePrCommentExists();
        WorkflowFile prComment = WorkflowInventory.loadWorkflow(PR_COMMENT_WORKFLOW);

        Object topLevel = WorkflowInventory.topLevelPermissionsOf(prComment);
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(topLevel instanceof Map<?, ?> m && m.isEmpty())
                .as("%s top-level permissions: must be exactly {} -- found %s",
                        PR_COMMENT_WORKFLOW, topLevel)
                .isTrue();

        boolean anyJobHoldsExactlyPullRequestsWrite = prComment.jobs().values().stream()
                .anyMatch(job -> job.permissions() instanceof Map<?, ?> m
                        && m.size() == 1
                        && "write".equals(String.valueOf(m.get("pull-requests"))));
        softly.assertThat(anyJobHoldsExactlyPullRequestsWrite)
                .as("%s must have a job whose permissions: is exactly {pull-requests: write} "
                        + "-- the minimal scope needed to post the coverage comment", PR_COMMENT_WORKFLOW)
                .isTrue();

        softly.assertAll();
    }

    private static void assumePrCommentExists() {
        assertThat(Files.exists(WorkflowInventory.WORKFLOWS_DIR.resolve(PR_COMMENT_WORKFLOW)))
                .as("%s must exist -- expected to be RED until Lane C (devops-infra-engineer) "
                        + "creates it per ADR-CI-14", PR_COMMENT_WORKFLOW)
                .isTrue();
    }

    private static int indexOfFirst(List<WorkflowStep> steps, java.util.function.Predicate<WorkflowStep> predicate) {
        for (int i = 0; i < steps.size(); i++) {
            if (predicate.test(steps.get(i))) {
                return i;
            }
        }
        return -1;
    }
}
