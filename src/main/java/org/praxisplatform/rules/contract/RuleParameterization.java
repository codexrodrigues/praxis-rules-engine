package org.praxisplatform.rules.contract;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Objects;

/**
 * Values for one exact product template, without a caller-authored executor.
 * @param templateKey exact product template identity
 * @param templateVersion exact template revision
 * @param values explicitly declared parameter values; omission selects a published default
 */
public record RuleParameterization(String templateKey, String templateVersion, ObjectNode values) {
    /** Captures values defensively. */
    public RuleParameterization {
        Objects.requireNonNull(templateKey, "templateKey is required");
        Objects.requireNonNull(templateVersion, "templateVersion is required");
        values = Objects.requireNonNull(values, "values is required").deepCopy();
    }

    /** @return a defensive copy of the explicitly declared values */
    @Override
    public ObjectNode values() {
        return values.deepCopy();
    }
}
