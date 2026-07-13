package org.praxisplatform.rules.contract;

import java.util.Objects;

/**
 * Stable semantic identity of one immutable RuleSet version.
 *
 * @param domainKey governed domain identity
 * @param boundedContextKey bounded context identity
 * @param ruleSetKey stable RuleSet identity
 * @param operationKey operation evaluated by the RuleSet
 * @param version positive immutable version
 */
public record RuleSetRef(
        String domainKey,
        String boundedContextKey,
        String ruleSetKey,
        String operationKey,
        int version) {

    /** Validates stable identity components without relying on transport or storage names. */
    public RuleSetRef {
        domainKey = required(domainKey, "domainKey");
        boundedContextKey = required(boundedContextKey, "boundedContextKey");
        ruleSetKey = required(ruleSetKey, "ruleSetKey");
        operationKey = required(operationKey, "operationKey");
        if (version < 1) {
            throw new IllegalArgumentException("version must be positive");
        }
    }

    private static String required(String value, String field) {
        Objects.requireNonNull(value, field + " is required");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
