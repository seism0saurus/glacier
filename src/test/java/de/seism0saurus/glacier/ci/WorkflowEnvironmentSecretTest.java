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
 * Gate (WorkflowEnvironmentSecret, SR-CI-16 repo-side half): every deploy secret is reachable
 * only from a job gated behind a GitHub {@code environment:}, and the {@code deploy} job in
 * {@code build-and-deploy.yml} specifically declares one.
 *
 * <p>ADR-CI-16 records environment-scoped secrets + a deployment-tag restriction as a
 * documented manual control: the repository-settings half (required reviewers, which
 * branches/tags may deploy to the {@code glacier.seism0saurus.de} environment) lives in GitHub
 * settings, not in a file this gate can read, and is tracked as RR-4 — a residual risk the
 * user accepted, to be verified manually and recorded in the acceptance commit. This gate
 * covers the half that *is* structurally verifiable from the workflow YAML: the repo-side
 * precondition that a job referencing deploy secrets is always the kind of job GitHub's
 * environment-protection rules can actually gate (i.e. it declares {@code environment:} at
 * all) — without an {@code environment:} declaration, environment-level protection rules
 * (required reviewers, deployment branch/tag policies) have nothing to attach to, no matter
 * how they are configured in repository settings.
 *
 * <h2>Rules enforced</h2>
 * <ol>
 *   <li>Any job (in any workflow) that references {@code SSH_KEY}, {@code KNOWN_HOSTS},
 *       {@code SSH_USER}, or {@code SSH_PORT} must declare {@code environment:}.</li>
 *   <li>{@code build-and-deploy.yml}'s {@code deploy} job specifically declares
 *       {@code environment:}.</li>
 * </ol>
 *
 * <h2>State</h2>
 * Both rules are expected GREEN already today: the {@code deploy} job in
 * {@code build-and-deploy.yml} is the only place the four secrets are referenced, and it
 * already declares {@code environment: glacier.seism0saurus.de}. This gate is a regression
 * tripwire — Lane C's rebuild must not accidentally move a deploy-secret reference into a job
 * without {@code environment:}, and RR-4's manual GitHub-settings half must still be verified
 * and recorded separately (this gate cannot see repository Environment protection-rule
 * configuration).
 *
 * <p>Mode applicability: mode-agnostic.
 *
 * <p>OWASP: A05:2021 — Security Misconfiguration; A01:2021 — Broken Access Control.
 * Security requirement: SR-CI-16 (repo-side half); ADR-CI-16.
 */
class WorkflowEnvironmentSecretTest {

    private static final String DEPLOY_WORKFLOW = "build-and-deploy.yml";
    private static final String DEPLOY_JOB = "deploy";

    private static final Set<String> DEPLOY_SECRETS = Set.of(
            "SSH_KEY", "KNOWN_HOSTS", "SSH_USER", "SSH_PORT"
    );

    private static final Pattern SECRET_REFERENCE = Pattern.compile("secrets\\.([A-Za-z0-9_]+)");

    @Test
    void everyJobReferencingADeploySecretDeclaresAnEnvironment() {
        List<WorkflowFile> all = WorkflowInventory.loadAllWorkflows();
        SoftAssertions softly = new SoftAssertions();

        for (WorkflowFile wf : all) {
            for (WorkflowJob job : wf.jobs().values()) {
                Set<String> found = secretNamesIn(job.raw());
                boolean referencesDeploySecret = found.stream().anyMatch(DEPLOY_SECRETS::contains);
                if (!referencesDeploySecret) {
                    continue;
                }
                softly.assertThat(job.environment())
                        .as("%s job '%s' references a deploy secret but declares no "
                                + "environment: -- GitHub Environment protection rules "
                                + "(required reviewers, deployment branch/tag policy) have "
                                + "nothing to attach to without one (SR-CI-16/ADR-CI-16)",
                                wf.relativePath(), job.id())
                        .isNotNull();
            }
        }

        softly.assertAll();
    }

    @Test
    void deployJobDeclaresAnEnvironment() {
        WorkflowFile bad = WorkflowInventory.loadWorkflow(DEPLOY_WORKFLOW);
        WorkflowJob deploy = bad.job(DEPLOY_JOB)
                .orElseThrow(() -> new AssertionError(DEPLOY_WORKFLOW + " must define a '" + DEPLOY_JOB + "' job"));

        assertThat(deploy.environment())
                .as("%s job '%s' must declare environment: (ADR-CI-16)", DEPLOY_WORKFLOW, DEPLOY_JOB)
                .isNotNull();
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
}
