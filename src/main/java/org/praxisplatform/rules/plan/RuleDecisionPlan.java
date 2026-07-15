package org.praxisplatform.rules.plan;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.praxisplatform.rules.contract.DecisionBinding;
import org.praxisplatform.rules.contract.DecisionSlot;
import org.praxisplatform.rules.contract.RuleImplementationRef;
import org.praxisplatform.rules.contract.RuleSetDefinition;

/**
 * Immutable, deterministically ordered execution plan compiled from one RuleSet version.
 *
 * @param definition validated source definition
 * @param orderedBindings topologically sorted enabled bindings
 * @param slotsByKey validated slot index
 * @param implementationRefs exact trusted Java coordinates bound to this plan
 * @param planDigest canonical SHA-256 digest
 */
public record RuleDecisionPlan(
        RuleSetDefinition definition,
        List<DecisionBinding> orderedBindings,
        Map<String, DecisionSlot> slotsByKey,
        List<RuleImplementationRef> implementationRefs,
        String planDigest) {

    /** Copies compiled collections and requires a stable digest. */
    public RuleDecisionPlan {
        definition = Objects.requireNonNull(definition, "definition is required");
        orderedBindings = List.copyOf(orderedBindings);
        slotsByKey = Map.copyOf(slotsByKey);
        implementationRefs = implementationRefs == null ? List.of() : List.copyOf(implementationRefs);
        if (implementationRefs.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("implementationRefs must not contain null");
        }
        if (planDigest == null || planDigest.isBlank()) {
            throw new IllegalArgumentException("planDigest must not be blank");
        }
    }

    /**
     * Creates a plan without Java implementation evidence for source compatibility.
     * @param definition validated definition
     * @param orderedBindings ordered enabled bindings
     * @param slotsByKey validated slot index
     * @param planDigest canonical digest
     */
    public RuleDecisionPlan(
            RuleSetDefinition definition,
            List<DecisionBinding> orderedBindings,
            Map<String, DecisionSlot> slotsByKey,
            String planDigest) {
        this(definition, orderedBindings, slotsByKey, List.of(), planDigest);
    }
}
