package org.praxisplatform.rules.contract;

/** Product-owned limit on tenant customization for a decision slot. */
public enum OverridePolicy {
    /** No customer binding is permitted. */
    FORBIDDEN,
    /** A customer binding may only restrict the product result. */
    RESTRICT_ONLY,
    /** Declared parameters may change within their governed schema. */
    PARAMETERIZABLE,
    /** The exact slot may be replaced under governed publication. */
    REPLACEABLE
}
