package org.praxisplatform.rules.violation;

import java.util.Map;
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
     * Creates a catalog whose contents cannot change after construction.
     * @param descriptors code-indexed descriptions
     * @return immutable catalog
     */
    static RuleErrorCatalog of(Map<String, RuleErrorDescriptor> descriptors) {
        return new MapRuleErrorCatalog(descriptors);
    }
}
