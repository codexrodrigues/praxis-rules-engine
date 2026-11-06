package org.praxisplatform.rules.violation;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable catalog that rejects an index inconsistent with the declared error identity.
 */
public final class MapRuleErrorCatalog implements RuleErrorCatalog {
    private final Map<String, RuleErrorDescriptor> descriptors;

    /**
     * Snapshots descriptions and verifies every index key against the error identity.
     * @param descriptors complete code-indexed catalog
     */
    public MapRuleErrorCatalog(Map<String, RuleErrorDescriptor> descriptors) {
        this.descriptors = Map.copyOf(Objects.requireNonNull(descriptors, "descriptors"));
        this.descriptors.forEach((key, descriptor) -> {
            if (!key.equals(descriptor.code())) throw new IllegalArgumentException("Catalog key must equal descriptor code");
        });
    }

    @Override public Optional<RuleErrorDescriptor> find(String code) {
        return code == null ? Optional.empty() : Optional.ofNullable(descriptors.get(code));
    }
}
