package org.praxisplatform.rules.snapshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.praxisplatform.rules.contract.CompositionPolicy;
import org.praxisplatform.rules.contract.DecisionBinding;
import org.praxisplatform.rules.contract.DecisionSlot;
import org.praxisplatform.rules.contract.DecisionSource;
import org.praxisplatform.rules.contract.OverridePolicy;
import org.praxisplatform.rules.contract.RuleExecutorType;
import org.praxisplatform.rules.contract.RuleSetDefinition;
import org.praxisplatform.rules.digest.PraxisCanonicalJson;
import org.praxisplatform.rules.facts.PraxisFactTreeBuilder;
import org.praxisplatform.rules.ontology.OntologyRuleSetBuilder;
import org.praxisplatform.rules.violation.RuleErrorCatalog;

/**
 * Canonical product-baseline admission verifier for governed {@link RuleSetDefinition} candidates.
 *
 * <p>Verifies that a candidate rule set preserves the product identity, fail policy, decision slots,
 * permitted ontological roots, protected product bindings, and reserved product error codes before
 * compilation into a {@link TypedCompiledRuleSnapshot}.
 */
public final class RuleSetProductAdmissionVerifier {
    private static final ObjectMapper JSON = new ObjectMapper();

    private RuleSetProductAdmissionVerifier() {}

    public static void require(
            RuleSetDefinition product,
            RuleSetDefinition candidate,
            Collection<String> permittedRoots) {
        require(product, candidate, permittedRoots, null);
    }

    public static void require(
            RuleSetDefinition product,
            RuleSetDefinition candidate,
            Collection<String> permittedRoots,
            RuleErrorCatalog reservedProductErrorCatalog) {
        Objects.requireNonNull(product, "product");
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(permittedRoots, "permittedRoots");
        Map<String, DecisionSlot> productSlots = slots(product);
        Map<String, DecisionSlot> candidateSlots = slots(candidate);
        if (!product.ref().sameIdentity(candidate.ref())
                || product.failPolicy() != candidate.failPolicy()
                || !sameSlots(productSlots, candidateSlots)
                || !new HashSet<>(permittedRoots).containsAll(candidate.availableRoots())) {
            throw new IllegalArgumentException(
                    "Identidade, slots, raízes ou política do produto divergentes");
        }
        Map<String, DecisionBinding> canonical = new HashMap<>();
        product.bindings().stream()
                .filter(b -> b.source() == DecisionSource.PRODUCT)
                .forEach(b -> canonical.put(b.bindingKey(), b));
        for (DecisionBinding binding : candidate.bindings()) {
            if (binding.source() != DecisionSource.PRODUCT) {
                continue;
            }
            DecisionBinding expected = canonical.get(binding.bindingKey());
            if (expected == null
                    || !sameContent(expected, binding)
                    || (!expected.enabled() && binding.enabled())) {
                throw new IllegalArgumentException("O candidato reescreveu conteúdo do produto");
            }
        }
        for (DecisionBinding expected : canonical.values()) {
            DecisionSlot slot = productSlots.get(expected.slotKey());
            boolean retained =
                    candidate.bindings().stream()
                            .anyMatch(
                                    b ->
                                            b.enabled() == expected.enabled()
                                                    && sameContent(expected, b));
            if (slot.overridePolicy() == OverridePolicy.FORBIDDEN && !retained) {
                throw new IllegalArgumentException("Decisão protegida do produto alterada");
            }
            if (expected.enabled()
                    && !retained
                    && candidate.bindings().stream()
                            .noneMatch(
                                    b ->
                                            b.enabled()
                                                    && b.source() == DecisionSource.CUSTOMER
                                                    && b.slotKey().equals(expected.slotKey())
                                                    && (b.compositionPolicy()
                                                                    == CompositionPolicy.REPLACE_EXACT
                                                            || b.compositionPolicy()
                                                                    == CompositionPolicy.PARAMETERIZE))) {
                throw new IllegalArgumentException(
                        "Decisão do produto ausente sem substituição declarada");
            }
        }
        if (reservedProductErrorCatalog != null) {
            for (var entry : candidate.errorDescriptors().entrySet()) {
                var descriptor = entry.getValue();
                if (reservedProductErrorCatalog.find(descriptor.code()).isPresent()
                        && !Objects.equals(
                                product.errorDescriptors().get(entry.getKey()), descriptor)
                        && !Objects.equals(
                                product.errorDescriptors().get(descriptor.code()), descriptor)) {
                    throw new IllegalArgumentException(
                            "Descritor do cliente usa código reservado do produto");
                }
            }
        }
        for (DecisionBinding binding : candidate.bindings()) {
            if (!binding.enabled()
                    || binding.executor() == null
                    || binding.executor().type() != RuleExecutorType.JSON_LOGIC) {
                continue;
            }
            List<String> referenced = OntologyRuleSetBuilder.roots(binding.executor().expression());
            if (!candidate.availableRoots().containsAll(referenced)
                    || !referenced.stream()
                            .allMatch(
                                    root ->
                                            binding.requiredFactPaths().stream()
                                                    .anyMatch(
                                                            path ->
                                                                    OntologyRuleSetBuilder.roots(
                                                                                    PraxisFactTreeBuilder
                                                                                            .create()
                                                                                            .put(
                                                                                                    "var",
                                                                                                    path)
                                                                                            .build())
                                                                            .contains(root)))) {
                throw new IllegalArgumentException(
                        "Raízes da expressão não estão cobertas por requiredFactPaths e pelo contrato do binding");
            }
        }
    }

    private static Map<String, DecisionSlot> slots(RuleSetDefinition definition) {
        Map<String, DecisionSlot> slots = new HashMap<>();
        for (DecisionSlot slot : definition.slots()) {
            if (slots.put(slot.slotKey(), slot) != null) {
                throw new IllegalArgumentException("Slot duplicado");
            }
        }
        return slots;
    }

    private static boolean sameSlots(
            Map<String, DecisionSlot> expected, Map<String, DecisionSlot> actual) {
        if (!expected.keySet().equals(actual.keySet())) {
            return false;
        }
        for (var entry : expected.entrySet()) {
            if (!sameCanonicalJson(entry.getValue(), actual.get(entry.getKey()))) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameContent(DecisionBinding expected, DecisionBinding actual) {
        DecisionBinding normalized =
                new DecisionBinding(
                        actual.bindingKey(),
                        actual.slotKey(),
                        actual.source(),
                        actual.compositionPolicy(),
                        actual.executor(),
                        actual.dependsOn(),
                        actual.order(),
                        expected.enabled(),
                        actual.falseDecision(),
                        actual.falseReasonCode(),
                        actual.requiredFactPaths(),
                        actual.parameterization());
        return sameCanonicalJson(expected, normalized);
    }

    private static boolean sameCanonicalJson(Object left, Object right) {
        if (Objects.equals(left, right)) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        return PraxisCanonicalJson.canonicalize(JSON.valueToTree(left))
                .equals(PraxisCanonicalJson.canonicalize(JSON.valueToTree(right)));
    }
}
