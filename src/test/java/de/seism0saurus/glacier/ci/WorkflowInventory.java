package de.seism0saurus.glacier.ci;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Shared SnakeYAML-based structural harness for the {@code .github/workflows/*.yml}
 * security-gate test suite (ADR-CI-07: "CI invariants as SnakeYAML unit-test gates").
 *
 * <h2>Why this exists</h2>
 * The secure CI-pipeline rebuild (see
 * {@code docs/decisions/2026-07-03-planning-secure-ci-pipeline.md}) encodes ~12 structural
 * security invariants (trust-tier topology, least-privilege {@code permissions:}, no
 * {@code pull_request_target}, SHA-pinned {@code uses:}, no self-hosted runners, script-injection
 * safety, cache-family isolation, SARIF fork-tolerance, etc.) as executable tests rather than
 * as prose review checklists. Every one of those gates needs to walk the same YAML object
 * tree — jobs, steps, triggers, permissions. Without a shared harness, each gate test
 * re-implements {@code Map} casts and re-discovers the same SnakeYAML gotchas (most notably:
 * the unquoted {@code on:} key parses as {@link Boolean#TRUE} under YAML 1.1, not the string
 * {@code "on"} — see {@link WorkflowFile}). Re-implementing that per test class is exactly the
 * "raw grep where structure counts" anti-pattern this harness exists to avoid: a gate that
 * silently stops matching after a harmless YAML reformat is a false sense of security, worse
 * than no gate.
 *
 * <p>This class is the single entry point. All CI structural-gate tests — this module's own
 * {@link WorkflowYamlInventoryTest} and {@link BigboneChecksumPinTest}, and the security-gate
 * classes added alongside them (script-injection, SHA-pin, no-self-hosted-runner,
 * pwn-request/secret-isolation, permissions-minimization, deploy-trigger, cache-poisoning,
 * SARIF fork-tolerance, environment/chain gates) — are expected to load workflow data through
 * {@link #loadAllWorkflows()} / {@link #loadWorkflow(String)} rather than opening
 * {@code .github/workflows/*.yml} themselves. A gate that hand-rolls its own file list risks
 * silently excluding a workflow the architecture requires it to cover (see
 * {@code WorkflowYamlInventoryTest#everyAdrGovernedWorkflowFileExistsAndParses} for the
 * completeness check that catches this).
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * List<WorkflowFile> all = WorkflowInventory.loadAllWorkflows();
 * WorkflowFile pr = WorkflowInventory.loadWorkflow("pull-request.yml");
 *
 * Map<String, Object> triggers = WorkflowInventory.triggersOf(pr);
 * assertThat(triggers).containsKey("pull_request");
 *
 * for (WorkflowJob job : WorkflowInventory.jobsOf(pr).values()) {
 *     Object permissions = WorkflowInventory.permissionsOf(job);
 *     // ...
 * }
 *
 * for (WorkflowStep step : WorkflowInventory.runSteps(pr)) {
 *     assertThat(step.run()).doesNotContain("${{ github.event.");
 * }
 *
 * for (String uses : WorkflowInventory.usesReferences(pr)) {
 *     assertThat(uses).matches(".*@[0-9a-f]{40}(\\s*#.*)?$");
 * }
 * }</pre>
 *
 * <h2>Mode applicability</h2>
 * Mode-agnostic — CI workflow correctness is independent of Glacier's operational mode
 * (live / fallback / killswitch / insecure).
 *
 * @see <a href="https://docs.github.com/en/actions/security-guides/security-hardening-for-github-actions">
 *      GitHub Actions security hardening guide</a>
 */
public final class WorkflowInventory {

    /** Directory containing all GitHub Actions workflow definitions, relative to the repo root. */
    public static final Path WORKFLOWS_DIR = Paths.get(".github/workflows");

    private static final Pattern SECRET_REFERENCE =
            Pattern.compile("secrets\\.([A-Za-z0-9_]+)");

    private WorkflowInventory() {
        // Static utility class -- not instantiable.
    }

    /**
     * Loads every {@code .yml}/{@code .yaml} file directly under {@link #WORKFLOWS_DIR},
     * sorted by file name for deterministic iteration order.
     *
     * @return all currently-present workflow files, parsed
     * @throws UncheckedIOException if the directory or a file cannot be read
     */
    public static List<WorkflowFile> loadAllWorkflows() {
        try (Stream<Path> entries = Files.list(WORKFLOWS_DIR)) {
            List<Path> files = entries
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return n.endsWith(".yml") || n.endsWith(".yaml");
                    })
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();

            List<WorkflowFile> result = new ArrayList<>();
            for (Path file : files) {
                String relativePath = WORKFLOWS_DIR.resolve(file.getFileName()).toString()
                        .replace('\\', '/');
                result.add(WorkflowFile.load(file, relativePath));
            }
            return result;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to list " + WORKFLOWS_DIR, e);
        }
    }

    /**
     * Loads a single workflow file by name.
     *
     * @param fileNameOrRelativePath either a bare file name (e.g. {@code "security.yml"}) or
     *                               a path relative to the repo root
     *                               (e.g. {@code ".github/workflows/security.yml"})
     * @return the parsed workflow
     * @throws UncheckedIOException if the file does not exist or cannot be read — callers in
     *                              a RED-by-design test (a workflow Lane C has not created yet)
     *                              should assert {@link #WORKFLOWS_DIR} listing or
     *                              {@code Files.exists(...)} first for a readable failure
     *                              message instead of catching this
     */
    public static WorkflowFile loadWorkflow(String fileNameOrRelativePath) {
        Path candidate = Paths.get(fileNameOrRelativePath);
        Path resolved = candidate.getParent() != null
                ? candidate
                : WORKFLOWS_DIR.resolve(fileNameOrRelativePath);
        String relativePath = resolved.toString().replace('\\', '/');
        try {
            return WorkflowFile.load(resolved, relativePath);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load workflow " + relativePath, e);
        }
    }

    /** The normalized {@code on:} trigger block of {@code workflow} (see {@link WorkflowFile#triggers()}). */
    public static Map<String, Object> triggersOf(WorkflowFile workflow) {
        return workflow.triggers();
    }

    /** The {@code jobs:} map of {@code workflow}, insertion-ordered as written in the file. */
    public static Map<String, WorkflowJob> jobsOf(WorkflowFile workflow) {
        return workflow.jobs();
    }

    /** Every step across every job of {@code workflow} that runs a shell script ({@code run:}). */
    public static List<WorkflowStep> runSteps(WorkflowFile workflow) {
        return workflow.runSteps();
    }

    /** Every step across every job of {@code workflow}, regardless of shape. */
    public static List<WorkflowStep> allSteps(WorkflowFile workflow) {
        return workflow.allSteps();
    }

    /**
     * The raw {@code permissions:} value of {@code job} — {@code null} means the job declares
     * no permissions of its own and inherits the workflow's top-level {@code permissions:}
     * (or the repository/organization default token scope, if that is also absent).
     */
    public static Object permissionsOf(WorkflowJob job) {
        return job.permissions();
    }

    /** The raw top-level {@code permissions:} value of {@code workflow}; {@code null} if absent. */
    public static Object topLevelPermissionsOf(WorkflowFile workflow) {
        return workflow.permissions();
    }

    /**
     * Every {@code uses:} reference in {@code workflow} — step-level action calls and
     * job-level reusable-workflow calls alike, in file order.
     */
    public static List<String> usesReferences(WorkflowFile workflow) {
        return workflow.allUsesReferences();
    }

    /**
     * Recursively scans the entire parsed YAML tree of {@code workflow} (triggers, job
     * bodies, step {@code env:}/{@code with:}/{@code run:} — everywhere) for
     * {@code secrets.<NAME>} references and returns the distinct secret names found, in
     * first-encountered order.
     *
     * <p>Intended for gates that must prove a secret-free path stays secret-free (e.g. "no
     * non-{@code GITHUB_TOKEN} secret reachable from an untrusted trigger") without having to
     * separately special-case every place a secret expression could appear
     * ({@code ${{ secrets.X }}} in {@code run:}, {@code env:}, {@code with:}, or even
     * {@code if:}).
     */
    public static Set<String> secretReferences(WorkflowFile workflow) {
        Set<String> found = new LinkedHashSet<>();
        collectSecretReferences(workflow.raw(), found);
        return found;
    }

    private static void collectSecretReferences(Object node, Set<String> found) {
        if (node instanceof Map<?, ?> map) {
            for (Object value : map.values()) {
                collectSecretReferences(value, found);
            }
        } else if (node instanceof List<?> list) {
            for (Object value : list) {
                collectSecretReferences(value, found);
            }
        } else if (node instanceof String s) {
            Matcher matcher = SECRET_REFERENCE.matcher(s);
            while (matcher.find()) {
                found.add(matcher.group(1));
            }
        }
    }
}
