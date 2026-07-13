package org.praxisplatform.rules.contract;

import java.util.List;
import java.util.Objects;

/**
 * One pure executable binding attached to a stable decision slot.
 *
 * @param bindingKey stable binding identity
 * @param slotKey target slot identity
 * @param source declared semantic owner
 * @param compositionPolicy customer composition policy, or {@code null} for non-customer bindings
 * @param executor pure executor reference
 * @param dependsOn binding dependencies
 * @param order deterministic tie-breaker inside a stage
 * @param enabled whether publication included this binding
 * @param falseDecision outcome used when a JSON Logic condition is false; {@code null} for Java
 * @param falseReasonCode stable reason code used for the false outcome; {@code null} for Java
 * @param requiredFactPaths fact paths that must exist before evaluation
 */
public record DecisionBinding(
        String bindingKey,
        String slotKey,
        DecisionSource source,
        CompositionPolicy compositionPolicy,
        RuleExecutorRef executor,
        List<String> dependsOn,
        int order,
        boolean enabled,
        RuleDecision falseDecision,
        String falseReasonCode,
        List<String> requiredFactPaths) {

    /** Normalizes collections and validates binding-local invariants. */
    public DecisionBinding {
        bindingKey = required(bindingKey, "bindingKey");
        slotKey = required(slotKey, "slotKey");
        source = Objects.requireNonNull(source, "source is required");
        executor = Objects.requireNonNull(executor, "executor is required");
        dependsOn = normalizedKeys(dependsOn, "dependsOn");
        requiredFactPaths = normalizedKeys(requiredFactPaths, "requiredFactPaths");
        if (executor.type() == RuleExecutorType.JSON_LOGIC) {
            falseDecision = falseDecision == null ? RuleDecision.DENY : falseDecision;
            if (falseDecision != RuleDecision.DENY
                    && falseDecision != RuleDecision.NOT_APPLICABLE
                    && falseDecision != RuleDecision.INCONCLUSIVE) {
                throw new IllegalArgumentException(
                        "falseDecision must be DENY, NOT_APPLICABLE, or INCONCLUSIVE");
            }
            if (falseReasonCode == null || falseReasonCode.isBlank()) {
                throw new IllegalArgumentException("JSON_LOGIC binding requires falseReasonCode");
            }
            falseReasonCode = falseReasonCode.trim();
        } else if (falseDecision != null || falseReasonCode != null) {
            throw new IllegalArgumentException(
                    "JAVA binding decisions are returned by the executor and cannot declare false outcome");
        }
    }

    private static List<String> normalizedKeys(List<String> values, String field) {
        if (values == null) {
            return List.of();
        }
        List<String> normalized = values.stream()
                .map(value -> required(value, field + " entry"))
                .distinct()
                .sorted()
                .toList();
        if (normalized.size() != values.size()) {
            throw new IllegalArgumentException(field + " must not contain duplicates");
        }
        return normalized;
    }

    private static String required(String value, String field) {
        Objects.requireNonNull(value, field + " is required");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
