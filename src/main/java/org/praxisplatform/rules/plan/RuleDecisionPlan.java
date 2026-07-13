package org.praxisplatform.rules.plan;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.praxisplatform.rules.contract.DecisionBinding;
import org.praxisplatform.rules.contract.DecisionSlot;
import org.praxisplatform.rules.contract.RuleSetDefinition;

/**
 * Immutable, deterministically ordered execution plan compiled from one RuleSet version.
 *
 * @param definition validated source definition
 * @param orderedBindings topologically sorted enabled bindings
 * @param slotsByKey validated slot index
 * @param planDigest canonical SHA-256 digest
 */
public record RuleDecisionPlan(
        RuleSetDefinition definition,
        List<DecisionBinding> orderedBindings,
        Map<String, DecisionSlot> slotsByKey,
        String planDigest) {

    /** Copies compiled collections and requires a stable digest. */
    public RuleDecisionPlan {
        definition = Objects.requireNonNull(definition, "definition is required");
        orderedBindings = List.copyOf(orderedBindings);
        slotsByKey = Map.copyOf(slotsByKey);
        if (planDigest == null || planDigest.isBlank()) {
            throw new IllegalArgumentException("planDigest must not be blank");
        }
    }
}
