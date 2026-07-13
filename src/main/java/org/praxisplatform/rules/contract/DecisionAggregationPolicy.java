package org.praxisplatform.rules.contract;

/** Closed deterministic aggregation policies for all enabled bindings of one slot. */
public enum DecisionAggregationPolicy {
    /** A singular slot exposes the outcome of its only enabled binding. */
    SINGLE_RESULT,
    /** Any denial wins; otherwise inconclusive, allow, and not-applicable precedence is applied. */
    DENY_OVERRIDES
}
