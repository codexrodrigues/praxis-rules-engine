package org.praxisplatform.rules.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;
import org.praxisplatform.rules.contract.DecisionBinding;
import org.praxisplatform.rules.contract.RuleSetRef;

/**
 * Frozen inputs visible to one trusted Java binding implementation.
 *
 * @param ruleSetRef exact RuleSet version
 * @param binding current binding
 * @param facts host-resolved facts; no lazy I/O contract is exposed
 * @param nowUtc frozen UTC instant
 * @param userTimeZone explicit evaluation time zone
 */
public record RuleExecutorContext(
        RuleSetRef ruleSetRef,
        DecisionBinding binding,
        JsonNode facts,
        String nowUtc,
        String userTimeZone) {

    /** Snapshots mutable facts and requires explicit temporal context. */
    public RuleExecutorContext {
        ruleSetRef = Objects.requireNonNull(ruleSetRef, "ruleSetRef is required");
        binding = Objects.requireNonNull(binding, "binding is required");
        facts = Objects.requireNonNull(facts, "facts are required").deepCopy();
        if (nowUtc == null || nowUtc.isBlank()) {
            throw new IllegalArgumentException("nowUtc must not be blank");
        }
        if (userTimeZone == null || userTimeZone.isBlank()) {
            throw new IllegalArgumentException("userTimeZone must not be blank");
        }
    }

    /**
     * Returns a defensive copy of the evaluated facts.
     * @return copied facts snapshot
     */
    @Override
    public JsonNode facts() {
        return facts.deepCopy();
    }
}
