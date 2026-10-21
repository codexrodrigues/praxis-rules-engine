package org.praxisplatform.rules.contract;

/** Evidence of how a resolved parameter acquired its value. */
public enum RuleParameterValueOrigin {
    /** Explicitly declared by the caller, including null, zero and false. */
    DECLARED,
    /** Taken from the product-owned published default. */
    DEFAULT
}
