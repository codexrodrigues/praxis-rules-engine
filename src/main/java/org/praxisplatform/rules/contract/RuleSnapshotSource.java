package org.praxisplatform.rules.contract;

import java.util.Locale;
import java.util.Objects;

/**
 * Immutable provenance of one governed definition included in a published snapshot.
 *
 * @param definitionId stable control-plane definition identity
 * @param definitionKey stable semantic definition key
 * @param version positive immutable definition version
 * @param sourceHash SHA-256 of the governed definition content
 */
public record RuleSnapshotSource(
        String definitionId,
        String definitionKey,
        int version,
        String sourceHash) {

    /** Validates explicit provenance coordinates. */
    public RuleSnapshotSource {
        definitionId = required(definitionId, "definitionId");
        definitionKey = required(definitionKey, "definitionKey");
        if (version < 1) {
            throw new IllegalArgumentException("version must be positive");
        }
        sourceHash = required(sourceHash, "sourceHash").toUpperCase(Locale.ROOT);
        if (!sourceHash.matches("[A-F0-9]{64}")) {
            throw new IllegalArgumentException("sourceHash must be a SHA-256 hex digest");
        }
    }

    private static String required(String value, String field) {
        Objects.requireNonNull(value, field + " is required");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
