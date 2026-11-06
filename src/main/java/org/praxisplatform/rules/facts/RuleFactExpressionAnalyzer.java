package org.praxisplatform.rules.facts;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.praxisplatform.rules.facts.RuleFactSchema.Shape;

/** Structural analysis of global and iterator-local JSON Logic paths. */
final class RuleFactExpressionAnalyzer {
    private static final Set<String> ITERATORS = Set.of("map", "filter", "all", "some", "none", "reduce");
    private static final Shape BOOL = new Shape("boolean", false);
    private static final Shape NUMBER = new Shape("number", false);
    private static final Shape STRING = new Shape("string", false);
    private static final Shape NULL = new Shape("null", true);
    private int visits;

    private RuleFactExpressionAnalyzer() { }

    static void validate(JsonNode expression, RuleFactSchema schema) {
        new RuleFactExpressionAnalyzer().infer(expression, List.of(schema.root()), "$expression");
    }

    private List<Shape> infer(JsonNode node, List<Shape> scope, String path) {
        if (++visits > 50_000) throw new RuleFactContractException(RuleFactContractException.Code.ADMISSION_LIMIT_EXCEEDED, path);
        if (node == null || node.isNull()) return List.of(NULL);
        if (node.isBoolean()) return List.of(BOOL);
        if (node.isNumber()) return List.of(NUMBER);
        if (node.isTextual()) {
            Shape literal = new Shape("string", false);
            literal.literalText = node.textValue();
            return List.of(literal);
        }
        if (node.isArray()) {
            List<Shape> items = new ArrayList<>();
            for (int i = 0; i < node.size(); i++) items.addAll(infer(node.get(i), scope, path + "/" + i));
            return arrays(items.isEmpty() ? List.of(NULL) : items, node.size(), node.size());
        }
        if (node.size() != 1) {
            Map<String, List<Shape>> properties = new LinkedHashMap<>();
            for (var entry : node.properties()) properties.put(entry.getKey(), infer(entry.getValue(), scope, path + "/" + entry.getKey()));
            Shape object = new Shape("object", false);
            Map<String, Shape> children = new LinkedHashMap<>();
            // Object-valued accumulator fields must have one structural shape.
            properties.forEach((key, shapes) -> {
                if (shapes.size() != 1) type(path + "/" + key);
                children.put(key, shapes.getFirst());
            });
            object.properties = Map.copyOf(children);
            return List.of(object);
        }
        var entry = node.properties().iterator().next();
        String op = entry.getKey();
        JsonNode raw = entry.getValue();
        List<JsonNode> args = raw.isArray() ? elements(raw) : List.of(raw);
        if (op.equals("var")) {
            String name = raw.isTextual() ? raw.textValue() : raw.get(0).textValue();
            List<Shape> resolved = scope.stream().map(s -> RuleFactSchema.resolve(s, name, path)).toList();
            if (raw.isArray() && raw.size() == 2) return union(resolved, infer(raw.get(1), scope, path + "/default"));
            return resolved;
        }
        if (ITERATORS.contains(op)) return iterator(op, args, scope, path);
        List<List<Shape>> values = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) values.add(infer(args.get(i), scope, path + "/" + op + "/" + i));
        switch (op) {
            case "+", "-", "*", "/", "%", "min", "max", "round", "ceil", "floor", "abs" -> {
                values.forEach(v -> require(v, Set.of("number", "integer"), path));
                return List.of(NUMBER);
            }
            case "date", "yearsSince", "monthsSince", "daysSince", "isToday", "inLast", "weekdayIn" -> {
                require(values.getFirst(), Set.of("number", "integer", "string"), path);
                for (Shape shape : values.getFirst()) {
                    if (!shape.type.equals("string")) continue;
                    if (shape.literalText != null) {
                        String format = shape.literalText.length() == 10 ? "date" : "date-time";
                        try { RuleFactFormats.validate(format, shape.literalText, path); }
                        catch (RuleFactContractException invalid) { type(path); }
                    } else if (!Set.of("date", "date-time").contains(shape.format)) type(path);
                }
                if (op.equals("inLast")) {
                    require(values.get(1), Set.of("number", "integer"), path);
                    require(values.get(2), Set.of("string"), path);
                }
                if (op.equals("weekdayIn")) {
                    require(values.get(1), Set.of("array"), path);
                    require(values.get(1).stream().map(s -> s.items).toList(), Set.of("integer", "number"), path);
                }
                return List.of(Set.of("date", "yearsSince", "monthsSince", "daysSince").contains(op) ? NUMBER : BOOL);
            }
            case "==", "===", "!=", "!==", "!", "!!", "isBlank" -> { return List.of(BOOL); }
            case "<", ">", "<=", ">=" -> {
                for (Shape left : values.get(0)) for (Shape right : values.get(1)) {
                    if (left.type.equals("null") || right.type.equals("null")) continue;
                    if (!(numeric(left) && numeric(right)) && !(left.type.equals("string") && right.type.equals("string"))) type(path);
                }
                return List.of(BOOL);
            }
            case "in", "contains" -> {
                require(values.get(op.equals("in") ? 1 : 0), Set.of("array", "string"), path);
                return List.of(BOOL);
            }
            case "startsWith", "endsWith", "matches" -> {
                values.forEach(v -> require(v, Set.of("string"), path));
                return List.of(BOOL);
            }
            case "len" -> { require(values.getFirst(), Set.of("array", "object", "string"), path); return List.of(NUMBER); }
            case "cat", "stringify" -> { return List.of(STRING); }
            case "substr" -> {
                require(values.getFirst(), Set.of("string"), path);
                for (int i = 1; i < values.size(); i++) require(values.get(i), Set.of("number", "integer"), path);
                return List.of(STRING);
            }
            case "now", "toNumber" -> { return List.of(NUMBER); }
            case "jsonGet", "hasKey" -> {
                require(values.getFirst(), Set.of("object"), path);
                if (args.size() != 2 || !args.get(1).isTextual()) type(path);
                List<Shape> leaves = values.getFirst().stream().map(s -> RuleFactSchema.resolve(s, args.get(1).textValue(), path)).toList();
                return op.equals("hasKey") ? List.of(BOOL) : leaves;
            }
            case "if" -> {
                List<Shape> branches = new ArrayList<>();
                for (int i = 1; i < values.size(); i += 2) branches.addAll(values.get(i));
                if (values.size() % 2 == 1) branches.addAll(values.getLast());
                else branches.add(NULL);
                return bounded(branches);
            }
            case "and", "or" -> { return bounded(values.stream().flatMap(List::stream).toList()); }
            case "coalesce" -> {
                List<Shape> available = values.stream().flatMap(List::stream)
                        .filter(s -> !s.type.equals("null") && !"".equals(s.literalText)).toList();
                return available.isEmpty() ? List.of(NULL) : bounded(available);
            }
            case "merge" -> {
                List<Shape> items = new ArrayList<>();
                values.stream().flatMap(List::stream).forEach(s -> items.add(s.type.equals("array") ? s.items : s));
                return arrays(items);
            }
            default -> throw new RuleFactContractException(RuleFactContractException.Code.EXPRESSION_TYPE_INVALID, path);
        }
    }

    private List<Shape> iterator(String op, List<JsonNode> args, List<Shape> scope, String path) {
        List<Shape> sources = infer(args.getFirst(), scope, path + "/source");
        require(sources, Set.of("array"), path + "/source");
        List<Shape> items = sources.stream().filter(s -> s.type.equals("array")).map(s -> s.items).toList();
        if (items.isEmpty()) type(path);
        if (op.equals("reduce")) {
            List<Shape> initial = args.size() == 3 ? infer(args.get(2), scope, path + "/initial") : List.of(NULL);
            List<Shape> output = new ArrayList<>();
            for (Shape item : items) for (Shape acc : initial) {
                Shape frame = new Shape("object", false);
                frame.properties = Map.of("current", item, "accumulator", acc);
                List<Shape> body = infer(args.get(1), List.of(frame), path + "/reduce");
                for (Shape result : body) if (!sameStructure(acc, result)) type(path + "/accumulator");
                output.addAll(body);
            }
            return union(initial, output);
        }
        List<Shape> body = infer(args.get(1), items, path + "/" + op);
        if (op.equals("map")) return arrays(body);
        if (op.equals("filter")) return sources;
        return List.of(BOOL);
    }

    private static boolean sameStructure(Shape a, Shape b) {
        if (numeric(a) && numeric(b)) return true;
        if (!a.type.equals(b.type)) return false;
        if (a.type.equals("object")) return a.properties.keySet().equals(b.properties.keySet())
                && a.properties.entrySet().stream().allMatch(e -> sameStructure(e.getValue(), b.properties.get(e.getKey())));
        if (a.type.equals("array")) return sameStructure(a.items, b.items);
        return true;
    }

    private static boolean numeric(Shape value) { return value.type.equals("number") || value.type.equals("integer"); }
    private static void require(List<Shape> values, Set<String> types, String path) {
        for (Shape value : values) if (!types.contains(value.type)) type(path);
    }
    private static List<Shape> arrays(List<Shape> items) {
        return arrays(items, 0, 10_000);
    }
    private static List<Shape> arrays(List<Shape> items, int minimum, int maximum) {
        return bounded(items).stream().map(item -> {
            Shape array = new Shape("array", false); array.items = item;
            array.minimum = minimum; array.maximum = maximum; return array;
        }).toList();
    }
    private static List<Shape> union(List<Shape> a, List<Shape> b) {
        return bounded(java.util.stream.Stream.concat(a.stream(), b.stream()).toList());
    }
    private static List<Shape> bounded(List<Shape> shapes) {
        List<Shape> result = shapes.stream().distinct().limit(257).toList();
        if (result.size() > 256) throw new RuleFactContractException(RuleFactContractException.Code.ADMISSION_LIMIT_EXCEEDED, "$expression/types");
        return result;
    }
    private static List<JsonNode> elements(JsonNode value) {
        List<JsonNode> result = new ArrayList<>(); value.forEach(result::add); return result;
    }
    private static void type(String path) { throw new RuleFactContractException(RuleFactContractException.Code.EXPRESSION_TYPE_INVALID, path); }
}
