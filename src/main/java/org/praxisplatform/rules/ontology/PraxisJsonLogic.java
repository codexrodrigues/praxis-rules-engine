package org.praxisplatform.rules.ontology;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import org.praxisplatform.rules.facts.PraxisFactTreeBuilder;
import org.praxisplatform.rules.jsonlogic.internal.PraxisPath;

/**
 * Injection-safe, Jackson-backed JSON Logic DSL aligned with the 5 canonical Praxis ontological
 * roots ({@code draft}, {@code current}, {@code <concept>}, {@code neighborhood}, {@code context}).
 *
 * <p>All nodes are constructed via Jackson {@link ObjectNode}/{@link ArrayNode} trees and validated
 * against {@link PraxisPath}, eliminating manual JSON string concatenation and injection risks.
 * Every {@link Expr} wraps an immutable copy of a valid single-operator JSON Logic {@link ObjectNode}
 * suitable for {@link org.praxisplatform.rules.jsonlogic.PraxisJsonLogicEngine},
 * {@link PraxisFactTreeBuilder} AST root/path extraction, and {@link OntologyRuleSetBuilder}.</p>
 */
public final class PraxisJsonLogic {
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final ObjectMapper JSON = new ObjectMapper();

    private PraxisJsonLogic() {}

    /**
     * Immutable JSON Logic expression node backed by a defensive {@link ObjectNode} copy.
     */
    public static final class Expr {
        private final ObjectNode node;

        private Expr(ObjectNode node) {
            this.node = Objects.requireNonNull(node, "node").deepCopy();
        }

        /**
         * Returns a defensive {@link ObjectNode} copy of this JSON Logic expression.
         *
         * @return detached JSON Logic {@link ObjectNode}
         */
        public ObjectNode toJsonNode() {
            return node.deepCopy();
        }

        /**
         * Serializes this expression to canonical JSON text.
         *
         * @return valid JSON Logic string
         */
        public String toJson() {
            return PraxisJsonLogic.toJson(node);
        }

        /**
         * Combines this predicate with another expression using logical {@code and}.
         *
         * @param other additional predicate
         * @return combined {@code and} expression
         */
        public Expr and(Expr other) {
            return PraxisJsonLogic.and(this, Objects.requireNonNull(other, "other"));
        }

        /**
         * Combines this predicate with another expression using logical {@code or}.
         *
         * @param other alternative predicate
         * @return combined {@code or} expression
         */
        public Expr or(Expr other) {
            return PraxisJsonLogic.or(this, Objects.requireNonNull(other, "other"));
        }

        /**
         * Negates this expression using logical {@code !}.
         *
         * @return negated expression
         */
        public Expr not() {
            return PraxisJsonLogic.not(this);
        }

        /**
         * Guards this predicate with an applicability condition: {@code !condition || this}.
         *
         * @param condition applicability condition
         * @return conditional expression returning {@code true} when {@code condition} is false
         */
        public Expr when(Expr condition) {
            return PraxisJsonLogic.when(condition, this);
        }

        /**
         * Enforces this predicate only when {@code context.operation !== "DELETE"}.
         *
         * @return non-delete guarded expression
         */
        public Expr nonDelete() {
            return PraxisJsonLogic.nonDelete(this);
        }

        /**
         * Enforces this predicate only when {@code context.operation === operation}.
         *
         * @param operation operation key under {@code context.operation}
         * @return operation-scoped expression
         */
        public Expr whenOperation(String operation) {
            return PraxisJsonLogic.whenOperation(operation, this);
        }

        @Override
        public String toString() {
            return toJson();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Expr expr && node.equals(expr.node);
        }

        @Override
        public int hashCode() {
            return node.hashCode();
        }
    }

    /**
     * Serializes a JSON Logic {@link ObjectNode} to a JSON string.
     *
     * @param node JSON Logic expression object
     * @return serialized JSON string
     */
    public static String toJson(ObjectNode node) {
        return toJson((JsonNode) node);
    }

    /**
     * Serializes any JSON Logic {@link JsonNode} to a JSON string.
     *
     * @param node JSON node
     * @return serialized JSON string
     */
    public static String toJson(JsonNode node) {
        Objects.requireNonNull(node, "node");
        try {
            return JSON.writeValueAsString(node);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Failed to serialize JSON Logic node", failure);
        }
    }

    /**
     * Serializes an {@link Expr} to a JSON string.
     *
     * @param expression DSL expression
     * @return serialized JSON string
     */
    public static String toJson(Expr expression) {
        return Objects.requireNonNull(expression, "expression").toJson();
    }

    /**
     * Creates a validated JSON Logic {@code var} reference against an ontological root path.
     *
     * @param path non-blank Praxis variable path (for example {@code "draft.startDate"})
     * @return {@code {"var": path}} expression
     * @throws IllegalArgumentException when the path is blank, invalid, or targets {@code facts.*}
     */
    public static Expr var(String path) {
        String normalized = requireFactPath(path);
        ObjectNode node = NODES.objectNode();
        node.put("var", normalized);
        return new Expr(node);
    }

    /**
     * Creates a validated JSON Logic {@code var} reference with an explicit default value.
     *
     * @param path non-blank Praxis variable path
     * @param defaultValue fallback expression or scalar value used when path is absent
     * @return {@code {"var": [path, defaultValue]}} expression
     */
    public static Expr var(String path, Object defaultValue) {
        String normalized = requireFactPath(path);
        ArrayNode args = NODES.arrayNode();
        args.add(normalized);
        args.add(toOperand(defaultValue));
        ObjectNode node = NODES.objectNode();
        node.set("var", args);
        return new Expr(node);
    }

    /**
     * References a field under the canonical {@code draft} root.
     *
     * @param field field or nested subpath under {@code draft}
     * @return {@code {"var": "draft.<field>"}}
     */
    public static Expr draft(String field) {
        return var(PraxisFactTreeBuilder.ROOT_DRAFT + "." + requireSubpath(field, "field"));
    }

    /**
     * References a field under the canonical {@code current} root.
     *
     * @param field field or nested subpath under {@code current}
     * @return {@code {"var": "current.<field>"}}
     */
    public static Expr current(String field) {
        return var(PraxisFactTreeBuilder.ROOT_CURRENT + "." + requireSubpath(field, "field"));
    }

    /**
     * References a field under a 1-hop domain concept root.
     *
     * @param root domain concept root name (must not be {@code "facts"})
     * @param field field or nested subpath under the concept root
     * @return {@code {"var": "<root>.<field>"}}
     */
    public static Expr concept(String root, String field) {
        String cleanRoot = requireConceptRoot(root);
        String cleanField = requireSubpath(field, "field");
        return var(cleanRoot + "." + cleanField);
    }

    /**
     * References a field under the canonical {@code neighborhood} root.
     *
     * @param field field or nested subpath under {@code neighborhood}
     * @return {@code {"var": "neighborhood.<field>"}}
     */
    public static Expr neighborhood(String field) {
        return var(PraxisFactTreeBuilder.ROOT_NEIGHBORHOOD + "." + requireSubpath(field, "field"));
    }

    /**
     * References a field under the canonical {@code context} root.
     *
     * @param field field or nested subpath under {@code context}
     * @return {@code {"var": "context.<field>"}}
     */
    public static Expr context(String field) {
        return var(PraxisFactTreeBuilder.ROOT_CONTEXT + "." + requireSubpath(field, "field"));
    }

    /**
     * References an iterator-local variable inside {@code map}, {@code filter}, {@code all},
     * {@code some}, {@code none}, or {@code reduce} (including {@code ""} for the current item).
     *
     * @param name iterator-local variable path (may be {@code ""})
     * @return {@code {"var": name}} expression
     */
    public static Expr localVar(String name) {
        Objects.requireNonNull(name, "name");
        if (!name.isEmpty()) {
            try {
                PraxisPath.parse(name);
            } catch (RuntimeException invalid) {
                throw new IllegalArgumentException("Invalid local variable path: " + name, invalid);
            }
        }
        ObjectNode node = NODES.objectNode();
        node.put("var", name);
        return new Expr(node);
    }

    /**
     * Converts a Java scalar, collection, {@link LocalDate}, or {@link JsonNode} into a safe
     * native JSON Logic literal node.
     *
     * @param value scalar, {@code null}, {@link LocalDate}, enum, collection, or {@link JsonNode}
     * @return detached native {@link JsonNode}
     */
    public static JsonNode literal(Object value) {
        if (value instanceof Expr) {
            throw new IllegalArgumentException("Use the Expr directly instead of wrapping it with literal()");
        }
        return toOperand(value);
    }

    /**
     * Builds a loose equality comparison {@code {"==": [left, right]}}.
     *
     * @param left left expression
     * @param right right expression or scalar literal (may be {@code null})
     * @return equality expression
     */
    public static Expr eq(Expr left, Object right) {
        return binary("==", Objects.requireNonNull(left, "left"), right);
    }

    /**
     * Builds a loose equality comparison {@code {"==": [{"var": path}, right]}}.
     *
     * @param path variable path
     * @param right right expression or scalar literal (may be {@code null})
     * @return equality expression
     */
    public static Expr eq(String path, Object right) {
        return eq(var(path), right);
    }

    /**
     * Builds a loose equality comparison {@code {"==": [left, right]}}.
     *
     * @param left left JSON node operand
     * @param right right expression or scalar literal (may be {@code null})
     * @return equality expression
     */
    public static Expr eq(JsonNode left, Object right) {
        return op("==", Objects.requireNonNull(left, "left"), right);
    }

    /**
     * Builds a strict equality comparison {@code {"===": [left, right]}}.
     *
     * @param left left expression
     * @param right right expression or scalar literal (may be {@code null})
     * @return strict equality expression
     */
    public static Expr strictEq(Expr left, Object right) {
        return binary("===", Objects.requireNonNull(left, "left"), right);
    }

    /**
     * Builds a strict equality comparison {@code {"===": [{"var": path}, right]}}.
     *
     * @param path variable path
     * @param right right expression or scalar literal (may be {@code null})
     * @return strict equality expression
     */
    public static Expr strictEq(String path, Object right) {
        return strictEq(var(path), right);
    }

    /**
     * Builds a strict equality comparison {@code {"===": [left, right]}}.
     *
     * @param left left JSON node operand
     * @param right right expression or scalar literal (may be {@code null})
     * @return strict equality expression
     */
    public static Expr strictEq(JsonNode left, Object right) {
        return op("===", Objects.requireNonNull(left, "left"), right);
    }

    /**
     * Builds a loose inequality comparison {@code {"!=": [left, right]}}.
     *
     * @param left left expression
     * @param right right expression or scalar literal (may be {@code null})
     * @return inequality expression
     */
    public static Expr neq(Expr left, Object right) {
        return binary("!=", Objects.requireNonNull(left, "left"), right);
    }

    /**
     * Builds a loose inequality comparison {@code {"!=": [{"var": path}, right]}}.
     *
     * @param path variable path
     * @param right right expression or scalar literal (may be {@code null})
     * @return inequality expression
     */
    public static Expr neq(String path, Object right) {
        return neq(var(path), right);
    }

    /**
     * Builds a loose inequality comparison {@code {"!=": [left, right]}}.
     *
     * @param left left JSON node operand
     * @param right right expression or scalar literal (may be {@code null})
     * @return inequality expression
     */
    public static Expr neq(JsonNode left, Object right) {
        return op("!=", Objects.requireNonNull(left, "left"), right);
    }

    /**
     * Builds a strict inequality comparison {@code {"!==": [left, right]}}.
     *
     * @param left left expression
     * @param right right expression or scalar literal (may be {@code null})
     * @return strict inequality expression
     */
    public static Expr strictNeq(Expr left, Object right) {
        return binary("!==", Objects.requireNonNull(left, "left"), right);
    }

    /**
     * Builds a strict inequality comparison {@code {"!==": [{"var": path}, right]}}.
     *
     * @param path variable path
     * @param right right expression or scalar literal (may be {@code null})
     * @return strict inequality expression
     */
    public static Expr strictNeq(String path, Object right) {
        return strictNeq(var(path), right);
    }

    /**
     * Builds a strict inequality comparison {@code {"!==": [left, right]}}.
     *
     * @param left left JSON node operand
     * @param right right expression or scalar literal (may be {@code null})
     * @return strict inequality expression
     */
    public static Expr strictNeq(JsonNode left, Object right) {
        return op("!==", Objects.requireNonNull(left, "left"), right);
    }

    /**
     * Builds a greater-than comparison {@code {">": [left, right]}}.
     *
     * @param left left expression
     * @param right right expression or comparable literal
     * @return greater-than expression
     */
    public static Expr gt(Expr left, Object right) {
        return binary(">", Objects.requireNonNull(left, "left"), right);
    }

    /**
     * Builds a greater-than comparison {@code {">": [{"var": path}, right]}}.
     *
     * @param path variable path
     * @param right right expression or comparable literal
     * @return greater-than expression
     */
    public static Expr gt(String path, Object right) {
        return gt(var(path), right);
    }

    /**
     * Builds a greater-than-or-equal comparison {@code {">=": [left, right]}}.
     *
     * @param left left expression
     * @param right right expression or comparable literal
     * @return greater-than-or-equal expression
     */
    public static Expr gte(Expr left, Object right) {
        return binary(">=", Objects.requireNonNull(left, "left"), right);
    }

    /**
     * Builds a greater-than-or-equal comparison {@code {">=": [{"var": path}, right]}}.
     *
     * @param path variable path
     * @param right right expression or comparable literal
     * @return greater-than-or-equal expression
     */
    public static Expr gte(String path, Object right) {
        return gte(var(path), right);
    }

    /**
     * Builds a less-than comparison {@code {"<": [left, right]}}.
     *
     * @param left left expression
     * @param right right expression or comparable literal
     * @return less-than expression
     */
    public static Expr lt(Expr left, Object right) {
        return binary("<", Objects.requireNonNull(left, "left"), right);
    }

    /**
     * Builds a less-than comparison {@code {"<": [{"var": path}, right]}}.
     *
     * @param path variable path
     * @param right right expression or comparable literal
     * @return less-than expression
     */
    public static Expr lt(String path, Object right) {
        return lt(var(path), right);
    }

    /**
     * Builds a less-than-or-equal comparison {@code {"<=": [left, right]}}.
     *
     * @param left left expression
     * @param right right expression or comparable literal
     * @return less-than-or-equal expression
     */
    public static Expr lte(Expr left, Object right) {
        return binary("<=", Objects.requireNonNull(left, "left"), right);
    }

    /**
     * Builds a less-than-or-equal comparison {@code {"<=": [{"var": path}, right]}}.
     *
     * @param path variable path
     * @param right right expression or comparable literal
     * @return less-than-or-equal expression
     */
    public static Expr lte(String path, Object right) {
        return lte(var(path), right);
    }

    /**
     * Builds a logical conjunction {@code {"and": [...]}}.
     *
     * @param expressions non-empty array of conditions
     * @return conjunction expression
     */
    public static Expr and(Expr... expressions) {
        Objects.requireNonNull(expressions, "expressions");
        return and(Arrays.asList(expressions));
    }

    /**
     * Builds a logical conjunction {@code {"and": [...]}}.
     *
     * @param expressions non-empty collection of conditions
     * @return conjunction expression
     */
    public static Expr and(Collection<Expr> expressions) {
        return variadicExpr("and", expressions);
    }

    /**
     * Builds a logical disjunction {@code {"or": [...]}}.
     *
     * @param expressions non-empty array of conditions
     * @return disjunction expression
     */
    public static Expr or(Expr... expressions) {
        Objects.requireNonNull(expressions, "expressions");
        return or(Arrays.asList(expressions));
    }

    /**
     * Builds a logical disjunction {@code {"or": [...]}}.
     *
     * @param expressions non-empty collection of conditions
     * @return disjunction expression
     */
    public static Expr or(Collection<Expr> expressions) {
        return variadicExpr("or", expressions);
    }

    /**
     * Builds a logical negation {@code {"!": [expression]}}.
     *
     * @param expression target expression
     * @return negated expression
     */
    public static Expr not(Expr expression) {
        return op("!", Objects.requireNonNull(expression, "expression"));
    }

    /**
     * Builds an {@code in} membership/substring test {@code {"in": [needle, haystack]}}.
     *
     * @param needle item or substring expression
     * @param haystack array, collection, string, or expression resolving to one
     * @return {@code in} expression
     */
    public static Expr in(Expr needle, Object haystack) {
        return binary("in", Objects.requireNonNull(needle, "needle"), Objects.requireNonNull(haystack, "haystack"));
    }

    /**
     * Builds an {@code in} membership/substring test {@code {"in": [{"var": needlePath}, haystack]}}.
     *
     * @param needlePath variable path for the candidate item or substring
     * @param haystack array, collection, string, or expression resolving to one
     * @return {@code in} expression
     */
    public static Expr in(String needlePath, Object haystack) {
        return in(var(needlePath), haystack);
    }

    /**
     * Builds an {@code in} membership test against an explicit list of candidate values.
     *
     * @param needle item expression
     * @param values candidate values
     * @return {@code in} expression
     */
    public static Expr inValues(Expr needle, Object... values) {
        Objects.requireNonNull(values, "values");
        return in(needle, Arrays.asList(values));
    }

    /**
     * Builds an {@code in} membership test for a variable path against an explicit list of values.
     *
     * @param needlePath variable path for the candidate item
     * @param values candidate values
     * @return {@code in} expression
     */
    public static Expr inValues(String needlePath, Object... values) {
        Objects.requireNonNull(values, "values");
        return in(var(needlePath), Arrays.asList(values));
    }

    /**
     * Builds a Praxis {@code contains} expression {@code {"contains": [haystack, needle]}}.
     *
     * @param haystack string or array expression
     * @param needle expected element or substring
     * @return {@code contains} expression
     */
    public static Expr contains(Expr haystack, Object needle) {
        return binary("contains", Objects.requireNonNull(haystack, "haystack"), needle);
    }

    /**
     * Builds a Praxis {@code contains} expression {@code {"contains": [{"var": haystackPath}, needle]}}.
     *
     * @param haystackPath variable path for string or array
     * @param needle expected element or substring
     * @return {@code contains} expression
     */
    public static Expr contains(String haystackPath, Object needle) {
        return contains(var(haystackPath), needle);
    }

    /**
     * Builds a Praxis {@code startsWith} expression {@code {"startsWith": [text, prefix]}}.
     *
     * @param text string expression
     * @param prefix prefix literal or expression
     * @return {@code startsWith} expression
     */
    public static Expr startsWith(Expr text, Object prefix) {
        return binary("startsWith", Objects.requireNonNull(text, "text"), Objects.requireNonNull(prefix, "prefix"));
    }

    /**
     * Builds a Praxis {@code startsWith} expression {@code {"startsWith": [{"var": path}, prefix]}}.
     *
     * @param path variable path
     * @param prefix prefix literal or expression
     * @return {@code startsWith} expression
     */
    public static Expr startsWith(String path, Object prefix) {
        return startsWith(var(path), prefix);
    }

    /**
     * Builds a Praxis {@code endsWith} expression {@code {"endsWith": [text, suffix]}}.
     *
     * @param text string expression
     * @param suffix suffix literal or expression
     * @return {@code endsWith} expression
     */
    public static Expr endsWith(Expr text, Object suffix) {
        return binary("endsWith", Objects.requireNonNull(text, "text"), Objects.requireNonNull(suffix, "suffix"));
    }

    /**
     * Builds a Praxis {@code endsWith} expression {@code {"endsWith": [{"var": path}, suffix]}}.
     *
     * @param path variable path
     * @param suffix suffix literal or expression
     * @return {@code endsWith} expression
     */
    public static Expr endsWith(String path, Object suffix) {
        return endsWith(var(path), suffix);
    }

    /**
     * Builds a Praxis safe-regex {@code matches} expression {@code {"matches": [text, pattern]}}.
     *
     * @param text string expression
     * @param pattern bounded safe regex pattern
     * @return {@code matches} expression
     */
    public static Expr matches(Expr text, String pattern) {
        return binary("matches", Objects.requireNonNull(text, "text"), Objects.requireNonNull(pattern, "pattern"));
    }

    /**
     * Builds a Praxis safe-regex {@code matches} expression {@code {"matches": [{"var": path}, pattern]}}.
     *
     * @param path variable path
     * @param pattern bounded safe regex pattern
     * @return {@code matches} expression
     */
    public static Expr matches(String path, String pattern) {
        return matches(var(path), pattern);
    }

    /**
     * Builds a null check {@code {"==": [target, null]}} (treating missing and {@code null} consistently).
     *
     * @param target expression to check
     * @return null-check expression
     */
    public static Expr isNull(Expr target) {
        return eq(Objects.requireNonNull(target, "target"), null);
    }

    /**
     * Builds a null check {@code {"==": [{"var": path}, null]}}.
     *
     * @param path variable path
     * @return null-check expression
     */
    public static Expr isNull(String path) {
        return isNull(var(path));
    }

    /**
     * Builds a non-null check {@code {"!=": [target, null]}}.
     *
     * @param target expression to check
     * @return non-null check expression
     */
    public static Expr notNull(Expr target) {
        return neq(Objects.requireNonNull(target, "target"), null);
    }

    /**
     * Builds a non-null check {@code {"!=": [{"var": path}, null]}}.
     *
     * @param path variable path
     * @return non-null check expression
     */
    public static Expr notNull(String path) {
        return notNull(var(path));
    }

    /**
     * Builds a Praxis {@code isBlank} check {@code {"isBlank": [target]}}.
     *
     * @param target expression to check
     * @return blank-check expression
     */
    public static Expr isBlank(Expr target) {
        return op("isBlank", Objects.requireNonNull(target, "target"));
    }

    /**
     * Builds a Praxis {@code isBlank} check {@code {"isBlank": [{"var": path}]}}.
     *
     * @param path variable path
     * @return blank-check expression
     */
    public static Expr isBlank(String path) {
        return isBlank(var(path));
    }

    /**
     * Builds a negation of {@code isBlank}: {@code {"!": [{"isBlank": [target]}]}}.
     *
     * @param target expression to check
     * @return non-blank check expression
     */
    public static Expr notBlank(Expr target) {
        return not(isBlank(target));
    }

    /**
     * Builds a negation of {@code isBlank} for a path: {@code {"!": [{"isBlank": [{"var": path}]}]}}.
     *
     * @param path variable path
     * @return non-blank check expression
     */
    public static Expr notBlank(String path) {
        return notBlank(var(path));
    }

    /**
     * Encodes an implication {@code condition -> predicate} as {@code {"or": [{"!": [condition]}, predicate]}}.
     * When {@code condition} is false, the rule passes ({@code true}).
     *
     * @param condition applicability guard
     * @param predicate invariant enforced when {@code condition} holds
     * @return guarded implication expression
     */
    public static Expr when(Expr condition, Expr predicate) {
        return or(not(Objects.requireNonNull(condition, "condition")), Objects.requireNonNull(predicate, "predicate"));
    }

    /**
     * Enforces {@code predicate} on all non-{@code DELETE} operations ({@code context.operation === "DELETE"} bypasses).
     *
     * @param predicate rule invariant for create/update/custom operations
     * @return non-delete guarded expression
     */
    public static Expr nonDelete(Expr predicate) {
        return or(strictEq(context("operation"), "DELETE"), Objects.requireNonNull(predicate, "predicate"));
    }

    /**
     * Enforces {@code predicate} only when {@code context.operation === operation}.
     *
     * @param operation operation key under {@code context.operation}
     * @param predicate rule invariant for the matching operation
     * @return operation-scoped implication expression
     */
    public static Expr whenOperation(String operation, Expr predicate) {
        if (operation == null || operation.isBlank()) {
            throw new IllegalArgumentException("operation must not be blank");
        }
        return when(strictEq(context("operation"), operation), predicate);
    }

    /**
     * Validates an inclusive effective-date interval {@code start <= end}, allowing open bounds when
     * either endpoint is {@code null}. Compatible with both {@code ISO_8601} and {@code EPOCH_DAY}.
     *
     * @param startPath variable path for start date
     * @param endPath variable path for end date
     * @return open-bound date-order expression
     */
    public static Expr validDateRange(String startPath, String endPath) {
        return validDateRange(var(startPath), var(endPath));
    }

    /**
     * Validates an inclusive effective-date interval {@code start <= end}, allowing open bounds when
     * either endpoint is {@code null}.
     *
     * @param start start date expression
     * @param end end date expression
     * @return open-bound date-order expression
     */
    public static Expr validDateRange(Expr start, Expr end) {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        return or(isNull(start), isNull(end), lte(start, end));
    }

    /**
     * Asserts that a 1-hop domain concept node was resolved and marked {@code present === true}.
     *
     * @param root domain concept root name
     * @return {@code {"===": [{"var": "<root>.present"}, true]}}
     */
    public static Expr conceptPresent(String root) {
        return strictEq(concept(root, "present"), true);
    }

    /**
     * Asserts that a 1-hop domain concept node is present and its boolean active flag is {@code true}.
     *
     * @param root domain concept root name
     * @param activeField boolean field under {@code <root>} indicating active status
     * @return conjunction requiring {@code <root>.present === true && <root>.<activeField> === true}
     */
    public static Expr conceptActive(String root, String activeField) {
        return and(conceptPresent(root), strictEq(concept(root, activeField), true));
    }

    /**
     * Asserts that an integer collision counter under {@code neighborhood} equals {@code 0}.
     *
     * @param neighborhoodField field under {@code neighborhood}
     * @return {@code {"===": [{"var": "neighborhood.<field>"}, 0]}}
     */
    public static Expr zeroConflict(String neighborhoodField) {
        return strictEq(neighborhood(neighborhoodField), 0);
    }

    /**
     * Validates a nullable percentage field in {@code [0, 100]}.
     *
     * @param path variable path
     * @return percentage-range expression
     */
    public static Expr percentRange(String path) {
        return percentRange(var(path));
    }

    /**
     * Validates a nullable percentage expression in {@code [0, 100]}.
     *
     * @param target percentage expression
     * @return percentage-range expression
     */
    public static Expr percentRange(Expr target) {
        Objects.requireNonNull(target, "target");
        return or(isNull(target), and(gte(target, 0), lte(target, 100)));
    }

    /**
     * Builds a universal quantifier {@code {"all": [collection, predicate]}}.
     *
     * @param collection collection expression
     * @param predicate element predicate (use {@link #localVar(String)} inside)
     * @return {@code all} expression
     */
    public static Expr all(Expr collection, Expr predicate) {
        return binary("all", Objects.requireNonNull(collection, "collection"), Objects.requireNonNull(predicate, "predicate"));
    }

    /**
     * Builds a universal quantifier {@code {"all": [{"var": collectionPath}, predicate]}}.
     *
     * @param collectionPath variable path to collection
     * @param predicate element predicate (use {@link #localVar(String)} inside)
     * @return {@code all} expression
     */
    public static Expr all(String collectionPath, Expr predicate) {
        return all(var(collectionPath), predicate);
    }

    /**
     * Builds an existential quantifier {@code {"some": [collection, predicate]}}.
     *
     * @param collection collection expression
     * @param predicate element predicate (use {@link #localVar(String)} inside)
     * @return {@code some} expression
     */
    public static Expr some(Expr collection, Expr predicate) {
        return binary("some", Objects.requireNonNull(collection, "collection"), Objects.requireNonNull(predicate, "predicate"));
    }

    /**
     * Builds an existential quantifier {@code {"some": [{"var": collectionPath}, predicate]}}.
     *
     * @param collectionPath variable path to collection
     * @param predicate element predicate (use {@link #localVar(String)} inside)
     * @return {@code some} expression
     */
    public static Expr some(String collectionPath, Expr predicate) {
        return some(var(collectionPath), predicate);
    }

    /**
     * Builds a negative quantifier {@code {"none": [collection, predicate]}}.
     *
     * @param collection collection expression
     * @param predicate element predicate (use {@link #localVar(String)} inside)
     * @return {@code none} expression
     */
    public static Expr none(Expr collection, Expr predicate) {
        return binary("none", Objects.requireNonNull(collection, "collection"), Objects.requireNonNull(predicate, "predicate"));
    }

    /**
     * Builds a negative quantifier {@code {"none": [{"var": collectionPath}, predicate]}}.
     *
     * @param collectionPath variable path to collection
     * @param predicate element predicate (use {@link #localVar(String)} inside)
     * @return {@code none} expression
     */
    public static Expr none(String collectionPath, Expr predicate) {
        return none(var(collectionPath), predicate);
    }

    /**
     * Builds a collection filter {@code {"filter": [collection, predicate]}}.
     *
     * @param collection collection expression
     * @param predicate element predicate (use {@link #localVar(String)} inside)
     * @return {@code filter} expression
     */
    public static Expr filter(Expr collection, Expr predicate) {
        return binary("filter", Objects.requireNonNull(collection, "collection"), Objects.requireNonNull(predicate, "predicate"));
    }

    /**
     * Builds a collection filter {@code {"filter": [{"var": collectionPath}, predicate]}}.
     *
     * @param collectionPath variable path to collection
     * @param predicate element predicate (use {@link #localVar(String)} inside)
     * @return {@code filter} expression
     */
    public static Expr filter(String collectionPath, Expr predicate) {
        return filter(var(collectionPath), predicate);
    }

    /**
     * Builds a collection projection {@code {"map": [collection, transform]}}.
     *
     * @param collection collection expression
     * @param transform element transform (use {@link #localVar(String)} inside)
     * @return {@code map} expression
     */
    public static Expr map(Expr collection, Expr transform) {
        return binary("map", Objects.requireNonNull(collection, "collection"), Objects.requireNonNull(transform, "transform"));
    }

    /**
     * Builds a collection projection {@code {"map": [{"var": collectionPath}, transform]}}.
     *
     * @param collectionPath variable path to collection
     * @param transform element transform (use {@link #localVar(String)} inside)
     * @return {@code map} expression
     */
    public static Expr map(String collectionPath, Expr transform) {
        return map(var(collectionPath), transform);
    }

    /**
     * Builds a collection reduction {@code {"reduce": [collection, reducer, initial]}}.
     *
     * @param collection collection expression
     * @param reducer reduction step referencing {@code current} and {@code accumulator}
     * @param initial initial accumulator value or expression
     * @return {@code reduce} expression
     */
    public static Expr reduce(Expr collection, Expr reducer, Object initial) {
        return op("reduce", Objects.requireNonNull(collection, "collection"), Objects.requireNonNull(reducer, "reducer"), initial);
    }

    /**
     * Builds a collection reduction {@code {"reduce": [{"var": collectionPath}, reducer, initial]}}.
     *
     * @param collectionPath variable path to collection
     * @param reducer reduction step referencing {@code current} and {@code accumulator}
     * @param initial initial accumulator value or expression
     * @return {@code reduce} expression
     */
    public static Expr reduce(String collectionPath, Expr reducer, Object initial) {
        return reduce(var(collectionPath), reducer, initial);
    }

    /**
     * Builds a conditional branch {@code {"if": [condition, whenTrue, whenFalse]}}.
     *
     * @param condition boolean guard
     * @param whenTrue branch value or expression when truthy
     * @param whenFalse branch value or expression when falsy
     * @return {@code if} expression
     */
    public static Expr ifElse(Expr condition, Object whenTrue, Object whenFalse) {
        return op("if", Objects.requireNonNull(condition, "condition"), whenTrue, whenFalse);
    }

    /**
     * Builds an arithmetic addition {@code {"+": [...]}}.
     *
     * @param terms one or more numeric operands or expressions
     * @return addition expression
     */
    public static Expr add(Object... terms) {
        return variadicAny("+", terms);
    }

    /**
     * Builds an arithmetic subtraction {@code {"-": [left, right]}}.
     *
     * @param left minuend
     * @param right subtrahend
     * @return subtraction expression
     */
    public static Expr sub(Object left, Object right) {
        return op("-", left, right);
    }

    /**
     * Builds an arithmetic multiplication {@code {"*": [...]}}.
     *
     * @param factors one or more numeric operands or expressions
     * @return multiplication expression
     */
    public static Expr mul(Object... factors) {
        return variadicAny("*", factors);
    }

    /**
     * Builds an arithmetic division {@code {"/": [numerator, denominator]}}.
     *
     * @param numerator dividend
     * @param denominator divisor
     * @return division expression
     */
    public static Expr div(Object numerator, Object denominator) {
        return op("/", numerator, denominator);
    }

    /**
     * Builds any registered JSON Logic operator node safely via Jackson {@link ObjectNode}.
     *
     * @param operator non-blank operator name
     * @param args operator arguments (expressions, scalars, collections, {@code null})
     * @return single-operator {@link Expr}
     */
    public static Expr op(String operator, Object... args) {
        if (operator == null || operator.isBlank()) {
            throw new IllegalArgumentException("operator must not be blank");
        }
        Objects.requireNonNull(args, "args");
        ArrayNode array = NODES.arrayNode();
        for (Object arg : args) {
            array.add(toOperand(arg));
        }
        ObjectNode node = NODES.objectNode();
        node.set(operator.trim(), array);
        return new Expr(node);
    }

    private static Expr binary(String operator, Object left, Object right) {
        ArrayNode args = NODES.arrayNode();
        args.add(toOperand(left));
        args.add(toOperand(right));
        ObjectNode node = NODES.objectNode();
        node.set(operator, args);
        return new Expr(node);
    }

    private static Expr variadicExpr(String operator, Collection<Expr> expressions) {
        Objects.requireNonNull(expressions, "expressions");
        if (expressions.isEmpty()) {
            throw new IllegalArgumentException("Operator '" + operator + "' requires at least one expression");
        }
        ArrayNode args = NODES.arrayNode();
        for (Expr expr : expressions) {
            args.add(Objects.requireNonNull(expr, "expression").toJsonNode());
        }
        ObjectNode node = NODES.objectNode();
        node.set(operator, args);
        return new Expr(node);
    }

    private static Expr variadicAny(String operator, Object... args) {
        Objects.requireNonNull(args, "args");
        if (args.length == 0) {
            throw new IllegalArgumentException("Operator '" + operator + "' requires at least one argument");
        }
        return op(operator, args);
    }

    private static JsonNode toOperand(Object value) {
        if (value == null) {
            return NODES.nullNode();
        }
        if (value instanceof Expr expr) {
            return expr.toJsonNode();
        }
        if (value instanceof JsonNode json) {
            if (json.isMissingNode() || json.isPojo() || json.isBinary()) {
                throw new IllegalArgumentException("Unsupported JSON Logic operand node: " + json.getNodeType());
            }
            return json.deepCopy();
        }
        if (value instanceof CharSequence || value instanceof Character) {
            return NODES.textNode(value.toString());
        }
        if (value instanceof Boolean bool) {
            return NODES.booleanNode(bool);
        }
        if (value instanceof Integer integer) {
            return NODES.numberNode(integer);
        }
        if (value instanceof Long longVal) {
            return NODES.numberNode(longVal);
        }
        if (value instanceof BigInteger bigInt) {
            return NODES.numberNode(bigInt);
        }
        if (value instanceof BigDecimal decimal) {
            return NODES.numberNode(decimal.stripTrailingZeros());
        }
        if (value instanceof Double d) {
            if (!Double.isFinite(d)) {
                throw new IllegalArgumentException("JSON Logic numbers must be finite");
            }
            return NODES.numberNode(BigDecimal.valueOf(d).stripTrailingZeros());
        }
        if (value instanceof Float f) {
            if (!Float.isFinite(f)) {
                throw new IllegalArgumentException("JSON Logic numbers must be finite");
            }
            return NODES.numberNode(BigDecimal.valueOf(f).stripTrailingZeros());
        }
        if (value instanceof Number num) {
            return NODES.numberNode(new BigDecimal(num.toString()).stripTrailingZeros());
        }
        if (value instanceof Enum<?> enumeration) {
            return NODES.textNode(enumeration.name());
        }
        if (value instanceof LocalDate date) {
            return NODES.textNode(date.format(DateTimeFormatter.ISO_LOCAL_DATE));
        }
        if (value instanceof LocalDateTime dateTime) {
            return NODES.textNode(dateTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        }
        if (value instanceof OffsetDateTime dateTime) {
            return NODES.textNode(dateTime.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        }
        if (value instanceof ZonedDateTime dateTime) {
            return NODES.textNode(dateTime.format(DateTimeFormatter.ISO_ZONED_DATE_TIME));
        }
        if (value instanceof Iterable<?> iterable) {
            ArrayNode array = NODES.arrayNode();
            for (Object item : iterable) {
                array.add(toOperand(item));
            }
            return array;
        }
        if (value.getClass().isArray()) {
            ArrayNode array = NODES.arrayNode();
            int len = java.lang.reflect.Array.getLength(value);
            for (int i = 0; i < len; i++) {
                array.add(toOperand(java.lang.reflect.Array.get(value, i)));
            }
            return array;
        }
        throw new IllegalArgumentException("Unsupported JSON Logic operand type: " + value.getClass().getName());
    }

    private static String requireFactPath(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Variable path must not be blank");
        }
        List<String> segments;
        try {
            segments = PraxisPath.parse(path);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Invalid Praxis JSON Logic path: " + path, invalid);
        }
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("Variable path must not be empty; use localVar(\"\") inside iterators");
        }
        if ("facts".equals(segments.getFirst())) {
            throw new IllegalArgumentException("Flattened scalar root 'facts' is forbidden in the Praxis ontology");
        }
        return path;
    }

    private static String requireSubpath(String subpath, String paramName) {
        if (subpath == null || subpath.isBlank()) {
            throw new IllegalArgumentException(paramName + " must not be blank");
        }
        if (subpath.startsWith(".") || subpath.startsWith("$")) {
            throw new IllegalArgumentException(paramName + " must be relative to its ontological root: " + subpath);
        }
        return subpath;
    }

    private static String requireConceptRoot(String root) {
        if (root == null || root.isBlank()) {
            throw new IllegalArgumentException("Concept root must not be blank");
        }
        String trimmed = root.trim();
        if ("facts".equals(trimmed)) {
            throw new IllegalArgumentException("Flattened scalar root 'facts' is forbidden in the Praxis ontology");
        }
        List<String> segments;
        try {
            segments = PraxisPath.parse(trimmed);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Invalid concept root: " + root, invalid);
        }
        if (segments.size() != 1) {
            throw new IllegalArgumentException("Concept root must be a single top-level identifier: " + root);
        }
        return trimmed;
    }
}
