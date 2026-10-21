package org.praxisplatform.rules.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;
import java.util.Map;
import java.util.TreeMap;
import java.util.Collections;
import org.praxisplatform.rules.contract.DecisionBinding;
import org.praxisplatform.rules.contract.RuleSetRef;

/**
 * Frozen inputs visible to one trusted Java binding implementation.
 *
 * <p>The engine supplies only eligible direct ALLOW outputs from this evaluation.
 * Values must be native JSON trees with finite numbers; POJO, binary, and missing
 * Jackson nodes are rejected rather than converted.</p>
 *
 * @param ruleSetRef exact RuleSet version
 * @param binding current binding
 * @param facts host-resolved native JSON facts with finite numbers; no lazy I/O contract is exposed
 * @param directDependencyOutputs eligible direct ALLOW outputs, keyed and ordered by binding key;
 *        absent Java output is omitted and explicit JSON null is preserved
 * @param nowUtc frozen UTC instant
 * @param userTimeZone explicit evaluation time zone
 */
public record RuleExecutorContext(
        RuleSetRef ruleSetRef,
        DecisionBinding binding,
        JsonNode facts,
        Map<String, JsonNode> directDependencyOutputs,
        String nowUtc,
        String userTimeZone) {

    /** Snapshots mutable facts and requires explicit temporal context. */
    public RuleExecutorContext {
        ruleSetRef = Objects.requireNonNull(ruleSetRef, "ruleSetRef is required");
        binding = Objects.requireNonNull(binding, "binding is required");
        facts = Objects.requireNonNull(facts, "facts are required");
        RuleJsonBudget.requireJsonValue(facts);
        facts = facts.deepCopy();
        directDependencyOutputs = copyOutputs(directDependencyOutputs);
        if (nowUtc == null || nowUtc.isBlank()) {
            throw new IllegalArgumentException("nowUtc must not be blank");
        }
        if (userTimeZone == null || userTimeZone.isBlank()) {
            throw new IllegalArgumentException("userTimeZone must not be blank");
        }
    }

    /**
     * Returns an ordered, unmodifiable map with defensive copies of all JSON values.
     * @return isolated direct dependency outputs; never includes transitive dependencies or facts
     */
    @Override
    public Map<String, JsonNode> directDependencyOutputs() {
        return copyOutputs(directDependencyOutputs);
    }

    private static Map<String, JsonNode> copyOutputs(Map<String, JsonNode> outputs) {
        Objects.requireNonNull(outputs, "directDependencyOutputs are required");
        Map<String, JsonNode> copy = new TreeMap<>();
        outputs.forEach((key, value) -> {
            Objects.requireNonNull(key, "dependency binding key is required");
            Objects.requireNonNull(value, "dependency output must use NullNode for JSON null");
            RuleJsonBudget.requireJsonValue(value);
            copy.put(key, value.deepCopy());
        });
        return Collections.unmodifiableMap(copy);
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
