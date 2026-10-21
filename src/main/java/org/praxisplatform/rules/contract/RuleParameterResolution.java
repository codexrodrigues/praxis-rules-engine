package org.praxisplatform.rules.contract;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;
import java.util.Objects;

/**
 * Pure deterministic projection of admitted parameter values into an executable template.
 * @param executor derived JSON Logic executor
 * @param effectiveValues admitted values including defaults
 * @param origins declared/default origin for each effective value
 */
public record RuleParameterResolution(
        RuleExecutorRef executor,
        ObjectNode effectiveValues,
        Map<String, RuleParameterValueOrigin> origins) {
    /** Captures the complete resolution immutably. */
    public RuleParameterResolution {
        Objects.requireNonNull(executor, "executor is required");
        effectiveValues = Objects.requireNonNull(effectiveValues, "effectiveValues is required").deepCopy();
        origins = Map.copyOf(origins);
    }

    /** @return a defensive copy of effective values */
    @Override
    public ObjectNode effectiveValues() {
        return effectiveValues.deepCopy();
    }
}
