package org.praxisplatform.rules.parameters;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.praxisplatform.rules.contract.RuleDecision;
import org.praxisplatform.rules.contract.RuleEngineLimits;
import org.praxisplatform.rules.contract.RuleExecutorRef;
import org.praxisplatform.rules.contract.RuleExecutorType;
import org.praxisplatform.rules.contract.RuleParameterDefinition;
import org.praxisplatform.rules.contract.RuleParameterResolution;
import org.praxisplatform.rules.contract.RuleParameterTemplate;
import org.praxisplatform.rules.contract.RuleParameterType;
import org.praxisplatform.rules.contract.RuleParameterValueOrigin;
import org.praxisplatform.rules.contract.RuleRuntimeCompatibility;
import org.praxisplatform.rules.digest.PraxisCanonicalJson;
import org.praxisplatform.rules.plan.RulePlanException;
import org.praxisplatform.rules.plan.RulePlanIssueCode;
import org.praxisplatform.rules.runtime.RuleJsonBudget;
import org.praxisplatform.rules.jsonlogic.PraxisJsonLogicEngine;
import org.praxisplatform.rules.jsonlogic.PraxisJsonLogicException;
import org.praxisplatform.rules.jsonlogic.model.JsonLogicValidationOptions;

/** Pure product-template admission and deterministic scalar literal materialization. */
public final class RuleParameterResolver {
    /** Maximum scalar parameters in one product template. */
    public static final int MAX_PARAMETERS = 128;
    /** Maximum total literal replacement sites in one product template. */
    public static final int MAX_LITERAL_SITES = 256;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final PraxisJsonLogicEngine JSON_LOGIC = new PraxisJsonLogicEngine();

    private RuleParameterResolver() {
    }

    /**
     * Validates schema, original literal sites and exact executable scope without caller values.
     * This admits required parameters without inventing values for them.
     * @param template immutable product-owned template
     * @throws RulePlanException with PLAN_PARAMETER_TEMPLATE_INVALID when proof is missing
     */
    public static void validateTemplate(RuleParameterTemplate template) {
        if (template == null || template.templateKey().isBlank()
                || !template.templateKey().contains(":") || template.templateVersion().isBlank()
                || !template.templateKey().equals(template.templateKey().trim())
                || !template.templateVersion().equals(template.templateVersion().trim())) {
            throw templateFailure("An exact namespaced product template identity is required");
        }
        if (template.executor().type() != RuleExecutorType.JSON_LOGIC
                || !RuleRuntimeCompatibility.JSON_LOGIC_DIALECT_VERSION.equals(
                        template.executor().implementationVersion())) {
            throw templateFailure("Only the exact current JSON Logic dialect supports parameterization");
        }
        if (template.falseDecision() != RuleDecision.DENY
                && template.falseDecision() != RuleDecision.NOT_APPLICABLE
                && template.falseDecision() != RuleDecision.INCONCLUSIVE) {
            throw templateFailure("Template requires an explicit admitted false outcome");
        }
        if (template.falseReasonCode() == null || template.falseReasonCode().isBlank()
                || template.requiredFactPaths().stream().anyMatch(path -> path == null || path.isBlank())
                || new HashSet<>(template.requiredFactPaths()).size() != template.requiredFactPaths().size()) {
            throw templateFailure("Template false reason and required facts must be unambiguous");
        }
        if (template.parameters().isEmpty() || template.parameters().size() > MAX_PARAMETERS) {
            throw templateFailure("Template must declare 1..128 scalar parameters");
        }
        JsonNode expression = template.executor().expression();
        bounded(expression, true);
        int[] schemaBytes = {0};
        chargeSchema(expression, schemaBytes);
        chargeSchema(JSON.valueToTree(template.templateKey()), schemaBytes);
        chargeSchema(JSON.valueToTree(template.templateVersion()), schemaBytes);
        chargeSchema(JSON.valueToTree(template.falseReasonCode()), schemaBytes);
        chargeSchema(JSON.valueToTree(template.requiredFactPaths()), schemaBytes);
        validateExpression(expression, true);
        Set<String> names = new HashSet<>();
        Set<String> sites = new HashSet<>();
        for (RuleParameterDefinition parameter : template.parameters()) {
            if (parameter.name().isBlank() || !parameter.name().equals(parameter.name().trim())
                    || !names.add(parameter.name())) {
                throw templateFailure("Parameter keys must be nonblank, exact and distinct");
            }
            if (parameter.unit() != null && (parameter.unit().isBlank()
                    || !parameter.unit().equals(parameter.unit().trim()))) {
                throw templateFailure("Published parameter unit must be nonblank and exact");
            }
            chargeSchema(JSON.valueToTree(parameter.name()), schemaBytes);
            chargeSchema(JSON.valueToTree(parameter.unit()), schemaBytes);
            boolean numeric = parameter.type() == RuleParameterType.NUMBER
                    || parameter.type() == RuleParameterType.INTEGER;
            if ((!numeric && (parameter.minimum() != null || parameter.maximum() != null))
                    || (parameter.minimum() != null && parameter.maximum() != null
                        && parameter.minimum().compareTo(parameter.maximum()) > 0)) {
                throw templateFailure("Only numeric parameters admit ordered inclusive bounds");
            }
            boundedNumber(parameter.minimum());
            boundedNumber(parameter.maximum());
            if (parameter.minimum() != null) chargeSchema(JSON.valueToTree(parameter.minimum()), schemaBytes);
            if (parameter.maximum() != null) chargeSchema(JSON.valueToTree(parameter.maximum()), schemaBytes);
            if (parameter.allowedValues().size() > 10_000) {
                throw templateFailure("Parameter enumeration exceeds the maximum array size");
            }
            if ((!parameter.required() || parameter.readOnly()) && parameter.defaultValue() == null) {
                throw templateFailure("Optional and read-only parameters require an explicit published default");
            }
            Set<String> enumeration = new HashSet<>();
            for (JsonNode allowed : parameter.allowedValues()) {
                bounded(allowed, true);
                chargeSchema(allowed, schemaBytes);
                validateValue(parameter, allowed, true, false);
                if (!enumeration.add(PraxisCanonicalJson.canonicalize(allowed))) {
                    throw templateFailure("Parameter enumeration must contain distinct values");
                }
            }
            if (parameter.defaultValue() != null) {
                bounded(parameter.defaultValue(), true);
                chargeSchema(parameter.defaultValue(), schemaBytes);
                validateValue(parameter, parameter.defaultValue(), true, true);
            }
            if (parameter.expressionPointers().isEmpty()) {
                throw templateFailure("Every parameter must bind at least one existing scalar literal");
            }
            for (String pointer : parameter.expressionPointers()) {
                chargeSchema(JSON.valueToTree(pointer), schemaBytes);
                JsonNode literal = literalAt(expression, pointer);
                if (!sites.add(pointer) || sites.size() > MAX_LITERAL_SITES) {
                    throw templateFailure("Literal sites must be distinct and bounded to 256");
                }
                for (String other : sites) {
                    if (!pointer.equals(other) && (pointer.startsWith(other + "/")
                            || other.startsWith(pointer + "/"))) {
                        throw templateFailure("Literal sites cannot overlap");
                    }
                }
                validateValue(parameter, literal, true, true);
                if (parameter.defaultValue() != null && !same(literal, parameter.defaultValue())) {
                    throw templateFailure("Published defaults must equal their original template literals");
                }
            }
        }
        // Bound the complete schema too, including keys, units, pointers and enumerations.
        bounded(JSON.valueToTree(template), true);
    }

    /**
     * Resolves only published scalar values and replaces their exact product-owned literal sites.
     * Omitted defaults remain distinct from explicitly supplied null, zero and false. This method
     * has no RuleSet, host, facts, database, registry or I/O dependency.
     * @param template immutable product-owned schema and executable template
     * @param values declared object values, using exact published names
     * @return derived executor, effective values and their declared/default evidence
     * @throws RulePlanException with a stable parameter diagnostic when admission fails
     */
    public static RuleParameterResolution resolve(RuleParameterTemplate template, ObjectNode values) {
        validateTemplate(template);
        if (values == null) {
            throw valuesFailure("Parameter values must be an explicit object");
        }
        bounded(values, false);
        Set<String> known = new HashSet<>();
        template.parameters().forEach(parameter -> known.add(parameter.name()));
        if (values.properties().stream().anyMatch(entry -> !known.contains(entry.getKey()))) {
            throw valuesFailure("Parameter values contain an unknown product parameter");
        }
        ObjectNode effective = JSON.createObjectNode();
        Map<String, RuleParameterValueOrigin> origins = new LinkedHashMap<>();
        JsonNode expression = template.executor().expression();
        for (RuleParameterDefinition parameter : template.parameters().stream()
                .sorted(java.util.Comparator.comparing(RuleParameterDefinition::name)).toList()) {
            boolean declared = values.has(parameter.name());
            JsonNode value = declared ? values.get(parameter.name()) : parameter.defaultValue();
            if (value == null) {
                throw valuesFailure("Required product parameter is absent: " + parameter.name());
            }
            validateValue(parameter, value, false, true);
            if (parameter.readOnly() && !same(value, parameter.defaultValue())) {
                throw valuesFailure("Read-only product parameter cannot change: " + parameter.name());
            }
            effective.set(parameter.name(), value.deepCopy());
            origins.put(parameter.name(), declared
                    ? RuleParameterValueOrigin.DECLARED : RuleParameterValueOrigin.DEFAULT);
            for (String pointer : parameter.expressionPointers()) {
                JsonPointer site = JsonPointer.compile(pointer);
                JsonNode parent = expression.at(site.head());
                if (parent instanceof ArrayNode array) {
                    array.set(site.last().getMatchingIndex(), value.deepCopy());
                } else {
                    ((ObjectNode) parent).set(site.last().getMatchingProperty(), value.deepCopy());
                }
                bounded(expression, false);
            }
        }
        bounded(effective, false);
        bounded(expression, false);
        validateExpression(expression, false);
        return new RuleParameterResolution(RuleExecutorRef.jsonLogic(expression), effective, origins);
    }

    private static JsonNode literalAt(JsonNode expression, String pointer) {
        if (pointer == null || pointer.isEmpty() || !pointer.startsWith("/") || pointer.length() > 4096) {
            throw templateFailure("Literal sites require a bounded non-root RFC 6901 pointer");
        }
        String[] segments = pointer.substring(1).split("/", -1);
        if (segments.length > 64) {
            throw templateFailure("Literal site exceeds the maximum expression depth");
        }
        JsonNode current = expression;
        for (String encoded : segments) {
            for (int index = 0; index < encoded.length(); index++) {
                if (encoded.charAt(index) == '~') {
                    if (++index >= encoded.length() || (encoded.charAt(index) != '0'
                            && encoded.charAt(index) != '1')) {
                        throw templateFailure("Literal site contains an invalid pointer escape");
                    }
                }
            }
            String key = encoded.replace("~1", "/").replace("~0", "~");
            if (current.isObject()) {
                // var and missing path operands are addressing metadata, never literals.
                if (current.size() == 1 && (current.has("var") || current.has("missing")
                        || current.has("missing_some"))) {
                    throw templateFailure("Parameter sites cannot replace variable addressing metadata");
                }
                current = current.path(key);
            } else if (current.isArray()) {
                // Enforce canonical array indexing to prevent equivalent duplicate pointers.
                if (key.isEmpty() || key.length() > 5 || (key.length() > 1 && key.charAt(0) == '0')
                        || !key.chars().allMatch(c -> c >= '0' && c <= '9')) {
                    throw templateFailure("Literal site requires an existing canonical array index");
                }
                current = current.path(Integer.parseInt(key));
            } else {
                throw templateFailure("Literal site cannot traverse a scalar or operator key");
            }
        }
        if (!current.isValueNode() || current.isMissingNode() || current.isPojo() || current.isBinary()) {
            throw templateFailure("Literal site must identify an existing native scalar operand");
        }
        return current;
    }

    private static void validateValue(
            RuleParameterDefinition parameter, JsonNode value, boolean schema, boolean checkEnumeration) {
        boolean admitted = value.isNull() ? parameter.nullable() : switch (parameter.type()) {
            case STRING -> value.isTextual();
            case BOOLEAN -> value.isBoolean();
            case NUMBER -> value.isNumber();
            case INTEGER -> value.isNumber() && value.decimalValue().stripTrailingZeros().scale() <= 0;
        };
        if (admitted && value.isNumber()) {
            BigDecimal number = value.decimalValue();
            admitted = (parameter.minimum() == null || number.compareTo(parameter.minimum()) >= 0)
                    && (parameter.maximum() == null || number.compareTo(parameter.maximum()) <= 0);
        }
        if (admitted && checkEnumeration && !parameter.allowedValues().isEmpty()) {
            admitted = parameter.allowedValues().stream().anyMatch(allowed -> same(allowed, value));
        }
        if (!admitted) {
            String message = "Value violates product parameter restriction: " + parameter.name();
            throw schema ? templateFailure(message) : valuesFailure(message);
        }
    }

    private static void validateExpression(JsonNode expression, boolean schema) {
        try {
            // The pure resolver supports the canonical built-in registry. Root availability
            // is still checked against the real RuleSet by the final plan compiler.
            JSON_LOGIC.validate(expression, new JsonLogicValidationOptions(List.of(), null,
                    false, "1970-01-01T00:00:00Z", "UTC", true));
        } catch (PraxisJsonLogicException exception) {
            String message = "Invalid parameterized JSON Logic expression [" + exception.getCode() + "]";
            throw schema ? templateFailure(message) : valuesFailure(message);
        }
    }

    private static boolean same(JsonNode left, JsonNode right) {
        return PraxisCanonicalJson.canonicalize(left).equals(PraxisCanonicalJson.canonicalize(right));
    }

    private static void boundedNumber(BigDecimal number) {
        if (number != null && ((long) number.precision() + Math.abs((long) number.scale()) > 65_536)) {
            throw templateFailure("Numeric bound exceeds the maximum canonical size");
        }
    }

    private static void chargeSchema(JsonNode value, int[] bytes) {
        bounded(value, true);
        int addition = PraxisCanonicalJson.canonicalize(value).getBytes(StandardCharsets.UTF_8).length;
        if (addition > RuleEngineLimits.MAX_PUBLIC_RESULT_BYTES - bytes[0]) {
            throw templateFailure("Combined parameter schema exceeds the canonical byte limit");
        }
        bytes[0] += addition;
    }

    private static void bounded(JsonNode value, boolean schema) {
        try {
            RuleJsonBudget.validateValue(value);
        } catch (IllegalArgumentException failure) {
            throw schema ? templateFailure("Template exceeds native JSON structural or byte limits")
                    : valuesFailure("Parameter values exceed native JSON structural or byte limits");
        }
    }

    private static RulePlanException templateFailure(String message) {
        return new RulePlanException(RulePlanIssueCode.PLAN_PARAMETER_TEMPLATE_INVALID, message, null);
    }

    private static RulePlanException valuesFailure(String message) {
        return new RulePlanException(RulePlanIssueCode.PLAN_PARAMETER_VALUES_INVALID, message, null);
    }
}
