package org.praxisplatform.rules.ontology;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.praxisplatform.rules.contract.CompositionPolicy;
import org.praxisplatform.rules.contract.DecisionAggregationPolicy;
import org.praxisplatform.rules.contract.DecisionBinding;
import org.praxisplatform.rules.contract.DecisionSlot;
import org.praxisplatform.rules.contract.DecisionSource;
import org.praxisplatform.rules.contract.DecisionStage;
import org.praxisplatform.rules.contract.OverridePolicy;
import org.praxisplatform.rules.contract.RuleDecision;
import org.praxisplatform.rules.contract.RuleExecutorRef;
import org.praxisplatform.rules.contract.RuleFailPolicy;
import org.praxisplatform.rules.contract.RuleParameterDefinition;
import org.praxisplatform.rules.contract.RuleParameterTemplate;
import org.praxisplatform.rules.contract.RuleParameterType;
import org.praxisplatform.rules.contract.RuleParameterization;
import org.praxisplatform.rules.contract.RuleRuntimeCompatibility;
import org.praxisplatform.rules.contract.RuleSetDefinition;
import org.praxisplatform.rules.contract.RuleSetRef;
import org.praxisplatform.rules.contract.SlotCardinality;
import org.praxisplatform.rules.facts.PraxisFactTreeBuilder;
import org.praxisplatform.rules.violation.MapRuleErrorCatalog;
import org.praxisplatform.rules.violation.RuleErrorCatalog;
import org.praxisplatform.rules.violation.RuleErrorDescriptor;

/**
 * Canonical declarative builder for governed {@link RuleSetDefinition}s over the 5 ontological roots
 * ({@code draft}, {@code current}, {@code <concept>}, {@code neighborhood}, {@code context}),
 * automatically inferring {@code availableRoots} and {@code requiredFactPaths} from the JSON Logic AST
 * and rejecting the flattened scalar root {@code facts.*}.
 */
public final class OntologyRuleSetBuilder {
    private static final ObjectMapper JSON = new ObjectMapper();

    private OntologyRuleSetBuilder() {}

    /**
     * Atomic declarative rule entry with 1:1 lineage and public error metadata.
     */
    public record Rule(
            String code,
            String field,
            String message,
            String origin,
            String lineage,
            String expression,
            OverridePolicy policy) {

        public Rule {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(message, "message");
            Objects.requireNonNull(expression, "expression");
            policy = policy == null ? OverridePolicy.FORBIDDEN : policy;
        }

        public Rule(
                String code,
                String field,
                String message,
                String origin,
                String lineage,
                String expression) {
            this(code, field, message, origin, lineage, expression, OverridePolicy.FORBIDDEN);
        }

        public static Rule replaceable(
                String code,
                String field,
                String message,
                String origin,
                String lineage,
                String expression) {
            return new Rule(
                    code, field, message, origin, lineage, expression, OverridePolicy.REPLACEABLE);
        }

        public String slot(String prefix) {
            return prefix + "." + code.toLowerCase(Locale.ROOT);
        }

        public RuleErrorDescriptor toRuleErrorDescriptor() {
            return new RuleErrorDescriptor(code, field, message, lineage, origin);
        }
    }

    /**
     * Builds an immutable {@link RuleErrorCatalog} directly from a collection of {@link Rule}s,
     * eliminating manual duplication between rule definitions and error catalog maps.
     */
    public static RuleErrorCatalog catalogOf(Collection<Rule> rules) {
        if (rules == null || rules.isEmpty()) {
            return new MapRuleErrorCatalog(Map.of());
        }
        Map<String, RuleErrorDescriptor> map = new LinkedHashMap<>();
        for (Rule rule : rules) {
            if (rule == null) {
                continue;
            }
            RuleErrorDescriptor descriptor = rule.toRuleErrorDescriptor();
            RuleErrorDescriptor existing = map.putIfAbsent(descriptor.code(), descriptor);
            if (existing != null && !existing.equals(descriptor)) {
                throw new IllegalArgumentException(
                        "Conflicting RuleErrorDescriptor for code: " + descriptor.code());
            }
        }
        return new MapRuleErrorCatalog(map);
    }

    public static RuleSetDefinition definition(
            RuleSetRef ref,
            String slotPrefix,
            String customerKeyPrefix,
            List<Rule> product,
            Rule customer,
            boolean includeCustomerBinding,
            Map<String, RuleParameterTemplate> templates) {
        List<DecisionSlot> slots = new ArrayList<>();
        List<DecisionBinding> bindings = new ArrayList<>();
        Map<String, RuleParameterTemplate> safeTemplates = templates == null ? Map.of() : templates;
        for (Rule rule : product) {
            String slot = rule.slot(slotPrefix);
            RuleParameterTemplate template = safeTemplates.get(rule.code());
            OverridePolicy policy = template == null ? rule.policy() : OverridePolicy.PARAMETERIZABLE;
            slots.add(
                    new DecisionSlot(
                            slot,
                            policy == OverridePolicy.FORBIDDEN
                                    ? DecisionStage.PROTECTED_GUARD
                                    : DecisionStage.DOMAIN_DECISION,
                            SlotCardinality.SINGLE,
                            policy,
                            DecisionAggregationPolicy.SINGLE_RESULT,
                            template,
                            List.of()));
            bindings.add(
                    binding(
                            "product." + slot,
                            slot,
                            DecisionSource.PRODUCT,
                            null,
                            rule.expression(),
                            rule.code(),
                            bindings.size() + 1));
        }
        if (customer != null) {
            String customerSlot = customer.slot(slotPrefix);
            slots.add(
                    new DecisionSlot(
                            customerSlot,
                            DecisionStage.DOMAIN_DECISION,
                            SlotCardinality.MULTIPLE,
                            OverridePolicy.RESTRICT_ONLY,
                            DecisionAggregationPolicy.DENY_OVERRIDES,
                            null,
                            List.of()));
            if (includeCustomerBinding) {
                String bindingKey = customerKeyPrefix + customerSlot;
                bindings.add(
                        binding(
                                bindingKey,
                                customerSlot,
                                DecisionSource.CUSTOMER,
                                CompositionPolicy.AUGMENT,
                                customer.expression(),
                                customer.code(),
                                bindings.size() + 1));
            }
        }
        return RuleSetDefinition.withInferredRoots(
                ref,
                slots,
                bindings,
                RuleRuntimeCompatibility.current(),
                RuleFailPolicy.FAIL_CLOSED);
    }

    /**
     * Attaches a customer {@link RuleErrorDescriptor} keyed by {@code bindingKey} as required by
     * {@link org.praxisplatform.rules.plan.PraxisRulePlanCompiler} and {@link org.praxisplatform.rules.violation.PraxisRuleViolationException}.
     */
    public static RuleSetDefinition withCustomerErrorDescriptor(
            RuleSetDefinition base, String bindingKey, Rule customer) {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(bindingKey, "bindingKey");
        Objects.requireNonNull(customer, "customer");
        Map<String, RuleErrorDescriptor> descriptors = new LinkedHashMap<>(base.errorDescriptors());
        descriptors.put(bindingKey, customer.toRuleErrorDescriptor());
        return new RuleSetDefinition(
                base.ref(),
                base.availableRoots(),
                base.slots(),
                base.bindings(),
                base.compatibility(),
                base.failPolicy(),
                descriptors);
    }

    public static RuleParameterTemplate integerTemplate(
            String code,
            String expression,
            String key,
            String parameter,
            int defaultValue,
            int maximum,
            String pointer,
            String unit) {
        DecisionBinding product =
                binding("product.template", "template", DecisionSource.PRODUCT, null, expression, code, 0);
        return new RuleParameterTemplate(
                key,
                "1.0",
                product.executor(),
                RuleDecision.DENY,
                code,
                product.requiredFactPaths(),
                List.of(
                        new RuleParameterDefinition(
                                parameter,
                                RuleParameterType.INTEGER,
                                true,
                                false,
                                false,
                                unit,
                                JSON.getNodeFactory().numberNode(defaultValue),
                                BigDecimal.ZERO,
                                BigDecimal.valueOf(maximum).stripTrailingZeros(),
                                List.of(),
                                List.of(pointer))));
    }

    public static RuleSetDefinition parameterize(
            RuleSetDefinition base, Map<String, Integer> values) {
        List<DecisionBinding> bindings = new ArrayList<>();
        for (DecisionBinding binding : base.bindings()) {
            DecisionSlot slot =
                    base.slots().stream()
                            .filter(s -> s.slotKey().equals(binding.slotKey()))
                            .findFirst()
                            .orElseThrow();
            Integer value =
                    values.get(binding.falseReasonCode() == null ? "" : binding.falseReasonCode());
            RuleParameterTemplate template = slot.parameterTemplate();
            if (binding.source() == DecisionSource.PRODUCT
                    && value != null
                    && template != null
                    && base.bindings().stream()
                            .noneMatch(
                                    b ->
                                            b.slotKey().equals(slot.slotKey())
                                                    && b.source() == DecisionSource.CUSTOMER
                                                    && b.enabled())) {
                bindings.add(
                        new DecisionBinding(
                                binding.bindingKey(),
                                binding.slotKey(),
                                binding.source(),
                                binding.compositionPolicy(),
                                binding.executor(),
                                binding.dependsOn(),
                                binding.order(),
                                false,
                                binding.falseDecision(),
                                binding.falseReasonCode(),
                                binding.requiredFactPaths(),
                                binding.parameterization()));
                var parameters =
                        JSON.createObjectNode().put(template.parameters().getFirst().name(), value);
                bindings.add(
                        new DecisionBinding(
                                "customer.config." + slot.slotKey(),
                                slot.slotKey(),
                                DecisionSource.CUSTOMER,
                                CompositionPolicy.PARAMETERIZE,
                                null,
                                List.of(),
                                binding.order(),
                                true,
                                null,
                                null,
                                List.of(),
                                new RuleParameterization(
                                        template.templateKey(), template.templateVersion(), parameters)));
            } else {
                bindings.add(binding);
            }
        }
        return new RuleSetDefinition(
                base.ref(),
                base.availableRoots(),
                base.slots(),
                bindings,
                base.compatibility(),
                base.failPolicy(),
                base.errorDescriptors());
    }

    public static DecisionBinding binding(
            String key,
            String slot,
            DecisionSource source,
            CompositionPolicy policy,
            String expression,
            String code,
            int order) {
        try {
            JsonNode tree = JSON.readTree(expression);
            List<String> requiredRoots = roots(tree);
            if (requiredRoots.contains("facts")) {
                throw new IllegalStateException(
                        "Raiz escalar achatada 'facts' proibida na ontologia Praxis para " + key);
            }
            return new DecisionBinding(
                    key,
                    slot,
                    source,
                    policy,
                    RuleExecutorRef.jsonLogic(tree),
                    List.of(),
                    order,
                    true,
                    RuleDecision.DENY,
                    code,
                    requiredRoots,
                    null);
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("Expressão JSON Logic inválida: " + key, invalid);
        }
    }

    /**
     * Upgrades bindings and parameter templates in {@code base} to require exact leaf fact paths
     * referenced by their JSON Logic AST.
     */
    public static RuleSetDefinition requireReferencedFactPaths(RuleSetDefinition base) {
        List<DecisionBinding> bindings =
                base.bindings().stream()
                        .map(
                                binding ->
                                        new DecisionBinding(
                                                binding.bindingKey(),
                                                binding.slotKey(),
                                                binding.source(),
                                                binding.compositionPolicy(),
                                                binding.executor(),
                                                binding.dependsOn(),
                                                binding.order(),
                                                binding.enabled(),
                                                binding.falseDecision(),
                                                binding.falseReasonCode(),
                                                binding.executor() != null
                                                                && binding.executor().expression() != null
                                                        ? factPaths(binding.executor().expression())
                                                        : binding.requiredFactPaths(),
                                                binding.parameterization()))
                        .toList();
        List<DecisionSlot> slots =
                base.slots().stream()
                        .map(
                                slot -> {
                                    RuleParameterTemplate template = slot.parameterTemplate();
                                    if (template == null) {
                                        return slot;
                                    }
                                    RuleParameterTemplate exactTemplate =
                                            new RuleParameterTemplate(
                                                    template.templateKey(),
                                                    template.templateVersion(),
                                                    template.executor(),
                                                    template.falseDecision(),
                                                    template.falseReasonCode(),
                                                    factPaths(template.executor().expression()),
                                                    template.parameters());
                                    return new DecisionSlot(
                                            slot.slotKey(),
                                            slot.stage(),
                                            slot.cardinality(),
                                            slot.overridePolicy(),
                                            slot.aggregationPolicy(),
                                            exactTemplate,
                                            slot.requiredControlDependencies());
                                })
                        .toList();
        return RuleSetDefinition.withInferredRoots(
                base.ref(),
                slots,
                bindings,
                base.compatibility(),
                base.failPolicy(),
                base.errorDescriptors());
    }

    public static List<String> roots(JsonNode node) {
        return PraxisFactTreeBuilder.extractReferencedRoots(node);
    }

    public static List<String> factPaths(JsonNode node) {
        return PraxisFactTreeBuilder.extractReferencedFactPaths(node);
    }

    public static List<String> mergeRequiredRoots(
            List<String> baseRequiredFactPaths, JsonNode expression) {
        List<String> merged =
                new ArrayList<>(baseRequiredFactPaths == null ? List.of() : baseRequiredFactPaths);
        for (String root : roots(expression)) {
            boolean covered =
                    merged.stream().anyMatch(path -> path.equals(root) || path.startsWith(root + "."));
            if (!covered) {
                merged.add(root);
            }
        }
        return List.copyOf(merged);
    }

    public static String eqVar(String factPath, String literal) {
        return "{\"==\":[{\"var\":\"" + factPath + "\"},\"" + literal + "\"]}";
    }

    public static String neVar(String factPath, String literal) {
        return "{\"!=\":[{\"var\":\"" + factPath + "\"},\"" + literal + "\"]}";
    }

    public static String gtVar(String factPath, Number threshold) {
        return "{\">\":[{\"var\":\"" + factPath + "\"}," + threshold + "]}";
    }

    public static String unchanged(String fieldName) {
        return "{\"==\":[{\"var\":\"draft."
                + fieldName
                + "\"},{\"var\":\"current."
                + fieldName
                + "\"}]}";
    }
}
