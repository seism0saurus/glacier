package de.seism0saurus.glacier.ci;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gate 5.8 (WorkflowYamlInventory): bijective completeness of the {@code .github/workflows/}
 * inventory against the trust-tier topology, plus the relocated SR-NA-01..09 npm-audit
 * invariants (ADR-CI-07, {@code docs/decisions/2026-07-03-planning-secure-ci-pipeline.md}).
 *
 * <h2>Why this class changed shape</h2>
 * Before the trust-tier rebuild, {@code security.yml} carried {@code push}/{@code pull_request}
 * triggers of its own and ran the {@code frontend-audit} (npm audit) and {@code secret-scan}
 * (gitleaks) jobs directly, gated by those triggers. This class asserted exactly that: three
 * triggers present, {@code frontend-audit} job present with specific steps.
 *
 * <p>Under the new topology, {@code security.yml} is repaired into a pure
 * <b>schedule + workflow_dispatch</b> wrapper around the secret-free reusable core
 * ({@code _build} + {@code _security-dast}) — weekly CVE re-detection and manual full scans,
 * nothing event-driven. The push/PR-triggered supply-chain checks that used to live in
 * {@code security.yml} (frontend dependency audit, secret scanning) move into
 * {@code _build.yml}, the secret-free reusable core called by {@code quality.yml} (every
 * branch push), {@code pull-request.yml} (every PR, including forks), {@code full-suite.yml}
 * (manual dispatch), <em>and</em> {@code security.yml} itself (weekly schedule) — so weekly CVE
 * detection (SR-NA-03) is preserved as a side effect of {@code security.yml} calling
 * {@code _build}, without {@code security.yml} needing its own copy of the audit job.
 *
 * <p><b>This is the exact failure mode gate 5.8 exists to prevent</b>: if this test kept
 * asserting against {@code security.yml} after the jobs moved out, it would report green
 * forever — vacuously, because it would simply find nothing to check in a workflow that
 * legitimately no longer contains the assertions' subject matter. Every assertion below that
 * used to target {@code security.yml} now targets the new home ({@code _build.yml}) instead,
 * <em>and</em> additionally asserts the old home no longer duplicates the job (drift guard: a
 * relocation must be a move, not a copy).
 *
 * <h2>Deliberate RED state (as of Lane A / tdd-ddd-implementer, before Lane C)</h2>
 * The reusable core files ({@code _build.yml}, {@code _e2e.yml}, {@code _security-dast.yml})
 * and the new top-level workflows ({@code quality.yml}, {@code pr-comment.yml},
 * {@code full-suite.yml}) do not exist yet — Lane C (devops-infra-engineer) creates them.
 * {@code security.yml} still carries its old {@code push}/{@code pull_request} triggers and
 * its own {@code frontend-audit}/{@code secret-scan} jobs, and {@code verify.yml} (the
 * branch-marker release workflow retired by ADR-CI-18) still exists. Every test method below
 * is therefore RED until Lane C's rebuild lands; see the class-level {@code ./mvnw} evidence
 * recorded in the implementation decision log. Gate 5.5 ({@code quality.yml}'s own trigger
 * correctness) belongs to the {@code secure-tdd-implementer} lane (SR-CI-13) and is
 * intentionally NOT duplicated here — this class only asserts file-inventory completeness and
 * the SR-NA relocation, not {@code quality.yml}'s internal trigger semantics.
 *
 * <p>Parsing strategy: all assertions traverse the {@link WorkflowInventory} object model —
 * no string grep — so YAML reordering/reformatting cannot produce a false positive or
 * false negative.
 *
 * <p><b>Mode applicability</b>: mode-agnostic — CI workflow correctness is independent of
 * Glacier's operational mode (live / fallback / killswitch / insecure).
 *
 * <p>OWASP: A06:2021 — Vulnerable and Outdated Components (frontend supply-chain gate);
 * A05:2021 — Security Misconfiguration (workflow-inventory drift).
 * Security requirements: SR-NA-01, SR-NA-03, SR-NA-04, SR-NA-05, SR-NA-07, SR-NA-09; ADR-CI-01,
 * ADR-CI-06, ADR-CI-07, ADR-CI-18.
 *
 * @see <a href="https://owasp.org/Top10/A06_2021-Vulnerable_and_Outdated_Components/">OWASP A06:2021</a>
 */
class WorkflowYamlInventoryTest {

    /**
     * The complete, ADR-governed target set of workflow files under {@code .github/workflows/}
     * (topology table in {@code docs/decisions/2026-07-03-planning-secure-ci-pipeline.md}).
     * Deliberately does <b>not</b> include {@code setup-java-cache.yml}: that file is
     * pre-existing dead code (superseded by the {@code .github/actions/setup-java-cache}
     * composite action; nothing calls it — verified via
     * {@code grep -rn "setup-java-cache.yml" .github/workflows/*.yml}) that predates and is
     * out of scope for this ADR set. Its removal is a separate, not-yet-made decision and is
     * intentionally left unconstrained here rather than silently forced through this gate.
     */
    private static final Set<String> ADR_GOVERNED_WORKFLOW_FILES = Set.of(
            "quality.yml",
            "pull-request.yml",
            "pr-comment.yml",
            "full-suite.yml",
            "build-and-deploy.yml",
            "security.yml",
            "codeql.yml",
            "mutation.yml",
            "_build.yml",
            "_e2e.yml",
            "_security-dast.yml"
    );

    /**
     * Files that ADR-CI-18 explicitly retires: {@code verify.yml}'s
     * {@code push: branches: ["*.*.*"]} trigger was the branch-based release marker that git-tag
     * releases (ADR-CI-18) replace. {@code quality.yml} (push on every branch, calling
     * {@code _build}) supersedes its "push triggers quality checks" role.
     */
    private static final Set<String> RETIRED_WORKFLOW_FILES = Set.of("verify.yml");

    private static final String BUILD_CORE = "_build.yml";
    private static final String SECURITY_YML = "security.yml";

    /**
     * Bijective completeness check (gate 5.8's core purpose): the discovered
     * {@code .github/workflows/} file set, minus the one documented out-of-scope exception,
     * must equal exactly the ADR-governed target set — no ADR-mandated file missing, no
     * unexplained extra file, and the ADR-CI-18-retired {@code verify.yml} gone.
     *
     * <p>This exists so that if Lane C forgets to create a reusable-core file (or forgets to
     * remove {@code verify.yml}), the gap is caught here directly — independently of whether
     * any individual security gate happens to reference that specific file by name. A gate
     * that only asserts properties of files it already expects to exist can never notice a
     * file that should exist but doesn't.
     */
    @Test
    void everyAdrGovernedWorkflowFileExistsAndParses() {
        List<WorkflowFile> discovered = WorkflowInventory.loadAllWorkflows();
        Set<String> discoveredNames = new LinkedHashSet<>();
        for (WorkflowFile file : discovered) {
            discoveredNames.add(file.fileName());
        }

        SoftAssertions softly = new SoftAssertions();

        for (String expected : ADR_GOVERNED_WORKFLOW_FILES) {
            softly.assertThat(discoveredNames)
                    .as("ADR-governed workflow file '%s' must exist under .github/workflows/ "
                            + "(topology table, 2026-07-03-planning-secure-ci-pipeline.md) "
                            + "-- expected to be RED until Lane C (devops-infra-engineer) builds it",
                            expected)
                    .contains(expected);
        }

        for (String retired : RETIRED_WORKFLOW_FILES) {
            softly.assertThat(discoveredNames)
                    .as("'%s' must be removed -- ADR-CI-18 retires the branch-based release "
                            + "marker it implemented; quality.yml supersedes its push-trigger role",
                            retired)
                    .doesNotContain(retired);
        }

        Set<String> unexplained = new LinkedHashSet<>(discoveredNames);
        unexplained.removeAll(ADR_GOVERNED_WORKFLOW_FILES);
        unexplained.removeAll(RETIRED_WORKFLOW_FILES);
        unexplained.remove("setup-java-cache.yml"); // documented out-of-scope exception, see field javadoc

        softly.assertThat(unexplained)
                .as("every .github/workflows/*.yml file must be accounted for in either "
                        + "ADR_GOVERNED_WORKFLOW_FILES or the documented out-of-scope exception "
                        + "-- an unexplained file here means the completeness gate has a blind spot")
                .isEmpty();

        for (WorkflowFile file : discovered) {
            softly.assertThat(file.raw())
                    .as("%s must parse to a non-empty YAML mapping", file.relativePath())
                    .isNotEmpty();
        }

        softly.assertAll();
    }

    /**
     * SR-NA-03: {@code security.yml}'s new role is schedule-driven CVE re-detection plus a
     * manual full-scan escape hatch -- nothing else. It must no longer carry {@code push} or
     * {@code pull_request} triggers of its own; those responsibilities move to
     * {@code quality.yml} / {@code pull-request.yml} calling the shared {@code _build} core
     * (gate 5.5, owned by secure-tdd-implementer -- not duplicated here).
     *
     * <p>Replaces the pre-rebuild {@code triggersIncludePushPullRequestAndSchedule} assertion,
     * which required {@code push}+{@code pull_request}+{@code schedule}. That assertion is the
     * exact "gate keeps checking the old shape" trap this class's javadoc describes: after the
     * rebuild, requiring {@code push}/{@code pull_request} on {@code security.yml} would fail
     * for the *right* reason once Lane C repairs it, so the requirement is inverted here.
     */
    @Test
    void securityYmlTriggersAreScheduleAndWorkflowDispatchOnly() {
        WorkflowFile securityYml = WorkflowInventory.loadWorkflow(SECURITY_YML);
        Map<String, Object> triggers = WorkflowInventory.triggersOf(securityYml);

        assertThat(triggers)
                .as("security.yml must keep its 'schedule' trigger (SR-NA-03: weekly CVE detection)")
                .containsKey("schedule");
        assertThat(triggers)
                .as("security.yml must keep a 'workflow_dispatch' trigger (manual full-scan escape hatch)")
                .containsKey("workflow_dispatch");
        assertThat(triggers)
                .as("security.yml must NOT have a 'push' trigger anymore -- that responsibility "
                        + "moves to quality.yml calling the shared _build core (ADR-CI-01)")
                .doesNotContainKey("push");
        assertThat(triggers)
                .as("security.yml must NOT have a 'pull_request' trigger anymore -- that "
                        + "responsibility moves to pull-request.yml calling the shared _build "
                        + "core, uniformly for same-repo AND fork PRs (ADR-CI-01)")
                .doesNotContainKey("pull_request");
    }

    /**
     * SR-NA-01, SR-NA-04, SR-NA-05, SR-NA-07, SR-NA-09: the {@code frontend-audit} job (npm
     * dependency audit) must live in the secret-free reusable core ({@code _build.yml}) with
     * its invariants intact, and must no longer also exist in {@code security.yml} (a
     * relocation must be a move, not a copy -- leaving a stale duplicate behind would let the
     * two copies drift apart silently).
     */
    @Test
    void frontendAuditMovesToBuildCoreAndNoLongerDuplicatesInSecurityYml() {
        assertThat(Files.exists(WorkflowInventory.WORKFLOWS_DIR.resolve(BUILD_CORE)))
                .as("_build.yml must exist before the frontend-audit relocation can be verified "
                        + "-- expected to be RED until Lane C creates the reusable core")
                .isTrue();

        WorkflowFile buildCore = WorkflowInventory.loadWorkflow(BUILD_CORE);
        assertThat(buildCore.jobs())
                .as("_build.yml must define a 'frontend-audit' job (relocated from security.yml, SR-NA-07)")
                .containsKey("frontend-audit");

        WorkflowJob frontendAudit = buildCore.job("frontend-audit").orElseThrow();
        List<WorkflowStep> steps = frontendAudit.steps();
        assertThat(steps)
                .as("frontend-audit job in _build.yml must have at least one step")
                .isNotEmpty();

        boolean foundNpmCi = steps.stream().anyMatch(s -> s.runContains("npm ci"));
        assertThat(foundNpmCi)
                .as("frontend-audit job in _build.yml must have a step with 'npm ci' (SR-NA-05)")
                .isTrue();

        boolean foundNpmAudit = steps.stream().anyMatch(s -> s.runContains("npm audit"));
        assertThat(foundNpmAudit)
                .as("frontend-audit job in _build.yml must have a step with 'npm audit' (SR-NA-01)")
                .isTrue();

        boolean frontendWorkingDir = steps.stream().anyMatch(step -> {
            if (!step.hasRun()) {
                return false;
            }
            if (step.runContains("npm ci") || step.runContains("npm audit")) {
                return "frontend".equals(step.workingDirectory()) || step.runContains("cd frontend");
            }
            return false;
        });
        assertThat(frontendWorkingDir)
                .as("npm ci/npm audit steps in _build.yml must target the Angular package.json "
                        + "via working-directory: frontend (or cd frontend) (SR-NA-09)")
                .isTrue();

        WorkflowStep auditStep = steps.stream()
                .filter(s -> s.runContains("npm audit"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no 'npm audit' step found in _build.yml frontend-audit job"));

        assertThat(auditStep.run())
                .as("npm audit step in _build.yml must specify --audit-level=high or "
                        + "--audit-level=critical to avoid low/moderate noise (SR-NA-01)")
                .satisfiesAnyOf(
                        s -> assertThat(s).contains("--audit-level=high"),
                        s -> assertThat(s).contains("--audit-level=critical")
                );
        assertThat(auditStep.run())
                .as("npm audit step in _build.yml must include --omit=dev to scope the scan to "
                        + "production runtime dependencies only (SR-NA-01)")
                .contains("--omit=dev");

        Object continueOnError = auditStep.continueOnError();
        if (continueOnError != null) {
            assertThat(continueOnError)
                    .as("npm audit step in _build.yml must not have continue-on-error: true -- "
                            + "that would swallow failures and defeat the gate (SR-NA-04)")
                    .isEqualTo(Boolean.FALSE);
        }

        WorkflowFile securityYml = WorkflowInventory.loadWorkflow(SECURITY_YML);
        assertThat(securityYml.jobs())
                .as("security.yml must NO LONGER define its own 'frontend-audit' job -- the "
                        + "relocation to _build.yml must be a move, not a copy, or the two "
                        + "copies will drift apart silently")
                .doesNotContainKey("frontend-audit");
    }

    /**
     * The {@code secret-scan} (gitleaks) job moves alongside {@code frontend-audit} into
     * {@code _build.yml}, for the same reason: it is a cheap, secret-free, static check that
     * belongs in the reusable core so every caller (push, PR, manual dispatch, weekly
     * schedule) gets incremental secret-scanning coverage uniformly, instead of only when
     * {@code security.yml}'s own {@code push}/{@code pull_request} triggers happened to fire.
     */
    @Test
    void secretScanMovesToBuildCoreAndNoLongerDuplicatesInSecurityYml() {
        assertThat(Files.exists(WorkflowInventory.WORKFLOWS_DIR.resolve(BUILD_CORE)))
                .as("_build.yml must exist before the secret-scan relocation can be verified "
                        + "-- expected to be RED until Lane C creates the reusable core")
                .isTrue();

        WorkflowFile buildCore = WorkflowInventory.loadWorkflow(BUILD_CORE);
        assertThat(buildCore.jobs())
                .as("_build.yml must define a 'secret-scan' job (relocated from security.yml)")
                .containsKey("secret-scan");

        WorkflowJob secretScan = buildCore.job("secret-scan").orElseThrow();
        boolean usesGitleaks = secretScan.stepUsesReferences().stream()
                .anyMatch(u -> u.contains("gitleaks/gitleaks-action"));
        assertThat(usesGitleaks)
                .as("secret-scan job in _build.yml must use gitleaks/gitleaks-action (D-13/SR-8)")
                .isTrue();

        // fetch-depth: 0 is required so gitleaks can scan the full pushed commit range
        // (base^..head) rather than a shallow, single-commit clone.
        boolean fullHistoryCheckout = secretScan.steps().stream()
                .anyMatch(s -> s.hasUses() && s.usesContains("actions/checkout")
                        && "0".equals(String.valueOf(s.with().get("fetch-depth"))));
        assertThat(fullHistoryCheckout)
                .as("secret-scan job in _build.yml must check out with fetch-depth: 0 so "
                        + "gitleaks can scan the full pushed/PR commit range")
                .isTrue();

        WorkflowFile securityYml = WorkflowInventory.loadWorkflow(SECURITY_YML);
        assertThat(securityYml.jobs())
                .as("security.yml must NO LONGER define its own 'secret-scan' job -- the "
                        + "relocation to _build.yml must be a move, not a copy")
                .doesNotContainKey("secret-scan");
    }

    /**
     * Gate 5.8 addendum (devops fix, 2026-07-03): {@code actionlint} (GitHub's own de-facto
     * standard static analyzer for Actions workflow YAML) closes a real gap that none of the
     * SnakeYAML-based structural gates in this {@code ci} package can close on their own --
     * they parse workflow <em>shape</em> but never evaluate {@code ${{ }}} expressions, so a
     * job-level {@code if:} referencing a context that is not legal there (e.g. {@code matrix}
     * -- only valid inside a job that declares a {@code strategy.matrix}) parses as perfectly
     * valid YAML and sails through every existing gate, while GitHub rejects the whole
     * workflow file at push time ("workflow file issue", instant 0s failure on every run). A
     * real push of this branch surfaced exactly that defect in {@code _e2e.yml}.
     *
     * <p>This test locks the fix -- a pinned, checksum-verified {@code actionlint} job added to
     * the secret-free {@code _build.yml} core -- against silent removal, renaming, or
     * degradation into an unpinned/unverified download, the same "a gate must not silently
     * vanish" concern documented on
     * {@link #frontendAuditMovesToBuildCoreAndNoLongerDuplicatesInSecurityYml()}.
     *
     * <p>Anti-vacuity: asserts against the job's actual id ({@code "actionlint"}) and its
     * actual human-readable {@code name:}, plus the concrete two-step structure {@code
     * _build.yml} currently implements (a pinned + checksum-verified install step, then a
     * separate step that actually invokes the {@code actionlint} binary against the workflow
     * directory) -- renaming/removing the job, dropping the checksum verification, or removing
     * the invocation step (installed but never run) each independently turns this test red.
     */
    @Test
    void actionlintJobLintsWorkflowsWithPinnedChecksumVerifiedInstall() {
        WorkflowFile buildCore = WorkflowInventory.loadWorkflow(BUILD_CORE);

        assertThat(buildCore.jobs())
                .as("_build.yml must define an 'actionlint' job -- GitHub Actions workflow "
                        + "schema/expression linter closing the gap SnakeYAML-based structural "
                        + "gates cannot close (they never evaluate ${{ }} expressions)")
                .containsKey("actionlint");

        WorkflowJob actionlint = buildCore.job("actionlint").orElseThrow();

        assertThat(actionlint.raw().get("name"))
                .as("actionlint job must keep its human-readable name identifying it as the "
                        + "actionlint lint job -- a rename here without updating this gate "
                        + "would be an unnoticed drift")
                .isEqualTo("Lint GitHub Actions workflows (actionlint)");

        List<WorkflowStep> steps = actionlint.steps();
        assertThat(steps)
                .as("actionlint job in _build.yml must have at least one step")
                .isNotEmpty();

        WorkflowStep installStep = steps.stream()
                .filter(s -> s.hasRun() && s.runContains("curl") && s.runContains("actionlint"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no actionlint install step (curl download) found in _build.yml actionlint job"));

        assertThat(installStep.run())
                .as("actionlint install step must verify the downloaded binary's checksum "
                        + "(sha256sum -c) -- an unverified supply-chain download is exactly the "
                        + "trust-boundary risk this job's own vetting rationale argues against")
                .contains("sha256sum -c");

        assertThat(installStep.env())
                .as("actionlint version must be pinned via an explicit env var, not a floating "
                        + "'latest' download -- reproducible, auditable builds require a fixed version")
                .containsKey("ACTIONLINT_VERSION");
        assertThat(installStep.env())
                .as("the expected checksum must be pinned via an explicit env var alongside the "
                        + "version, so the sha256sum -c verification has a fixed known-good "
                        + "value to compare the download against")
                .containsKey("ACTIONLINT_SHA256");

        boolean invokesActionlintAgainstWorkflows = steps.stream()
                .anyMatch(s -> s.hasRun()
                        && s.run().trim().startsWith("actionlint")
                        && s.runContains(".github/workflows"));
        assertThat(invokesActionlintAgainstWorkflows)
                .as("actionlint job in _build.yml must have a step that actually invokes the "
                        + "'actionlint' binary against .github/workflows/*.yml -- installing it "
                        + "without ever running it would be a vacuous gate")
                .isTrue();
    }
}
