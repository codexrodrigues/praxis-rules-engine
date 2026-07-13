package org.praxisplatform.rules.contract;

/** Closed execution stages ordered from protected checks to pure effect planning. */
public enum DecisionStage {
    /** Security, authorization, legal, and structural integrity checks. */
    PROTECTED_GUARD,
    /** Product and customer business decisions. */
    DOMAIN_DECISION,
    /** Checks that depend on an otherwise successful domain decision. */
    POST_DECISION,
    /** Pure creation of typed intent data; never execution of an effect. */
    EFFECT_INTENT
}
