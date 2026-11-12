package org.praxisplatform.rules.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.HashSet;
import org.praxisplatform.rules.contract.CompositionPolicy;
import org.praxisplatform.rules.contract.DecisionBinding;
import org.praxisplatform.rules.contract.DecisionAggregationPolicy;
import org.praxisplatform.rules.contract.DecisionSlot;
import org.praxisplatform.rules.contract.DecisionSource;
import org.praxisplatform.rules.contract.DecisionStage;
import org.praxisplatform.rules.contract.OverridePolicy;
import org.praxisplatform.rules.contract.RuleExecutorType;
import org.praxisplatform.rules.contract.RuleEvaluationResult;
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
import org.praxisplatform.rules.jsonlogic.model.JsonLogicLimits;
import org.praxisplatform.rules.runtime.RuleBindingExecutorRegistry;
import org.praxisplatform.rules.parameters.RuleParameterResolver;
import org.praxisplatform.rules.contract.RuleParameterTemplate;
import org.praxisplatform.rules.contract.RuleParameterization;

/** Stateless compiler that validates and deterministically orders a RuleSet definition. */
public final class PraxisRulePlanCompiler {
    private static final ObjectMapper JSON = new ObjectMapper();
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
        Map<String, DecisionBinding> sourceBindings = indexEnabledBindings(definition.bindings());
        Map<String, DecisionBinding> bindings = materializeParameters(definition, slots, sourceBindings);
        validateControlDependencies(slots, bindings);
        validateBindings(definition, slots, bindings);
        validateErrorDescriptors(definition, bindings);
        List<DecisionBinding> ordered = topologicalOrder(slots, bindings);
        RuleDecisionPlan plan = new RuleDecisionPlan(
                definition,
                ordered,
                slots,
                implementationRefs(ordered),
                digest(definition, ordered));
        validateMinimalResultEnvelope(plan);
        return plan;
    }

    /**
     * Compiles a non-persistent preview containing selected bindings and their complete control graph.
     * The complete source definition is admitted before selecting the graph, including parameterized bindings.
     *
     * @param definition complete source RuleSet
     * @param selectedBindingKeys enabled binding identities to preview
     * @return independently compiled preview with the transitive dependency closure
     */
    public RuleDecisionPlan compilePreview(RuleSetDefinition definition, List<String> selectedBindingKeys) {
        if (selectedBindingKeys == null || selectedBindingKeys.isEmpty()
                || selectedBindingKeys.size() > MAX_BINDINGS
                || selectedBindingKeys.stream().anyMatch(key -> key == null || key.isBlank())) {
            throw new IllegalArgumentException("Preview requires a bounded non-empty list of binding identities");
        }
        RuleDecisionPlan complete = compile(definition);
        Map<String, DecisionBinding> enabled = new LinkedHashMap<>();
        complete.orderedBindings().forEach(binding -> enabled.put(binding.bindingKey(), binding));
        var closure = new java.util.LinkedHashSet<String>();
        var pending = new java.util.ArrayDeque<String>();
        selectedBindingKeys.stream().map(String::trim).forEach(pending::add);
        while (!pending.isEmpty()) {
            String key = pending.removeFirst();
            DecisionBinding binding = enabled.get(key);
            if (binding == null) {
                throw failure(RulePlanIssueCode.PLAN_REFERENCE_MISSING,
                        "Preview binding is missing or disabled: " + key, key);
            }
            if (closure.add(key)) {
                pending.addAll(binding.dependsOn());
            }
        }
        List<DecisionBinding> bindings = definition.bindings().stream()
                .filter(binding -> binding.enabled() && closure.contains(binding.bindingKey()))
                .toList();
        var selectedSlots = bindings.stream().map(DecisionBinding::slotKey)
                .collect(java.util.stream.Collectors.toSet());
        List<DecisionSlot> slots = definition.slots().stream()
                .filter(slot -> selectedSlots.contains(slot.slotKey())).toList();
        var descriptors = definition.errorDescriptors().entrySet().stream()
                .filter(entry -> closure.contains(entry.getKey()))
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        var ref = definition.ref();
        return compile(new RuleSetDefinition(
                new org.praxisplatform.rules.contract.RuleSetRef(
                        ref.domainKey(), ref.boundedContextKey(), ref.ruleSetKey(), "preview", ref.version()),
                definition.availableRoots(), slots, bindings, definition.compatibility(),
                definition.failPolicy(), descriptors));
    }

    private void validateErrorDescriptors(RuleSetDefinition definition, Map<String, DecisionBinding> bindings) {
        if (definition.errorDescriptors().isEmpty()) return;
        Map<String, Long> activeCodes = bindings.values().stream()
                .filter(binding -> binding.falseReasonCode() != null)
                .collect(java.util.stream.Collectors.groupingBy(
                        DecisionBinding::falseReasonCode, java.util.stream.Collectors.counting()));
        var reservedCodes = new java.util.HashSet<String>();
        definition.bindings().stream().filter(binding -> binding.source() != DecisionSource.CUSTOMER)
                .forEach(binding -> reservedCodes.add(binding.falseReasonCode()));
        definition.slots().stream().filter(slot -> slot.parameterTemplate() != null)
                .forEach(slot -> reservedCodes.add(slot.parameterTemplate().falseReasonCode()));
        definition.errorDescriptors().forEach((key, descriptor) -> {
            DecisionBinding binding = bindings.get(key);
            if (binding == null || binding.source() != DecisionSource.CUSTOMER
                    || binding.falseDecision() != RuleDecision.DENY
                    || !descriptor.code().equals(binding.falseReasonCode())
                    || activeCodes.getOrDefault(descriptor.code(), 0L) != 1L
                    || reservedCodes.contains(descriptor.code())) {
                throw failure(RulePlanIssueCode.PLAN_OVERRIDE_INVALID,
                        "Error descriptor must identify one enabled customer denial with a distinct reason code", key);
            }
        });
    }

    private Map<String, DecisionBinding> materializeParameters(
            RuleSetDefinition definition,
            Map<String, DecisionSlot> slots, Map<String, DecisionBinding> sourceBindings) {
        for (DecisionSlot slot : slots.values().stream()
                .sorted(Comparator.comparing(DecisionSlot::slotKey)).toList()) {
            if (slot.overridePolicy() == OverridePolicy.PARAMETERIZABLE) {
                RuleParameterResolver.validateTemplate(slot.parameterTemplate());
                var template = slot.parameterTemplate();
                var product = new DecisionBinding("template:" + slot.slotKey(), slot.slotKey(),
                        DecisionSource.PRODUCT, null, template.executor(), List.of(), 0, true,
                        template.falseDecision(), template.falseReasonCode(), template.requiredFactPaths(), null);
                validateExecutor(definition, product);
                validateRequiredFacts(definition, product);
            } else if (slot.parameterTemplate() != null) {
                throw failure(RulePlanIssueCode.PLAN_PARAMETER_TEMPLATE_INVALID,
                        "Only PARAMETERIZABLE slots may publish a parameter template", null);
            }
        }
        Map<String, DecisionBinding> derived = new LinkedHashMap<>();
        for (DecisionBinding binding : sourceBindings.values().stream()
                .sorted(Comparator.comparing(DecisionBinding::bindingKey)).toList()) {
            DecisionSlot slot = slots.get(binding.slotKey());
            if (slot == null) {
                throw failure(RulePlanIssueCode.PLAN_REFERENCE_MISSING,
                        "Binding references unknown slot: " + binding.slotKey(), binding.bindingKey());
            }
            validateComposition(binding, slot);
            if (binding.compositionPolicy() != CompositionPolicy.PARAMETERIZE) {
                if (binding.parameterization() != null) {
                    throw failure(RulePlanIssueCode.PLAN_PARAMETERIZATION_INVALID,
                            "Only PARAMETERIZE bindings may declare parameter values", binding.bindingKey());
                }
                derived.put(binding.bindingKey(), binding);
                continue;
            }
            RuleParameterization values = binding.parameterization();
            RuleParameterTemplate template = slot.parameterTemplate();
            if (values == null || template == null
                    || !template.templateKey().equals(values.templateKey())
                    || !template.templateVersion().equals(values.templateVersion())
                    || binding.executor() != null || binding.falseDecision() != null
                    || binding.falseReasonCode() != null || !binding.requiredFactPaths().isEmpty()) {
                throw failure(RulePlanIssueCode.PLAN_PARAMETERIZATION_INVALID,
                        "PARAMETERIZE requires the exact product template and values without execution metadata",
                        binding.bindingKey());
            }
            try {
                var resolved = RuleParameterResolver.resolve(template, values.values());
                derived.put(binding.bindingKey(), new DecisionBinding(
                        binding.bindingKey(), binding.slotKey(), binding.source(), binding.compositionPolicy(),
                        resolved.executor(), binding.dependsOn(), binding.order(), binding.enabled(),
                        template.falseDecision(), template.falseReasonCode(), template.requiredFactPaths(), values));
            } catch (RulePlanException exception) {
                throw failure(exception.getCode(), exception.getMessage(), binding.bindingKey());
            }
        }
        return Map.copyOf(derived);
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
        for (DecisionSlot slot : definition.slots()) {
            if (slot.requiredControlDependencies().size() > MAX_DEPENDENCIES_PER_BINDING) {
                throw failure(RulePlanIssueCode.PLAN_LIMIT_EXCEEDED,
                        "Slot exceeds mandatory control dependency limit: " + slot.slotKey(), null);
            }
        }
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
            if (!binding.dependsOn().containsAll(slot.requiredControlDependencies())) {
                throw failure(RulePlanIssueCode.PLAN_REQUIRED_CONTROL_MISSING,
                        "Binding omits a product-owned control dependency for slot: " + slot.slotKey(),
                        binding.bindingKey());
            }
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
            if ((slot.stage() == DecisionStage.EFFECT_INTENT
                            || slot.stage() == DecisionStage.TRANSFORMATION_INTENT)
                    && binding.dependsOn().stream()
                            .map(bindings::get)
                            .map(dependency -> slots.get(dependency.slotKey()).stage())
                            .noneMatch(stage -> stage == DecisionStage.DOMAIN_DECISION
                                    || stage == DecisionStage.POST_DECISION)) {
                throw failure(
                        RulePlanIssueCode.PLAN_DEPENDENCY_INVALID,
                        "Intent binding requires at least one direct domain or post decision dependency",
                        binding.bindingKey());
            }
        }
        slots.forEach((slotKey, slot) -> {
            if (slot.cardinality() == SlotCardinality.SINGLE && cardinality.getOrDefault(slotKey, 0) != 1) {
                throw failure(
                        RulePlanIssueCode.PLAN_CARDINALITY_INVALID,
                        "Single slot requires exactly one enabled binding: " + slotKey,
                        null);
            }
        });
    }

    private void validateControlDependencies(
            Map<String, DecisionSlot> slots, Map<String, DecisionBinding> bindings) {
        for (DecisionSlot slot : slots.values()) {
            for (String key : slot.requiredControlDependencies()) {
                DecisionBinding guard = bindings.get(key);
                DecisionSlot guardSlot = guard == null ? null : slots.get(guard.slotKey());
                if (guard == null || guard.source() != DecisionSource.PRODUCT || guardSlot == null
                        || guardSlot.stage() != DecisionStage.PROTECTED_GUARD
                        || guardSlot.overridePolicy() != OverridePolicy.FORBIDDEN) {
                    throw failure(RulePlanIssueCode.PLAN_REQUIRED_CONTROL_INVALID,
                            "Required control must reference an enabled protected product guard: " + key, key);
                }
            }
        }
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
        if (!RuleRuntimeCompatibility.JSON_LOGIC_DIALECT_VERSION.equals(
                binding.executor().implementationVersion())) {
            throw failure(
                    RulePlanIssueCode.PLAN_COMPATIBILITY_INVALID,
                    "JSON Logic executor uses an incompatible dialect version: "
                            + binding.executor().implementationVersion(),
                    binding.bindingKey());
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
            RulePlanIssueCode code = (exception.getCode() == org.praxisplatform.rules.jsonlogic.model.JsonLogicIssueCode.RULE_ROOT_UNKNOWN
                    || exception.getCode() == org.praxisplatform.rules.jsonlogic.model.JsonLogicIssueCode.RULE_CONTEXT_AMBIGUOUS)
                    ? RulePlanIssueCode.PLAN_REFERENCE_MISSING
                    : RulePlanIssueCode.PLAN_EXPRESSION_INVALID;
            throw failure(
                    code,
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
        Map<String, DecisionBinding> source = definition.bindings().stream()
                .collect(java.util.stream.Collectors.toMap(DecisionBinding::bindingKey, binding -> binding));
        var canonical = JSON.createObjectNode();
        canonical.set("ref", JSON.valueToTree(definition.ref()));
        canonical.set("availableRoots", JSON.valueToTree(definition.availableRoots()));
        canonical.set("compatibility", JSON.valueToTree(definition.compatibility()));
        canonical.put("failPolicy", definition.failPolicy().name());
        canonical.set("slots", JSON.valueToTree(definition.slots().stream()
                .sorted(Comparator.comparing(DecisionSlot::slotKey)).toList()));
        canonical.set("sourceBindings", JSON.valueToTree(ordered.stream()
                .map(binding -> source.get(binding.bindingKey())).toList()));
        canonical.set("compiledBindings", JSON.valueToTree(ordered));
        if (!definition.errorDescriptors().isEmpty()) {
            canonical.set("errorDescriptors", JSON.valueToTree(definition.errorDescriptors()));
        }
        canonical.set("implementations", JSON.valueToTree(implementationRefs(ordered)));
        return PraxisCanonicalJson.sha256(canonical);
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

    private void validateMinimalResultEnvelope(RuleDecisionPlan plan) {
        RuleEvaluationResult minimal = new RuleEvaluationResult(
                RuleDecision.TECHNICAL_ERROR,
                plan.definition().ref(),
                plan.planDigest(),
                List.of(),
                List.of("EVALUATION_RESULT_LIMIT_EXCEEDED"),
                "0".repeat(64),
                plan.definition().compatibility(),
                plan.implementationRefs(),
                plan.definition().failPolicy(),
                List.of());
        try {
            jsonLogicEngine.validateResultValue(JSON.valueToTree(minimal), JsonLogicLimits.DEFAULT);
        } catch (PraxisJsonLogicException exception) {
            throw failure(
                    RulePlanIssueCode.PLAN_LIMIT_EXCEEDED,
                    "RuleSet cannot produce a bounded public result envelope",
                    null);
        }
    }

    private RulePlanException failure(RulePlanIssueCode code, String message, String bindingKey) {
        return new RulePlanException(code, message, bindingKey);
    }
}
