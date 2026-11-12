package org.praxisplatform.rules.facts;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Immutable parsed representation of a governed {@code /rules/facts/<family>.json} catalog
 * document (`praxis.domain-rule-fact-catalog.v2`).
 *
 * <p>Eliminates per-family JSON parsing boilerplate in host {@code <Family>Facts} classes while
 * preserving the full set of curated {@link RuleFactDescriptor} entries for Rules Studio, LLM
 * explainability, and governance auditing.
 *
 * @param schemaVersion catalog contract version (e.g. {@link #SCHEMA_VERSION_V2})
 * @param operations ordered map of operation catalogs keyed by {@code operationKey}
 */
public record RuleFactCatalogDocument(
        String schemaVersion, Map<String, RuleFactOperationCatalog> operations) {

    /** Canonical schema version for G1/G3 ontological fact catalogs. */
    public static final String SCHEMA_VERSION_V2 = "praxis.domain-rule-fact-catalog.v2";

    public RuleFactCatalogDocument {
        if (schemaVersion == null || schemaVersion.isBlank()) {
            throw new IllegalArgumentException("schemaVersion is required");
        }
        schemaVersion = schemaVersion.trim();
        Objects.requireNonNull(operations, "operations");
        if (operations.isEmpty()) {
            throw new IllegalArgumentException("operations must not be empty");
        }
        operations = Map.copyOf(new LinkedHashMap<>(operations));
    }

    /**
     * Finds the operation catalog for {@code operationKey}.
     *
     * @param operationKey operation key
     * @return optional operation catalog
     */
    public Optional<RuleFactOperationCatalog> findOperation(String operationKey) {
        if (operationKey == null || operationKey.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(operations.get(operationKey.trim()));
    }

    /**
     * Returns the operation catalog for {@code operationKey} or throws {@link
     * IllegalArgumentException} if absent.
     *
     * @param operationKey operation key
     * @return operation catalog
     */
    public RuleFactOperationCatalog requireOperation(String operationKey) {
        return findOperation(operationKey)
                .orElseThrow(
                        () ->
                                new IllegalArgumentException(
                                        "Operation '"
                                                + operationKey
                                                + "' is not declared in the fact catalog"));
    }

    /**
     * Resolves a curated {@link RuleFactDescriptor} within a specific {@code operationKey}.
     *
     * @param operationKey operation key
     * @param pathOrPointer dotted fact path or RFC 6901 JSON Schema pointer
     * @return matching descriptor when present
     */
    public Optional<RuleFactDescriptor> findFact(String operationKey, String pathOrPointer) {
        return findOperation(operationKey).flatMap(op -> op.findFact(pathOrPointer));
    }

    /**
     * Searches across all operations in declaration order for a curated {@link RuleFactDescriptor}
     * matching {@code pathOrPointer}.
     *
     * @param pathOrPointer dotted fact path or RFC 6901 JSON Schema pointer
     * @return first matching descriptor when present
     */
    public Optional<RuleFactDescriptor> findFactAcrossOperations(String pathOrPointer) {
        for (RuleFactOperationCatalog op : operations.values()) {
            Optional<RuleFactDescriptor> match = op.findFact(pathOrPointer);
            if (match.isPresent()) {
                return match;
            }
        }
        return Optional.empty();
    }

    /**
     * Builds an immutable {@link EnumMap} of {@link RuleFactContract}s for all constants of
     * {@code operationEnum} without Java executor input contracts.
     *
     * @param operationEnum operation enum class
     * @param operationKeyExtractor function extracting the string key for each enum constant
     * @param hostContractVersion installed host contract version coordinate
     * @param providerRef canonical fact provider coordinate
     * @param <O> operation enum type
     * @return immutable map of {@link RuleFactContract}s keyed by {@code O}
     */
    public <O extends Enum<O>> Map<O, RuleFactContract> buildContracts(
            Class<O> operationEnum,
            Function<O, String> operationKeyExtractor,
            String hostContractVersion,
            String providerRef) {
        return buildContracts(
                operationEnum,
                operationKeyExtractor,
                hostContractVersion,
                providerRef,
                ignored -> List.of());
    }

    /**
     * Builds an immutable {@link EnumMap} of {@link RuleFactContract}s for all constants of
     * {@code operationEnum} with operation-specific Java executor input contracts.
     *
     * @param operationEnum operation enum class
     * @param operationKeyExtractor function extracting the string key for each enum constant
     * @param hostContractVersion installed host contract version coordinate
     * @param providerRef canonical fact provider coordinate
     * @param executorsByOperation supplier of Java executor input contracts per operation
     * @param <O> operation enum type
     * @return immutable map of {@link RuleFactContract}s keyed by {@code O}
     */
    public <O extends Enum<O>> Map<O, RuleFactContract> buildContracts(
            Class<O> operationEnum,
            Function<O, String> operationKeyExtractor,
            String hostContractVersion,
            String providerRef,
            Function<O, List<RuleFactContract.JavaInputs>> executorsByOperation) {
        Objects.requireNonNull(operationEnum, "operationEnum");
        Objects.requireNonNull(operationKeyExtractor, "operationKeyExtractor");
        Objects.requireNonNull(executorsByOperation, "executorsByOperation");
        EnumMap<O, RuleFactContract> contracts = new EnumMap<>(operationEnum);
        for (O op : operationEnum.getEnumConstants()) {
            String opKey = operationKeyExtractor.apply(op);
            RuleFactOperationCatalog opCatalog = requireOperation(opKey);
            contracts.put(
                    op,
                    opCatalog.toContract(
                            hostContractVersion,
                            providerRef,
                            executorsByOperation.apply(op)));
        }
        return Map.copyOf(contracts);
    }
}
