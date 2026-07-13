package org.praxisplatform.rules.contract;

/** Number of enabled bindings a slot can contain after publication. */
public enum SlotCardinality {
    /** Exactly one enabled binding is allowed. */
    SINGLE,
    /** Multiple enabled bindings are allowed and use deterministic deny-overrides. */
    MULTIPLE
}
