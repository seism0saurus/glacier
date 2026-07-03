package de.seism0saurus.glacier.ci;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gate 5.7 (WorkflowCachePoisoningIsolation): a fork PR (or any {@code pull_request}-triggered
 * run) must never read from, or write into, the same {@code actions/cache} namespace that
 * {@code main}-branch builds use.
 *
 * <p>GitHub Actions caches are keyed and scoped such that a PR run can populate a cache entry
 * that a later {@code main} build then restores (cache-poisoning: e.g. a malicious PR primes a
 * Maven/npm cache entry with a tampered dependency, then a maintainer's subsequent trusted
 * build on {@code main} silently restores it). ADR-CI-09 requires every cache key/restore-key
 * used from a {@code pull_request}-triggered path to carry a distinguishing {@code pull}/{@code pr}
 * prefix and to never reference the {@code main} cache family, whether the cache step lives
 * directly in the PR-triggered file or the PR-triggered file passes a prefix value into a
 * reusable-workflow call that performs the caching.
 *
 * <h2>Rules enforced (scope: files whose {@code on:} contains {@code pull_request})</h2>
 * <ol>
 *   <li>Every {@code actions/cache}/{@code actions/cache/restore} step's {@code key:} and
 *       {@code restore-keys:} contain a {@code pull} or {@code pr} token and never a
 *       {@code -main-}/{@code -main} token.</li>
 *   <li>Every {@code with:} input passed from a PR-triggered workflow to a called reusable
 *       workflow, whose input name suggests a cache-prefix (contains "cache" or "prefix"),
 *       carries a {@code pull}/{@code pr} value and never {@code main} — covering the
 *       architecture where caching logic is centralized in the {@code _build}/{@code _e2e}
 *       reusable core and parameterized by the caller.</li>
 * </ol>
 *
 * <h2>Deliberate RED / GREEN state</h2>
 * {@code pull-request.yml} already prefixes every cache key with {@code -pull-} today (e.g.
 * {@code ${{ runner.os }}-m2-pull-...}) and never references {@code -main-} — rule 1 is
 * expected GREEN against the current file. If Lane C hoists caching into {@code _build.yml}/
 * {@code _e2e.yml} and has {@code pull-request.yml} call them, rule 1 becomes vacuous for that
 * file (no cache steps left to check directly) and rule 2 becomes the operative check instead
 * — Lane C must pass a {@code pull}/{@code pr}-prefixed value through a {@code with:} input
 * whose name contains "cache" or "prefix" for this gate to still exercise the invariant; see
 * the hand-off note in the implementation report.
 *
 * <p>Mode applicability: mode-agnostic.
 *
 * <p>OWASP: A08:2021 — Software and Data Integrity Failures (supply-chain cache poisoning).
 * Security requirement: SR-CI-12; ADR-CI-09.
 */
class WorkflowCachePoisoningIsolationTest {

    @Test
    void prTriggeredWorkflowsIsolateCacheKeysFromTheMainFamily() {
        List<WorkflowFile> all = WorkflowInventory.loadAllWorkflows();
        SoftAssertions softly = new SoftAssertions();
        boolean anyPrTriggeredFound = false;

        for (WorkflowFile wf : all) {
            if (!WorkflowInventory.triggersOf(wf).containsKey("pull_request")) {
                continue;
            }
            anyPrTriggeredFound = true;

            for (WorkflowStep step : wf.allSteps()) {
                if (step.hasUses() && step.usesContains("actions/cache")) {
                    checkCacheField(softly, wf, step.name(), "key", step.with().get("key"));
                    checkCacheField(softly, wf, step.name(), "restore-keys", step.with().get("restore-keys"));
                }
            }

            for (WorkflowJob job : wf.jobs().values()) {
                if (!job.callsReusableWorkflow()) {
                    continue;
                }
                for (Map.Entry<String, Object> entry : job.with().entrySet()) {
                    String inputName = entry.getKey().toLowerCase(Locale.ROOT);
                    if (inputName.contains("cache") || inputName.contains("prefix")) {
                        checkCacheField(softly, wf, job.id(), entry.getKey(), entry.getValue());
                    }
                }
            }
        }

        softly.assertThat(anyPrTriggeredFound)
                .as("expected at least one pull_request-triggered workflow to exist "
                        + "(pull-request.yml) -- an empty scope would make this gate vacuously green")
                .isTrue();

        softly.assertAll();
    }

    private static void checkCacheField(SoftAssertions softly, WorkflowFile wf, String stepOrJobId,
                                          String fieldName, Object value) {
        if (value == null) {
            return;
        }
        String text = String.valueOf(value).toLowerCase(Locale.ROOT);
        boolean hasPullOrPrToken = text.contains("pull") || text.contains("-pr-") || text.endsWith("-pr")
                || text.contains("pr-");
        boolean referencesMainFamily = text.contains("-main-") || text.contains("-main\n") || text.endsWith("-main");

        softly.assertThat(hasPullOrPrToken)
                .as("%s '%s' field '%s' must carry a pull/pr prefix token -- found '%s' "
                        + "(SR-CI-12: cache-poisoning isolation for PR-triggered runs)",
                        wf.relativePath(), stepOrJobId, fieldName, value)
                .isTrue();
        softly.assertThat(referencesMainFamily)
                .as("%s '%s' field '%s' must never reference the main cache family -- found '%s'",
                        wf.relativePath(), stepOrJobId, fieldName, value)
                .isFalse();
    }
}
