package org.praxisplatform.rules.contract;

/** Closed business and runtime outcome taxonomy for a RuleSet evaluation. */
public enum RuleDecision {
    /** Every applicable decision permits the operation. */
    ALLOW,
    /** A business rule or protected guard explicitly denies the operation. */
    DENY,
    /** The decision is valid but does not apply to the supplied facts. */
    NOT_APPLICABLE,
    /** Required, fresh, or authorized facts are insufficient for a decision. */
    INCONCLUSIVE,
    /** Contract, implementation, limit, or runtime execution failed. */
    TECHNICAL_ERROR
}
