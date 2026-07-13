package org.praxisplatform.rules.contract;

/** How a customer binding participates in the decision produced by a slot. */
public enum CompositionPolicy {
    /** Adds an independent decision under an explicit multi-binding aggregator. */
    AUGMENT,
    /** May only make the effective outcome more restrictive. */
    RESTRICT,
    /** Changes declared values within a product-owned parameter schema. */
    PARAMETERIZE,
    /** Replaces exactly one product-approved replaceable slot. */
    REPLACE_EXACT
}
