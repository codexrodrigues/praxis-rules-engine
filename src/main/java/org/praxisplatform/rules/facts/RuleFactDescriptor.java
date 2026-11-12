package org.praxisplatform.rules.facts;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Curated semantic, structural and governance descriptor for a single node in a
 * {@link RuleFactSchema}.
 *
 * <p>Bridges the structural JSON Schema pointer (such as
 * {@code #/properties/draft/properties/dtini} or
 * {@code #/properties/neighborhood/properties/periods/items/properties/days}) with its canonical
 * dotted fact path ({@code draft.dtini} or {@code neighborhood.periods.days}), canonical Studio
 * {@code valueType} and {@code nullable} contract, localized human labels and descriptions,
 * provenance ({@code providerRef}, {@code evidenceRefs}), and LGPD/policy classification
 * ({@code sensitivity}, {@code redaction}).
 *
 * @param schemaPointer RFC 6901 JSON Pointer inside the operation {@code snapshotSchema}
 * @param factPath canonical dotted fact path used by JSON Logic / Java executors
 * @param valueType canonical Studio value type ({@code boolean}, {@code string}, {@code number},
 *     {@code date}, {@code string-array}, {@code date-array}, {@code number-array})
 * @param nullable whether {@code null} is permitted by the operation {@code snapshotSchema}
 * @param labels localized short UI labels keyed by BCP-47 locale tag (e.g. {@code pt-BR})
 * @param descriptions localized domain and legal descriptions keyed by BCP-47 locale tag
 * @param providerRef canonical fact provider identifier and version
 * @param evidenceRefs non-empty list of legacy/legal/technical lineage references
 * @param sensitivity canonical data sensitivity ({@code NON_SENSITIVE}, {@code PERSONAL},
 *     {@code SENSITIVE}, {@code SECRET}; extended aliases are accepted and normalized via
 *     {@link #canonicalSensitivity()})
 * @param redaction redaction policy for logs, exports and AI prompts ({@code NONE}, {@code MASK},
 *     {@code HASH}, {@code OMIT})
 */
public record RuleFactDescriptor(
        String schemaPointer,
        String factPath,
        String valueType,
        boolean nullable,
        Map<String, String> labels,
        Map<String, String> descriptions,
        String providerRef,
        List<String> evidenceRefs,
        String sensitivity,
        String redaction) {

    /**
     * Canonical sensitivity tokens enforced by {@code praxis-config-starter}
     * ({@code DomainRuleService.FACT_SENSITIVITIES}) and {@code praxis.domain-rule-fact-catalog.v2}.
     */
    public static final Set<String> CANONICAL_SENSITIVITIES =
            Set.of("NON_SENSITIVE", "PERSONAL", "SENSITIVE", "SECRET");

    /**
     * Accepted sensitivity classifications (canonical tokens plus granular DTO aliases that normalize
     * deterministically via {@link #normalizeSensitivity(String)}).
     */
    public static final Set<String> ALLOWED_SENSITIVITIES =
            Set.of(
                    "NON_SENSITIVE",
                    "PERSONAL",
                    "SENSITIVE",
                    "SECRET",
                    "SENSITIVE_PERSONAL",
                    "FINANCIAL",
                    "CREDENTIAL");

    /** Allowed redaction policies in v1/v2 fact catalogs. */
    public static final Set<String> ALLOWED_REDACTIONS =
            Set.of("NONE", "MASK", "HASH", "OMIT");

    /** Canonical Studio value types accepted by {@code DomainRuleService.FACT_VALUE_TYPES}. */
    public static final Set<String> ALLOWED_VALUE_TYPES =
            Set.of(
                    "boolean",
                    "string",
                    "number",
                    "date",
                    "string-array",
                    "date-array",
                    "number-array",
                    "array<number>");

    /** Pattern enforced by {@code DomainRuleService} for Studio {@code factCatalog.facts[].path}. */
    public static final Pattern STABLE_FACT_PATH_PATTERN =
            Pattern.compile("^[A-Za-z][A-Za-z0-9_.:-]{0,254}$");

    /** Convenience constructor defaulting {@code valueType = "string"} and {@code nullable = true}. */
    public RuleFactDescriptor(
            String schemaPointer,
            String factPath,
            Map<String, String> labels,
            Map<String, String> descriptions,
            String providerRef,
            List<String> evidenceRefs,
            String sensitivity,
            String redaction) {
        this(
                schemaPointer,
                factPath,
                "string",
                true,
                labels,
                descriptions,
                providerRef,
                evidenceRefs,
                sensitivity,
                redaction);
    }

    public RuleFactDescriptor {
        schemaPointer = requireNonBlank(schemaPointer, "schemaPointer");
        if (!schemaPointer.startsWith("#/")) {
            throw new IllegalArgumentException(
                    "schemaPointer must start with '#/': " + schemaPointer);
        }
        factPath =
                (factPath == null || factPath.isBlank())
                        ? schemaPointerToFactPath(schemaPointer)
                        : factPath.trim();
        valueType =
                (valueType == null || valueType.isBlank()) ? "string" : valueType.trim();
        if (!ALLOWED_VALUE_TYPES.contains(valueType)) {
            throw new IllegalArgumentException(
                    "Unsupported valueType '" + valueType + "' for " + schemaPointer);
        }
        labels = copyStringMap(labels, "labels");
        descriptions = copyStringMap(descriptions, "descriptions");
        providerRef = requireNonBlank(providerRef, "providerRef");
        Objects.requireNonNull(evidenceRefs, "evidenceRefs");
        if (evidenceRefs.isEmpty()) {
            throw new IllegalArgumentException(
                    "evidenceRefs must not be empty for " + schemaPointer);
        }
        List<String> copiedEvidence = new ArrayList<>(evidenceRefs.size());
        for (String ref : evidenceRefs) {
            copiedEvidence.add(requireNonBlank(ref, "evidenceRef"));
        }
        evidenceRefs = List.copyOf(copiedEvidence);
        sensitivity = requireNonBlank(sensitivity, "sensitivity").toUpperCase(Locale.ROOT);
        if (!ALLOWED_SENSITIVITIES.contains(sensitivity)) {
            throw new IllegalArgumentException(
                    "Unsupported sensitivity '" + sensitivity + "' for " + schemaPointer);
        }
        redaction = requireNonBlank(redaction, "redaction").toUpperCase(Locale.ROOT);
        if (!ALLOWED_REDACTIONS.contains(redaction)) {
            throw new IllegalArgumentException(
                    "Unsupported redaction '" + redaction + "' for " + schemaPointer);
        }
    }

    /**
     * Returns the sensitivity normalized to the 4-token canonical set
     * ({@code NON_SENSITIVE}, {@code PERSONAL}, {@code SENSITIVE}, {@code SECRET}) required by
     * {@code DomainRuleService}.
     *
     * @return canonical sensitivity token
     */
    public String canonicalSensitivity() {
        return normalizeSensitivity(sensitivity);
    }

    /**
     * Normalizes granular sensitivity aliases into the 4-token canonical platform taxonomy
     * ({@code NON_SENSITIVE}, {@code PERSONAL}, {@code SENSITIVE}, {@code SECRET}).
     *
     * @param raw sensitivity token
     * @return normalized canonical sensitivity token
     */
    public static String normalizeSensitivity(String raw) {
        if (raw == null || raw.isBlank()) {
            return "NON_SENSITIVE";
        }
        return switch (raw.trim().toUpperCase(Locale.ROOT)) {
            case "PERSONAL" -> "PERSONAL";
            case "SENSITIVE", "SENSITIVE_PERSONAL", "FINANCIAL" -> "SENSITIVE";
            case "SECRET", "CREDENTIAL" -> "SECRET";
            default -> "NON_SENSITIVE";
        };
    }

    /**
     * Returns a {@code DomainRuleService}-compliant stable fact path matching
     * {@link #STABLE_FACT_PATH_PATTERN}.
     *
     * @param rawPath dotted fact path
     * @return sanitized stable fact path
     */
    public static String toStableFactPath(String rawPath) {
        requireNonBlank(rawPath, "rawPath");
        String candidate =
                rawPath.trim()
                        .replace("[]", ":items")
                        .replace(".*", ":any")
                        .replace("*", "any");
        if (candidate.length() > 255) {
            candidate = candidate.substring(0, 255);
        }
        return candidate;
    }

    /**
     * Resolves the localized label for {@code locale}, falling back to {@code pt-BR}, {@code en-US},
     * or the first available entry.
     *
     * @param locale preferred locale tag (e.g. {@code "pt-BR"})
     * @return localized label or {@code factPath} when empty
     */
    public String label(String locale) {
        return resolveLocalized(labels, locale, factPath);
    }

    /**
     * Resolves the localized domain description for {@code locale}, falling back to {@code pt-BR},
     * {@code en-US}, or the first available entry.
     *
     * @param locale preferred locale tag (e.g. {@code "pt-BR"})
     * @return localized description or {@code factPath} when empty
     */
    public String description(String locale) {
        return resolveLocalized(descriptions, locale, factPath);
    }

    /**
     * Converts a JSON Schema pointer (such as {@code #/properties/draft/properties/dtini} or
     * {@code #/properties/neighborhood/properties/periods/items/properties/dtini}) into its
     * canonical dotted fact path ({@code draft.dtini} or {@code neighborhood.periods.dtini}).
     *
     * @param schemaPointer RFC 6901 JSON Schema pointer starting with {@code #/}
     * @return canonical dotted fact path
     */
    public static String schemaPointerToFactPath(String schemaPointer) {
        requireNonBlank(schemaPointer, "schemaPointer");
        if (!schemaPointer.startsWith("#/")) {
            throw new IllegalArgumentException(
                    "schemaPointer must start with '#/': " + schemaPointer);
        }
        String[] rawTokens = schemaPointer.substring(2).split("/");
        List<String> segments = new ArrayList<>();
        for (int i = 0; i < rawTokens.length; i++) {
            String token = unescapePointerToken(rawTokens[i]);
            if ("properties".equals(token) && i + 1 < rawTokens.length) {
                segments.add(unescapePointerToken(rawTokens[++i]));
            } else if ("items".equals(token)) {
                if (i == rawTokens.length - 1 && !segments.isEmpty()) {
                    int lastIdx = segments.size() - 1;
                    segments.set(lastIdx, segments.get(lastIdx) + "[]");
                }
            } else if ("additionalProperties".equals(token)) {
                segments.add("*");
            }
        }
        return String.join(".", segments);
    }

    /**
     * Resolves a canonical dotted fact path (such as {@code draft.dtini} or
     * {@code neighborhood.periods.dtini}) into its exact JSON Schema pointer against
     * {@code snapshotSchema}.
     *
     * @param factPath dotted fact path
     * @param snapshotSchema root JSON Schema of the operation
     * @return RFC 6901 JSON Schema pointer starting with {@code #/}
     */
    public static String factPathToSchemaPointer(String factPath, JsonNode snapshotSchema) {
        requireNonBlank(factPath, "factPath");
        Objects.requireNonNull(snapshotSchema, "snapshotSchema");
        String normalized = factPath.trim();
        boolean terminalItems =
                normalized.endsWith("[]") || normalized.endsWith(":items");
        normalized = normalized.replace(":items", "").replace("[]", "");
        String[] parts = normalized.split("\\.");
        StringBuilder pointer = new StringBuilder("#");
        JsonNode current = snapshotSchema;
        for (String part : parts) {
            if (current != null && isArraySchema(current)) {
                pointer.append("/items");
                current = current.path("items");
            }
            if ("*".equals(part) || "any".equals(part)) {
                pointer.append("/additionalProperties");
                current = current != null ? current.path("additionalProperties") : null;
            } else {
                pointer.append("/properties/").append(escapePointerToken(part));
                current = current != null ? current.path("properties").path(part) : null;
            }
        }
        if (terminalItems && current != null && isArraySchema(current)) {
            pointer.append("/items");
        }
        return pointer.toString();
    }

    /**
     * Infers the canonical Studio {@code valueType} for a JSON Schema node inside
     * {@code snapshotSchema}.
     *
     * @param targetSchema JSON Schema node at the target pointer
     * @return canonical Studio value type ({@code boolean}, {@code number}, {@code date},
     *     {@code string}, {@code string-array}, {@code number-array}, {@code date-array})
     */
    public static String inferSchemaValueType(JsonNode targetSchema) {
        if (targetSchema == null || targetSchema.isMissingNode() || targetSchema.isNull()) {
            return "string";
        }
        String primaryType = extractPrimarySchemaType(targetSchema);
        if (primaryType == null) {
            return "string";
        }
        return switch (primaryType) {
            case "boolean" -> "boolean";
            case "integer", "number" -> "number";
            case "string" -> {
                String fmt = targetSchema.path("format").asText("");
                yield ("date".equalsIgnoreCase(fmt) || "date-time".equalsIgnoreCase(fmt))
                        ? "date"
                        : "string";
            }
            case "array" -> {
                JsonNode items = targetSchema.path("items");
                String itemType = extractPrimarySchemaType(items);
                if ("integer".equals(itemType) || "number".equals(itemType)) {
                    yield "number-array";
                }
                if ("string".equals(itemType)) {
                    String itemFmt = items.path("format").asText("");
                    yield ("date".equalsIgnoreCase(itemFmt)
                                    || "date-time".equalsIgnoreCase(itemFmt))
                            ? "date-array"
                            : "string-array";
                }
                yield "string";
            }
            default -> "string";
        };
    }

    /**
     * Determines whether {@code null} is permitted for the JSON Schema node referenced by
     * {@code schemaPointer} inside {@code snapshotSchema}.
     *
     * @param snapshotSchema root JSON Schema of the operation
     * @param schemaPointer RFC 6901 JSON Schema pointer starting with {@code #/}
     * @return {@code true} if nullable or optional in parent object
     */
    public static boolean inferSchemaNullable(JsonNode snapshotSchema, String schemaPointer) {
        if (snapshotSchema == null
                || schemaPointer == null
                || !schemaPointer.startsWith("#/")) {
            return true;
        }
        JsonNode target = snapshotSchema.at(schemaPointer.substring(1));
        if (target == null || target.isMissingNode() || target.isNull()) {
            return true;
        }
        if (allowsNullType(target)) {
            return true;
        }
        if (target.path("nullable").asBoolean(false)) {
            return true;
        }
        int lastProps = schemaPointer.lastIndexOf("/properties/");
        if (lastProps >= 1) {
            String propToken = unescapePointerToken(schemaPointer.substring(lastProps + 12));
            if (!propToken.contains("/")) {
                String parentPtr = schemaPointer.substring(1, lastProps);
                JsonNode parent =
                        parentPtr.isEmpty() ? snapshotSchema : snapshotSchema.at(parentPtr);
                JsonNode req = parent != null ? parent.get("required") : null;
                if (req != null && req.isArray()) {
                    for (JsonNode r : req) {
                        if (propToken.equals(r.asText())) {
                            return false;
                        }
                    }
                    return true;
                }
            }
        }
        return false;
    }

    private static String extractPrimarySchemaType(JsonNode schemaNode) {
        if (schemaNode == null || schemaNode.isMissingNode() || schemaNode.isNull()) {
            return null;
        }
        JsonNode typeNode = schemaNode.get("type");
        if (typeNode == null) {
            return null;
        }
        if (typeNode.isTextual()) {
            String t = typeNode.asText();
            return "null".equals(t) ? null : t;
        }
        if (typeNode.isArray()) {
            for (JsonNode item : typeNode) {
                if (item.isTextual() && !"null".equals(item.asText())) {
                    return item.asText();
                }
            }
        }
        return null;
    }

    private static boolean allowsNullType(JsonNode schemaNode) {
        JsonNode typeNode = schemaNode.get("type");
        if (typeNode == null) {
            return false;
        }
        if (typeNode.isTextual()) {
            return "null".equals(typeNode.asText());
        }
        if (typeNode.isArray()) {
            for (JsonNode item : typeNode) {
                if (item.isTextual() && "null".equals(item.asText())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isArraySchema(JsonNode schemaNode) {
        return "array".equals(extractPrimarySchemaType(schemaNode));
    }

    private static String unescapePointerToken(String token) {
        return token.replace("~1", "/").replace("~0", "~");
    }

    private static String escapePointerToken(String token) {
        return token.replace("~", "~0").replace("/", "~1");
    }

    private static String resolveLocalized(
            Map<String, String> map, String locale, String fallback) {
        if (map == null || map.isEmpty()) {
            return fallback;
        }
        if (locale != null && map.containsKey(locale)) {
            return map.get(locale);
        }
        if (map.containsKey("pt-BR")) {
            return map.get("pt-BR");
        }
        if (map.containsKey("en-US")) {
            return map.get("en-US");
        }
        return map.values().iterator().next();
    }

    private static Map<String, String> copyStringMap(Map<String, String> source, String fieldName) {
        Objects.requireNonNull(source, fieldName);
        if (source.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be empty");
        }
        Map<String, String> copy = new LinkedHashMap<>();
        source.forEach(
                (k, v) ->
                        copy.put(
                                requireNonBlank(k, fieldName + ".key"),
                                requireNonBlank(v, fieldName + "[" + k + "]")));
        return Map.copyOf(copy);
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required and must not be blank");
        }
        return value.trim();
    }
}
