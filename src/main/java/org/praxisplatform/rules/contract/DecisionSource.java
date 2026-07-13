package org.praxisplatform.rules.contract;

/** Declared semantic owner of a decision binding. */
public enum DecisionSource {
    /** Security or authorization invariant owned by the platform or host. */
    SECURITY,
    /** Official product rule. */
    PRODUCT,
    /** Governed tenant customization. */
    CUSTOMER,
    /** Pure infrastructure intent generated after a decision. */
    GENERATED_INFRASTRUCTURE
}
