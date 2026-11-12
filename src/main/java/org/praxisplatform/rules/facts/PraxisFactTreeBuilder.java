package org.praxisplatform.rules.facts;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import com.fasterxml.jackson.databind.node.*;
import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import org.praxisplatform.rules.jsonlogic.PraxisJsonLogicEngine;
import org.praxisplatform.rules.jsonlogic.internal.PraxisPath;
import org.praxisplatform.rules.jsonlogic.model.JsonLogicLimits;

/**
 * Bounded deterministic serialization of preloaded, acyclic host context. No I/O, lazy loading,
 * domain inference or rule comparison is performed. Each returned tree is detached.
 */
public final class PraxisFactTreeBuilder {
    /** Canonical ontological root for the candidate mutation state (NEW). */
    public static final String ROOT_DRAFT = "draft";
    /** Canonical ontological root for the persisted pre-mutation state (OLD). */
    public static final String ROOT_CURRENT = "current";
    /** Canonical ontological root for N-hop sibling/aggregate facts in the same scope. */
    public static final String ROOT_NEIGHBORHOOD = "neighborhood";
    /** Canonical ontological root for frozen execution, tenant or parameter context. */
    public static final String ROOT_CONTEXT = "context";

    private static final Set<String> LOCAL_SCOPE_OPERATORS =
            Set.of("map", "filter", "all", "some", "none", "reduce");
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final ObjectMapper INTROSPECTOR = new ObjectMapper()
            .setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
    private static final PraxisJsonLogicEngine VALIDATOR = new PraxisJsonLogicEngine();
    private final ObjectNode facts = NODES.objectNode();

    /**
     * Creates an empty context. Builders are local to one facts capture, not shared across threads.
     */
    public PraxisFactTreeBuilder() { }

    /**
     * Starts a new facts capture.
     * @return empty fluent builder
     */
    public static PraxisFactTreeBuilder create() { return new PraxisFactTreeBuilder(); }

    /**
     * Captures an existing object context as the root of a new builder.
     * @param context object, record or map
     * @return builder initialized from its root properties
     */
    public static PraxisFactTreeBuilder from(Object context) {
        ObjectNode root = toObjectNode(context);
        PraxisFactTreeBuilder builder = create();
        builder.facts.setAll(root);
        return builder;
    }

    /**
     * Captures a property, preserving explicit null values.
     * @param name literal top-level property name
     * @param value host value, including explicit null
     * @return this builder
     */
    public PraxisFactTreeBuilder put(String name, Object value) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Fact name is required");
        facts.set(name, toJsonNode(value));
        return this;
    }

    /**
     * Captures or merges into a nested object built fluently via a child {@link PraxisFactTreeBuilder}.
     * @param name literal top-level property name
     * @param consumer callback that populates the nested object
     * @return this builder
     */
    public PraxisFactTreeBuilder putObject(String name, Consumer<PraxisFactTreeBuilder> consumer) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Fact name is required");
        Objects.requireNonNull(consumer, "consumer");
        PraxisFactTreeBuilder nested = facts.get(name) instanceof ObjectNode existing ? from(existing) : create();
        consumer.accept(nested);
        facts.set(name, nested.build());
        return this;
    }

    /**
     * Captures the candidate mutation state under the canonical {@link #ROOT_DRAFT} root.
     * @param draft candidate mutation object, record or map
     * @return this builder
     */
    public PraxisFactTreeBuilder putDraft(Object draft) {
        return put(ROOT_DRAFT, draft);
    }

    /**
     * Captures the candidate mutation state fluently under the canonical {@link #ROOT_DRAFT} root.
     * @param consumer callback that populates the {@code draft} node
     * @return this builder
     */
    public PraxisFactTreeBuilder putDraft(Consumer<PraxisFactTreeBuilder> consumer) {
        return putObject(ROOT_DRAFT, consumer);
    }

    /**
     * Captures the persisted pre-mutation state under the canonical {@link #ROOT_CURRENT} root.
     * @param current persisted pre-mutation object, record or map
     * @return this builder
     */
    public PraxisFactTreeBuilder putCurrent(Object current) {
        return put(ROOT_CURRENT, current);
    }

    /**
     * Captures the persisted pre-mutation state fluently under the canonical {@link #ROOT_CURRENT} root.
     * @param consumer callback that populates the {@code current} node
     * @return this builder
     */
    public PraxisFactTreeBuilder putCurrent(Consumer<PraxisFactTreeBuilder> consumer) {
        return putObject(ROOT_CURRENT, consumer);
    }

    /**
     * Captures a 1-hop related domain concept resolved via identity/FK join.
     * @param conceptKey domain concept root name
     * @param concept related concept projection, record or map
     * @return this builder
     */
    public PraxisFactTreeBuilder putConcept(String conceptKey, Object concept) {
        return put(conceptKey, concept);
    }

    /**
     * Captures a 1-hop related domain concept fluently via a nested builder.
     * @param conceptKey domain concept root name
     * @param consumer callback that populates the concept node
     * @return this builder
     */
    public PraxisFactTreeBuilder putConcept(String conceptKey, Consumer<PraxisFactTreeBuilder> consumer) {
        return putObject(conceptKey, consumer);
    }

    /**
     * Captures N-hop aggregate or sibling facts under the canonical {@link #ROOT_NEIGHBORHOOD} root.
     * @param neighborhood aggregate or collection neighborhood object, record or map
     * @return this builder
     */
    public PraxisFactTreeBuilder putNeighborhood(Object neighborhood) {
        return put(ROOT_NEIGHBORHOOD, neighborhood);
    }

    /**
     * Captures N-hop aggregate or sibling facts fluently under the canonical {@link #ROOT_NEIGHBORHOOD} root.
     * @param consumer callback that populates the {@code neighborhood} node
     * @return this builder
     */
    public PraxisFactTreeBuilder putNeighborhood(Consumer<PraxisFactTreeBuilder> consumer) {
        return putObject(ROOT_NEIGHBORHOOD, consumer);
    }

    /**
     * Captures frozen execution, tenant or parameter facts under the canonical {@link #ROOT_CONTEXT} root.
     * @param context execution context object, record or map
     * @return this builder
     */
    public PraxisFactTreeBuilder putContext(Object context) {
        return put(ROOT_CONTEXT, context);
    }

    /**
     * Captures frozen execution, tenant or parameter facts fluently under the canonical {@link #ROOT_CONTEXT} root.
     * @param consumer callback that populates the {@code context} node
     * @return this builder
     */
    public PraxisFactTreeBuilder putContext(Consumer<PraxisFactTreeBuilder> consumer) {
        return putObject(ROOT_CONTEXT, consumer);
    }

    /**
     * Captures scalar bean properties without reading persistence associations.
     * @param name top-level fact name
     * @param bean preloaded bean containing scalar properties and embedded values
     * @return this builder
     */
    public PraxisFactTreeBuilder putScalarBean(String name, Object bean) {
        return put(name, toScalarObjectNode(bean));
    }

    /**
     * Projects scalar properties, scalar collections and explicitly embedded values.
     * Association annotations are recognized by name, without a persistence dependency.
     * Unknown object properties are omitted before their accessors are invoked.
     * @param bean preloaded object or record, never an entity graph to traverse
     * @return bounded detached scalar projection
     */
    public static ObjectNode toScalarObjectNode(Object bean) {
        Objects.requireNonNull(bean, "bean");
        ObjectNode result = scalarBean(bean, new IdentityHashMap<>(), new Budget(), 0);
        VALIDATOR.validateResultValue(result, JsonLogicLimits.DEFAULT);
        return result;
    }

    private static ObjectNode scalarBean(Object bean, IdentityHashMap<Object, Boolean> ancestors, Budget budget, int depth) {
        budget.visit(depth);
        if (ancestors.put(bean, Boolean.TRUE) != null) throw new IllegalArgumentException("Cyclic embedded facts are not supported");
        try {
            ObjectNode result = NODES.objectNode();
            var properties = INTROSPECTOR.getSerializationConfig().introspect(INTROSPECTOR.constructType(bean.getClass()))
                    .findProperties().stream().sorted(Comparator.comparing(BeanPropertyDefinition::getName)).toList();
            for (var property : properties) {
                var accessor = property.getAccessor();
                if (accessor == null || persistenceAnnotation(property, Set.of("ManyToOne", "OneToMany", "OneToOne", "ManyToMany"))) continue;
                var type = accessor.getType();
                boolean embedded = persistenceAnnotation(property, Set.of("Embedded", "EmbeddedId"));
                boolean scalar = scalarType(type.getRawClass());
                boolean collection = (type.isCollectionLikeType() || type.isArrayType()) && type.getContentType() != null
                        && scalarType(type.getContentType().getRawClass());
                if (!embedded && !scalar && !collection) continue;
                accessor.fixAccess(true);
                Object value = accessor.getValue(bean);
                text(property.getName(), budget);
                result.set(property.getName(), embedded && value != null ? scalarBean(value, ancestors, budget, depth + 1)
                        : convertScalarValue(value, ancestors, budget, depth + 1));
            }
            return result;
        } finally { ancestors.remove(bean); }
    }

    private static boolean scalarType(Class<?> type) {
        return type.isPrimitive() || Number.class.isAssignableFrom(type) || CharSequence.class.isAssignableFrom(type)
                || type == Character.class || type == Boolean.class || type.isEnum() || type == UUID.class
                || java.time.temporal.TemporalAccessor.class.isAssignableFrom(type)
                || type == Duration.class || type == Period.class;
    }

    private static JsonNode convertScalarValue(Object value, IdentityHashMap<Object, Boolean> ancestors, Budget budget, int depth) {
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) if (item != null && !scalarType(item.getClass()))
                throw new IllegalArgumentException("Scalar fact collections cannot contain objects");
        } else if (value != null && value.getClass().isArray()) {
            for (int i = 0; i < Array.getLength(value); i++) {
                Object item = Array.get(value, i);
                if (item != null && !scalarType(item.getClass())) throw new IllegalArgumentException("Scalar fact arrays cannot contain objects");
            }
        } else if (value != null && !scalarType(value.getClass())) {
            throw new IllegalArgumentException("Scalar facts cannot contain objects");
        }
        return convert(value, ancestors, budget, depth);
    }

    private static boolean persistenceAnnotation(BeanPropertyDefinition property, Set<String> names) {
        for (var member : Arrays.asList(property.getField(), property.getGetter(), property.getSetter())) {
            if (member == null) continue;
            for (var annotation : member.annotations()) {
                String name = annotation.annotationType().getName();
                if (names.stream().anyMatch(simple -> name.equals("jakarta.persistence." + simple) || name.equals("javax.persistence." + simple))) return true;
            }
        }
        return false;
    }

    /**
     * Captures a non-null property while leaving null values absent.
     * @param name fact name
     * @param value optional host value
     * @return this builder; null leaves the property absent
     */
    public PraxisFactTreeBuilder putIfNotNull(String name, Object value) {
        return value == null ? this : put(name, value);
    }

    /**
     * Computes a derived host fact from a detached view of the current capture.
     * @param name derived fact name
     * @param derivation pure host function receiving a detached snapshot
     * @return this builder
     */
    public PraxisFactTreeBuilder derive(String name, Function<ObjectNode, ?> derivation) {
        return put(name, Objects.requireNonNull(derivation, "derivation").apply(build()));
    }

    /**
     * Materializes a detached snapshot with deterministic object order.
     * @return sorted, detached JSON object
     */
    public ObjectNode build() { return toObjectNode(facts); }

    /**
     * Captures one preloaded object as a bounded native JSON object.
     * @param context host context
     * @return deterministic object
     * @throws IllegalArgumentException for scalar roots
     */
    public static ObjectNode toObjectNode(Object context) {
        JsonNode node = toJsonNode(context);
        if (!(node instanceof ObjectNode object)) throw new IllegalArgumentException("Facts root must be an object");
        return object;
    }

    /**
     * Captures a supported host value using deterministic native JSON types.
     * @param value preloaded host value
     * @return bounded native JSON with finite numbers
     */
    public static JsonNode toJsonNode(Object value) {
        JsonNode node = convert(value, new IdentityHashMap<>(), new Budget(), 0);
        VALIDATOR.validateResultValue(node, JsonLogicLimits.DEFAULT);
        return node;
    }

    private static JsonNode convert(Object value, IdentityHashMap<Object, Boolean> ancestors, Budget budget, int depth) {
        budget.visit(depth);
        if (value == null) return NODES.nullNode();
        if (value instanceof JsonNode json) {
            if (json.isNull()) return NODES.nullNode();
            if (json.isTextual()) return text(json.textValue(), budget);
            if (json.isBoolean()) return NODES.booleanNode(json.booleanValue());
            if (json.isNumber()) return number(json.numberValue(), budget);
            if (!json.isContainerNode()) throw new IllegalArgumentException("Facts require native JSON values");
        }
        if (value instanceof CharSequence || value instanceof Character || value instanceof UUID) return text(value.toString(), budget);
        if (value instanceof Boolean bool) return NODES.booleanNode(bool);
        if (value instanceof Number number) return number(number, budget);
        if (value instanceof Enum<?> enumeration) return text(enumeration.name(), budget);
        if (value instanceof LocalDate date) return text(date.format(DateTimeFormatter.ISO_LOCAL_DATE), budget);
        if (value instanceof LocalDateTime dateTime) return text(dateTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME), budget);
        if (value instanceof OffsetDateTime dateTime) return text(dateTime.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME), budget);
        if (value instanceof ZonedDateTime dateTime) return text(dateTime.format(DateTimeFormatter.ISO_ZONED_DATE_TIME), budget);
        if (value instanceof Instant || value instanceof LocalTime || value instanceof OffsetTime
                || value instanceof Year || value instanceof YearMonth || value instanceof Duration || value instanceof Period)
            return text(value.toString(), budget);
        if (value instanceof java.time.temporal.TemporalAccessor || value instanceof java.time.temporal.TemporalAmount)
            return text(value.toString(), budget);
        if (ancestors.put(value, Boolean.TRUE) != null) throw new IllegalArgumentException("Cyclic facts are not supported");
        try {
            if (value instanceof Optional<?> optional) return convert(optional.orElse(null), ancestors, budget, depth + 1);
            if (value instanceof Map<?, ?> map) {
                TreeMap<String, Object> sorted = new TreeMap<>();
                for (var entry : map.entrySet()) {
                    if (!(entry.getKey() instanceof String key)) throw new IllegalArgumentException("Fact map keys must be strings");
                    text(key, budget);
                    sorted.put(key, entry.getValue());
                }
                ObjectNode object = NODES.objectNode();
                sorted.forEach((key, nested) -> object.set(key, convert(nested, ancestors, budget, depth + 1)));
                return object;
            }
            if (value instanceof ObjectNode object) {
                ObjectNode copy = NODES.objectNode();
                object.properties().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                    text(entry.getKey(), budget);
                    copy.set(entry.getKey(), convert(entry.getValue(), ancestors, budget, depth + 1));
                });
                return copy;
            }
            if (value instanceof Collection<?> || value instanceof ArrayNode || value.getClass().isArray()) {
                List<JsonNode> entries = new ArrayList<>();
                if (value instanceof Iterable<?> iterable) {
                    for (Object nested : iterable) entries.add(convert(nested, ancestors, budget, depth + 1));
                } else {
                    for (int i = 0; i < Array.getLength(value); i++) entries.add(convert(Array.get(value, i), ancestors, budget, depth + 1));
                }
                if (value instanceof Set<?>) entries.sort(Comparator.comparing(JsonNode::toString));
                ArrayNode array = NODES.arrayNode();
                entries.forEach(array::add);
                return array;
            }
            List<BeanPropertyDefinition> properties = INTROSPECTOR.getSerializationConfig()
                    .introspect(INTROSPECTOR.constructType(value.getClass())).findProperties().stream()
                    .filter(property -> property.getAccessor() != null)
                    .sorted(Comparator.comparing(BeanPropertyDefinition::getName)).toList();
            if (properties.isEmpty()) throw new IllegalArgumentException("Unsupported fact type: " + value.getClass().getName());
            ObjectNode object = NODES.objectNode();
            for (BeanPropertyDefinition property : properties) {
                text(property.getName(), budget);
                var accessor = property.getAccessor();
                accessor.fixAccess(true);
                object.set(property.getName(), convert(accessor.getValue(value), ancestors, budget, depth + 1));
            }
            return object;
        } finally { ancestors.remove(value); }
    }

    private static TextNode text(String value, Budget budget) {
        if (value.length() > 64_000) throw new IllegalArgumentException("Fact string limit exceeded");
        budget.bytes(value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        return NODES.textNode(value);
    }

    private static JsonNode number(Number value, Budget budget) {
        if (value instanceof Double d && !Double.isFinite(d) || value instanceof Float f && !Float.isFinite(f))
            throw new IllegalArgumentException("Fact numbers must be finite");
        BigDecimal decimal = value instanceof BigDecimal d ? d : value instanceof BigInteger i
                ? new BigDecimal(i) : new BigDecimal(value.toString());
        if (decimal.precision() + Math.abs((long) decimal.scale()) > 64_000)
            throw new IllegalArgumentException("Fact number limit exceeded");
        budget.bytes(decimal.precision() + Math.abs((long) decimal.scale()));
        return NODES.numberNode(decimal.stripTrailingZeros());
    }

    private static final class Budget {
        private int nodes;
        private long bytes;
        void visit(int depth) {
            if (depth > 64 || ++nodes > 10_000) throw new IllegalArgumentException("Fact structure limit exceeded");
        }
        void bytes(long amount) {
            if ((bytes += amount) > 256_000) throw new IllegalArgumentException("Fact byte limit exceeded");
        }
    }

    /**
     * Inclusive ranges; null starts/ends mean unbounded. Inverted ranges are invalid.
     *
     * @param start first start
     * @param end first end
     * @param otherStart second start
     * @param otherEnd second end
     *
     * @return whether the ranges share at least one date
     */
    public static boolean hasDateRangeOverlap(LocalDate start, LocalDate end, LocalDate otherStart, LocalDate otherEnd) {
        requireRange(start, end);
        requireRange(otherStart, otherEnd);
        return (end == null || otherStart == null || !end.isBefore(otherStart))
                && (otherEnd == null || start == null || !otherEnd.isBefore(start));
    }

    /**
     * Tests an inclusive effective date interval.
     * @param reference required date
     * @param start optional lower bound
     * @param end optional upper bound
     * @return inclusive membership
     */
    public static boolean isWithinDateRange(LocalDate reference, LocalDate start, LocalDate end) {
        Objects.requireNonNull(reference, "reference");
        requireRange(start, end);
        return (start == null || !reference.isBefore(start)) && (end == null || !reference.isAfter(end));
    }

    private static void requireRange(LocalDate start, LocalDate end) {
        if (start != null && end != null && end.isBefore(start)) throw new IllegalArgumentException("Inverted date range");
    }

    /**
     * Extracts deterministic top-level ontological roots referenced by {@code var} operations
     * outside iterator-local scopes ({@code map}, {@code filter}, {@code all}, {@code some},
     * {@code none}, {@code reduce}).
     *
     * @param expression JSON Logic AST node
     * @return immutable list of distinct top-level roots in encounter order
     */
    public static List<String> extractReferencedRoots(JsonNode expression) {
        Set<String> roots = new LinkedHashSet<>();
        for (String path : extractReferencedFactPaths(expression)) {
            List<String> segments = PraxisPath.parse(path);
            if (!segments.isEmpty()) {
                roots.add(segments.get(0));
            }
        }
        return List.copyOf(roots);
    }

    /**
     * Extracts deterministic fact paths referenced by {@code var} operations outside
     * iterator-local scopes ({@code map}, {@code filter}, {@code all}, {@code some},
     * {@code none}, {@code reduce}).
     *
     * @param expression JSON Logic AST node
     * @return immutable list of distinct referenced fact paths in encounter order
     */
    public static List<String> extractReferencedFactPaths(JsonNode expression) {
        Set<String> paths = new LinkedHashSet<>();
        collectReferencedFactPaths(expression, paths, false);
        return List.copyOf(paths);
    }

    private static void collectReferencedFactPaths(JsonNode node, Set<String> paths, boolean localScope) {
        if (node == null || node.isValueNode()) {
            return;
        }
        if (node.isArray()) {
            node.forEach(child -> collectReferencedFactPaths(child, paths, localScope));
            return;
        }
        if (node.size() == 1) {
            String operator = node.properties().iterator().next().getKey();
            JsonNode arguments = node.get(operator);
            if (LOCAL_SCOPE_OPERATORS.contains(operator)
                    && arguments != null
                    && arguments.isArray()
                    && arguments.size() >= 2) {
                collectReferencedFactPaths(arguments.get(0), paths, localScope);
                collectReferencedFactPaths(arguments.get(1), paths, true);
                for (int index = 2; index < arguments.size(); index++) {
                    collectReferencedFactPaths(arguments.get(index), paths, localScope);
                }
                return;
            }
        }
        JsonNode variable = node.get("var");
        if (!localScope && node.size() == 1 && variable != null) {
            JsonNode pathNode = variable.isArray() && !variable.isEmpty() ? variable.get(0) : variable;
            if (pathNode != null && pathNode.isTextual() && !pathNode.textValue().isBlank()) {
                String raw = pathNode.textValue();
                List<String> segments = PraxisPath.parse(raw);
                if (!segments.isEmpty()) {
                    paths.add(raw.startsWith("$.") ? raw.substring(2) : raw);
                }
            }
            if (variable.isArray() && variable.size() > 1) {
                collectReferencedFactPaths(variable.get(1), paths, localScope);
            }
            return;
        }
        node.properties().forEach(entry -> collectReferencedFactPaths(entry.getValue(), paths, localScope));
    }

    /**
     * Null-preserving integer property setter on an {@link ObjectNode}.
     *
     * @param node target JSON object node
     * @param field property name
     * @param value nullable integer value
     */
    public static void putInt(ObjectNode node, String field, Integer value) {
        Objects.requireNonNull(node, "node");
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }

    /**
     * Null-preserving long property setter on an {@link ObjectNode}.
     *
     * @param node target JSON object node
     * @param field property name
     * @param value nullable long value
     */
    public static void putLong(ObjectNode node, String field, Long value) {
        Objects.requireNonNull(node, "node");
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }

    /**
     * Null-preserving decimal property setter on an {@link ObjectNode}.
     *
     * @param node target JSON object node
     * @param field property name
     * @param value nullable decimal value
     */
    public static void putDecimal(ObjectNode node, String field, BigDecimal value) {
        Objects.requireNonNull(node, "node");
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }

    /**
     * Null-preserving boolean property setter on an {@link ObjectNode}.
     *
     * @param node target JSON object node
     * @param field property name
     * @param value nullable boolean value
     */
    public static void putBool(ObjectNode node, String field, Boolean value) {
        Objects.requireNonNull(node, "node");
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }

    /**
     * Null-preserving text property setter on an {@link ObjectNode}.
     *
     * @param node target JSON object node
     * @param field property name
     * @param value nullable text value
     */
    public static void putText(ObjectNode node, String field, String value) {
        Objects.requireNonNull(node, "node");
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }

    /**
     * Null-preserving ISO-8601 civil date property setter on an {@link ObjectNode}.
     *
     * @param node target JSON object node
     * @param field property name
     * @param value nullable {@link LocalDate} value
     */
    public static void putDate(ObjectNode node, String field, LocalDate value) {
        Objects.requireNonNull(node, "node");
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value.toString());
        }
    }

    /**
     * Null-preserving string collection setter on an {@link ObjectNode}.
     *
     * @param node target JSON object node
     * @param field property name
     * @param values collection of strings (null elements are skipped; null collection creates empty array)
     */
    public static void putStringList(ObjectNode node, String field, Collection<String> values) {
        Objects.requireNonNull(node, "node");
        ArrayNode array = node.putArray(field);
        if (values != null) {
            for (String item : values) {
                if (item != null) {
                    array.add(item);
                }
            }
        }
    }
}
