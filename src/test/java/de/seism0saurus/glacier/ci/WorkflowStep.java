package de.seism0saurus.glacier.ci;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Value object for a single GitHub Actions workflow step, as parsed from a {@code steps:}
 * list entry by SnakeYAML.
 *
 * <p>Part of the {@link WorkflowInventory} structural-gate harness (ADR-CI-07). This type
 * exists so that CI security-gate tests traverse a typed object model instead of grepping
 * raw YAML text or re-implementing ad-hoc {@code Map} casts in every test class — the same
 * motivation documented on {@link WorkflowInventory}.
 *
 * <p>A step is either a {@code run:} step (shell script, {@link #run()} non-null) or a
 * {@code uses:} step (calls an action, {@link #uses()} non-null). GitHub Actions forbids
 * specifying both on the same step, but this type does not enforce that — it is a read
 * model over whatever YAML exists, including malformed input, so gates can assert the
 * absence of invalid shapes themselves.
 *
 * @param raw               the unmodified step map, for access to fields not modeled below
 *                           (e.g. {@code timeout-minutes}, custom {@code id} conventions)
 * @param id                the step's {@code id:}, or {@code null} if absent
 * @param name              the step's {@code name:}, or {@code null} if absent
 * @param uses              the step's {@code uses:} action/workflow reference, or {@code null}
 *                          if this is a {@code run:} step
 * @param run               the step's {@code run:} shell script, or {@code null} if this is a
 *                          {@code uses:} step
 * @param shell             the step's explicit {@code shell:} override, or {@code null}
 * @param env               the step-level {@code env:} map; empty (never {@code null}) if absent
 * @param with              the step-level {@code with:} map (action inputs); empty if absent
 * @param workingDirectory  the step's {@code working-directory:}, or {@code null} if absent
 * @param ifCondition       the raw {@code if:} value (usually a {@link String} expression,
 *                          occasionally a {@link Boolean}); {@code null} if absent
 * @param continueOnError   the raw {@code continue-on-error:} value (usually a
 *                          {@link Boolean}); {@code null} if absent, which GitHub Actions
 *                          treats as {@code false}
 */
public record WorkflowStep(
        Map<String, Object> raw,
        String id,
        String name,
        String uses,
        String run,
        String shell,
        Map<String, Object> env,
        Map<String, Object> with,
        String workingDirectory,
        Object ifCondition,
        Object continueOnError
) {

    /**
     * Builds a {@link WorkflowStep} from a single SnakeYAML-parsed step map.
     *
     * @param stepMap one entry of a job's {@code steps:} list
     * @return the typed step
     */
    @SuppressWarnings("unchecked")
    public static WorkflowStep from(Map<String, Object> stepMap) {
        return new WorkflowStep(
                Collections.unmodifiableMap(new LinkedHashMap<>(stepMap)),
                asString(stepMap.get("id")),
                asString(stepMap.get("name")),
                asString(stepMap.get("uses")),
                asString(stepMap.get("run")),
                asString(stepMap.get("shell")),
                asMap(stepMap.get("env")),
                asMap(stepMap.get("with")),
                asString(stepMap.get("working-directory")),
                stepMap.get("if"),
                stepMap.get("continue-on-error")
        );
    }

    /** True if this step runs a shell script (has a non-null {@code run:}). */
    public boolean hasRun() {
        return run != null;
    }

    /** True if this step invokes an action or reusable workflow (has a non-null {@code uses:}). */
    public boolean hasUses() {
        return uses != null;
    }

    /** True if {@link #run()} is non-null and contains {@code needle} as a substring. */
    public boolean runContains(String needle) {
        return run != null && run.contains(needle);
    }

    /** True if {@link #uses()} is non-null and contains {@code needle} as a substring. */
    public boolean usesContains(String needle) {
        return uses != null && uses.contains(needle);
    }

    /** True if {@link #uses()} references a local composite action or reusable workflow ({@code ./}-prefixed). */
    public boolean isLocalReference() {
        return uses != null && uses.startsWith("./");
    }

    private static String asString(Object value) {
        return value instanceof String s ? s : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> m) {
            return Collections.unmodifiableMap((Map<String, Object>) m);
        }
        return Map.of();
    }
}
