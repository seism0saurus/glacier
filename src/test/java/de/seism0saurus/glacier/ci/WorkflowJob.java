package de.seism0saurus.glacier.ci;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Value object for a single job entry under a workflow's {@code jobs:} map.
 *
 * <p>Part of the {@link WorkflowInventory} structural-gate harness (ADR-CI-07). A job is
 * either a <b>step-running job</b> (has {@code runs-on:} + {@code steps:}) or a
 * <b>reusable-workflow-calling job</b> (has {@code uses:} pointing at a {@code workflow_call}
 * workflow, typically {@code ./.github/workflows/_*.yml}, optionally with {@code with:} inputs
 * and {@code secrets:}). This type models both shapes so callers do not need to branch on
 * job structure themselves.
 *
 * @param id                 the job's map key (e.g. {@code "frontend-audit"})
 * @param raw                the unmodified job map, for fields not modeled below
 * @param runsOn             the raw {@code runs-on:} value — a {@link String}, a
 *                           {@link List} of strings, or (for matrix expressions) another
 *                           shape; use {@link #runsOnAsList()} for a normalized view
 * @param permissions        the raw {@code permissions:} value — a {@link Map} of scope to
 *                           level, or the shorthand {@link String}s {@code "read-all"} /
 *                           {@code "write-all"}; {@code null} if the job does not declare its
 *                           own permissions (in which case it inherits the workflow's
 *                           top-level {@code permissions:}, or the default token scope if that
 *                           is also absent — ADR-CI-02/03)
 * @param needs              the job's {@code needs:} dependencies, normalized to a list
 *                           (empty if absent, singleton if a bare string, verbatim if a list)
 * @param env                the job-level {@code env:} map; empty if absent
 * @param ifCondition        the raw {@code if:} value; {@code null} if absent
 * @param environment        the raw {@code environment:} value — a {@link String} name or a
 *                           {@link Map} with {@code name}/{@code url}; {@code null} if the job
 *                           is not gated behind a GitHub Environment (ADR-CI-16)
 * @param steps              the job's steps, in file order; empty if this job calls a
 *                           reusable workflow instead ({@link #callsReusableWorkflow()})
 * @param calledWorkflow     the job-level {@code uses:} reusable-workflow reference, or
 *                           {@code null} if this job runs its own steps
 * @param with               job-level {@code with:} inputs passed to a called reusable
 *                           workflow; empty if absent
 * @param secrets            the raw job-level {@code secrets:} value passed to a called
 *                           reusable workflow — typically the string {@code "inherit"} or an
 *                           explicit map; {@code null} if absent (ADR-CI-02 forbids
 *                           {@code secrets: inherit} into the secret-free reusable core)
 */
public record WorkflowJob(
        String id,
        Map<String, Object> raw,
        Object runsOn,
        Object permissions,
        List<String> needs,
        Map<String, Object> env,
        Object ifCondition,
        Object environment,
        List<WorkflowStep> steps,
        String calledWorkflow,
        Map<String, Object> with,
        Object secrets
) {

    /**
     * Builds a {@link WorkflowJob} from a single SnakeYAML-parsed job map.
     *
     * @param id     the job's key under {@code jobs:}
     * @param jobMap the job's YAML body
     * @return the typed job
     */
    @SuppressWarnings("unchecked")
    public static WorkflowJob from(String id, Map<String, Object> jobMap) {
        List<WorkflowStep> steps = new ArrayList<>();
        Object stepsValue = jobMap.get("steps");
        if (stepsValue instanceof List<?> list) {
            for (Object entry : list) {
                if (entry instanceof Map<?, ?> m) {
                    steps.add(WorkflowStep.from((Map<String, Object>) m));
                }
            }
        }

        return new WorkflowJob(
                id,
                Collections.unmodifiableMap(new LinkedHashMap<>(jobMap)),
                jobMap.get("runs-on"),
                jobMap.get("permissions"),
                normalizeNeeds(jobMap.get("needs")),
                asMap(jobMap.get("env")),
                jobMap.get("if"),
                jobMap.get("environment"),
                Collections.unmodifiableList(steps),
                jobMap.get("uses") instanceof String s ? s : null,
                asMap(jobMap.get("with")),
                jobMap.get("secrets")
        );
    }

    /** True if this job calls a reusable workflow ({@code jobs.<id>.uses:}) instead of running its own steps. */
    public boolean callsReusableWorkflow() {
        return calledWorkflow != null;
    }

    /** The subset of {@link #steps()} that run a shell script (have a non-null {@code run:}). */
    public List<WorkflowStep> runSteps() {
        return steps.stream().filter(WorkflowStep::hasRun).toList();
    }

    /** The {@code uses:} references of every step in this job that invokes an action or workflow. */
    public List<String> stepUsesReferences() {
        return steps.stream().map(WorkflowStep::uses).filter(java.util.Objects::nonNull).toList();
    }

    /**
     * Normalized view of {@link #runsOn()}: a single string becomes a singleton list, a list
     * is returned unchanged (as strings), and any other shape (e.g. a matrix expression map)
     * falls back to its string representation so callers always get a non-null list.
     */
    public List<String> runsOnAsList() {
        if (runsOn == null) {
            return List.of();
        }
        if (runsOn instanceof String s) {
            return List.of(s);
        }
        if (runsOn instanceof List<?> l) {
            return l.stream().map(String::valueOf).toList();
        }
        return List.of(String.valueOf(runsOn));
    }

    private static List<String> normalizeNeeds(Object needsValue) {
        if (needsValue == null) {
            return List.of();
        }
        if (needsValue instanceof String s) {
            return List.of(s);
        }
        if (needsValue instanceof List<?> l) {
            return l.stream().map(String::valueOf).toList();
        }
        return List.of(String.valueOf(needsValue));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> m) {
            return Collections.unmodifiableMap((Map<String, Object>) m);
        }
        return Map.of();
    }
}
