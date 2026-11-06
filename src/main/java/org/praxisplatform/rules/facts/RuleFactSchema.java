package org.praxisplatform.rules.facts;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.praxisplatform.rules.digest.PraxisCanonicalJson;
import org.praxisplatform.rules.jsonlogic.internal.PraxisPath;
import org.praxisplatform.rules.runtime.RuleJsonBudget;

/** Immutable compiled instance of the closed G1 structural schema profile. */
public final class RuleFactSchema {
    /** Maximum nested schema shape depth. */
    public static final int MAX_DEPTH = 32;
    /** Maximum compiled shapes per operation. */
    public static final int MAX_SHAPES = 4_096;
    /** Maximum serialized schema bytes. */
    public static final int MAX_SCHEMA_BYTES = 1_024_000;

    private final Shape root;
    private final JsonNode schema;
    private final String digest;

    private RuleFactSchema(Shape root, JsonNode schema) {
        this.root = root;
        this.schema = schema.deepCopy();
        this.digest = PraxisCanonicalJson.sha256(schema);
    }

    /**
     * Compiles a bounded schema without resolving external references or performing I/O.
     * @param schema closed profile object schema
     * @return detached immutable schema
     */
    public static RuleFactSchema compile(JsonNode schema) {
        new Compiler().shape(schema, "#", 0);
        JsonNode captured = schema.deepCopy();
        Shape root = new Compiler().shape(captured, "#", 0);
        if (!root.type.equals("object") || root.nullable) invalid("#");
        if (captured.toString().getBytes(StandardCharsets.UTF_8).length > MAX_SCHEMA_BYTES) limit("#");
        return new RuleFactSchema(root, captured);
    }

    /** @return defensive copy of the admitted schema */
    public JsonNode schema() { return schema.deepCopy(); }

    /** @return canonical schema digest */
    public String digest() { return digest; }

    /**
     * Validates bounded native facts, preserving missing versus explicit null.
     * @param facts complete provider snapshot
     */
    public void validate(JsonNode facts) {
        RuleJsonBudget.validateValue(facts);
        root.validate(facts, "$");
    }

    /**
     * Resolves a static global fact path against the schema.
     * @param path canonical Praxis variable path
     * @return declared primitive/container type, excluding nullability
     */
    public String typeAt(String path) { return resolve(root, path, path).type; }

    /**
     * Checks whether an explicit null can occur at a path or one of its containers.
     * @param path canonical Praxis variable path
     * @return true if a Java executor must accept null at this path
     */
    public boolean nullableAt(String path) {
        Shape shape = root;
        boolean nullable = shape.nullable || shape.type.equals("null");
        for (String part : PraxisPath.parse(path)) {
            shape = step(shape, part, path);
            nullable |= shape.nullable || shape.type.equals("null");
        }
        return nullable;
    }

    /**
     * Requires structural presence along a binding's mandatory path.
     * Nullable containers remain valid explicit values.
     * @param path binding-required fact path
     */
    public void requirePath(String path) {
        Shape shape = root;
        for (String part : PraxisPath.parse(path)) {
            if (shape.type.equals("object") && !shape.required.contains(part)) undeclared(path);
            if (shape.type.equals("array")) {
                try { if (Integer.parseInt(part) >= shape.minimum) undeclared(path); }
                catch (NumberFormatException invalid) { undeclared(path); }
            }
            shape = step(shape, part, path);
        }
    }

    /**
     * Validates global and lexical iterator paths and operator input types.
     * The canonical planner must validate syntax, arity and expression budgets first.
     * @param expression already syntax-validated canonical expression
     */
    public void validateExpression(JsonNode expression) {
        RuleJsonBudget.validateValue(expression);
        RuleFactExpressionAnalyzer.validate(expression, this);
    }

    Shape root() { return root; }

    static Shape resolve(Shape start, String path, String diagnostic) {
        Shape shape = start;
        for (String part : PraxisPath.parse(path)) {
            shape = step(shape, part, diagnostic);
        }
        if (shape == null) undeclared(diagnostic);
        return shape;
    }

    private static Shape step(Shape shape, String part, String diagnostic) {
        Shape result = null;
        if (shape != null && shape.type.equals("object")) {
            result = shape.properties.getOrDefault(part, shape.additional);
        } else if (shape != null && shape.type.equals("array") && part.matches("[0-9]+")) {
            try { if (Integer.parseInt(part) >= shape.maximum) undeclared(diagnostic); }
            catch (NumberFormatException invalid) { undeclared(diagnostic); }
            result = shape.items;
        }
        if (result == null) undeclared(diagnostic);
        return result;
    }

    /**
     * Tests whether a declared descendant is unavailable because an admitted ancestor is null.
     * @param facts already validated snapshot
     * @param path declared required path
     * @return true only for explicit null at a nullable schema ancestor
     */
    public boolean hasNullAncestor(JsonNode facts, String path) {
        Shape shape = root;
        JsonNode value = facts;
        for (String part : PraxisPath.parse(path)) {
            if (value == null || value.isMissingNode()) return false;
            if (value.isNull()) return shape.nullable || shape.type.equals("null");
            if (shape.type.equals("object")) {
                shape = shape.properties.getOrDefault(part, shape.additional);
                value = value.get(part);
            } else if (shape.type.equals("array") && part.matches("[0-9]+")) {
                shape = shape.items;
                try { value = value.get(Integer.parseInt(part)); }
                catch (NumberFormatException invalid) { return false; }
            } else return false;
            if (shape == null) return false;
        }
        return false;
    }

    static void invalid(String path) { throw new RuleFactContractException(RuleFactContractException.Code.SCHEMA_INVALID, path); }
    static void limit(String path) { throw new RuleFactContractException(RuleFactContractException.Code.SCHEMA_LIMIT_EXCEEDED, path); }
    static void undeclared(String path) { throw new RuleFactContractException(RuleFactContractException.Code.PATH_UNDECLARED, path); }
    static void mismatch(String path) { throw new RuleFactContractException(RuleFactContractException.Code.FACTS_SCHEMA_INVALID, path); }

    static final class Shape {
        final String type;
        final boolean nullable;
        Map<String, Shape> properties = Map.of();
        Set<String> required = Set.of();
        Shape additional;
        Shape items;
        int maximum;
        int minimum;
        BigDecimal lower;
        BigDecimal upper;
        String format = "";
        Set<String> choices = Set.of();
        boolean enumNull;
        boolean enumDeclared;
        String literalText;

        Shape(String type, boolean nullable) { this.type = type; this.nullable = nullable; }

        void validate(JsonNode value, String path) {
            if (value == null || value.isMissingNode()) mismatch(path);
            if (value.isNull()) {
                if ((!nullable && !type.equals("null")) || (enumDeclared && !enumNull)) mismatch(path);
                return;
            }
            switch (type) {
                case "object" -> {
                    if (!value.isObject() || value.size() > maximum) mismatch(path);
                    for (String name : required) if (!value.has(name)) mismatch(path + "/" + escape(name));
                    for (var field : value.properties()) {
                        Shape child = properties.getOrDefault(field.getKey(), additional);
                        // Dynamic map keys and unexpected input names may themselves contain private data.
                        String coordinate = properties.containsKey(field.getKey()) ? escape(field.getKey()) : "*";
                        if (child == null) mismatch(path + "/" + coordinate);
                        child.validate(field.getValue(), path + "/" + coordinate);
                    }
                }
                case "array" -> {
                    if (!value.isArray() || value.size() < minimum || value.size() > maximum) mismatch(path);
                    for (int i = 0; i < value.size(); i++) items.validate(value.get(i), path + "/" + i);
                }
                case "string" -> {
                    if (!value.isTextual()) mismatch(path);
                    String text = value.textValue();
                    if (text.codePointCount(0, text.length()) > maximum
                            || (enumDeclared && !choices.contains(text))) mismatch(path);
                    RuleFactFormats.validate(format, text, path);
                }
                case "number", "integer" -> {
                    if (!value.isNumber()) mismatch(path);
                    BigDecimal number = value.decimalValue();
                    if (type.equals("integer") && number.stripTrailingZeros().scale() > 0) mismatch(path);
                    if (lower != null && number.compareTo(lower) < 0 || upper != null && number.compareTo(upper) > 0) mismatch(path);
                }
                case "boolean" -> { if (!value.isBoolean()) mismatch(path); }
                default -> mismatch(path);
            }
        }
    }

    private static String escape(String key) { return key.replace("~", "~0").replace("/", "~1"); }

    private static final class Compiler {
        private int shapes;
        private long textBytes;

        Shape shape(JsonNode node, String path, int depth) {
            if (depth > MAX_DEPTH || ++shapes > MAX_SHAPES) limit(path);
            if (node == null || !node.isObject()) invalid(path);
            JsonNode declared = node.get("type");
            if (declared == null) invalid(path);
            boolean nullable = declared.isArray();
            if (nullable && (declared.size() != 2 || !declared.get(1).isTextual()
                    || !declared.get(1).textValue().equals("null"))) invalid(path);
            JsonNode first = nullable ? declared.get(0) : declared;
            if (!first.isTextual()) invalid(path);
            String type = first.textValue();
            Set<String> keys = switch (type) {
                case "object" -> Set.of("type", "properties", "required", "additionalProperties", "maxProperties");
                case "array" -> Set.of("type", "items", "minItems", "maxItems");
                case "string" -> Set.of("type", "maxLength", "format", "enum");
                case "integer", "number" -> Set.of("type", "minimum", "maximum");
                case "boolean", "null" -> Set.of("type");
                default -> throw new RuleFactContractException(RuleFactContractException.Code.SCHEMA_INVALID, path);
            };
            for (var entry : node.properties()) if (!keys.contains(entry.getKey())) invalid(path + "/" + entry.getKey());
            if (nullable && type.equals("null")) invalid(path);
            Shape result = new Shape(type, nullable);
            switch (type) {
                case "object" -> {
                    JsonNode properties = node.get("properties"), required = node.get("required"), extra = node.get("additionalProperties");
                    if (properties == null || !properties.isObject() || properties.size() > 256
                            || required == null || !required.isArray() || required.size() > 256 || extra == null) invalid(path);
                    result.maximum = bound(node, "maxProperties", 0, 256, path);
                    Map<String, Shape> children = new LinkedHashMap<>();
                    for (var entry : properties.properties()) {
                        text(entry.getKey(), 255, path);
                        children.put(entry.getKey(), shape(entry.getValue(), path + "/properties/" + escape(entry.getKey()), depth + 1));
                    }
                    Set<String> names = new LinkedHashSet<>();
                    for (JsonNode name : required) {
                        if (!name.isTextual() || !children.containsKey(name.textValue()) || !names.add(name.textValue())) invalid(path);
                    }
                    if (result.maximum < names.size()) invalid(path);
                    result.properties = Map.copyOf(children);
                    result.required = Set.copyOf(names);
                    if (extra.isObject()) result.additional = shape(extra, path + "/additionalProperties", depth + 1);
                    else if (!extra.isBoolean() || extra.booleanValue()) invalid(path);
                }
                case "array" -> {
                    result.minimum = bound(node, "minItems", 0, 10_000, path);
                    result.maximum = bound(node, "maxItems", 0, 10_000, path);
                    if (result.minimum > result.maximum) invalid(path);
                    result.items = shape(node.get("items"), path + "/items", depth + 1);
                }
                case "string" -> {
                    result.maximum = bound(node, "maxLength", 1, 64_000, path);
                    if (node.has("format")) {
                        if (!node.get("format").isTextual() || !RuleFactFormats.SUPPORTED.contains(node.get("format").textValue())) invalid(path);
                        result.format = node.get("format").textValue();
                    }
                    if (node.has("enum")) {
                        result.enumDeclared = true;
                        JsonNode values = node.get("enum");
                        if (!values.isArray() || values.isEmpty() || values.size() > 256) invalid(path);
                        Set<String> choices = new LinkedHashSet<>();
                        for (JsonNode value : values) {
                            if (value.isNull()) {
                                if (!nullable || result.enumNull) invalid(path);
                                result.enumNull = true;
                            } else {
                                if (!value.isTextual() || !choices.add(value.textValue())) invalid(path);
                                if (value.textValue().codePointCount(0, value.textValue().length()) > result.maximum) invalid(path);
                                text(value.textValue(), 64_000, path, true);
                                try { RuleFactFormats.validate(result.format, value.textValue(), path); }
                                catch (RuleFactContractException invalidFormat) { invalid(path); }
                            }
                        }
                        // An enum containing only null is represented explicitly too.
                        result.choices = Set.copyOf(choices);
                    }
                }
                case "number", "integer" -> {
                    result.lower = number(node.get("minimum"), path);
                    result.upper = number(node.get("maximum"), path);
                    if (result.lower != null && result.upper != null && result.lower.compareTo(result.upper) > 0) invalid(path);
                }
                default -> { }
            }
            return result;
        }

        private int bound(JsonNode node, String key, int min, int max, String path) {
            JsonNode value = node.get(key);
            if (value == null || !value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < min || value.intValue() > max) invalid(path + "/" + key);
            return value.intValue();
        }

        private BigDecimal number(JsonNode node, String path) {
            if (node == null) return null;
            if (!node.isNumber() || node.isFloatingPointNumber() && !Double.isFinite(node.doubleValue())) invalid(path);
            BigDecimal value = node.decimalValue();
            if (value.precision() > 256 || Math.abs((long) value.scale()) > 512) limit(path);
            return value;
        }

        private void text(String value, int max, String path) { text(value, max, path, false); }
        private void text(String value, int max, String path, boolean allowEmpty) {
            if ((!allowEmpty && value.isEmpty()) || value.length() > max) invalid(path);
            textBytes += value.getBytes(StandardCharsets.UTF_8).length;
            if (textBytes > MAX_SCHEMA_BYTES) limit(path);
        }
    }
}
