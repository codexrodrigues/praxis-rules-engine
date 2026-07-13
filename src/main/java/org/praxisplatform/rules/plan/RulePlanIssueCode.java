package org.praxisplatform.rules.plan;

/** Stable compiler diagnostics for invalid RuleSet plans. */
public enum RulePlanIssueCode {
    /** Slot or binding identity is duplicated. */
    PLAN_IDENTITY_DUPLICATE,
    /** A binding references a slot or dependency that does not exist. */
    PLAN_REFERENCE_MISSING,
    /** A dependency crosses the RuleSet boundary or points to a later stage. */
    PLAN_DEPENDENCY_INVALID,
    /** The dependency graph contains a cycle. */
    PLAN_CYCLE,
    /** Slot cardinality is violated. */
    PLAN_CARDINALITY_INVALID,
    /** Customer composition is incompatible with product override policy. */
    PLAN_OVERRIDE_INVALID,
    /** A protected guard is not structurally protected. */
    PLAN_PROTECTED_GUARD_INVALID,
    /** A binding outcome or policy is incompatible with its execution stage. */
    PLAN_STAGE_INVALID,
    /** JSON Logic expression is outside the canonical dialect. */
    PLAN_EXPRESSION_INVALID,
    /** Required trusted Java implementation is absent. */
    PLAN_IMPLEMENTATION_UNAVAILABLE,
    /** RuleSet contract, dialect, corpus, or implementation version is incompatible. */
    PLAN_COMPATIBILITY_INVALID,
    /** Plan size exceeds deterministic compiler limits. */
    PLAN_LIMIT_EXCEEDED
}
