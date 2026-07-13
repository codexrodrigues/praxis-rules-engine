package org.praxisplatform.rules.jsonlogic.model;

/**
 * Result of evaluating a rule once.
 * @param value public value produced by the expression
 * @param truthy canonical JSON Logic truthiness of that value
 */
public record JsonLogicEvaluationResult(
    Object value,
    boolean truthy
) {
}
