package de.seism0saurus.glacier.ci;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gate 5.3 (WorkflowDeployTrigger / NoDeploySecrets): the deploy credentials
 * ({@code SSH_KEY}, {@code KNOWN_HOSTS}, {@code SSH_USER}, {@code SSH_PORT}) are reachable from
 * exactly one place in the whole repository — the {@code environment:}-gated {@code deploy} job
 * inside {@code build-and-deploy.yml} — and that job can only ever run after the full quality
 * gate chain (e2e + trivy + zap) has passed on a published image. It also repairs the live
 * ADR-CI-10 defect: {@code build-and-deploy.yml} currently accepts a
 * {@code pull_request: closed} trigger with no {@code if:} guard on its downstream jobs, so
 * closing (e.g. merging) any same-repo PR against {@code main} runs the *entire* chain —
 * including {@code deploy} with real SSH secrets — in PR event context.
 *
 * <h2>Rules enforced</h2>
 * <ol>
 *   <li>{@code build-and-deploy.yml} has no {@code pull_request} trigger at all (ADR-CI-10:
 *       release moves to {@code push: tags: ['v*.*.*']} per ADR-CI-18; the merge-to-main path
 *       is covered by that tag push, not by a PR-close event).</li>
 *   <li>Only {@code build-and-deploy.yml} references any of the four deploy secrets, and only
 *       inside a job that also declares {@code environment:} (SR-CI-05/17).</li>
 *   <li>No step anywhere in the {@code deploy} job runs {@code set -x} (or enables
 *       {@code bash -x}) — doing so would echo the SSH key material handled in that job into
 *       the public Actions log (D-13/SR-8 sensitive-data-in-logs discipline, applied to CI
 *       logs the same way {@code LogScrubber} applies it to application logs).</li>
 *   <li>{@code deploy} transitively {@code needs} {@code publish-image}, and
 *       {@code publish-image} transitively needs a job covering each of e2e, Trivy, and ZAP —
 *       so a broken/failing security scan blocks the image from ever reaching a state where
 *       {@code deploy} can run (SR-CI-19).</li>
 * </ol>
 *
 * <h2>Deliberate RED state (before Lane C)</h2>
 * Rule 1 is RED today: {@code build-and-deploy.yml} has
 * {@code pull_request: { branches: [main], types: [closed] }} live in its {@code on:} block.
 * Rules 2–4 are expected GREEN already against the current file (the four secrets are only
 * referenced in the {@code deploy} job, which has {@code environment: glacier.seism0saurus.de};
 * no {@code set -x}; {@code deploy needs publish-image needs [e2e, trivy-fs, trivy-image,
 * zap-and-headers]}) — Lane C must not regress these while fixing rule 1.
 *
 * <p>Mode applicability: mode-agnostic.
 *
 * <p>OWASP: A02:2021 — Cryptographic Failures (credential handling); A05:2021 — Security
 * Misconfiguration. Security requirements: SR-CI-04, SR-CI-05, SR-CI-17, SR-CI-19; ADR-CI-08,
 * ADR-CI-10, ADR-CI-16.
 */
class WorkflowDeployTriggerTest {

    private static final String DEPLOY_WORKFLOW = "build-and-deploy.yml";
    private static final String DEPLOY_JOB = "deploy";
    private static final String PUBLISH_IMAGE_JOB = "publish-image";

    private static final Set<String> DEPLOY_SECRETS = Set.of(
            "SSH_KEY", "KNOWN_HOSTS", "SSH_USER", "SSH_PORT"
    );

    private static final Pattern SECRET_REFERENCE = Pattern.compile("secrets\\.([A-Za-z0-9_]+)");
    private static final Pattern SET_X = Pattern.compile("(^|[\\s;&|])set\\s+-\\w*x\\w*\\b|bash\\s+-\\w*x\\w*\\b");

    @Test
    void buildAndDeployHasNoPullRequestTrigger() {
        WorkflowFile bad = WorkflowInventory.loadWorkflow(DEPLOY_WORKFLOW);
        Map<String, Object> triggers = WorkflowInventory.triggersOf(bad);

        assertThat(triggers)
                .as("%s must not have a pull_request trigger (ADR-CI-10: the live "
                        + "'pull_request: closed' trigger lets closing a same-repo PR against "
                        + "main run the whole chain -- including deploy with real SSH secrets "
                        + "-- in PR event context; release now happens on push: tags: ['v*.*.*'])",
                        DEPLOY_WORKFLOW)
                .doesNotContainKey("pull_request");
    }

    @Test
    void deploySecretsOnlyReachableFromBuildAndDeployEnvironmentGatedJob() {
        List<WorkflowFile> all = WorkflowInventory.loadAllWorkflows();
        SoftAssertions softly = new SoftAssertions();

        for (WorkflowFile wf : all) {
            for (WorkflowJob job : wf.jobs().values()) {
                Set<String> found = secretNamesIn(job.raw());
                Set<String> deploySecretsFound = found.stream()
                        .filter(DEPLOY_SECRETS::contains)
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
                if (deploySecretsFound.isEmpty()) {
                    continue;
                }
                softly.assertThat(wf.fileName())
                        .as("job '%s' in %s references deploy secret(s) %s -- these must only "
                                + "ever be reachable from build-and-deploy.yml (SR-CI-05)",
                                job.id(), wf.relativePath(), deploySecretsFound)
                        .isEqualTo(DEPLOY_WORKFLOW);
                softly.assertThat(job.environment())
                        .as("job '%s' in %s references deploy secret(s) %s but has no "
                                + "environment: -- deploy credentials must only be reachable "
                                + "from an environment-gated job (SR-CI-17, ADR-CI-16)",
                                job.id(), wf.relativePath(), deploySecretsFound)
                        .isNotNull();
            }
        }

        softly.assertAll();
    }

    @Test
    void deployJobDoesNotEnableShellTracing() {
        WorkflowFile bad = WorkflowInventory.loadWorkflow(DEPLOY_WORKFLOW);
        WorkflowJob deploy = bad.job(DEPLOY_JOB)
                .orElseThrow(() -> new AssertionError(DEPLOY_WORKFLOW + " must define a '" + DEPLOY_JOB + "' job"));

        SoftAssertions softly = new SoftAssertions();
        for (WorkflowStep step : deploy.runSteps()) {
            Matcher m = SET_X.matcher(step.run());
            softly.assertThat(m.find())
                    .as("deploy job step '%s' in %s must not enable shell tracing (set -x / "
                            + "bash -x) -- the job handles SSH_KEY material and tracing would "
                            + "echo it into the public Actions log (D-13/SR-8)",
                            step.name(), DEPLOY_WORKFLOW)
                    .isFalse();
        }
        softly.assertAll();
    }

    @Test
    void deployTransitivelyNeedsPublishImageWhichNeedsE2eTrivyAndZap() {
        WorkflowFile bad = WorkflowInventory.loadWorkflow(DEPLOY_WORKFLOW);

        Set<String> deployClosure = transitiveNeeds(bad, DEPLOY_JOB);
        assertThat(deployClosure)
                .as("deploy job in %s must transitively need '%s' (SR-CI-19: deploy only runs "
                        + "after the image is published, which itself only happens after the "
                        + "full quality gate passes)", DEPLOY_WORKFLOW, PUBLISH_IMAGE_JOB)
                .contains(PUBLISH_IMAGE_JOB);

        Set<String> publishClosure = transitiveNeeds(bad, PUBLISH_IMAGE_JOB);
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(publishClosure.stream().anyMatch(id -> id.toLowerCase(java.util.Locale.ROOT).contains("e2e")))
                .as("publish-image in %s must transitively need an e2e job -- found needs-closure %s",
                        DEPLOY_WORKFLOW, publishClosure)
                .isTrue();
        softly.assertThat(publishClosure.stream().anyMatch(id -> id.toLowerCase(java.util.Locale.ROOT).contains("trivy")))
                .as("publish-image in %s must transitively need a Trivy scan job -- found needs-closure %s",
                        DEPLOY_WORKFLOW, publishClosure)
                .isTrue();
        softly.assertThat(publishClosure.stream().anyMatch(id -> id.toLowerCase(java.util.Locale.ROOT).contains("zap")))
                .as("publish-image in %s must transitively need a ZAP scan job -- found needs-closure %s",
                        DEPLOY_WORKFLOW, publishClosure)
                .isTrue();
        softly.assertAll();
    }

    private static Set<String> secretNamesIn(Object node) {
        Set<String> found = new LinkedHashSet<>();
        collect(node, found);
        return found;
    }

    private static void collect(Object node, Set<String> found) {
        if (node instanceof Map<?, ?> map) {
            for (Object v : map.values()) {
                collect(v, found);
            }
        } else if (node instanceof List<?> list) {
            for (Object v : list) {
                collect(v, found);
            }
        } else if (node instanceof String s) {
            Matcher m = SECRET_REFERENCE.matcher(s);
            while (m.find()) {
                found.add(m.group(1));
            }
        }
    }

    /** Breadth-first closure of {@code needs} edges starting at {@code startJobId}, excluding the start id itself. */
    private static Set<String> transitiveNeeds(WorkflowFile workflow, String startJobId) {
        Set<String> visited = new LinkedHashSet<>();
        java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>();
        queue.add(startJobId);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            WorkflowJob job = workflow.jobs().get(current);
            if (job == null) {
                continue;
            }
            for (String dep : job.needs()) {
                if (visited.add(dep)) {
                    queue.add(dep);
                }
            }
        }
        return visited;
    }
}
