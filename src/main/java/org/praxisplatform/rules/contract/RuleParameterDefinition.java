package org.praxisplatform.rules.contract;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * Product-owned executable scalar restriction and its literal sites in a template.
 * @param name exact parameter key
 * @param type closed scalar type
 * @param required whether omission without a default is invalid
 * @param nullable whether explicit JSON null is admitted
 * @param readOnly whether only the published default is admitted
 * @param unit published semantic unit; callers cannot replace it
 * @param defaultValue published default; Java null means absent, JSON null is explicit
 * @param minimum inclusive numeric minimum, or null
 * @param maximum inclusive numeric maximum, or null
 * @param allowedValues closed enumeration, empty for no enumeration
 * @param expressionPointers exact RFC 6901 pointers to existing scalar literal operands
 */
public record RuleParameterDefinition(
        String name,
        RuleParameterType type,
        boolean required,
        boolean nullable,
        boolean readOnly,
        String unit,
        @JsonInclude(JsonInclude.Include.NON_NULL) JsonNode defaultValue,
        BigDecimal minimum,
        BigDecimal maximum,
        List<JsonNode> allowedValues,
        List<String> expressionPointers) {
    /** Captures immutable schema data; semantic validation belongs to the pure resolver. */
    public RuleParameterDefinition {
        Objects.requireNonNull(name, "name is required");
        Objects.requireNonNull(type, "type is required");
        defaultValue = defaultValue == null ? null : defaultValue.deepCopy();
        allowedValues = allowedValues == null ? List.of()
                : allowedValues.stream().<JsonNode>map(value -> Objects.requireNonNull(value).deepCopy()).toList();
        expressionPointers = expressionPointers == null ? List.of() : List.copyOf(expressionPointers);
    }

    /** @return a defensive copy, or Java null when there is no default */
    @Override
    public JsonNode defaultValue() {
        return defaultValue == null ? null : defaultValue.deepCopy();
    }

    /** @return defensively copied enumeration values */
    @Override
    public List<JsonNode> allowedValues() {
        return allowedValues.stream().<JsonNode>map(JsonNode::deepCopy).toList();
    }
}
