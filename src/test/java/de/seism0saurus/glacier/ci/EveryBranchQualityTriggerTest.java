package de.seism0saurus.glacier.ci;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gate 5.5 (EveryBranchQualityTrigger): {@code quality.yml} gives every commit on every branch
 * a fast, secret-free quality signal by calling the shared {@code _build} reusable core — the
 * feature request's "Every commit on a branch should trigger quality checks" requirement,
 * satisfied without duplicating {@code _build.yml}'s job definitions and without ever touching
 * a repository secret (a compromised or malicious branch push must never be able to exfiltrate
 * a secret just by existing).
 *
 * <h2>Rules enforced</h2>
 * <ol>
 *   <li>{@code quality.yml} exists.</li>
 *   <li>Its only trigger is {@code push: branches: ['**']} — every branch, and specifically
 *       NOT a {@code tags:} filter (tag pushes are the release path, {@code build-and-deploy.yml}
 *       per ADR-CI-18 — {@code quality.yml} must not also fire on a release tag and duplicate
 *       or race that path).</li>
 *   <li>It is secret-free: no {@code secrets.X} reference anywhere in the file (run/env/with/
 *       uses/job-secrets, via {@link WorkflowInventory#secretReferences(WorkflowFile)}'s
 *       recursive scan), other than the ambient {@code GITHUB_TOKEN}.</li>
 *   <li>It calls only the {@code _build} reusable core — no inline steps of its own, no other
 *       reusable-workflow calls — keeping it a thin, auditable wrapper.</li>
 * </ol>
 *
 * <h2>Deliberate RED state (before Lane C)</h2>
 * {@code quality.yml} does not exist yet. Every assertion in this class is RED until Lane C
 * creates it calling {@code _build.yml} (itself also not yet created by Lane C).
 *
 * <p>Mode applicability: mode-agnostic.
 *
 * <p>OWASP: A05:2021 — Security Misconfiguration (trigger-scope correctness);
 * A08:2021 — Software and Data Integrity Failures. Security requirement: SR-CI-13;
 * ADR-CI-01, ADR-CI-18.
 */
class EveryBranchQualityTriggerTest {

    private static final String QUALITY_WORKFLOW = "quality.yml";
    private static final String SAFE_SECRET = "GITHUB_TOKEN";

    @Test
    void qualityYmlExists() {
        assertThat(Files.exists(WorkflowInventory.WORKFLOWS_DIR.resolve(QUALITY_WORKFLOW)))
                .as("%s must exist -- expected to be RED until Lane C creates it (SR-CI-13)",
                        QUALITY_WORKFLOW)
                .isTrue();
    }

    @Test
    void triggersOnPushToEveryBranchOnlyAndNeverOnTags() {
        assumeQualityYmlExists();
        WorkflowFile quality = WorkflowInventory.loadWorkflow(QUALITY_WORKFLOW);
        Map<String, Object> triggers = WorkflowInventory.triggersOf(quality);

        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(triggers)
                .as("%s must have a push trigger", QUALITY_WORKFLOW)
                .containsKey("push");
        softly.assertThat(triggers.keySet())
                .as("%s must be push-triggered only", QUALITY_WORKFLOW)
                .containsExactly("push");

        Object push = triggers.get("push");
        boolean allBranches = push instanceof Map<?, ?> m
                && m.get("branches") instanceof List<?> l
                && l.stream().anyMatch(b -> "**".equals(String.valueOf(b)));
        softly.assertThat(allBranches)
                .as("%s push trigger must be branches: ['**'] (every branch, per the feature "
                        + "request 'every commit on a branch should trigger quality checks') "
                        + "-- found %s", QUALITY_WORKFLOW, push)
                .isTrue();

        boolean hasTagsFilter = push instanceof Map<?, ?> m2 && m2.containsKey("tags");
        softly.assertThat(hasTagsFilter)
                .as("%s must NOT filter on tags: -- release tag pushes are the separate "
                        + "build-and-deploy.yml path (ADR-CI-18); quality.yml duplicating that "
                        + "trigger would race or double-run on every release", QUALITY_WORKFLOW)
                .isFalse();

        softly.assertAll();
    }

    @Test
    void isSecretFree() {
        assumeQualityYmlExists();
        WorkflowFile quality = WorkflowInventory.loadWorkflow(QUALITY_WORKFLOW);

        var secrets = WorkflowInventory.secretReferences(quality);
        var forbidden = secrets.stream().filter(s -> !SAFE_SECRET.equals(s)).toList();

        assertThat(forbidden)
                .as("%s must be completely secret-free (except the ambient GITHUB_TOKEN) -- "
                        + "found %s. It runs on every branch push, including forks with write "
                        + "access to a branch but no secrets clearance", QUALITY_WORKFLOW, forbidden)
                .isEmpty();
    }

    @Test
    void callsOnlyTheBuildCore() {
        assumeQualityYmlExists();
        WorkflowFile quality = WorkflowInventory.loadWorkflow(QUALITY_WORKFLOW);

        assertThat(quality.jobs())
                .as("%s must define exactly one job", QUALITY_WORKFLOW)
                .hasSize(1);

        WorkflowJob onlyJob = quality.jobs().values().iterator().next();
        assertThat(onlyJob.calledWorkflow())
                .as("%s's only job must call the _build reusable core, not run inline steps",
                        QUALITY_WORKFLOW)
                .isNotNull()
                .contains("_build.yml");
    }

    private static void assumeQualityYmlExists() {
        assertThat(Files.exists(WorkflowInventory.WORKFLOWS_DIR.resolve(QUALITY_WORKFLOW)))
                .as("%s must exist -- expected to be RED until Lane C creates it", QUALITY_WORKFLOW)
                .isTrue();
    }
}
