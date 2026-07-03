package de.seism0saurus.glacier.ci;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gate 5.11 (WorkflowNoSelfHostedRunner): every job runs on a GitHub-hosted runner
 * ({@code ubuntu-*}/{@code macos-*}/{@code windows-*}), never {@code self-hosted} (including
 * Actions Runner Controller / ARC label sets, which typically look like
 * {@code [self-hosted, linux, x64]} or a custom label such as {@code arc-runner-set}).
 *
 * <p>A self-hosted runner processing a fork PR's workflow run executes attacker-influenced
 * code on infrastructure the repository owner controls and that often has network access
 * beyond what an ephemeral GitHub-hosted VM has (e.g. reaching internal services) — a
 * materially larger blast radius than a throwaway GitHub-hosted VM torn down after the job.
 * Glacier has no self-hosted runner today and this repository's threat model (public
 * contributions, deploy secrets reachable only from one privileged workflow) does not call
 * for one; this gate keeps it that way.
 *
 * <h2>The codeql.yml ternary exception</h2>
 * {@code codeql.yml}'s {@code analyze} job uses a matrix-conditional runner expression,
 * {@code runs-on: ${{ (matrix.language == 'swift' && 'macos-latest') || 'ubuntu-latest' }}}
 * (a template inherited from GitHub's default CodeQL workflow scaffold, even though this repo's
 * matrix never actually includes {@code swift}). Both quoted literals inside that expression —
 * {@code 'macos-latest'} and {@code 'ubuntu-latest'} — must independently satisfy the
 * GitHub-hosted prefix check; this gate extracts every single-quoted literal from a templated
 * {@code runs-on:} expression and validates each one, so the ternary is tolerated on both
 * branches without special-casing the file by name.
 *
 * <h2>State</h2>
 * Expected GREEN against every workflow that exists today. Regression tripwire for future
 * edits (including Lane C's rebuild).
 *
 * <p>Mode applicability: mode-agnostic.
 *
 * <p>OWASP: A05:2021 — Security Misconfiguration. Security requirement: SR-CI-09; ADR-CI-13.
 */
class WorkflowNoSelfHostedRunnerTest {

    private static final Pattern GITHUB_HOSTED_PREFIX = Pattern.compile("^(ubuntu|macos|windows)-.*");
    private static final Pattern QUOTED_LITERAL = Pattern.compile("'([^']+)'");

    @Test
    void everyJobRunsOnAGithubHostedRunner() {
        List<WorkflowFile> all = WorkflowInventory.loadAllWorkflows();
        SoftAssertions softly = new SoftAssertions();

        for (WorkflowFile wf : all) {
            for (WorkflowJob job : WorkflowInventory.jobsOf(wf).values()) {
                for (String runsOnEntry : job.runsOnAsList()) {
                    List<String> candidates = literalsIn(runsOnEntry);
                    softly.assertThat(candidates)
                            .as("%s job '%s' runs-on: '%s' must contain at least one resolvable "
                                    + "runner literal", wf.relativePath(), job.id(), runsOnEntry)
                            .isNotEmpty();
                    for (String candidate : candidates) {
                        String lower = candidate.toLowerCase(Locale.ROOT);
                        softly.assertThat(GITHUB_HOSTED_PREFIX.matcher(lower).matches())
                                .as("%s job '%s' runs-on candidate '%s' (from '%s') must be a "
                                        + "GitHub-hosted runner (ubuntu-*/macos-*/windows-*) -- "
                                        + "self-hosted/ARC runners are forbidden repo-wide "
                                        + "(SR-CI-09/ADR-CI-13)",
                                        wf.relativePath(), job.id(), candidate, runsOnEntry)
                                .isTrue();
                    }
                }
            }
        }

        softly.assertAll();
    }

    /**
     * Meta-test proving {@link #literalsIn(String)} correctly tolerates the codeql.yml ternary
     * shape (both branches accepted) while still excluding the {@code ==} comparison operand
     * -- a regex subtly wrong in either direction would either silently accept a self-hosted
     * ternary branch or falsely flag the matrix's own comparison value as a runner name.
     */
    @Test
    void literalExtractionTreatsTernaryResultsButNotComparisonOperandsAsRunnerCandidates() {
        String ternary = "${{ (matrix.language == 'swift' && 'macos-latest') || 'ubuntu-latest' }}";
        List<String> literals = literalsIn(ternary);

        assertThat(literals)
                .as("ternary result branches must be extracted as runner candidates")
                .contains("macos-latest", "ubuntu-latest");
        assertThat(literals)
                .as("the == comparison operand ('swift', a matrix value, not a runner name) "
                        + "must NOT be extracted as a runner candidate")
                .doesNotContain("swift");
    }

    /**
     * Extracts the set of literal runner names to validate from a single {@code runs-on:}
     * entry: if the entry is a plain literal (e.g. {@code "ubuntu-latest"}), that literal
     * itself; if it is a templated expression (contains {@code ${{"}), every single-quoted
     * string literal found inside it that is a ternary *result* (tolerating the codeql.yml-style
     * {@code (matrix.language == 'swift' && 'macos-latest') || 'ubuntu-latest'} ternary).
     *
     * <p>Comparison operands (the right-hand side of {@code ==}, e.g. {@code 'swift'} above)
     * are deliberately excluded — they are matrix values being tested, not runner names the
     * job could actually run on, and must not be validated as if they were.
     */
    private static List<String> literalsIn(String runsOnEntry) {
        if (!runsOnEntry.contains("${{")) {
            return List.of(runsOnEntry);
        }
        String withoutComparisonOperands = runsOnEntry.replaceAll("==\\s*'[^']*'", "");
        List<String> literals = new ArrayList<>();
        Matcher m = QUOTED_LITERAL.matcher(withoutComparisonOperands);
        while (m.find()) {
            literals.add(m.group(1));
        }
        return literals;
    }
}
