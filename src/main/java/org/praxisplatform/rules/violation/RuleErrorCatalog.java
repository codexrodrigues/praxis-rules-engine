package org.praxisplatform.rules.violation;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Host-owned, public-safe error descriptions; absence never permits a rejected operation.
 */
@FunctionalInterface
public interface RuleErrorCatalog {
    /**
     * Resolves one public description without inferring a missing field or message.
     * @param code stable reason code
     * @return description, when declared
     */
    Optional<RuleErrorDescriptor> find(String code);

    /**
     * Exposes the underlying descriptor map when backed by an indexed catalog.
     * @return immutable descriptor map
     */
    default Map<String, RuleErrorDescriptor> asMap() {
        return Map.of();
    }

    /**
     * Creates a catalog whose contents cannot change after construction.
     * @param descriptors code-indexed descriptions
     * @return immutable catalog
     */
    static RuleErrorCatalog of(Map<String, RuleErrorDescriptor> descriptors) {
        return new MapRuleErrorCatalog(descriptors);
    }

    /**
     * Merges multiple modular error catalogs into a single deterministic catalog,
     * failing closed if two catalogs declare conflicting descriptors for the same code.
     * @param catalogs modular catalogs to combine
     * @return merged immutable catalog
     */
    static RuleErrorCatalog merge(Collection<? extends RuleErrorCatalog> catalogs) {
        if (catalogs == null || catalogs.isEmpty()) {
            return new MapRuleErrorCatalog(Map.of());
        }
        List<RuleErrorCatalog> nonNull = catalogs.stream()
                .filter(Objects::nonNull)
                .map(RuleErrorCatalog.class::cast)
                .toList();
        if (nonNull.isEmpty()) {
            return new MapRuleErrorCatalog(Map.of());
        }
        if (nonNull.size() == 1) {
            return nonNull.getFirst();
        }
        Map<String, RuleErrorDescriptor> merged = new LinkedHashMap<>();
        List<RuleErrorCatalog> dynamicDelegates = new java.util.ArrayList<>();
        for (RuleErrorCatalog catalog : nonNull) {
            Map<String, RuleErrorDescriptor> map = catalog.asMap();
            if (!map.isEmpty()) {
                for (Map.Entry<String, RuleErrorDescriptor> entry : map.entrySet()) {
                    RuleErrorDescriptor existing = merged.putIfAbsent(entry.getKey(), entry.getValue());
                    if (existing != null && !existing.equals(entry.getValue())) {
                        throw new IllegalArgumentException(
                                "Conflicting RuleErrorDescriptor for code: " + entry.getKey());
                    }
                }
            } else if (!(catalog instanceof MapRuleErrorCatalog)) {
                dynamicDelegates.add(catalog);
            }
        }
        MapRuleErrorCatalog indexed = new MapRuleErrorCatalog(merged);
        if (dynamicDelegates.isEmpty()) {
            return indexed;
        }
        List<RuleErrorCatalog> immutableDelegates = List.copyOf(dynamicDelegates);
        return new RuleErrorCatalog() {
            @Override
            public Optional<RuleErrorDescriptor> find(String code) {
                Optional<RuleErrorDescriptor> found = indexed.find(code);
                if (found.isPresent()) {
                    return found;
                }
                for (RuleErrorCatalog delegate : immutableDelegates) {
                    found = delegate.find(code);
                    if (found.isPresent()) {
                        return found;
                    }
                }
                return Optional.empty();
            }

            @Override
            public Map<String, RuleErrorDescriptor> asMap() {
                return indexed.asMap();
            }
        };
    }
}
