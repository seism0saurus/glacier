package de.seism0saurus.glacier.ci;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Value object for one {@code .github/workflows/*.yml} file, parsed into a traversable
 * object model.
 *
 * <p>Part of the {@link WorkflowInventory} structural-gate harness (ADR-CI-07). See
 * {@link WorkflowInventory} for the rationale (shared SnakeYAML traversal instead of
 * per-test raw-text grep) and for the entry points gate tests are expected to use.
 *
 * <p><b>The {@code on:} key quirk</b>: SnakeYAML 2.x parses YAML 1.1, under which the
 * unquoted scalar {@code on} is a boolean literal, not the string {@code "on"}. A raw
 * {@code Map} parse therefore stores the workflow's trigger block under the key
 * {@link Boolean#TRUE}, not {@code "on"} — a well-known gotcha that has silently broken
 * naive greps/casts before (see {@code WorkflowYamlInventoryTest} history). {@link #triggers()}
 * resolves this once, here, so no other test in the suite needs to know about it.
 *
 * @param path        the resolved filesystem path of the workflow file
 * @param relativePath the path relative to the repository root, e.g.
 *                    {@code ".github/workflows/security.yml"} — stable across CI/local runs
 * @param fileName    the bare file name, e.g. {@code "security.yml"}
 * @param name        the workflow's top-level {@code name:}, or {@code null} if absent
 * @param raw         the full unmodified root map, for anything not modeled below
 * @param triggers    the normalized {@code on:} block: trigger name (e.g. {@code "push"},
 *                    {@code "pull_request"}, {@code "schedule"}, {@code "workflow_dispatch"},
 *                    {@code "workflow_call"}, {@code "workflow_run"}) mapped to its raw
 *                    trigger configuration ({@code null} if the trigger has no configuration,
 *                    e.g. a bare {@code on: [push]} entry)
 * @param permissions the raw top-level {@code permissions:} value — a {@link Map} of scope
 *                    to level, or a shorthand {@link String} ({@code "read-all"} /
 *                    {@code "write-all"} / {@code "{}"} spelled as an empty map); {@code null}
 *                    if the workflow declares no top-level permissions at all (ADR-CI-03: a
 *                    gate concern, not a parsing concern — this type only reports what YAML
 *                    contains)
 * @param jobs        the workflow's {@code jobs:} map, insertion-ordered as written in the file
 */
public record WorkflowFile(
        Path path,
        String relativePath,
        String fileName,
        String name,
        Map<String, Object> raw,
        Map<String, Object> triggers,
        Object permissions,
        Map<String, WorkflowJob> jobs
) {

    /**
     * Parses the workflow file at {@code path} into a {@link WorkflowFile}.
     *
     * @param path         the file to read
     * @param relativePath the path to record as {@link #relativePath()} (repo-root relative,
     *                     stable across environments)
     * @return the parsed workflow
     * @throws IOException if the file cannot be read
     */
    @SuppressWarnings("unchecked")
    public static WorkflowFile load(Path path, String relativePath) throws IOException {
        Map<String, Object> root;
        try (InputStream in = Files.newInputStream(path)) {
            Object parsed = new Yaml().load(in);
            root = parsed instanceof Map ? (Map<String, Object>) parsed : Map.of();
        }

        Map<String, WorkflowJob> jobs = new LinkedHashMap<>();
        Object jobsValue = root.get("jobs");
        if (jobsValue instanceof Map<?, ?> jobsMap) {
            for (Map.Entry<?, ?> entry : jobsMap.entrySet()) {
                String jobId = String.valueOf(entry.getKey());
                if (entry.getValue() instanceof Map<?, ?> jobBody) {
                    jobs.put(jobId, WorkflowJob.from(jobId, (Map<String, Object>) jobBody));
                }
            }
        }

        return new WorkflowFile(
                path,
                relativePath,
                path.getFileName().toString(),
                root.get("name") instanceof String s ? s : null,
                Collections.unmodifiableMap(root),
                normalizeTriggers(extractOnBlock(root)),
                root.get("permissions"),
                Collections.unmodifiableMap(jobs)
        );
    }

    /**
     * Resolves the raw {@code on:} block from the parsed root map, accounting for the
     * SnakeYAML YAML-1.1 quirk where the unquoted key {@code on} parses as {@link Boolean#TRUE}
     * rather than the string {@code "on"}. Both spellings are checked so this keeps working
     * regardless of SnakeYAML version/configuration.
     */
    private static Object extractOnBlock(Map<String, Object> root) {
        if (root.containsKey(Boolean.TRUE)) {
            return root.get(Boolean.TRUE);
        }
        return root.get("on");
    }

    /**
     * Normalizes the raw {@code on:} value into a {@code Map<String, Object>} keyed by
     * trigger name, regardless of whether the YAML author wrote a map
     * ({@code on: {push: ..., pull_request: ...}}), a list ({@code on: [push, pull_request]}),
     * or a bare scalar ({@code on: push}).
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> normalizeTriggers(Object onValue) {
        if (onValue == null) {
            return Map.of();
        }
        if (onValue instanceof Map<?, ?> m) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                result.put(String.valueOf(e.getKey()), e.getValue());
            }
            return Collections.unmodifiableMap(result);
        }
        if (onValue instanceof List<?> l) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Object item : l) {
                result.put(String.valueOf(item), null);
            }
            return Collections.unmodifiableMap(result);
        }
        // Bare scalar, e.g. `on: push`.
        return Map.of(String.valueOf(onValue), null);
    }

    /** Looks up a job by id. */
    public Optional<WorkflowJob> job(String id) {
        return Optional.ofNullable(jobs.get(id));
    }

    /** Every step across every job, in file order (job order, then step order within each job). */
    public List<WorkflowStep> allSteps() {
        List<WorkflowStep> result = new ArrayList<>();
        for (WorkflowJob job : jobs.values()) {
            result.addAll(job.steps());
        }
        return Collections.unmodifiableList(result);
    }

    /** The subset of {@link #allSteps()} that run a shell script (have a non-null {@code run:}). */
    public List<WorkflowStep> runSteps() {
        return allSteps().stream().filter(WorkflowStep::hasRun).toList();
    }

    /**
     * Every {@code uses:} reference in this file — both step-level (action calls) and
     * job-level ({@code jobs.<id>.uses:}, i.e. reusable-workflow calls) — in file order.
     */
    public List<String> allUsesReferences() {
        List<String> result = new ArrayList<>();
        for (WorkflowJob job : jobs.values()) {
            if (job.calledWorkflow() != null) {
                result.add(job.calledWorkflow());
            }
            result.addAll(job.stepUsesReferences());
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * True if this file follows the project's {@code _}-prefix naming convention for the
     * secret-free reusable core ({@code _build.yml}, {@code _e2e.yml}, {@code _security-dast.yml}
     * — ADR-CI-01/02).
     */
    public boolean isReusableCoreWorkflow() {
        return fileName.startsWith("_");
    }
}
