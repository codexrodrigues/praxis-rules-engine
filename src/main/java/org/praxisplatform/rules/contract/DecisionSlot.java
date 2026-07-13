package org.praxisplatform.rules.contract;

import java.util.Objects;

/**
 * Stable composition point within a RuleSet.
 *
 * @param slotKey stable slot identity
 * @param stage closed execution stage
 * @param cardinality number of enabled bindings permitted
 * @param overridePolicy product-owned customization boundary
 * @param aggregationPolicy closed policy used to consolidate enabled bindings
 */
public record DecisionSlot(
        String slotKey,
        DecisionStage stage,
        SlotCardinality cardinality,
        OverridePolicy overridePolicy,
        DecisionAggregationPolicy aggregationPolicy) {

    /** Normalizes and validates the complete slot contract. */
    public DecisionSlot {
        Objects.requireNonNull(slotKey, "slotKey is required");
        if (slotKey.isBlank()) {
            throw new IllegalArgumentException("slotKey must not be blank");
        }
        slotKey = slotKey.trim();
        stage = Objects.requireNonNull(stage, "stage is required");
        cardinality = Objects.requireNonNull(cardinality, "cardinality is required");
        overridePolicy = Objects.requireNonNull(overridePolicy, "overridePolicy is required");
        aggregationPolicy = Objects.requireNonNull(aggregationPolicy, "aggregationPolicy is required");
        if (cardinality == SlotCardinality.SINGLE
                && aggregationPolicy != DecisionAggregationPolicy.SINGLE_RESULT) {
            throw new IllegalArgumentException("SINGLE slot requires SINGLE_RESULT aggregation");
        }
        if (cardinality == SlotCardinality.MULTIPLE
                && aggregationPolicy != DecisionAggregationPolicy.DENY_OVERRIDES) {
            throw new IllegalArgumentException("MULTIPLE slot requires DENY_OVERRIDES aggregation");
        }
    }
}
