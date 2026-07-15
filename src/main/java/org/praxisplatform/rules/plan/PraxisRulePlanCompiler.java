package org.praxisplatform.rules.plan;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.HashSet;
import java.util.TreeMap;
import org.praxisplatform.rules.contract.CompositionPolicy;
import org.praxisplatform.rules.contract.DecisionBinding;
import org.praxisplatform.rules.contract.DecisionAggregationPolicy;
import org.praxisplatform.rules.contract.DecisionSlot;
import org.praxisplatform.rules.contract.DecisionSource;
import org.praxisplatform.rules.contract.DecisionStage;
import org.praxisplatform.rules.contract.OverridePolicy;
import org.praxisplatform.rules.contract.RuleExecutorType;
import org.praxisplatform.rules.contract.RuleImplementationRef;
import org.praxisplatform.rules.contract.RuleDecision;
import org.praxisplatform.rules.contract.RuleRuntimeCompatibility;
import org.praxisplatform.rules.contract.RuleSetDefinition;
import org.praxisplatform.rules.contract.SlotCardinality;
import org.praxisplatform.rules.digest.PraxisCanonicalJson;
import org.praxisplatform.rules.jsonlogic.PraxisJsonLogicEngine;
import org.praxisplatform.rules.jsonlogic.PraxisJsonLogicException;
import org.praxisplatform.rules.jsonlogic.internal.PraxisPath;
import org.praxisplatform.rules.jsonlogic.model.JsonLogicValidationOptions;
import org.praxisplatform.rules.runtime.RuleBindingExecutorRegistry;

/** Stateless compiler that validates and deterministically orders a RuleSet definition. */
public final class PraxisRulePlanCompiler {
    private static final int MAX_SLOTS = 256;
    private static final int MAX_BINDINGS = 1_024;
    private static final int MAX_DEPENDENCIES_PER_BINDING = 128;
    private static final int MAX_DEPENDENCY_EDGES = 8_192;
    private static final int MAX_DEPENDENT_FAN_OUT = 128;

    private final PraxisJsonLogicEngine jsonLogicEngine;
    private final RuleBindingExecutorRegistry executorRegistry;

    /**
     * Creates a compiler using the canonical JSON Logic engine and supplied Java registry.
     * @param executorRegistry trusted Java executor registry
     */
    public PraxisRulePlanCompiler(RuleBindingExecutorRegistry executorRegistry) {
        this(new PraxisJsonLogicEngine(), executorRegistry);
    }

    /**
     * Creates a compiler from explicit pure runtime collaborators.
     * @param jsonLogicEngine canonical declarative engine
     * @param executorRegistry trusted Java executor registry
     */
    public PraxisRulePlanCompiler(
            PraxisJsonLogicEngine jsonLogicEngine,
            RuleBindingExecutorRegistry executorRegistry) {
        this.jsonLogicEngine = java.util.Objects.requireNonNull(jsonLogicEngine, "jsonLogicEngine is required");
        this.executorRegistry = java.util.Objects.requireNonNull(executorRegistry, "executorRegistry is required");
    }

    /**
     * Validates a complete definition and returns its immutable execution plan.
     * @param definition immutable RuleSet definition
     * @return validated deterministic plan
     */
    public RuleDecisionPlan compile(RuleSetDefinition definition) {
        java.util.Objects.requireNonNull(definition, "definition is required");
        validateCompatibility(definition);
        enforceLimits(definition);
        Map<String, DecisionSlot> slots = indexSlots(definition.slots());
        Map<String, DecisionBinding> bindings = indexEnabledBindings(definition.bindings());
        validateBindings(definition, slots, bindings);
        List<DecisionBinding> ordered = topologicalOrder(slots, bindings);
        return new RuleDecisionPlan(
                definition,
                ordered,
                slots,
                implementationRefs(ordered),
                digest(definition, ordered));
    }

    private void validateCompatibility(RuleSetDefinition definition) {
        if (!RuleRuntimeCompatibility.current().equals(definition.compatibility())) {
            throw failure(
                    RulePlanIssueCode.PLAN_COMPATIBILITY_INVALID,
                    "RuleSet compatibility baseline is not supported by this engine",
                    null);
        }
    }

    private void enforceLimits(RuleSetDefinition definition) {
        if (definition.slots().size() > MAX_SLOTS || definition.bindings().size() > MAX_BINDINGS) {
            throw failure(RulePlanIssueCode.PLAN_LIMIT_EXCEEDED, "RuleSet exceeds compiler limits", null);
        }
        int edges = 0;
        Map<String, Integer> fanOut = new HashMap<>();
        for (DecisionBinding binding : definition.bindings()) {
            if (binding.dependsOn().size() > MAX_DEPENDENCIES_PER_BINDING) {
                throw failure(
                        RulePlanIssueCode.PLAN_LIMIT_EXCEEDED,
                        "Binding exceeds dependency limit: " + binding.bindingKey(),
                        binding.bindingKey());
            }
            edges += binding.dependsOn().size();
            binding.dependsOn().forEach(dependency -> fanOut.merge(dependency, 1, Integer::sum));
        }
        if (edges > MAX_DEPENDENCY_EDGES
                || fanOut.values().stream().anyMatch(count -> count > MAX_DEPENDENT_FAN_OUT)) {
            throw failure(
                    RulePlanIssueCode.PLAN_LIMIT_EXCEEDED,
                    "RuleSet dependency graph exceeds edge or fan-out limits",
                    null);
        }
    }

    private Map<String, DecisionSlot> indexSlots(List<DecisionSlot> slots) {
        Map<String, DecisionSlot> indexed = new LinkedHashMap<>();
        for (DecisionSlot slot : slots) {
            if (indexed.putIfAbsent(slot.slotKey(), slot) != null) {
                throw failure(
                        RulePlanIssueCode.PLAN_IDENTITY_DUPLICATE,
                        "Duplicate slotKey: " + slot.slotKey(),
                        null);
            }
            if (slot.stage() == DecisionStage.PROTECTED_GUARD
                    && slot.overridePolicy() != OverridePolicy.FORBIDDEN) {
                throw failure(
                        RulePlanIssueCode.PLAN_PROTECTED_GUARD_INVALID,
                        "Protected guard must use FORBIDDEN: " + slot.slotKey(),
                        null);
            }
        }
        return Map.copyOf(indexed);
    }

    private Map<String, DecisionBinding> indexEnabledBindings(List<DecisionBinding> bindings) {
        Map<String, DecisionBinding> indexed = new LinkedHashMap<>();
        java.util.Set<String> identities = new HashSet<>();
        for (DecisionBinding binding : bindings) {
            if (!identities.add(binding.bindingKey())) {
                throw failure(
                        RulePlanIssueCode.PLAN_IDENTITY_DUPLICATE,
                        "Duplicate bindingKey: " + binding.bindingKey(),
                        binding.bindingKey());
            }
            if (binding.enabled()) {
                indexed.put(binding.bindingKey(), binding);
            }
        }
        return Map.copyOf(indexed);
    }

    private void validateBindings(
            RuleSetDefinition definition,
            Map<String, DecisionSlot> slots,
            Map<String, DecisionBinding> bindings) {
        Map<String, Integer> cardinality = new HashMap<>();
        for (DecisionBinding binding : bindings.values()) {
            DecisionSlot slot = slots.get(binding.slotKey());
            if (slot == null) {
                throw failure(
                        RulePlanIssueCode.PLAN_REFERENCE_MISSING,
                        "Binding references unknown slot: " + binding.slotKey(),
                        binding.bindingKey());
            }
            cardinality.merge(slot.slotKey(), 1, Integer::sum);
            if (slot.stage() == DecisionStage.EFFECT_INTENT
                    && binding.executor().type() == RuleExecutorType.JSON_LOGIC
                    && binding.falseDecision() != RuleDecision.NOT_APPLICABLE) {
                throw failure(
                        RulePlanIssueCode.PLAN_STAGE_INVALID,
                        "Effect-intent binding must use NOT_APPLICABLE when its condition is false",
                        binding.bindingKey());
            }
            if (slot.stage() == DecisionStage.TRANSFORMATION_INTENT
                    && binding.executor().type() != RuleExecutorType.JAVA) {
                throw failure(
                        RulePlanIssueCode.PLAN_STAGE_INVALID,
                        "Transformation-intent binding requires a trusted Java executor",
                        binding.bindingKey());
            }
            if (slot.stage() == DecisionStage.EFFECT_INTENT && binding.dependsOn().isEmpty()) {
                throw failure(
                        RulePlanIssueCode.PLAN_STAGE_INVALID,
                        "Effect-intent binding requires an explicit decision dependency",
                        binding.bindingKey());
            }
            if (slot.stage() == DecisionStage.TRANSFORMATION_INTENT && binding.dependsOn().isEmpty()) {
                throw failure(
                        RulePlanIssueCode.PLAN_STAGE_INVALID,
                        "Transformation-intent binding requires an explicit decision dependency",
                        binding.bindingKey());
            }
            validateComposition(binding, slot);
            validateRequiredFacts(definition, binding);
            validateExecutor(definition, binding);
            for (String dependencyKey : binding.dependsOn()) {
                DecisionBinding dependency = bindings.get(dependencyKey);
                if (dependency == null) {
                    throw failure(
                            RulePlanIssueCode.PLAN_REFERENCE_MISSING,
                            "Binding dependency is missing or disabled: " + dependencyKey,
                            binding.bindingKey());
                }
                DecisionSlot dependencySlot = slots.get(dependency.slotKey());
                if (dependencySlot.stage().ordinal() > slot.stage().ordinal()) {
                    throw failure(
                            RulePlanIssueCode.PLAN_DEPENDENCY_INVALID,
                            "Binding depends on a later stage: " + dependencyKey,
                            binding.bindingKey());
                }
            }
        }
        cardinality.forEach((slotKey, count) -> {
            if (slots.get(slotKey).cardinality() == SlotCardinality.SINGLE && count > 1) {
                throw failure(
                        RulePlanIssueCode.PLAN_CARDINALITY_INVALID,
                        "Single slot has multiple enabled bindings: " + slotKey,
                        null);
            }
        });
    }

    private void validateRequiredFacts(RuleSetDefinition definition, DecisionBinding binding) {
        for (String path : binding.requiredFactPaths()) {
            try {
                List<String> segments = PraxisPath.parse(path);
                if (segments.isEmpty() || !definition.availableRoots().contains(segments.get(0))) {
                    throw failure(
                            RulePlanIssueCode.PLAN_REFERENCE_MISSING,
                            "Required fact path uses an unavailable root: " + path,
                            binding.bindingKey());
                }
            } catch (PraxisJsonLogicException exception) {
                throw failure(
                        RulePlanIssueCode.PLAN_REFERENCE_MISSING,
                        "Required fact path is invalid: " + path,
                        binding.bindingKey());
            }
        }
    }

    private void validateComposition(DecisionBinding binding, DecisionSlot slot) {
        if (binding.source() != DecisionSource.CUSTOMER) {
            if (binding.compositionPolicy() != null) {
                throw failure(
                        RulePlanIssueCode.PLAN_OVERRIDE_INVALID,
                        "Only customer bindings declare composition policy",
                        binding.bindingKey());
            }
            return;
        }
        CompositionPolicy composition = binding.compositionPolicy();
        boolean allowed = switch (slot.overridePolicy()) {
            case FORBIDDEN -> false;
            case RESTRICT_ONLY -> composition == CompositionPolicy.RESTRICT
                    || (composition == CompositionPolicy.AUGMENT
                            && slot.cardinality() == SlotCardinality.MULTIPLE
                            && slot.aggregationPolicy() == DecisionAggregationPolicy.DENY_OVERRIDES);
            case PARAMETERIZABLE -> composition == CompositionPolicy.PARAMETERIZE;
            case REPLACEABLE -> composition == CompositionPolicy.REPLACE_EXACT;
        };
        if (!allowed) {
            throw failure(
                    RulePlanIssueCode.PLAN_OVERRIDE_INVALID,
                    "Customer composition is incompatible with slot override policy: " + slot.slotKey(),
                    binding.bindingKey());
        }
    }

    private void validateExecutor(RuleSetDefinition definition, DecisionBinding binding) {
        if (binding.executor().type() == RuleExecutorType.JAVA) {
            if (!executorRegistry.contains(binding.executor().implementationKey())) {
                throw failure(
                        RulePlanIssueCode.PLAN_IMPLEMENTATION_UNAVAILABLE,
                        "Java implementation is unavailable: " + binding.executor().implementationKey(),
                        binding.bindingKey());
            }
            if (!executorRegistry.isCompatible(
                    binding.executor().implementationKey(),
                    binding.executor().implementationVersion())) {
                throw failure(
                        RulePlanIssueCode.PLAN_COMPATIBILITY_INVALID,
                        "Java implementation version is incompatible: "
                                + binding.executor().implementationKey()
                                + "@"
                                + binding.executor().implementationVersion(),
                        binding.bindingKey());
            }
            boolean trustedExtension = executorRegistry.isTrustedExtension(
                    binding.executor().implementationKey(),
                    binding.executor().implementationVersion());
            if (binding.source() == DecisionSource.CUSTOMER && !trustedExtension) {
                throw failure(
                        RulePlanIssueCode.PLAN_EXTENSION_TRUST_INVALID,
                        "Customer Java extension is not signed and allowlisted: "
                                + binding.executor().implementationKey()
                                + "@"
                                + binding.executor().implementationVersion(),
                        binding.bindingKey());
            }
            if (binding.source() != DecisionSource.CUSTOMER && trustedExtension) {
                throw failure(
                        RulePlanIssueCode.PLAN_EXTENSION_TRUST_INVALID,
                        "Attested customer extension cannot be relabeled as " + binding.source(),
                        binding.bindingKey());
            }
            return;
        }
        try {
            jsonLogicEngine.validate(
                    binding.executor().expression(),
                    new JsonLogicValidationOptions(
                            definition.availableRoots(),
                            null,
                            false,
                            "1970-01-01T00:00:00Z",
                            "UTC",
                            true));
            validateExpressionRoots(definition, binding, binding.executor().expression(), false);
        } catch (PraxisJsonLogicException exception) {
            throw failure(
                    RulePlanIssueCode.PLAN_EXPRESSION_INVALID,
                    "Invalid JSON Logic expression [" + exception.getCode() + "]",
                    binding.bindingKey());
        }
    }

    private void validateExpressionRoots(
            RuleSetDefinition definition,
            DecisionBinding binding,
            JsonNode node,
            boolean localScope) {
        if (node == null || node.isValueNode()) {
            return;
        }
        if (node.isArray()) {
            node.forEach(child -> validateExpressionRoots(definition, binding, child, localScope));
            return;
        }
        if (node.size() == 1) {
            String operator = node.properties().iterator().next().getKey();
            JsonNode arguments = node.get(operator);
            if (java.util.Set.of("map", "filter", "all", "some", "none", "reduce").contains(operator)
                    && arguments != null
                    && arguments.isArray()
                    && arguments.size() >= 2) {
                validateExpressionRoots(definition, binding, arguments.get(0), localScope);
                validateExpressionRoots(definition, binding, arguments.get(1), true);
                for (int index = 2; index < arguments.size(); index++) {
                    validateExpressionRoots(definition, binding, arguments.get(index), localScope);
                }
                return;
            }
        }
        JsonNode variable = node.get("var");
        if (!localScope && node.size() == 1 && variable != null) {
            JsonNode pathNode = variable.isArray() && !variable.isEmpty() ? variable.get(0) : variable;
            if (pathNode != null && pathNode.isTextual() && !pathNode.textValue().isBlank()) {
                String path = pathNode.textValue();
                List<String> segments;
                try {
                    segments = PraxisPath.parse(path);
                } catch (PraxisJsonLogicException exception) {
                    throw failure(
                            RulePlanIssueCode.PLAN_REFERENCE_MISSING,
                            "JSON Logic variable path is invalid: " + path,
                            binding.bindingKey());
                }
                if (!segments.isEmpty()
                        && !definition.availableRoots().contains(segments.get(0))) {
                    throw failure(
                            RulePlanIssueCode.PLAN_REFERENCE_MISSING,
                            "JSON Logic variable uses an unavailable root: " + path,
                            binding.bindingKey());
                }
            }
        }
        node.properties().forEach(entry ->
                validateExpressionRoots(definition, binding, entry.getValue(), localScope));
    }

    private List<DecisionBinding> topologicalOrder(
            Map<String, DecisionSlot> slots,
            Map<String, DecisionBinding> bindings) {
        Map<String, Integer> indegree = new HashMap<>();
        Map<String, List<String>> dependents = new HashMap<>();
        bindings.keySet().forEach(key -> {
            indegree.put(key, 0);
            dependents.put(key, new ArrayList<>());
        });
        bindings.values().forEach(binding -> binding.dependsOn().forEach(dependency -> {
            indegree.merge(binding.bindingKey(), 1, Integer::sum);
            dependents.get(dependency).add(binding.bindingKey());
        }));

        Comparator<DecisionBinding> comparator = Comparator
                .comparingInt((DecisionBinding binding) -> slots.get(binding.slotKey()).stage().ordinal())
                .thenComparingInt(DecisionBinding::order)
                .thenComparing(DecisionBinding::bindingKey);
        PriorityQueue<DecisionBinding> ready = new PriorityQueue<>(comparator);
        bindings.values().stream()
                .filter(binding -> indegree.get(binding.bindingKey()) == 0)
                .forEach(ready::add);

        List<DecisionBinding> ordered = new ArrayList<>();
        while (!ready.isEmpty()) {
            DecisionBinding binding = ready.remove();
            ordered.add(binding);
            dependents.get(binding.bindingKey()).stream().sorted().forEach(dependent -> {
                int remaining = indegree.merge(dependent, -1, Integer::sum);
                if (remaining == 0) {
                    ready.add(bindings.get(dependent));
                }
            });
        }
        if (ordered.size() != bindings.size()) {
            throw failure(RulePlanIssueCode.PLAN_CYCLE, "RuleSet dependency graph contains a cycle", null);
        }
        return List.copyOf(ordered);
    }

    private String digest(RuleSetDefinition definition, List<DecisionBinding> ordered) {
        StringBuilder canonical = new StringBuilder();
        canonical.append(definition.ref()).append('|')
                .append(definition.availableRoots()).append('|')
                .append(definition.compatibility()).append('|')
                .append(definition.failPolicy()).append('|');
        new TreeMap<>(definition.slots().stream().collect(java.util.stream.Collectors.toMap(
                DecisionSlot::slotKey,
                slot -> slot))).values().forEach(slot -> canonical.append(slot).append('|'));
        for (DecisionBinding binding : ordered) {
            canonical.append(binding.bindingKey()).append('|')
                    .append(binding.slotKey()).append('|')
                    .append(binding.source()).append('|')
                    .append(binding.compositionPolicy()).append('|')
                    .append(binding.executor().type()).append('|')
                    .append(binding.executor().implementationKey()).append('|')
                    .append(binding.executor().implementationVersion()).append('|')
                    .append(implementationTrustMaterial(binding)).append('|')
                    .append(PraxisCanonicalJson.canonicalize(binding.executor().expression())).append('|')
                    .append(binding.dependsOn()).append('|')
                    .append(binding.order()).append('|')
                    .append(binding.falseDecision()).append('|')
                    .append(binding.falseReasonCode()).append('|')
                    .append(binding.requiredFactPaths()).append(';');
        }
        return PraxisCanonicalJson.sha256Utf8(canonical.toString());
    }

    private List<RuleImplementationRef> implementationRefs(List<DecisionBinding> ordered) {
        return ordered.stream()
                .map(DecisionBinding::executor)
                .filter(executor -> executor.type() == RuleExecutorType.JAVA)
                .map(executor -> executorRegistry.implementationRef(executor.implementationKey()))
                .distinct()
                .sorted(Comparator
                        .comparing(RuleImplementationRef::implementationKey)
                        .thenComparing(RuleImplementationRef::implementationVersion))
                .toList();
    }

    private String implementationTrustMaterial(DecisionBinding binding) {
        if (binding.executor().type() != RuleExecutorType.JAVA) {
            return "DECLARATIVE";
        }
        var implementation = executorRegistry.implementationRef(binding.executor().implementationKey());
        if (implementation.extensionTrust() == null) {
            return implementation.implementationKey() + "@" + implementation.implementationVersion() + "|BUILT_IN";
        }
        var trust = implementation.extensionTrust();
        return implementation.implementationKey() + "@" + implementation.implementationVersion()
                + "|" + trust.artifactSha256()
                + "|" + trust.signatureIdentity()
                + "|" + trust.trustPolicyKey()
                + "|" + trust.verificationEvidenceSha256();
    }

    private RulePlanException failure(RulePlanIssueCode code, String message, String bindingKey) {
        return new RulePlanException(code, message, bindingKey);
    }
}
