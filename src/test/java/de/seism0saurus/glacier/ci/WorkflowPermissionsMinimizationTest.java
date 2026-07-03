package de.seism0saurus.glacier.ci;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gate 5.2 (WorkflowPermissionsMinimization): every workflow declares an explicit,
 * least-privilege top-level {@code permissions:} block, and every elevated {@code GITHUB_TOKEN}
 * scope is confined to a small, named allowlist of jobs that actually need it.
 *
 * <p>Without an explicit {@code permissions:} block, a workflow inherits the
 * repository/organization default token scope, which on many repositories (including this
 * one, historically) is the broad classic default (read/write across most scopes) — a
 * standing privilege escalation surface for any code path that can be coaxed into running
 * inside that workflow. ADR-CI-03 requires every workflow to opt in to an explicit minimal
 * baseline instead of relying on that ambient default.
 *
 * <h2>Rules enforced</h2>
 * <ol>
 *   <li>Every workflow file (including the {@code _*.yml} reusable core) has a top-level
 *       {@code permissions:} that is either the empty mapping {@code {}} or exactly
 *       {@code contents: read}.</li>
 *   <li>Any of the elevated write scopes ({@code packages}, {@code pull-requests},
 *       {@code security-events}, {@code id-token}, {@code attestations}, {@code contents} at
 *       {@code write} level) may only appear on a job whose id is on the allowlist:
 *       {@code publish-image}, {@code deploy}, {@code pr-comment}, {@code analyze}, a job id
 *       containing {@code sarif} (case-insensitive — Lane C's SARIF-uploading jobs, e.g. a
 *       renamed {@code trivy-fs-sarif}/{@code trivy-image-sarif}/{@code zap-sarif}, or any
 *       future SARIF-producing job), or {@code dependency-submission}.</li>
 *   <li>None of those allowlisted job ids — except a job that is granted only
 *       {@code security-events: write} (needed so PR-triggered static/SARIF analysis can
 *       still annotate the Security tab; GitHub already restricts a fork PR's token to
 *       read-only regardless) — may appear inside a {@code pull_request}-triggered workflow at
 *       all. {@code publish-image}/{@code deploy}/{@code pr-comment}/{@code dependency-submission}
 *       have no legitimate reason to exist in a PR-triggered file.</li>
 *   <li>{@code deploy}, {@code publish-image}, and {@code dependency-submission} job ids may
 *       only exist in {@code build-and-deploy.yml} — the single privileged, secret-bearing
 *       workflow (ADR-CI-01 trust-tier topology).</li>
 * </ol>
 *
 * <h2>Deliberate RED state (before Lane C)</h2>
 * {@code pull-request.yml}, {@code security.yml}, {@code build-and-deploy.yml}, and
 * {@code codeql.yml} have no top-level {@code permissions:} at all today (RED on rule 1).
 * {@code mutation.yml} already declares {@code permissions: contents: read} (GREEN on rule 1
 * for that file). {@code build-and-deploy.yml}'s {@code trivy-fs}/{@code trivy-image}/
 * {@code zap-and-headers} jobs grant {@code security-events: write} under job ids that do not
 * contain {@code sarif} — RED on rule 2 until Lane C either renames them or otherwise makes
 * the allowlist match (see hand-off). {@code publish-image} and {@code deploy} already only
 * exist in {@code build-and-deploy.yml} today (GREEN on rule 4).
 *
 * <p>Mode applicability: mode-agnostic.
 *
 * <p>OWASP: A05:2021 — Security Misconfiguration; A01:2021 — Broken Access Control
 * (least-privilege token scope). Security requirements: SR-CI-02, SR-CI-06; ADR-CI-02,
 * ADR-CI-03.
 *
 * @see <a href="https://docs.github.com/en/actions/security-guides/automatic-token-authentication#permissions-for-the-github_token">
 *      GitHub Actions — permissions for the GITHUB_TOKEN</a>
 */
class WorkflowPermissionsMinimizationTest {

    /**
     * Scopes whose {@code write} level constitutes an elevated privilege worth restricting to
     * an allowlisted job. {@code contents} is included because {@code contents: write} allows
     * pushing commits/tags/releases -- a materially different capability than the
     * {@code contents: read} baseline every workflow is allowed by default.
     */
    private static final Set<String> ELEVATED_WRITE_SCOPES = Set.of(
            "packages", "pull-requests", "security-events", "id-token", "attestations", "contents"
    );

    /** Job ids allowed to hold an elevated write scope, plus the {@code *sarif*} wildcard. */
    private static final Set<String> ALLOWLISTED_JOB_IDS = Set.of(
            "publish-image", "deploy", "pr-comment", "analyze", "dependency-submission"
    );

    /** Job ids that must never exist outside the single privileged deploy workflow. */
    private static final Set<String> DEPLOY_ONLY_JOB_IDS = Set.of(
            "deploy", "publish-image", "dependency-submission"
    );

    private static final String DEPLOY_WORKFLOW = "build-and-deploy.yml";

    @Test
    void everyWorkflowHasMinimalTopLevelPermissions() {
        List<WorkflowFile> all = WorkflowInventory.loadAllWorkflows();
        SoftAssertions softly = new SoftAssertions();

        for (WorkflowFile wf : all) {
            Object permissions = WorkflowInventory.topLevelPermissionsOf(wf);
            softly.assertThat(permissions)
                    .as("%s must declare an explicit top-level permissions: block (ADR-CI-03) "
                            + "-- absence means the workflow silently inherits the repository's "
                            + "default GITHUB_TOKEN scope instead of opting in to least privilege",
                            wf.relativePath())
                    .isNotNull();
            if (permissions == null) {
                continue;
            }
            boolean isEmptyMap = permissions instanceof Map<?, ?> m && m.isEmpty();
            boolean isContentsReadOnly = permissions instanceof Map<?, ?> m
                    && m.size() == 1
                    && "read".equals(String.valueOf(m.get("contents")));
            softly.assertThat(isEmptyMap || isContentsReadOnly)
                    .as("%s top-level permissions: must be exactly {} or {contents: read} -- "
                            + "found %s (any broader top-level grant belongs at job level on an "
                            + "allowlisted job, not ambiently on every job in the file)",
                            wf.relativePath(), permissions)
                    .isTrue();
        }

        softly.assertAll();
    }

    @Test
    void elevatedScopesOnlyOnAllowlistedJobs() {
        List<WorkflowFile> all = WorkflowInventory.loadAllWorkflows();
        SoftAssertions softly = new SoftAssertions();

        for (WorkflowFile wf : all) {
            for (WorkflowJob job : WorkflowInventory.jobsOf(wf).values()) {
                Set<String> elevated = elevatedScopesOf(job.permissions());
                if (elevated.isEmpty()) {
                    continue;
                }
                boolean allowlisted = isAllowlistedJobId(job.id());
                softly.assertThat(allowlisted)
                        .as("%s job '%s' grants elevated scope(s) %s but its id is not on the "
                                + "allowlist {publish-image, deploy, pr-comment, analyze, "
                                + "*sarif*, dependency-submission} (SR-CI-02/06)",
                                wf.relativePath(), job.id(), elevated)
                        .isTrue();
            }
        }

        softly.assertAll();
    }

    @Test
    void allowlistedJobsAbsentFromPullRequestWorkflowsExceptSecurityEventsOnly() {
        List<WorkflowFile> all = WorkflowInventory.loadAllWorkflows();
        SoftAssertions softly = new SoftAssertions();

        for (WorkflowFile wf : all) {
            if (!WorkflowInventory.triggersOf(wf).containsKey("pull_request")) {
                continue;
            }
            for (WorkflowJob job : WorkflowInventory.jobsOf(wf).values()) {
                if (!isAllowlistedJobId(job.id())) {
                    continue;
                }
                Set<String> elevated = elevatedScopesOf(job.permissions());
                Set<String> nonSecurityEvents = new LinkedHashSet<>(elevated);
                nonSecurityEvents.remove("security-events");
                softly.assertThat(nonSecurityEvents)
                        .as("%s is pull_request-triggered; allowlisted job '%s' must not hold "
                                + "any elevated scope beyond security-events: write in a "
                                + "PR-triggered workflow -- found %s (SR-CI-02: fork PRs must "
                                + "never reach packages/pull-requests/id-token/attestations/"
                                + "contents:write)",
                                wf.relativePath(), job.id(), nonSecurityEvents)
                        .isEmpty();
            }
        }

        softly.assertAll();
    }

    @Test
    void deployPublishAndDependencySubmissionOnlyExistInBuildAndDeploy() {
        List<WorkflowFile> all = WorkflowInventory.loadAllWorkflows();
        SoftAssertions softly = new SoftAssertions();

        for (WorkflowFile wf : all) {
            if (DEPLOY_WORKFLOW.equals(wf.fileName())) {
                continue;
            }
            for (String jobId : DEPLOY_ONLY_JOB_IDS) {
                softly.assertThat(wf.jobs())
                        .as("%s must not define job '%s' -- deploy/publish-image/"
                                + "dependency-submission are confined to the single privileged "
                                + "%s workflow (ADR-CI-01 trust-tier topology)",
                                wf.relativePath(), jobId, DEPLOY_WORKFLOW)
                        .doesNotContainKey(jobId);
            }
        }

        softly.assertAll();
    }

    private static boolean isAllowlistedJobId(String jobId) {
        String lower = jobId.toLowerCase(java.util.Locale.ROOT);
        return ALLOWLISTED_JOB_IDS.contains(jobId) || lower.contains("sarif");
    }

    @SuppressWarnings("unchecked")
    private static Set<String> elevatedScopesOf(Object permissions) {
        Set<String> result = new LinkedHashSet<>();
        if (permissions == null) {
            return result;
        }
        if (permissions instanceof String s) {
            if ("write-all".equalsIgnoreCase(s.trim())) {
                result.addAll(ELEVATED_WRITE_SCOPES);
            }
            return result;
        }
        if (permissions instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String scope = String.valueOf(e.getKey());
                String level = String.valueOf(e.getValue());
                if (ELEVATED_WRITE_SCOPES.contains(scope) && "write".equals(level)) {
                    result.add(scope);
                }
            }
        }
        return result;
    }
}
