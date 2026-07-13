package org.praxisplatform.rules.jsonlogic.operators;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Set;
import org.praxisplatform.rules.jsonlogic.PraxisJsonLogicException;
import org.praxisplatform.rules.jsonlogic.model.JsonLogicIssueCode;

/** Immutable-by-copy registry governing Praxis and trusted host operator extensions. */
public final class PraxisJsonLogicOperatorRegistry {
    private static final Set<String> RESERVED = Set.of("var", "==", "===", "!=", "!==", ">", ">=", "<", "<=", "!", "!!", "and", "or", "if", "in", "cat", "substr", "merge", "map", "filter", "reduce", "all", "some", "none", "+", "-", "*", "/", "%", "min", "max");
    private final Map<String, PraxisJsonLogicOperator> operators = new LinkedHashMap<>();

    /**
     * Builds a registry after validating names, descriptors, arity, and uniqueness.
     * @param operators initial trusted operator definitions
     * @throws PraxisJsonLogicException when a definition violates registry invariants
     */
    public PraxisJsonLogicOperatorRegistry(Collection<PraxisJsonLogicOperator> operators) {
        for (PraxisJsonLogicOperator operator : operators) {
            if (operator == null || operator.operator() == null || operator.operator().isBlank()) {
                throw conflict("<blank>", "must have a non-blank name");
            }
            JsonLogicOperatorDescriptor descriptor = operator.descriptor();
            if (descriptor == null || !operator.operator().equals(descriptor.operator())) {
                throw conflict(operator.operator(), "must expose a matching descriptor");
            }
            if (descriptor.minArgs() < 0 || descriptor.maxArgs() != null && descriptor.maxArgs() < descriptor.minArgs()) {
                throw conflict(operator.operator(), "has an invalid arity descriptor");
            }
            if (this.operators.putIfAbsent(operator.operator(), operator) != null) {
                throw conflict(operator.operator(), "is already registered");
            }
        }
    }

    /**
     * Finds an extension operator by its exact canonical name.
     * @param operator canonical operator name
     * @return operator implementation, or {@code null} when absent
     */
    public PraxisJsonLogicOperator get(String operator) {
        return operators.get(operator);
    }

    /**
     * Tests whether an extension operator is registered.
     * @param operator canonical operator name
     * @return {@code true} when registered
     */
    public boolean contains(String operator) {
        return operators.containsKey(operator);
    }

    /**
     * Returns a new registry enriched with namespaced host-owned operators.
     * @param additions trusted host operator definitions
     * @return new registry; this registry remains unchanged
     * @throws PraxisJsonLogicException when an addition is reserved, duplicated, or incorrectly described
     */
    public PraxisJsonLogicOperatorRegistry withHostOperators(Collection<PraxisJsonLogicOperator> additions) {
        Map<String, PraxisJsonLogicOperator> merged = new LinkedHashMap<>(operators);
        for (PraxisJsonLogicOperator operator : additions) {
            if (RESERVED.contains(operator.operator())) throw conflict(operator.operator(), "is native and reserved");
            if (!operator.operator().contains(":")) throw conflict(operator.operator(), "must use a namespace");
            JsonLogicOperatorDescriptor descriptor = operator.descriptor();
            String namespace = operator.operator().substring(0, operator.operator().indexOf(':'));
            if (!"host".equals(descriptor.source()) || !namespace.equals(descriptor.namespace())) {
                throw conflict(operator.operator(), "must declare matching namespace and host source");
            }
            if (merged.putIfAbsent(operator.operator(), operator) != null) throw conflict(operator.operator(), "is already registered");
        }
        return new PraxisJsonLogicOperatorRegistry(merged.values());
    }

    /**
     * Publishes extension contracts in deterministic registration order.
     * @return immutable operator descriptor list
     */
    public List<JsonLogicOperatorDescriptor> descriptors() {
        return operators.values().stream().map(PraxisJsonLogicOperator::descriptor).toList();
    }

    private static PraxisJsonLogicException conflict(String operator, String reason) {
        return new PraxisJsonLogicException(JsonLogicIssueCode.RULE_OPERATOR_CONFLICT,
            "JSON Logic operator \"" + operator + "\" " + reason + ".", "$", operator);
    }
}
