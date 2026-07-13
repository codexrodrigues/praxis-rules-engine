package org.praxisplatform.rules.jsonlogic.model;

/**
 * Stable, machine-readable problem found while validating a rule.
 * @param code platform issue code suitable for programmatic handling
 * @param message human-readable diagnostic
 * @param path JSON-style path of the invalid expression node
 * @param operator operator associated with the problem, when applicable
 */
public record JsonLogicValidationIssue(
    JsonLogicIssueCode code,
    String message,
    String path,
    String operator
) {
}
