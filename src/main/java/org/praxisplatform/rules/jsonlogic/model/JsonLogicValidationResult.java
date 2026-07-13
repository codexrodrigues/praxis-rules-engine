package org.praxisplatform.rules.jsonlogic.model;

import java.util.List;

/**
 * Complete outcome of validating a rule without executing it.
 * @param valid whether no validation issue was found
 * @param issues immutable diagnostics in deterministic traversal order
 */
public record JsonLogicValidationResult(
    boolean valid,
    List<JsonLogicValidationIssue> issues
) {
    /** Copies diagnostics so callers cannot mutate a completed validation outcome. */
    public JsonLogicValidationResult {
        issues = List.copyOf(issues);
    }
}
