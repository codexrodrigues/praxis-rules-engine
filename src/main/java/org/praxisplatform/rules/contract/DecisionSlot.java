package org.praxisplatform.rules.contract;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Objects;

/**
 * Stable composition point within a RuleSet.
 *
 * @param slotKey stable slot identity
 * @param stage closed execution stage
 * @param cardinality number of enabled bindings permitted
 * @param overridePolicy product-owned customization boundary
 * @param parameterTemplate product-owned scalar template, required for PARAMETERIZABLE slots
 * @param aggregationPolicy closed policy used to consolidate enabled bindings
 * @param requiredControlDependencies product guard bindings required by every enabled binding in this slot
 */
public record DecisionSlot(
        String slotKey,
        DecisionStage stage,
        SlotCardinality cardinality,
        OverridePolicy overridePolicy,
        DecisionAggregationPolicy aggregationPolicy,
        RuleParameterTemplate parameterTemplate,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> requiredControlDependencies) {

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
        requiredControlDependencies = normalizeControlDependencies(requiredControlDependencies);
        if (cardinality == SlotCardinality.SINGLE
                && aggregationPolicy != DecisionAggregationPolicy.SINGLE_RESULT) {
            throw new IllegalArgumentException("SINGLE slot requires SINGLE_RESULT aggregation");
        }
        if (cardinality == SlotCardinality.MULTIPLE
                && aggregationPolicy != DecisionAggregationPolicy.DENY_OVERRIDES) {
            throw new IllegalArgumentException("MULTIPLE slot requires DENY_OVERRIDES aggregation");
        }
    }

    private static List<String> normalizeControlDependencies(List<String> dependencies) {
        if (dependencies == null) {
            return List.of();
        }
        List<String> normalized = dependencies.stream().map(key -> {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("requiredControlDependencies entries must not be blank");
            }
            return key.trim();
        }).distinct().sorted().toList();
        if (normalized.size() != dependencies.size()) {
            throw new IllegalArgumentException("requiredControlDependencies must not contain duplicates");
        }
        return normalized;
    }
}
