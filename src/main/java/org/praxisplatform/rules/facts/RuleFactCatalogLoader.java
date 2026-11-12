package org.praxisplatform.rules.facts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Canonical loader and parser for {@code /rules/facts/*.json} catalog files
 * ({@link RuleFactCatalogDocument}).
 *
 * <p>Parses both the structural {@code snapshotSchema} (compiling each operation via
 * {@link RuleFactSchema#compile(JsonNode)}) and the semantic {@code facts[]} array
 * ({@link RuleFactDescriptor}), extracting exact Studio {@code valueType} and {@code nullable}
 * semantics from {@code snapshotSchema} while preserving curated PT-BR/EN-US labels, legal
 * descriptions, evidence references, LGPD sensitivity and redaction metadata alongside the
 * executable {@link RuleFactContract}.
 */
public final class RuleFactCatalogLoader {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RuleFactCatalogLoader() {}

    /**
     * Loads and compiles a {@link RuleFactCatalogDocument} from a classpath resource relative to
     * {@code anchor}.
     *
     * @param anchor class whose resource stream / classloader is used
     * @param resourcePath classpath resource path (e.g. {@code "/rules/facts/cargos.json"})
     * @return compiled {@link RuleFactCatalogDocument}
     */
    public static RuleFactCatalogDocument loadFromClasspath(Class<?> anchor, String resourcePath) {
        Objects.requireNonNull(anchor, "anchor");
        requireNonBlank(resourcePath, "resourcePath");
        try (InputStream in = anchor.getResourceAsStream(resourcePath)) {
            if (in == null) {
                String normalized =
                        resourcePath.startsWith("/") ? resourcePath.substring(1) : resourcePath;
                ClassLoader cl = anchor.getClassLoader();
                if (cl != null) {
                    try (InputStream clStream = cl.getResourceAsStream(normalized)) {
                        if (clStream != null) {
                            return parse(clStream, resourcePath);
                        }
                    }
                }
                throw new IllegalStateException(
                        "Classpath fact catalog resource not found: " + resourcePath);
            }
            return parse(in, resourcePath);
        } catch (IOException ex) {
            throw new IllegalStateException(
                    "Failed to read classpath fact catalog: " + resourcePath, ex);
        }
    }

    /**
     * Parses and compiles a {@link RuleFactCatalogDocument} from an {@link InputStream}.
     *
     * @param in input stream containing UTF-8 JSON
     * @param sourceDiagnostic diagnostic name for error messages
     * @return compiled {@link RuleFactCatalogDocument}
     */
    public static RuleFactCatalogDocument parse(InputStream in, String sourceDiagnostic) {
        Objects.requireNonNull(in, "in");
        try {
            JsonNode root = MAPPER.readTree(in);
            return parse(root);
        } catch (IOException ex) {
            throw new IllegalStateException(
                    "Invalid JSON in fact catalog: " + sourceDiagnostic, ex);
        }
    }

    /**
     * Parses and compiles a {@link RuleFactCatalogDocument} from a parsed {@link JsonNode}.
     *
     * @param root root JSON object of the fact catalog
     * @return compiled {@link RuleFactCatalogDocument}
     */
    public static RuleFactCatalogDocument parse(JsonNode root) {
        Objects.requireNonNull(root, "root");
        if (!root.isObject()) {
            throw new IllegalArgumentException("Fact catalog root must be a JSON object");
        }
        String schemaVersion =
                requireNonBlank(root.path("schemaVersion").asText(null), "schemaVersion");
        JsonNode operationsNode = root.get("operations");
        if (operationsNode == null || (!operationsNode.isArray() && !operationsNode.isObject())
                || operationsNode.isEmpty()) {
            throw new IllegalArgumentException(
                    "Fact catalog must declare a non-empty 'operations' array or map");
        }
        Map<String, RuleFactOperationCatalog> operations = new LinkedHashMap<>();
        if (operationsNode.isArray()) {
            for (JsonNode opNode : operationsNode) {
                String opKey =
                        requireNonBlank(opNode.path("operationKey").asText(null), "operationKey");
                parseAndRegisterOperation(operations, opKey, opNode);
            }
        } else {
            operationsNode
                    .properties()
                    .forEach(
                            entry ->
                                    parseAndRegisterOperation(
                                            operations, entry.getKey(), entry.getValue()));
        }
        return new RuleFactCatalogDocument(schemaVersion, operations);
    }

    private static void parseAndRegisterOperation(
            Map<String, RuleFactOperationCatalog> operations, String opKey, JsonNode opNode) {
        JsonNode snapshotSchemaNode = opNode.get("snapshotSchema");
        if (snapshotSchemaNode == null) {
            snapshotSchemaNode = opNode.get("schema");
        }
        if (snapshotSchemaNode == null || !snapshotSchemaNode.isObject()) {
            throw new IllegalArgumentException(
                    "Operation '" + opKey + "' must declare an object 'snapshotSchema'");
        }
        RuleFactSchema compiledSchema = RuleFactSchema.compile(snapshotSchemaNode);
        List<RuleFactDescriptor> descriptors =
                parseDescriptors(opKey, snapshotSchemaNode, opNode.get("facts"));
        RuleFactOperationCatalog opCatalog =
                new RuleFactOperationCatalog(opKey, compiledSchema, descriptors);
        if (operations.put(opKey, opCatalog) != null) {
            throw new IllegalArgumentException("Duplicate operationKey in fact catalog: " + opKey);
        }
    }

    private static List<RuleFactDescriptor> parseDescriptors(
            String opKey, JsonNode snapshotSchemaNode, JsonNode factsNode) {
        if (factsNode == null
                || factsNode.isNull()
                || !factsNode.isArray()
                || factsNode.isEmpty()) {
            return List.of();
        }
        List<RuleFactDescriptor> list = new ArrayList<>(factsNode.size());
        for (JsonNode factNode : factsNode) {
            String schemaPointer =
                    requireNonBlank(
                            factNode.path("schemaPointer").asText(null),
                            opKey + ".facts[].schemaPointer");
            JsonNode targetSchema = snapshotSchemaNode.at(schemaPointer.substring(1));
            String explicitValueType = factNode.path("valueType").asText(null);
            String valueType =
                    (explicitValueType != null && !explicitValueType.isBlank())
                            ? explicitValueType.trim()
                            : RuleFactDescriptor.inferSchemaValueType(targetSchema);
            boolean nullable =
                    factNode.has("nullable") && factNode.get("nullable").isBoolean()
                            ? factNode.get("nullable").asBoolean()
                            : RuleFactDescriptor.inferSchemaNullable(
                                    snapshotSchemaNode, schemaPointer);
            Map<String, String> labels =
                    readStringMap(factNode.get("labels"), opKey + ":" + schemaPointer + ".labels");
            Map<String, String> descriptions =
                    readStringMap(
                            factNode.get("descriptions"),
                            opKey + ":" + schemaPointer + ".descriptions");
            String providerRef =
                    requireNonBlank(
                            factNode.path("providerRef").asText(null),
                            opKey + ":" + schemaPointer + ".providerRef");
            List<String> evidenceRefs =
                    readStringList(
                            factNode.get("evidenceRefs"),
                            opKey + ":" + schemaPointer + ".evidenceRefs");
            String sensitivity =
                    requireNonBlank(
                            factNode.path("sensitivity").asText(null),
                            opKey + ":" + schemaPointer + ".sensitivity");
            String redaction =
                    requireNonBlank(
                            factNode.path("redaction").asText(null),
                            opKey + ":" + schemaPointer + ".redaction");
            list.add(
                    new RuleFactDescriptor(
                            schemaPointer,
                            RuleFactDescriptor.schemaPointerToFactPath(schemaPointer),
                            valueType,
                            nullable,
                            labels,
                            descriptions,
                            providerRef,
                            evidenceRefs,
                            sensitivity,
                            redaction));
        }
        return List.copyOf(list);
    }

    private static Map<String, String> readStringMap(JsonNode node, String diagnostic) {
        if (node == null || !node.isObject() || node.isEmpty()) {
            throw new IllegalArgumentException(diagnostic + " must be a non-empty JSON object");
        }
        Map<String, String> map = new LinkedHashMap<>();
        node.properties()
                .forEach(
                        entry ->
                                map.put(
                                        entry.getKey(),
                                        requireNonBlank(
                                                entry.getValue().asText(null),
                                                diagnostic + "." + entry.getKey())));
        return Map.copyOf(map);
    }

    private static List<String> readStringList(JsonNode node, String diagnostic) {
        if (node == null || !node.isArray() || node.isEmpty()) {
            throw new IllegalArgumentException(diagnostic + " must be a non-empty JSON array");
        }
        List<String> list = new ArrayList<>(node.size());
        for (JsonNode item : node) {
            list.add(requireNonBlank(item.asText(null), diagnostic + "[]"));
        }
        return List.copyOf(list);
    }

    private static String requireNonBlank(String value, String diagnostic) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(diagnostic + " must not be null or blank");
        }
        return value.trim();
    }
}
