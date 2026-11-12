package org.praxisplatform.rules.facts;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Operation-scoped fact catalog combining the compiled {@link RuleFactSchema} and the curated
 * {@link RuleFactDescriptor} entries declared for a single {@code operationKey}.
 *
 * @param operationKey canonical operation identifier (e.g. {@code "CREATE"}, {@code "UPDATE"})
 * @param schema compiled structural fact schema for this operation
 * @param facts immutable list of curated semantic descriptors in declaration order
 * @param factsByPointer lookup index keyed by RFC 6901 {@code schemaPointer}
 * @param factsByPath lookup index keyed by canonical dotted {@code factPath}
 */
public record RuleFactOperationCatalog(
        String operationKey,
        RuleFactSchema schema,
        List<RuleFactDescriptor> facts,
        Map<String, RuleFactDescriptor> factsByPointer,
        Map<String, RuleFactDescriptor> factsByPath) {

    public RuleFactOperationCatalog(
            String operationKey, RuleFactSchema schema, List<RuleFactDescriptor> facts) {
        this(
                operationKey,
                schema,
                facts,
                indexByPointer(facts),
                indexByPath(facts));
    }

    public RuleFactOperationCatalog {
        if (operationKey == null || operationKey.isBlank()) {
            throw new IllegalArgumentException("operationKey is required");
        }
        operationKey = operationKey.trim();
        schema = Objects.requireNonNull(schema, "schema");
        facts = List.copyOf(Objects.requireNonNull(facts, "facts"));
        factsByPointer =
                factsByPointer == null ? indexByPointer(facts) : Map.copyOf(factsByPointer);
        factsByPath = factsByPath == null ? indexByPath(facts) : Map.copyOf(factsByPath);
    }

    /**
     * Builds an executable {@link RuleFactContract} for this operation with no Java executor input
     * contracts.
     *
     * @param hostContractVersion installed host contract version coordinate
     * @param providerRef canonical fact provider coordinate
     * @return executable {@link RuleFactContract}
     */
    public RuleFactContract toContract(String hostContractVersion, String providerRef) {
        return toContract(hostContractVersion, providerRef, List.of());
    }

    /**
     * Builds an executable {@link RuleFactContract} for this operation with explicit Java executor
     * input contracts.
     *
     * @param hostContractVersion installed host contract version coordinate
     * @param providerRef canonical fact provider coordinate
     * @param javaExecutors declared Java executor input contracts
     * @return executable {@link RuleFactContract}
     */
    public RuleFactContract toContract(
            String hostContractVersion,
            String providerRef,
            List<RuleFactContract.JavaInputs> javaExecutors) {
        return new RuleFactContract(
                hostContractVersion,
                operationKey,
                providerRef,
                schema,
                javaExecutors == null ? List.of() : javaExecutors);
    }

    /**
     * Resolves a curated {@link RuleFactDescriptor} by either RFC 6901 {@code schemaPointer}
     * (starting with {@code #/}) or canonical dotted {@code factPath} (such as {@code draft.dtini}
     * or {@code neighborhood.periods.days}).
     *
     * @param pathOrPointer dotted fact path or JSON Schema pointer
     * @return matching descriptor when present
     */
    public Optional<RuleFactDescriptor> findFact(String pathOrPointer) {
        if (pathOrPointer == null || pathOrPointer.isBlank()) {
            return Optional.empty();
        }
        String trimmed = pathOrPointer.trim();
        RuleFactDescriptor byPointer = factsByPointer.get(trimmed);
        if (byPointer != null) {
            return Optional.of(byPointer);
        }
        RuleFactDescriptor byPath = factsByPath.get(trimmed);
        if (byPath != null) {
            return Optional.of(byPath);
        }
        String normalized = trimmed.replace(":items", "").replace("[]", "");
        byPath = factsByPath.get(normalized);
        if (byPath != null) {
            return Optional.of(byPath);
        }
        String derivedPointer =
                RuleFactDescriptor.factPathToSchemaPointer(trimmed, schema.schema());
        return Optional.ofNullable(factsByPointer.get(derivedPointer));
    }

    /**
     * Infers the canonical Studio {@code valueType} for {@code factPath} from the compiled
     * {@code snapshotSchema} of this operation, even when {@code factPath} is not listed in
     * {@code facts[]}.
     *
     * @param factPath dotted fact path
     * @return canonical Studio value type
     */
    public String inferValueType(String factPath) {
        Optional<RuleFactDescriptor> curated = findFact(factPath);
        if (curated.isPresent()) {
            return curated.get().valueType();
        }
        if (factPath == null || factPath.isBlank()) {
            return "string";
        }
        String ptr = RuleFactDescriptor.factPathToSchemaPointer(factPath, schema.schema());
        JsonNode target = schema.schema().at(ptr.substring(1));
        return RuleFactDescriptor.inferSchemaValueType(target);
    }

    /**
     * Infers whether {@code null} is permitted for {@code factPath} in this operation's
     * {@code snapshotSchema}.
     *
     * @param factPath dotted fact path
     * @return {@code true} if nullable
     */
    public boolean isNullable(String factPath) {
        Optional<RuleFactDescriptor> curated = findFact(factPath);
        if (curated.isPresent()) {
            return curated.get().nullable();
        }
        if (factPath == null || factPath.isBlank()) {
            return true;
        }
        String ptr = RuleFactDescriptor.factPathToSchemaPointer(factPath, schema.schema());
        return RuleFactDescriptor.inferSchemaNullable(schema.schema(), ptr);
    }

    private static Map<String, RuleFactDescriptor> indexByPointer(List<RuleFactDescriptor> facts) {
        Map<String, RuleFactDescriptor> map = new LinkedHashMap<>();
        for (RuleFactDescriptor descriptor : Objects.requireNonNull(facts, "facts")) {
            if (map.put(descriptor.schemaPointer(), descriptor) != null) {
                throw new IllegalArgumentException(
                        "Duplicate RuleFactDescriptor for schemaPointer: "
                                + descriptor.schemaPointer());
            }
        }
        return Map.copyOf(map);
    }

    private static Map<String, RuleFactDescriptor> indexByPath(List<RuleFactDescriptor> facts) {
        Map<String, RuleFactDescriptor> map = new LinkedHashMap<>();
        for (RuleFactDescriptor descriptor : Objects.requireNonNull(facts, "facts")) {
            map.putIfAbsent(descriptor.factPath(), descriptor);
            map.putIfAbsent(RuleFactDescriptor.toStableFactPath(descriptor.factPath()), descriptor);
            map.putIfAbsent(descriptor.factPath().replace("[]", ""), descriptor);
        }
        return Map.copyOf(map);
    }
}
