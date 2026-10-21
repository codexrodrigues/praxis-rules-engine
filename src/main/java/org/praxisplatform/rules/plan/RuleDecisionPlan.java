package org.praxisplatform.rules.plan;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.praxisplatform.rules.contract.DecisionBinding;
import org.praxisplatform.rules.contract.DecisionSlot;
import org.praxisplatform.rules.contract.DecisionSource;
import org.praxisplatform.rules.contract.DecisionStage;
import org.praxisplatform.rules.contract.RuleExecutorType;
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
     * Classifies the existing DAG edges using the fixed engine trust policy.
     *
     * <p>Both endpoints must be enabled PRODUCT Java bindings in DOMAIN_DECISION or
     * POST_DECISION. Other valid edges remain control-only. This derived query is shared by
     * runtime transport and host admission; it neither creates a second graph nor proves an
     * output exists or that its host-owned shape is compatible.</p>
     *
     * @param consumerBindingKey binding receiving the context
     * @return eligible direct producer keys in deterministic binding-key order, or empty for
     *         an unknown or ineligible consumer
     */
    public List<String> directDependencyOutputKeys(String consumerBindingKey) {
        DecisionBinding consumer = orderedBindings.stream()
                .filter(binding -> binding.bindingKey().equals(consumerBindingKey))
                .findFirst().orElse(null);
        if (!outputParticipant(consumer)) {
            return List.of();
        }
        return orderedBindings.stream()
                .filter(producer -> consumer.dependsOn().contains(producer.bindingKey()))
                .filter(this::outputParticipant)
                .map(DecisionBinding::bindingKey)
                .sorted()
                .toList();
    }

    private boolean outputParticipant(DecisionBinding binding) {
        if (binding == null || !binding.enabled() || binding.source() != DecisionSource.PRODUCT
                || binding.executor().type() != RuleExecutorType.JAVA) {
            return false;
        }
        DecisionSlot slot = slotsByKey.get(binding.slotKey());
        return slot != null && (slot.stage() == DecisionStage.DOMAIN_DECISION
                || slot.stage() == DecisionStage.POST_DECISION);
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
