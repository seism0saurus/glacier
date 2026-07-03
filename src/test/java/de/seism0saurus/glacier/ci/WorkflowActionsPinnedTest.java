package de.seism0saurus.glacier.ci;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Gate 5.10 (WorkflowActionsPinned): every third-party {@code uses:} reference is pinned to a
 * full 40-hex commit SHA, never a mutable tag or branch ({@code @v4}, {@code @main}), so a
 * compromised upstream action release (or a tag that is force-moved after review) cannot
 * silently start executing different code the next time a workflow runs.
 *
 * <p>Local composite actions ({@code ./.github/actions/...}) and local reusable-workflow calls
 * ({@code ./.github/workflows/_*.yml}) are exempt — they resolve to a commit inside this
 * repository itself, already pinned by the checked-out ref, not an external supply-chain
 * dependency (see {@link WorkflowStep#isLocalReference()}).
 *
 * <h2>State</h2>
 * This gate freezes an invariant the repository already satisfies today — every current
 * {@code uses:} reference (across {@code build-and-deploy.yml}, {@code pull-request.yml},
 * {@code security.yml}, {@code codeql.yml}, {@code mutation.yml}, {@code verify.yml}) is
 * already SHA-pinned. It exists as a regression ratchet: any future workflow edit (including
 * Lane C's rebuild, which introduces several new {@code uses:} references in
 * {@code quality.yml}/{@code pull-request.yml}/{@code pr-comment.yml}/{@code full-suite.yml}/
 * {@code _build.yml}/{@code _e2e.yml}/{@code _security-dast.yml}) must keep every new
 * third-party action pinned the same way — see the dependency-vetting skill's SHA-pin rule.
 *
 * <p>Mode applicability: mode-agnostic.
 *
 * <p>OWASP: A06:2021 — Vulnerable and Outdated Components (supply-chain pinning).
 * Security requirement: SR-CI-08; ADR-CI-12.
 */
class WorkflowActionsPinnedTest {

    private static final Pattern FULL_SHA_PIN = Pattern.compile(".*@[0-9a-f]{40}(\\s*#.*)?$");

    @Test
    void everyThirdPartyUsesReferenceIsPinnedToAFullCommitSha() {
        List<WorkflowFile> all = WorkflowInventory.loadAllWorkflows();
        SoftAssertions softly = new SoftAssertions();

        for (WorkflowFile wf : all) {
            for (String uses : WorkflowInventory.usesReferences(wf)) {
                if (uses.startsWith("./")) {
                    continue; // local composite action or local reusable-workflow call -- exempt
                }
                softly.assertThat(uses)
                        .as("%s references '%s', which must be pinned to a full 40-hex commit "
                                + "SHA (not a mutable tag/branch) -- SR-CI-08/ADR-CI-12, "
                                + "dependency-vetting skill", wf.relativePath(), uses)
                        .matches(FULL_SHA_PIN);
            }
        }

        softly.assertAll();
    }
}
