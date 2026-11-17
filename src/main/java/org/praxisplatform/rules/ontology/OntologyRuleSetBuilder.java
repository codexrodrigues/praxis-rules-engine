package org.praxisplatform.rules.ontology;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
     * Atomic declarative rule entry with 1:1 lineage, public error metadata, and optional execution stage.
     *
     * @param code unique machine-readable rule/error code
     * @param field target field path in the public request payload, or {@code null}
     * @param message curated public domain error message
     * @param origin rule origin reference
     * @param lineage curated public lineage description
     * @param expression canonical JSON Logic expression string
     * @param policy customization override boundary (defaults to {@link OverridePolicy#FORBIDDEN})
     * @param stage optional explicit {@link DecisionStage}; when {@code null}, product rules default
     *     to {@link DecisionStage#PROTECTED_GUARD} for {@code FORBIDDEN} and
     *     {@link DecisionStage#DOMAIN_DECISION} otherwise, while customer rules default to
     *     {@link DecisionStage#DOMAIN_DECISION}
     */
    public record Rule(
            String code,
            String field,
            String message,
            String origin,
            String lineage,
            String expression,
            OverridePolicy policy,
            DecisionStage stage) {

        /** Normalizes required fields and defaults a {@code null} override policy to {@link OverridePolicy#FORBIDDEN}. */
        public Rule {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(message, "message");
            Objects.requireNonNull(expression, "expression");
            policy = policy == null ? OverridePolicy.FORBIDDEN : policy;
        }

        /**
         * Creates a rule with an inferred stage based on {@code policy}.
         *
         * @param code unique rule/error code
         * @param field target field path
         * @param message public domain error message
         * @param origin rule origin reference
         * @param lineage curated public lineage
         * @param expression JSON Logic expression string
         * @param policy override policy
         */
        public Rule(
                String code,
                String field,
                String message,
                String origin,
                String lineage,
                String expression,
                OverridePolicy policy) {
            this(code, field, message, origin, lineage, expression, policy, null);
        }

        /**
         * Creates a non-overridable ({@link OverridePolicy#FORBIDDEN}) rule with inferred stage.
         *
         * @param code unique rule/error code
         * @param field target field path
         * @param message public domain error message
         * @param origin rule origin reference
         * @param lineage curated public lineage
         * @param expression JSON Logic expression string
         */
        public Rule(
                String code,
                String field,
                String message,
                String origin,
                String lineage,
                String expression) {
            this(code, field, message, origin, lineage, expression, OverridePolicy.FORBIDDEN, null);
        }

        /**
         * Creates a non-overridable rule from a Jackson JSON Logic AST node.
         *
         * @param code unique rule/error code
         * @param field target field path
         * @param message public domain error message
         * @param origin rule origin reference
         * @param lineage curated public lineage
         * @param expression JSON Logic expression tree
         */
        public Rule(
                String code,
                String field,
                String message,
                String origin,
                String lineage,
                JsonNode expression) {
            this(code, field, message, origin, lineage, serializeExpression(expression), OverridePolicy.FORBIDDEN, null);
        }

        /**
         * Creates a rule from a Jackson JSON Logic AST node with explicit override policy.
         *
         * @param code unique rule/error code
         * @param field target field path
         * @param message public domain error message
         * @param origin rule origin reference
         * @param lineage curated public lineage
         * @param expression JSON Logic expression tree
         * @param policy override policy
         */
        public Rule(
                String code,
                String field,
                String message,
                String origin,
                String lineage,
                JsonNode expression,
                OverridePolicy policy) {
            this(code, field, message, origin, lineage, serializeExpression(expression), policy, null);
        }

        /**
         * Creates a rule from a Jackson JSON Logic AST node with explicit policy and stage.
         *
         * @param code unique rule/error code
         * @param field target field path
         * @param message public domain error message
         * @param origin rule origin reference
         * @param lineage curated public lineage
         * @param expression JSON Logic expression tree
         * @param policy override policy
         * @param stage optional execution stage
         */
        public Rule(
                String code,
                String field,
                String message,
                String origin,
                String lineage,
                JsonNode expression,
                OverridePolicy policy,
                DecisionStage stage) {
            this(code, field, message, origin, lineage, serializeExpression(expression), policy, stage);
        }

        /**
         * Creates a non-overridable rule from a {@link PraxisJsonLogic.Expr}.
         *
         * @param code unique rule/error code
         * @param field target field path
         * @param message public domain error message
         * @param origin rule origin reference
         * @param lineage curated public lineage
         * @param expression typed JSON Logic DSL expression
         */
        public Rule(
                String code,
                String field,
                String message,
                String origin,
                String lineage,
                PraxisJsonLogic.Expr expression) {
            this(
                    code,
                    field,
                    message,
                    origin,
                    lineage,
                    Objects.requireNonNull(expression, "expression").toJson(),
                    OverridePolicy.FORBIDDEN,
                    null);
        }

        /**
         * Creates a rule from a {@link PraxisJsonLogic.Expr} with explicit override policy.
         *
         * @param code unique rule/error code
         * @param field target field path
         * @param message public domain error message
         * @param origin rule origin reference
         * @param lineage curated public lineage
         * @param expression typed JSON Logic DSL expression
         * @param policy override policy
         */
        public Rule(
                String code,
                String field,
                String message,
                String origin,
                String lineage,
                PraxisJsonLogic.Expr expression,
                OverridePolicy policy) {
            this(
                    code,
                    field,
                    message,
                    origin,
                    lineage,
                    Objects.requireNonNull(expression, "expression").toJson(),
                    policy,
                    null);
        }

        /**
         * Creates a rule from a {@link PraxisJsonLogic.Expr} with explicit override policy and stage.
         *
         * @param code unique rule/error code
         * @param field target field path
         * @param message public domain error message
         * @param origin rule origin reference
         * @param lineage curated public lineage
         * @param expression typed JSON Logic DSL expression
         * @param policy override policy
         * @param stage optional execution stage
         */
        public Rule(
                String code,
                String field,
                String message,
                String origin,
                String lineage,
                PraxisJsonLogic.Expr expression,
                OverridePolicy policy,
                DecisionStage stage) {
            this(
                    code,
                    field,
                    message,
                    origin,
                    lineage,
                    Objects.requireNonNull(expression, "expression").toJson(),
                    policy,
                    stage);
        }

        /**
         * Creates a replaceable ({@link OverridePolicy#REPLACEABLE}) rule from a JSON string expression.
         *
         * @param code unique rule/error code
         * @param field target field path
         * @param message public domain error message
         * @param origin rule origin reference
         * @param lineage curated public lineage
         * @param expression JSON Logic expression string
         * @return replaceable rule entry
         */
        public static Rule replaceable(
                String code,
                String field,
                String message,
                String origin,
                String lineage,
                String expression) {
            return new Rule(
                    code, field, message, origin, lineage, expression, OverridePolicy.REPLACEABLE, null);
        }

        /**
         * Creates a replaceable ({@link OverridePolicy#REPLACEABLE}) rule from a Jackson AST node.
         *
         * @param code unique rule/error code
         * @param field target field path
         * @param message public domain error message
         * @param origin rule origin reference
         * @param lineage curated public lineage
         * @param expression JSON Logic expression tree
         * @return replaceable rule entry
         */
        public static Rule replaceable(
                String code,
                String field,
                String message,
                String origin,
                String lineage,
                JsonNode expression) {
            return new Rule(
                    code, field, message, origin, lineage, expression, OverridePolicy.REPLACEABLE, null);
        }

        /**
         * Creates a replaceable ({@link OverridePolicy#REPLACEABLE}) rule from a {@link PraxisJsonLogic.Expr}.
         *
         * @param code unique rule/error code
         * @param field target field path
         * @param message public domain error message
         * @param origin rule origin reference
         * @param lineage curated public lineage
         * @param expression typed JSON Logic DSL expression
         * @return replaceable rule entry
         */
        public static Rule replaceable(
                String code,
                String field,
                String message,
                String origin,
                String lineage,
                PraxisJsonLogic.Expr expression) {
            return new Rule(
                    code, field, message, origin, lineage, expression, OverridePolicy.REPLACEABLE, null);
        }

        /**
         * Returns a copy of this rule pinned to an explicit {@link DecisionStage}.
         *
         * @param stage execution stage (may be {@code null} for stage inference)
         * @return rule copy with the specified stage
         */
        public Rule withStage(DecisionStage stage) {
            return new Rule(code, field, message, origin, lineage, expression, policy, stage);
        }

        /**
         * Computes the deterministic slot key for this rule under {@code prefix}.
         *
         * @param prefix slot prefix
         * @return normalized slot key {@code <prefix>.<lowercase-code>}
         */
        public String slot(String prefix) {
            return prefix + "." + code.toLowerCase(Locale.ROOT);
        }

        /**
         * Converts this rule into a public {@link RuleErrorDescriptor}.
         *
         * @return immutable error descriptor
         */
        public RuleErrorDescriptor toRuleErrorDescriptor() {
            return new RuleErrorDescriptor(code, field, message, lineage, origin);
        }
    }

    /**
     * Combined result pairing a governed {@link RuleSetDefinition} (carrying any attached customer
     * error descriptors) with immutable product and combined {@link RuleErrorCatalog} views.
     *
     * @param definition built {@link RuleSetDefinition}
     * @param productCatalog error catalog containing only product rules (safe for
     *     {@link org.praxisplatform.rules.snapshot.RuleSetProductAdmissionVerifier} reserved-code checks)
     * @param combinedCatalog merged error catalog containing both product and customer rules
     */
    public record RuleSetCatalogBundle(
            RuleSetDefinition definition,
            RuleErrorCatalog productCatalog,
            RuleErrorCatalog combinedCatalog) {

        /** Ensures all bundle components are non-null. */
        public RuleSetCatalogBundle {
            Objects.requireNonNull(definition, "definition");
            Objects.requireNonNull(productCatalog, "productCatalog");
            Objects.requireNonNull(combinedCatalog, "combinedCatalog");
        }
    }

    /**
     * Builds an immutable {@link RuleErrorCatalog} directly from a collection of {@link Rule}s,
     * eliminating manual duplication between rule definitions and error catalog maps.
     *
     * @param rules rule definitions to index
     * @return immutable {@link RuleErrorCatalog}
     * @throws IllegalArgumentException when two entries declare conflicting descriptors for the same code
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

    /**
     * Builds a combined immutable {@link RuleErrorCatalog} across product and customer rule collections,
     * failing closed on conflicting error codes.
     *
     * @param productRules product rule collection
     * @param customerRules customer rule collection
     * @return merged immutable {@link RuleErrorCatalog}
     */
    public static RuleErrorCatalog catalogOf(
            Collection<Rule> productRules, Collection<Rule> customerRules) {
        return RuleErrorCatalog.merge(List.of(catalogOf(productRules), catalogOf(customerRules)));
    }

    /**
     * Builds a governed {@link RuleSetDefinition} from product rules and an optional single customer rule.
     *
     * @param ref RuleSet identity
     * @param slotPrefix slot key prefix
     * @param customerKeyPrefix prefix prepended to customer slot keys when building customer binding keys
     * @param product product rule entries
     * @param customer optional single customer rule (may be {@code null})
     * @param includeCustomerBinding whether to emit an enabled customer binding for {@code customer}
     * @param templates optional parameter templates keyed by rule code
     * @return immutable {@link RuleSetDefinition} with inferred ontological roots
     */
    public static RuleSetDefinition definition(
            RuleSetRef ref,
            String slotPrefix,
            String customerKeyPrefix,
            List<Rule> product,
            Rule customer,
            boolean includeCustomerBinding,
            Map<String, RuleParameterTemplate> templates) {
        return definition(
                ref,
                slotPrefix,
                customerKeyPrefix,
                product,
                customer == null ? List.of() : List.of(customer),
                includeCustomerBinding,
                templates);
    }

    /**
     * Builds a governed {@link RuleSetDefinition} from product rules and zero or more customer rules.
     *
     * @param ref RuleSet identity
     * @param slotPrefix slot key prefix
     * @param customerKeyPrefix prefix prepended to customer slot keys when building customer binding keys
     * @param product product rule entries
     * @param customerRules zero or more customer rule entries (may be {@code null} or empty)
     * @param includeCustomerBindings whether to emit enabled customer bindings for {@code customerRules}
     * @param templates optional parameter templates keyed by rule code
     * @return immutable {@link RuleSetDefinition} with inferred ontological roots
     */
    public static RuleSetDefinition definition(
            RuleSetRef ref,
            String slotPrefix,
            String customerKeyPrefix,
            List<Rule> product,
            List<Rule> customerRules,
            boolean includeCustomerBindings,
            Map<String, RuleParameterTemplate> templates) {
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(slotPrefix, "slotPrefix");
        Objects.requireNonNull(customerKeyPrefix, "customerKeyPrefix");
        List<DecisionSlot> slots = new ArrayList<>();
        List<DecisionBinding> bindings = new ArrayList<>();
        Set<String> declaredSlotKeys = new LinkedHashSet<>();
        Map<String, RuleParameterTemplate> safeTemplates = templates == null ? Map.of() : templates;
        List<Rule> safeProduct = product == null ? List.of() : product;
        for (Rule rule : safeProduct) {
            if (rule == null) {
                continue;
            }
            String slot = rule.slot(slotPrefix);
            RuleParameterTemplate template = safeTemplates.get(rule.code());
            OverridePolicy policy = template == null ? rule.policy() : OverridePolicy.PARAMETERIZABLE;
            DecisionStage stage =
                    rule.stage() != null
                            ? rule.stage()
                            : (policy == OverridePolicy.FORBIDDEN
                                    ? DecisionStage.PROTECTED_GUARD
                                    : DecisionStage.DOMAIN_DECISION);
            if (declaredSlotKeys.add(slot)) {
                slots.add(
                        new DecisionSlot(
                                slot,
                                stage,
                                SlotCardinality.SINGLE,
                                policy,
                                DecisionAggregationPolicy.SINGLE_RESULT,
                                template,
                                List.of()));
            }
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
        List<Rule> safeCustomerRules = customerRules == null ? List.of() : customerRules;
        for (Rule customer : safeCustomerRules) {
            if (customer == null) {
                continue;
            }
            String customerSlot = customer.slot(slotPrefix);
            DecisionStage customerStage =
                    customer.stage() != null ? customer.stage() : DecisionStage.DOMAIN_DECISION;
            if (declaredSlotKeys.add(customerSlot)) {
                slots.add(
                        new DecisionSlot(
                                customerSlot,
                                customerStage,
                                SlotCardinality.MULTIPLE,
                                OverridePolicy.RESTRICT_ONLY,
                                DecisionAggregationPolicy.DENY_OVERRIDES,
                                null,
                                List.of()));
            }
            if (includeCustomerBindings) {
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
     * Builds a {@link RuleSetCatalogBundle} containing the {@link RuleSetDefinition} (with customer
     * error descriptors attached for enabled customer bindings) and its product/combined error catalogs.
     *
     * @param ref RuleSet identity
     * @param slotPrefix slot key prefix
     * @param customerKeyPrefix prefix prepended to customer slot keys when building customer binding keys
     * @param product product rule entries
     * @param customer optional single customer rule
     * @param includeCustomerBinding whether to emit an enabled customer binding
     * @param templates optional parameter templates keyed by rule code
     * @return combined definition and error catalog bundle
     */
    public static RuleSetCatalogBundle definitionWithCatalog(
            RuleSetRef ref,
            String slotPrefix,
            String customerKeyPrefix,
            List<Rule> product,
            Rule customer,
            boolean includeCustomerBinding,
            Map<String, RuleParameterTemplate> templates) {
        return definitionWithCatalog(
                ref,
                slotPrefix,
                customerKeyPrefix,
                product,
                customer == null ? List.of() : List.of(customer),
                includeCustomerBinding,
                templates);
    }

    /**
     * Builds a {@link RuleSetCatalogBundle} containing the {@link RuleSetDefinition} (with customer
     * error descriptors attached for all enabled customer bindings) and its product/combined error catalogs.
     *
     * @param ref RuleSet identity
     * @param slotPrefix slot key prefix
     * @param customerKeyPrefix prefix prepended to customer slot keys when building customer binding keys
     * @param product product rule entries
     * @param customerRules zero or more customer rule entries
     * @param includeCustomerBindings whether to emit enabled customer bindings
     * @param templates optional parameter templates keyed by rule code
     * @return combined definition and error catalog bundle
     */
    public static RuleSetCatalogBundle definitionWithCatalog(
            RuleSetRef ref,
            String slotPrefix,
            String customerKeyPrefix,
            List<Rule> product,
            List<Rule> customerRules,
            boolean includeCustomerBindings,
            Map<String, RuleParameterTemplate> templates) {
        RuleSetDefinition base =
                definition(
                        ref,
                        slotPrefix,
                        customerKeyPrefix,
                        product,
                        customerRules,
                        includeCustomerBindings,
                        templates);
        return withProductAndCustomerErrorDescriptors(
                base, customerKeyPrefix, slotPrefix, product, customerRules);
    }

    /**
     * Attaches a customer {@link RuleErrorDescriptor} keyed by {@code bindingKey} as required by
     * {@link org.praxisplatform.rules.plan.PraxisRulePlanCompiler} and
     * {@link org.praxisplatform.rules.violation.PraxisRuleViolationException}.
     *
     * @param base base definition
     * @param bindingKey exact customer binding key
     * @param customer customer rule supplying the descriptor
     * @return updated definition with the descriptor attached
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

    /**
     * Attaches public {@link RuleErrorDescriptor} entries to {@code base} for every rule in
     * {@code customerRules} whose {@code customerKeyPrefix + customer.slot(slotPrefix)} matches an
     * enabled {@link DecisionSource#CUSTOMER} binding in {@code base}.
     *
     * @param base base definition
     * @param customerKeyPrefix prefix prepended to customer slot keys
     * @param slotPrefix slot key prefix
     * @param customerRules customer rules to register
     * @return updated {@link RuleSetDefinition}
     */
    public static RuleSetDefinition withCustomerErrorDescriptors(
            RuleSetDefinition base,
            String customerKeyPrefix,
            String slotPrefix,
            List<Rule> customerRules) {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(customerKeyPrefix, "customerKeyPrefix");
        Objects.requireNonNull(slotPrefix, "slotPrefix");
        if (customerRules == null || customerRules.isEmpty()) {
            return base;
        }
        Set<String> enabledCustomerBindings = new LinkedHashSet<>();
        for (DecisionBinding binding : base.bindings()) {
            if (binding.enabled() && binding.source() == DecisionSource.CUSTOMER) {
                enabledCustomerBindings.add(binding.bindingKey());
            }
        }
        if (enabledCustomerBindings.isEmpty()) {
            return base;
        }
        Map<String, RuleErrorDescriptor> descriptors = new LinkedHashMap<>(base.errorDescriptors());
        for (Rule customer : customerRules) {
            if (customer == null) {
                continue;
            }
            String bindingKey = customerKeyPrefix + customer.slot(slotPrefix);
            if (enabledCustomerBindings.contains(bindingKey)) {
                descriptors.put(bindingKey, customer.toRuleErrorDescriptor());
            }
        }
        return new RuleSetDefinition(
                base.ref(),
                base.availableRoots(),
                base.slots(),
                base.bindings(),
                base.compatibility(),
                base.failPolicy(),
                descriptors);
    }

    /**
     * Attaches customer error descriptors for enabled customer bindings on {@code base} while indexing
     * product and combined {@link RuleErrorCatalog}s in a single {@link RuleSetCatalogBundle}.
     *
     * <p>Per {@link org.praxisplatform.rules.plan.PraxisRulePlanCompiler}, {@code RuleSetDefinition.errorDescriptors}
     * accepts only {@link DecisionSource#CUSTOMER} bindings, whereas product violations are resolved via
     * the product {@link RuleErrorCatalog}.</p>
     *
     * @param base base definition
     * @param customerKeyPrefix prefix prepended to customer slot keys
     * @param slotPrefix slot key prefix
     * @param product product rule entries
     * @param customerRules customer rule entries
     * @return bundle containing the updated definition and error catalogs
     */
    public static RuleSetCatalogBundle withProductAndCustomerErrorDescriptors(
            RuleSetDefinition base,
            String customerKeyPrefix,
            String slotPrefix,
            List<Rule> product,
            List<Rule> customerRules) {
        RuleSetDefinition updated =
                withCustomerErrorDescriptors(base, customerKeyPrefix, slotPrefix, customerRules);
        return new RuleSetCatalogBundle(
                updated, catalogOf(product), catalogOf(product, customerRules));
    }

    /**
     * Creates a single-parameter {@link RuleParameterType#INTEGER} template with minimum {@code 0}.
     *
     * @param code false reason code
     * @param expression JSON Logic expression string containing the literal site
     * @param key namespaced template key (must contain {@code ':'})
     * @param parameter parameter name
     * @param defaultValue default integer matching the original expression literal
     * @param maximum inclusive upper bound
     * @param pointer RFC 6901 pointer to the literal site
     * @param unit semantic unit label (or {@code null})
     * @return immutable parameter template
     */
    public static RuleParameterTemplate integerTemplate(
            String code,
            String expression,
            String key,
            String parameter,
            int defaultValue,
            int maximum,
            String pointer,
            String unit) {
        return integerTemplate(code, expression, key, parameter, defaultValue, 0, maximum, pointer, unit);
    }

    /**
     * Creates a single-parameter {@link RuleParameterType#INTEGER} template with explicit inclusive bounds.
     *
     * @param code false reason code
     * @param expression JSON Logic expression string containing the literal site
     * @param key namespaced template key (must contain {@code ':'})
     * @param parameter parameter name
     * @param defaultValue default integer matching the original expression literal
     * @param minimum inclusive lower bound
     * @param maximum inclusive upper bound
     * @param pointer RFC 6901 pointer to the literal site
     * @param unit semantic unit label (or {@code null})
     * @return immutable parameter template
     */
    public static RuleParameterTemplate integerTemplate(
            String code,
            String expression,
            String key,
            String parameter,
            int defaultValue,
            int minimum,
            int maximum,
            String pointer,
            String unit) {
        DecisionBinding product =
                binding("product.template", "template", DecisionSource.PRODUCT, null, expression, code, 0);
        return buildTemplate(
                product,
                code,
                key,
                parameter,
                RuleParameterType.INTEGER,
                JSON.getNodeFactory().numberNode(defaultValue),
                BigDecimal.valueOf(minimum),
                BigDecimal.valueOf(maximum).stripTrailingZeros(),
                List.of(),
                pointer,
                unit);
    }

    /**
     * Creates a single-parameter {@link RuleParameterType#INTEGER} template from a {@link JsonNode}.
     *
     * @param code false reason code
     * @param expression JSON Logic AST tree containing the literal site
     * @param key namespaced template key
     * @param parameter parameter name
     * @param defaultValue default integer matching the original literal
     * @param minimum inclusive lower bound
     * @param maximum inclusive upper bound
     * @param pointer RFC 6901 pointer to the literal site
     * @param unit semantic unit label (or {@code null})
     * @return immutable parameter template
     */
    public static RuleParameterTemplate integerTemplate(
            String code,
            JsonNode expression,
            String key,
            String parameter,
            int defaultValue,
            int minimum,
            int maximum,
            String pointer,
            String unit) {
        DecisionBinding product =
                binding("product.template", "template", DecisionSource.PRODUCT, null, expression, code, 0);
        return buildTemplate(
                product,
                code,
                key,
                parameter,
                RuleParameterType.INTEGER,
                JSON.getNodeFactory().numberNode(defaultValue),
                BigDecimal.valueOf(minimum),
                BigDecimal.valueOf(maximum).stripTrailingZeros(),
                List.of(),
                pointer,
                unit);
    }

    /**
     * Creates a single-parameter {@link RuleParameterType#INTEGER} template from a {@link PraxisJsonLogic.Expr}.
     *
     * @param code false reason code
     * @param expression DSL expression containing the literal site
     * @param key namespaced template key
     * @param parameter parameter name
     * @param defaultValue default integer matching the original literal
     * @param minimum inclusive lower bound
     * @param maximum inclusive upper bound
     * @param pointer RFC 6901 pointer to the literal site
     * @param unit semantic unit label (or {@code null})
     * @return immutable parameter template
     */
    public static RuleParameterTemplate integerTemplate(
            String code,
            PraxisJsonLogic.Expr expression,
            String key,
            String parameter,
            int defaultValue,
            int minimum,
            int maximum,
            String pointer,
            String unit) {
        return integerTemplate(
                code,
                Objects.requireNonNull(expression, "expression").toJsonNode(),
                key,
                parameter,
                defaultValue,
                minimum,
                maximum,
                pointer,
                unit);
    }

    /**
     * Creates a single-parameter {@link RuleParameterType#NUMBER} (decimal) template.
     *
     * @param code false reason code
     * @param expression JSON Logic expression string containing the literal site
     * @param key namespaced template key (must contain {@code ':'})
     * @param parameter parameter name
     * @param defaultValue default decimal value matching the original expression literal
     * @param minimum optional inclusive lower bound (or {@code null})
     * @param maximum optional inclusive upper bound (or {@code null})
     * @param pointer RFC 6901 pointer to the literal site
     * @param unit semantic unit label (or {@code null})
     * @return immutable parameter template
     */
    public static RuleParameterTemplate decimalTemplate(
            String code,
            String expression,
            String key,
            String parameter,
            BigDecimal defaultValue,
            BigDecimal minimum,
            BigDecimal maximum,
            String pointer,
            String unit) {
        Objects.requireNonNull(defaultValue, "defaultValue");
        DecisionBinding product =
                binding("product.template", "template", DecisionSource.PRODUCT, null, expression, code, 0);
        return buildTemplate(
                product,
                code,
                key,
                parameter,
                RuleParameterType.NUMBER,
                JSON.getNodeFactory().numberNode(defaultValue.stripTrailingZeros()),
                minimum,
                maximum,
                List.of(),
                pointer,
                unit);
    }

    /**
     * Creates a single-parameter {@link RuleParameterType#NUMBER} (decimal) template from a {@link JsonNode}.
     *
     * @param code false reason code
     * @param expression JSON Logic AST tree containing the literal site
     * @param key namespaced template key
     * @param parameter parameter name
     * @param defaultValue default decimal value matching the original expression literal
     * @param minimum optional inclusive lower bound (or {@code null})
     * @param maximum optional inclusive upper bound (or {@code null})
     * @param pointer RFC 6901 pointer to the literal site
     * @param unit semantic unit label (or {@code null})
     * @return immutable parameter template
     */
    public static RuleParameterTemplate decimalTemplate(
            String code,
            JsonNode expression,
            String key,
            String parameter,
            BigDecimal defaultValue,
            BigDecimal minimum,
            BigDecimal maximum,
            String pointer,
            String unit) {
        Objects.requireNonNull(defaultValue, "defaultValue");
        DecisionBinding product =
                binding("product.template", "template", DecisionSource.PRODUCT, null, expression, code, 0);
        return buildTemplate(
                product,
                code,
                key,
                parameter,
                RuleParameterType.NUMBER,
                JSON.getNodeFactory().numberNode(defaultValue.stripTrailingZeros()),
                minimum,
                maximum,
                List.of(),
                pointer,
                unit);
    }

    /**
     * Creates a single-parameter {@link RuleParameterType#NUMBER} (decimal) template from a {@link PraxisJsonLogic.Expr}.
     *
     * @param code false reason code
     * @param expression DSL expression containing the literal site
     * @param key namespaced template key
     * @param parameter parameter name
     * @param defaultValue default decimal value matching the original expression literal
     * @param minimum optional inclusive lower bound (or {@code null})
     * @param maximum optional inclusive upper bound (or {@code null})
     * @param pointer RFC 6901 pointer to the literal site
     * @param unit semantic unit label (or {@code null})
     * @return immutable parameter template
     */
    public static RuleParameterTemplate decimalTemplate(
            String code,
            PraxisJsonLogic.Expr expression,
            String key,
            String parameter,
            BigDecimal defaultValue,
            BigDecimal minimum,
            BigDecimal maximum,
            String pointer,
            String unit) {
        return decimalTemplate(
                code,
                Objects.requireNonNull(expression, "expression").toJsonNode(),
                key,
                parameter,
                defaultValue,
                minimum,
                maximum,
                pointer,
                unit);
    }

    /**
     * Creates a single-parameter {@link RuleParameterType#STRING} template without enum restrictions.
     *
     * @param code false reason code
     * @param expression JSON Logic expression string containing the literal site
     * @param key namespaced template key
     * @param parameter parameter name
     * @param defaultValue default string matching the original literal
     * @param pointer RFC 6901 pointer to the literal site
     * @param unit semantic unit label (or {@code null})
     * @return immutable parameter template
     */
    public static RuleParameterTemplate stringTemplate(
            String code,
            String expression,
            String key,
            String parameter,
            String defaultValue,
            String pointer,
            String unit) {
        return stringTemplate(code, expression, key, parameter, defaultValue, List.of(), pointer, unit);
    }

    /**
     * Creates a single-parameter {@link RuleParameterType#STRING} template with optional allowed values.
     *
     * @param code false reason code
     * @param expression JSON Logic expression string containing the literal site
     * @param key namespaced template key
     * @param parameter parameter name
     * @param defaultValue default string matching the original literal
     * @param allowedValues optional closed enumeration of allowed strings (empty for unrestricted)
     * @param pointer RFC 6901 pointer to the literal site
     * @param unit semantic unit label (or {@code null})
     * @return immutable parameter template
     */
    public static RuleParameterTemplate stringTemplate(
            String code,
            String expression,
            String key,
            String parameter,
            String defaultValue,
            List<String> allowedValues,
            String pointer,
            String unit) {
        Objects.requireNonNull(defaultValue, "defaultValue");
        DecisionBinding product =
                binding("product.template", "template", DecisionSource.PRODUCT, null, expression, code, 0);
        return buildStringTemplate(product, code, key, parameter, defaultValue, allowedValues, pointer, unit);
    }

    /**
     * Creates a single-parameter {@link RuleParameterType#STRING} template from a {@link JsonNode}.
     *
     * @param code false reason code
     * @param expression JSON Logic AST tree containing the literal site
     * @param key namespaced template key
     * @param parameter parameter name
     * @param defaultValue default string matching the original literal
     * @param allowedValues optional closed enumeration of allowed strings (empty for unrestricted)
     * @param pointer RFC 6901 pointer to the literal site
     * @param unit semantic unit label (or {@code null})
     * @return immutable parameter template
     */
    public static RuleParameterTemplate stringTemplate(
            String code,
            JsonNode expression,
            String key,
            String parameter,
            String defaultValue,
            List<String> allowedValues,
            String pointer,
            String unit) {
        Objects.requireNonNull(defaultValue, "defaultValue");
        DecisionBinding product =
                binding("product.template", "template", DecisionSource.PRODUCT, null, expression, code, 0);
        return buildStringTemplate(product, code, key, parameter, defaultValue, allowedValues, pointer, unit);
    }

    /**
     * Creates a single-parameter {@link RuleParameterType#STRING} template from a {@link PraxisJsonLogic.Expr}.
     *
     * @param code false reason code
     * @param expression DSL expression containing the literal site
     * @param key namespaced template key
     * @param parameter parameter name
     * @param defaultValue default string matching the original literal
     * @param allowedValues optional closed enumeration of allowed strings (empty for unrestricted)
     * @param pointer RFC 6901 pointer to the literal site
     * @param unit semantic unit label (or {@code null})
     * @return immutable parameter template
     */
    public static RuleParameterTemplate stringTemplate(
            String code,
            PraxisJsonLogic.Expr expression,
            String key,
            String parameter,
            String defaultValue,
            List<String> allowedValues,
            String pointer,
            String unit) {
        return stringTemplate(
                code,
                Objects.requireNonNull(expression, "expression").toJsonNode(),
                key,
                parameter,
                defaultValue,
                allowedValues,
                pointer,
                unit);
    }

    /**
     * Creates a single-parameter {@link RuleParameterType#BOOLEAN} template without unit label.
     *
     * @param code false reason code
     * @param expression JSON Logic expression string containing the literal site
     * @param key namespaced template key
     * @param parameter parameter name
     * @param defaultValue default boolean matching the original literal
     * @param pointer RFC 6901 pointer to the literal site
     * @return immutable parameter template
     */
    public static RuleParameterTemplate booleanTemplate(
            String code,
            String expression,
            String key,
            String parameter,
            boolean defaultValue,
            String pointer) {
        return booleanTemplate(code, expression, key, parameter, defaultValue, pointer, null);
    }

    /**
     * Creates a single-parameter {@link RuleParameterType#BOOLEAN} template.
     *
     * @param code false reason code
     * @param expression JSON Logic expression string containing the literal site
     * @param key namespaced template key
     * @param parameter parameter name
     * @param defaultValue default boolean matching the original literal
     * @param pointer RFC 6901 pointer to the literal site
     * @param unit semantic unit label (or {@code null})
     * @return immutable parameter template
     */
    public static RuleParameterTemplate booleanTemplate(
            String code,
            String expression,
            String key,
            String parameter,
            boolean defaultValue,
            String pointer,
            String unit) {
        DecisionBinding product =
                binding("product.template", "template", DecisionSource.PRODUCT, null, expression, code, 0);
        return buildTemplate(
                product,
                code,
                key,
                parameter,
                RuleParameterType.BOOLEAN,
                JSON.getNodeFactory().booleanNode(defaultValue),
                null,
                null,
                List.of(),
                pointer,
                unit);
    }

    /**
     * Creates a single-parameter {@link RuleParameterType#BOOLEAN} template from a {@link JsonNode}.
     *
     * @param code false reason code
     * @param expression JSON Logic AST tree containing the literal site
     * @param key namespaced template key
     * @param parameter parameter name
     * @param defaultValue default boolean matching the original literal
     * @param pointer RFC 6901 pointer to the literal site
     * @param unit semantic unit label (or {@code null})
     * @return immutable parameter template
     */
    public static RuleParameterTemplate booleanTemplate(
            String code,
            JsonNode expression,
            String key,
            String parameter,
            boolean defaultValue,
            String pointer,
            String unit) {
        DecisionBinding product =
                binding("product.template", "template", DecisionSource.PRODUCT, null, expression, code, 0);
        return buildTemplate(
                product,
                code,
                key,
                parameter,
                RuleParameterType.BOOLEAN,
                JSON.getNodeFactory().booleanNode(defaultValue),
                null,
                null,
                List.of(),
                pointer,
                unit);
    }

    /**
     * Creates a single-parameter {@link RuleParameterType#BOOLEAN} template from a {@link PraxisJsonLogic.Expr}.
     *
     * @param code false reason code
     * @param expression DSL expression containing the literal site
     * @param key namespaced template key
     * @param parameter parameter name
     * @param defaultValue default boolean matching the original literal
     * @param pointer RFC 6901 pointer to the literal site
     * @param unit semantic unit label (or {@code null})
     * @return immutable parameter template
     */
    public static RuleParameterTemplate booleanTemplate(
            String code,
            PraxisJsonLogic.Expr expression,
            String key,
            String parameter,
            boolean defaultValue,
            String pointer,
            String unit) {
        return booleanTemplate(
                code,
                Objects.requireNonNull(expression, "expression").toJsonNode(),
                key,
                parameter,
                defaultValue,
                pointer,
                unit);
    }

    private static RuleParameterTemplate buildStringTemplate(
            DecisionBinding product,
            String code,
            String key,
            String parameter,
            String defaultValue,
            List<String> allowedValues,
            String pointer,
            String unit) {
        List<JsonNode> allowedNodes =
                allowedValues == null
                        ? List.of()
                        : allowedValues.stream()
                                .map(val -> (JsonNode) JSON.getNodeFactory().textNode(
                                        Objects.requireNonNull(val, "allowedValue")))
                                .toList();
        return buildTemplate(
                product,
                code,
                key,
                parameter,
                RuleParameterType.STRING,
                JSON.getNodeFactory().textNode(defaultValue),
                null,
                null,
                allowedNodes,
                pointer,
                unit);
    }

    private static RuleParameterTemplate buildTemplate(
            DecisionBinding product,
            String code,
            String key,
            String parameter,
            RuleParameterType type,
            JsonNode defaultNode,
            BigDecimal minimum,
            BigDecimal maximum,
            List<JsonNode> allowedValues,
            String pointer,
            String unit) {
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
                                type,
                                true,
                                false,
                                false,
                                unit,
                                defaultNode,
                                minimum,
                                maximum,
                                allowedValues,
                                List.of(pointer))));
    }

    /**
     * Applies customer integer parameter overrides keyed by product {@code falseReasonCode}.
     *
     * @param base base RuleSet definition
     * @param values map from product {@code falseReasonCode} to integer override value
     * @return updated {@link RuleSetDefinition} with {@link CompositionPolicy#PARAMETERIZE} bindings
     */
    public static RuleSetDefinition parameterize(
            RuleSetDefinition base, Map<String, Integer> values) {
        return parameterizeValues(base, values);
    }

    /**
     * Applies typed customer parameter overrides ({@link Integer}, {@link Long}, {@link BigInteger},
     * {@link BigDecimal}, {@link Double}, {@link Float}, {@link String}, {@link Boolean},
     * {@link JsonNode}, or {@link Map} of parameter-name to value) keyed by product
     * {@code falseReasonCode}.
     *
     * @param base base RuleSet definition
     * @param values map from product {@code falseReasonCode} to typed parameter value or parameter map
     * @return updated {@link RuleSetDefinition} with {@link CompositionPolicy#PARAMETERIZE} bindings
     */
    public static RuleSetDefinition parameterizeValues(
            RuleSetDefinition base, Map<String, ?> values) {
        Objects.requireNonNull(base, "base");
        Map<String, ?> safeValues = values == null ? Map.of() : values;
        List<DecisionBinding> bindings = new ArrayList<>();
        for (DecisionBinding binding : base.bindings()) {
            DecisionSlot slot =
                    base.slots().stream()
                            .filter(s -> s.slotKey().equals(binding.slotKey()))
                            .findFirst()
                            .orElseThrow();
            String reasonCode = binding.falseReasonCode() == null ? "" : binding.falseReasonCode();
            Object rawValue = safeValues.get(reasonCode);
            RuleParameterTemplate template = slot.parameterTemplate();
            if (binding.source() == DecisionSource.PRODUCT
                    && rawValue != null
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
                ObjectNode parameters = JSON.createObjectNode();
                if (rawValue instanceof Map<?, ?> paramMap) {
                    for (Map.Entry<?, ?> entry : paramMap.entrySet()) {
                        if (!(entry.getKey() instanceof String paramName)) {
                            throw new IllegalArgumentException(
                                    "Parameter map keys must be strings for " + reasonCode);
                        }
                        putParameterValue(parameters, paramName, entry.getValue(), reasonCode);
                    }
                } else {
                    putParameterValue(
                            parameters, template.parameters().getFirst().name(), rawValue, reasonCode);
                }
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

    private static void putParameterValue(
            ObjectNode target, String paramName, Object value, String reasonCode) {
        if (value == null) {
            target.putNull(paramName);
        } else if (value instanceof JsonNode json) {
            target.set(paramName, json.deepCopy());
        } else if (value instanceof Integer integer) {
            target.put(paramName, integer);
        } else if (value instanceof Long longVal) {
            target.put(paramName, longVal);
        } else if (value instanceof BigInteger bigInt) {
            target.put(paramName, bigInt);
        } else if (value instanceof BigDecimal decimal) {
            target.put(paramName, decimal.stripTrailingZeros());
        } else if (value instanceof Double d) {
            if (!Double.isFinite(d)) {
                throw new IllegalArgumentException("Parameter value must be finite for " + reasonCode);
            }
            target.put(paramName, BigDecimal.valueOf(d).stripTrailingZeros());
        } else if (value instanceof Float f) {
            if (!Float.isFinite(f)) {
                throw new IllegalArgumentException("Parameter value must be finite for " + reasonCode);
            }
            target.put(paramName, BigDecimal.valueOf(f).stripTrailingZeros());
        } else if (value instanceof String str) {
            target.put(paramName, str);
        } else if (value instanceof Boolean bool) {
            target.put(paramName, bool);
        } else {
            throw new IllegalArgumentException(
                    "Unsupported parameter value type for "
                            + reasonCode
                            + ": "
                            + value.getClass().getName());
        }
    }

    /**
     * Parses a JSON Logic string and builds a {@link DecisionBinding} with ontological root
     * introspection, rejecting flattened {@code facts.*} references.
     *
     * @param key binding identity
     * @param slot target slot identity
     * @param source binding owner
     * @param policy customer composition policy (or {@code null} for product bindings)
     * @param expression JSON Logic string
     * @param code false reason code
     * @param order deterministic order tie-breaker
     * @return immutable {@link DecisionBinding}
     */
    public static DecisionBinding binding(
            String key,
            String slot,
            DecisionSource source,
            CompositionPolicy policy,
            String expression,
            String code,
            int order) {
        try {
            Objects.requireNonNull(expression, "expression");
            JsonNode tree = JSON.readTree(expression);
            if (tree == null || tree.isMissingNode()) {
                throw new IllegalStateException("Invalid JSON Logic expression for " + key);
            }
            return binding(key, slot, source, policy, tree, code, order);
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("Invalid JSON Logic expression for " + key, invalid);
        }
    }

    /**
     * Builds a {@link DecisionBinding} directly from a {@link JsonNode} expression with ontological
     * root introspection, rejecting flattened {@code facts.*} references.
     *
     * @param key binding identity
     * @param slot target slot identity
     * @param source binding owner
     * @param policy customer composition policy (or {@code null} for product bindings)
     * @param expression JSON Logic AST tree
     * @param code false reason code
     * @param order deterministic order tie-breaker
     * @return immutable {@link DecisionBinding}
     */
    public static DecisionBinding binding(
            String key,
            String slot,
            DecisionSource source,
            CompositionPolicy policy,
            JsonNode expression,
            String code,
            int order) {
        Objects.requireNonNull(expression, "expression");
        List<String> requiredRoots = roots(expression);
        if (requiredRoots.contains("facts")) {
            throw new IllegalStateException(
                    "Flattened scalar root 'facts' is forbidden in the Praxis ontology for " + key);
        }
        return new DecisionBinding(
                key,
                slot,
                source,
                policy,
                RuleExecutorRef.jsonLogic(expression),
                List.of(),
                order,
                true,
                RuleDecision.DENY,
                code,
                requiredRoots,
                null);
    }

    /**
     * Builds a {@link DecisionBinding} directly from a {@link PraxisJsonLogic.Expr}.
     *
     * @param key binding identity
     * @param slot target slot identity
     * @param source binding owner
     * @param policy customer composition policy (or {@code null} for product bindings)
     * @param expression typed JSON Logic DSL expression
     * @param code false reason code
     * @param order deterministic order tie-breaker
     * @return immutable {@link DecisionBinding}
     */
    public static DecisionBinding binding(
            String key,
            String slot,
            DecisionSource source,
            CompositionPolicy policy,
            PraxisJsonLogic.Expr expression,
            String code,
            int order) {
        return binding(
                key,
                slot,
                source,
                policy,
                Objects.requireNonNull(expression, "expression").toJsonNode(),
                code,
                order);
    }

    /**
     * Upgrades bindings and parameter templates in {@code base} to require exact leaf fact paths
     * referenced by their JSON Logic AST.
     *
     * @param base base RuleSet definition
     * @return updated {@link RuleSetDefinition} with exact leaf {@code requiredFactPaths}
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

    /**
     * Extracts distinct ontological roots referenced outside iterator-local scopes.
     *
     * @param node JSON Logic AST node
     * @return immutable list of referenced roots
     */
    public static List<String> roots(JsonNode node) {
        return PraxisFactTreeBuilder.extractReferencedRoots(node);
    }

    /**
     * Extracts distinct dotted fact paths referenced outside iterator-local scopes.
     *
     * @param node JSON Logic AST node
     * @return immutable list of referenced fact paths
     */
    public static List<String> factPaths(JsonNode node) {
        return PraxisFactTreeBuilder.extractReferencedFactPaths(node);
    }

    /**
     * Merges root declarations from {@code expression} into {@code baseRequiredFactPaths} without
     * duplicating roots already covered by dotted paths.
     *
     * @param baseRequiredFactPaths existing required paths (may be {@code null})
     * @param expression JSON Logic AST node
     * @return immutable merged list of required fact paths/roots
     */
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

    /**
     * Formats a loose equality JSON Logic string {@code {"==":[{"var":factPath},"literal"]}}.
     *
     * @param factPath variable path
     * @param literal literal string operand
     * @return serialized JSON Logic expression
     */
    public static String eqVar(String factPath, String literal) {
        return PraxisJsonLogic.eq(factPath, literal).toJson();
    }

    /**
     * Formats a loose inequality JSON Logic string {@code {"!=":[{"var":factPath},"literal"]}}.
     *
     * @param factPath variable path
     * @param literal literal string operand
     * @return serialized JSON Logic expression
     */
    public static String neVar(String factPath, String literal) {
        return PraxisJsonLogic.neq(factPath, literal).toJson();
    }

    /**
     * Formats a greater-than JSON Logic string {@code {">":[{"var":factPath},threshold]}}.
     *
     * @param factPath variable path
     * @param threshold numeric threshold
     * @return serialized JSON Logic expression
     */
    public static String gtVar(String factPath, Number threshold) {
        return PraxisJsonLogic.gt(factPath, threshold).toJson();
    }

    /**
     * Formats an immutability guard JSON Logic string {@code {"==":[{"var":"draft.<fieldName>"},{"var":"current.<fieldName>"}]}}.
     *
     * @param fieldName field name under both {@code draft} and {@code current}
     * @return serialized JSON Logic expression
     */
    public static String unchanged(String fieldName) {
        return PraxisJsonLogic.eq(
                        PraxisJsonLogic.draft(fieldName), PraxisJsonLogic.current(fieldName))
                .toJson();
    }

    private static String serializeExpression(JsonNode expression) {
        Objects.requireNonNull(expression, "expression");
        try {
            return JSON.writeValueAsString(expression);
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("Invalid JSON Logic expression tree", invalid);
        }
    }
}
