package org.praxisplatform.rules.jsonlogic.model;

/** Stable error taxonomy shared by validation and evaluation APIs. */
public enum JsonLogicIssueCode {
    /** Expression shape is not valid JSON Logic for the requested operation. */
    RULE_SHAPE_INVALID,
    /** Expression references an operator absent from the active registry. */
    RULE_OPERATOR_UNKNOWN,
    /** Operator registration conflicts with an existing or reserved definition. */
    RULE_OPERATOR_CONFLICT,
    /** Operator received fewer or more arguments than its descriptor permits. */
    RULE_ARITY_INVALID,
    /** Variable or object path is malformed. */
    RULE_PATH_INVALID,
    /** Implicit path resolution has more than one possible root. */
    RULE_CONTEXT_AMBIGUOUS,
    /** Rule addresses a root outside the caller-declared context. */
    RULE_ROOT_UNKNOWN,
    /** Operator argument cannot be interpreted as the required domain type. */
    RULE_ARGUMENT_TYPE_INVALID,
    /** Temporal value or frozen clock context is invalid. */
    RULE_TEMPORAL_INPUT_INVALID,
    /** Regular expression falls outside the engine's bounded safe subset. */
    RULE_REGEX_INVALID,
    /** Expression or produced value exceeds a configured resource limit. */
    RULE_LIMIT_EXCEEDED
}
