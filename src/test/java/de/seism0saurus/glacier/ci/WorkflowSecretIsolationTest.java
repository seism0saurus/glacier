package de.seism0saurus.glacier.ci;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gate 5.1 (WorkflowSecretIsolation / "pwn request" prevention): no workflow accepts
 * {@code pull_request_target}, and no untrusted-tier workflow (one reachable by a fork PR
 * diff or by an every-branch push) can reach any secret other than the ambient
 * {@code GITHUB_TOKEN} — checked across {@code run:}, {@code env:}, {@code with:}, {@code uses:},
 * and job-level {@code secrets:} alike, via {@link WorkflowInventory#secretReferences(WorkflowFile)}'s
 * recursive scan of the whole parsed file. {@code secrets: inherit} is forbidden everywhere,
 * since it defeats the whole secret-free reusable-core design (ADR-CI-02) the moment any
 * caller of a {@code _*.yml} core workflow uses it.
 *
 * <p><b>Attack prevented</b>: the classic GitHub Actions "pwn request" — a fork PR whose
 * diff runs in a workflow that has (a) a trigger reachable from the fork, and (b) access to
 * repository secrets. {@code pull_request_target} runs with the base-repo's token/secrets
 * against attacker-supplied code; a {@code pull_request}-triggered or every-branch-{@code push}
 * workflow that references a real secret has the same effect without even needing
 * {@code pull_request_target}, since the CI operator's own branch push is not the threat model
 * here — an external contributor's fork PR is. See GitHub Security Lab, "Preventing pwn
 * requests" and "Keeping your GitHub Actions and workflows secure: Untrusted input".
 *
 * <h2>Scope</h2>
 * "Untrusted-tier" for this gate = any workflow whose {@code on:} block contains
 * {@code pull_request} (in any shape), OR a {@code push} trigger whose {@code branches:} list
 * contains the literal wildcard {@code "**"} (i.e. {@code quality.yml}'s
 * {@code push: branches: ['**']} — SR-CI-13) — <b>plus every reusable core reachable from
 * one of those</b>. Being merely loaded by {@link WorkflowInventory#loadAllWorkflows()} does
 * NOT by itself put a {@code workflow_call}-only file in scope for the
 * secret-reachability check below: {@code loadAllWorkflows()} only guarantees the "no
 * {@code secrets: inherit} anywhere" assertion sees it, which is a different, narrower
 * invariant than "never references a non-{@code GITHUB_TOKEN} secret directly". A core such
 * as {@code _build.yml} carries no {@code pull_request}/{@code push} trigger of its own (only
 * {@code workflow_call}), so the naive direct-trigger check would silently skip it even
 * though {@code pull-request.yml} (fork-PR-triggered) and {@code quality.yml}
 * (every-branch-push-triggered) both invoke it with untrusted diff code via job
 * {@code uses:} — F-1 fix. {@link #isUntrustedTier(WorkflowFile, Set)} therefore closes
 * this with two overlapping nets: (1) a transitive BFS over local ({@code ./}-prefixed)
 * {@code uses:} references starting at every directly-untrusted workflow
 * ({@link #transitivelyReachableFromUntrustedTriggers(List)}), which covers any current or
 * future locally-called reusable file regardless of its name; and (2) a static fallback —
 * any file matching the {@code _*.yml} naming convention (ADR-CI-01/02,
 * {@link WorkflowFile#isReusableCoreWorkflow()}) that declares an {@code on: workflow_call}
 * trigger is always in scope, independent of whether the reachability graph resolves
 * correctly. {@link #detectorWouldFireOnASecretInjectedIntoAReusableCore()} is the
 * anti-vacuity anchor proving net (2) actually flags an injected secret.
 *
 * <h2>Deliberate RED state (before Lane C)</h2>
 * {@code build-and-deploy.yml} still carries a live {@code pull_request: closed} trigger
 * (ADR-CI-10 defect) at the time this gate is authored — that trigger does not itself
 * reference a secret directly inside {@code build-and-deploy.yml}'s {@code on:} block, but its
 * mere presence is exactly the shape SR-CI-01 forbids for a workflow whose downstream jobs
 * (deploy) hold real SSH secrets; gate 5.3 ({@link WorkflowDeployTriggerTest}) asserts its
 * removal directly. This class focuses on the secret-reachability invariant, which is already
 * satisfied by today's {@code pull-request.yml} (no non-GITHUB_TOKEN secrets referenced) —
 * expect this class's PULL_REQUEST_TARGET and NO_SECRETS_INHERIT assertions to already be
 * GREEN, and the "quality.yml must exist and be secret-free" assertion to be RED until Lane C
 * creates it.
 *
 * <p>Mode applicability: mode-agnostic.
 *
 * <p>OWASP: A05:2021 — Security Misconfiguration; A08:2021 — Software and Data Integrity
 * Failures (untrusted CI input). Security requirements: SR-CI-01, SR-CI-03; ADR-CI-01,
 * ADR-CI-02, ADR-CI-03, ADR-CI-10.
 *
 * @see <a href="https://docs.github.com/en/actions/security-guides/security-hardening-for-github-actions#understanding-the-risk-of-pull_request_target">
 *      GitHub Actions security hardening — the risk of pull_request_target</a>
 * @see <a href="https://securitylab.github.com/resources/github-actions-preventing-pwn-requests/">
 *      GitHub Security Lab — Preventing pwn requests</a>
 */
class WorkflowSecretIsolationTest {

    /** The only secret an untrusted-tier workflow may ever reference. */
    private static final String SAFE_SECRET = "GITHUB_TOKEN";

    @Test
    void noWorkflowUsesPullRequestTarget() {
        List<WorkflowFile> all = WorkflowInventory.loadAllWorkflows();
        SoftAssertions softly = new SoftAssertions();

        for (WorkflowFile wf : all) {
            Map<String, Object> triggers = WorkflowInventory.triggersOf(wf);
            softly.assertThat(triggers)
                    .as("%s must never use pull_request_target (ADR-CI-03: repo-wide ban -- "
                            + "it runs fork-PR code with base-repo secrets/token)", wf.relativePath())
                    .doesNotContainKey("pull_request_target");
        }

        softly.assertAll();
    }

    @Test
    void noWorkflowUsesSecretsInherit() {
        List<WorkflowFile> all = WorkflowInventory.loadAllWorkflows();
        SoftAssertions softly = new SoftAssertions();

        for (WorkflowFile wf : all) {
            for (WorkflowJob job : WorkflowInventory.jobsOf(wf).values()) {
                Object secrets = job.secrets();
                boolean inherits = secrets instanceof String s && "inherit".equalsIgnoreCase(s.trim());
                softly.assertThat(inherits)
                        .as("%s job '%s' must not use secrets: inherit -- it defeats the "
                                + "secret-free reusable-core design (ADR-CI-02) the moment any "
                                + "caller of a _*.yml core workflow uses it", wf.relativePath(), job.id())
                        .isFalse();
            }
        }

        softly.assertAll();
    }

    @Test
    void untrustedTierWorkflowsNeverReachNonGithubTokenSecrets() {
        List<WorkflowFile> all = WorkflowInventory.loadAllWorkflows();
        Set<String> transitivelyReachable = transitivelyReachableFromUntrustedTriggers(all);
        SoftAssertions softly = new SoftAssertions();
        boolean anyUntrustedFound = false;

        for (WorkflowFile wf : all) {
            if (!isUntrustedTier(wf, transitivelyReachable)) {
                continue;
            }
            anyUntrustedFound = true;
            Set<String> secretNames = WorkflowInventory.secretReferences(wf);
            Set<String> forbidden = secretNames.stream()
                    .filter(name -> !SAFE_SECRET.equals(name))
                    .collect(Collectors.toCollection(LinkedHashSet::new));

            softly.assertThat(forbidden)
                    .as("%s is untrusted-tier (pull_request-/every-branch-push-triggered, or a "
                            + "reusable core reachable from one of those via job uses:) and must "
                            + "never reach a secret other than GITHUB_TOKEN -- found %s "
                            + "(SR-CI-01/03: fork-PR pwn-request prevention, F-1 fix)",
                            wf.relativePath(), forbidden)
                    .isEmpty();
        }

        softly.assertThat(anyUntrustedFound)
                .as("expected at least one untrusted-tier workflow to exist (pull-request.yml "
                        + "today, quality.yml once Lane C creates it with push: branches: ['**']) "
                        + "-- an empty scope here would make this gate vacuously green")
                .isTrue();

        softly.assertAll();
    }

    /**
     * A workflow is untrusted-tier if a fork PR's diff can reach it -- directly (it triggers
     * on {@code pull_request}, or on {@code push} to every branch per SR-CI-13's
     * {@code branches: ['**']} shape), or transitively (a directly-untrusted workflow calls it
     * via a local job {@code uses:}, e.g. {@code pull-request.yml} -> {@code _build.yml}) --
     * or it is a reusable core that, by naming convention and {@code workflow_call} trigger,
     * exists specifically to be called from an untrusted tier regardless of whether today's
     * reachability graph currently proves it (F-1 fix: see the class Javadoc "Scope" section).
     */
    private static boolean isUntrustedTier(WorkflowFile wf, Set<String> transitivelyReachableFileNames) {
        if (isDirectlyUntrustedTier(wf)) {
            return true;
        }
        if (transitivelyReachableFileNames.contains(wf.fileName())) {
            return true;
        }
        return isReusableCoreCallableViaWorkflowCall(wf);
    }

    private static boolean isDirectlyUntrustedTier(WorkflowFile wf) {
        Map<String, Object> triggers = WorkflowInventory.triggersOf(wf);
        if (triggers.containsKey("pull_request")) {
            return true;
        }
        Object push = triggers.get("push");
        return isPushToAllBranches(push);
    }

    /**
     * Static naming-convention fallback (ADR-CI-01/02): any {@code _*.yml} file
     * ({@link WorkflowFile#isReusableCoreWorkflow()}) that declares an
     * {@code on: workflow_call} trigger is a reusable core meant to be invoked from an
     * untrusted tier, independent of whether {@link #transitivelyReachableFromUntrustedTriggers}
     * currently proves a caller edge exists. This is deliberately redundant with the BFS
     * below -- a harness bug in the reachability graph must not silently exempt a core from
     * the secret-reachability check.
     */
    private static boolean isReusableCoreCallableViaWorkflowCall(WorkflowFile wf) {
        return wf.isReusableCoreWorkflow()
                && WorkflowInventory.triggersOf(wf).containsKey("workflow_call");
    }

    @SuppressWarnings("unchecked")
    private static boolean isPushToAllBranches(Object pushConfig) {
        if (!(pushConfig instanceof Map<?, ?> map)) {
            return false;
        }
        Object branches = map.get("branches");
        if (!(branches instanceof List<?> list)) {
            return false;
        }
        return list.stream().anyMatch(b -> "**".equals(String.valueOf(b)));
    }

    /**
     * BFS over local ({@code ./}-prefixed) {@code uses:} references -- job-level
     * (reusable-workflow calls, e.g. {@code uses: ./.github/workflows/_build.yml}) and
     * step-level alike -- starting from every directly-untrusted-tier workflow. A local
     * reference is resolved to its bare file name (the part after the last {@code /}) so it
     * matches {@link WorkflowFile#fileName()} regardless of the relative-path depth written
     * in the caller; references that do not resolve to a known workflow file (e.g. composite
     * actions under {@code .github/actions/}) are simply not added to the frontier.
     */
    private static Set<String> transitivelyReachableFromUntrustedTriggers(List<WorkflowFile> all) {
        Map<String, WorkflowFile> byFileName = new LinkedHashMap<>();
        for (WorkflowFile wf : all) {
            byFileName.put(wf.fileName(), wf);
        }

        Set<String> visited = new LinkedHashSet<>();
        Deque<WorkflowFile> queue = new ArrayDeque<>();
        for (WorkflowFile wf : all) {
            if (isDirectlyUntrustedTier(wf)) {
                queue.add(wf);
                visited.add(wf.fileName());
            }
        }

        while (!queue.isEmpty()) {
            WorkflowFile current = queue.poll();
            for (String uses : current.allUsesReferences()) {
                if (uses == null || !uses.startsWith("./")) {
                    continue;
                }
                String fileName = uses.substring(uses.lastIndexOf('/') + 1);
                WorkflowFile target = byFileName.get(fileName);
                if (target != null && visited.add(fileName)) {
                    queue.add(target);
                }
            }
        }

        return visited;
    }

    /**
     * Anti-vacuity anchor for the F-1 fix. A gate that classifies reusable cores as
     * untrusted-tier but is never exercised against a poisoned fixture is exactly as
     * dangerous as no gate at all -- this proves that a secret injected directly into a
     * reusable core (bypassing {@code secrets: inherit} entirely, e.g. a maintainer
     * accidentally writing {@code ${{ secrets.EVIL_TOKEN }}} straight into a job {@code env:}
     * inside {@code _build.yml}) would actually turn
     * {@link #untrustedTierWorkflowsNeverReachNonGithubTokenSecrets()} red.
     */
    @Test
    void detectorWouldFireOnASecretInjectedIntoAReusableCore() {
        WorkflowFile fakeCore = fakeReusableCoreWithSecretReference("EVIL_TOKEN");

        // Step 1: classification must mark this file untrusted-tier from its own shape alone
        // (naming convention + workflow_call trigger) -- no reachability graph needed, so this
        // holds even if transitivelyReachableFromUntrustedTriggers had a bug.
        assertThat(isReusableCoreCallableViaWorkflowCall(fakeCore))
                .as("a _*.yml file with an `on: workflow_call` trigger must be classified as "
                        + "untrusted-tier regardless of reachability-graph resolution")
                .isTrue();
        assertThat(isUntrustedTier(fakeCore, Set.of()))
                .as("isUntrustedTier() must agree with the naming-convention fallback even when "
                        + "the transitive-reachability set is empty")
                .isTrue();

        // Step 2: the secret-reachability scan the gate runs against every untrusted-tier file
        // must actually find the injected secret and flag it as forbidden.
        Set<String> secretNames = WorkflowInventory.secretReferences(fakeCore);
        Set<String> forbidden = secretNames.stream()
                .filter(name -> !SAFE_SECRET.equals(name))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        assertThat(forbidden)
                .as("a secret directly referenced inside a reusable core must be detected as "
                        + "forbidden -- if this were empty, the F-1 fix would be vacuous")
                .containsExactly("EVIL_TOKEN");
    }

    @SuppressWarnings("unchecked")
    private static WorkflowFile fakeReusableCoreWithSecretReference(String secretName) {
        Map<String, Object> raw = Map.of(
                "name", "_evil",
                "jobs", Map.of(
                        "leak", Map.of(
                                "runs-on", "ubuntu-latest",
                                "env", Map.of("LEAKED", "${{ secrets." + secretName + " }}")
                        )
                )
        );
        return new WorkflowFile(
                Path.of(".github/workflows/_evil.yml"),
                ".github/workflows/_evil.yml",
                "_evil.yml",
                "_evil",
                raw,
                Collections.singletonMap("workflow_call", null),
                "read",
                Map.of()
        );
    }
}
