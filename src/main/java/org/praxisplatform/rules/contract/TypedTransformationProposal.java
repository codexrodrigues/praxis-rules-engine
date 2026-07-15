package org.praxisplatform.rules.contract;

import java.util.Objects;

/**
 * Validated transformation proposal enriched with exact binding provenance and redacted digests.
 *
 * @param proposalKey stable proposal identity
 * @param bindingKey exact producing binding
 * @param slotKey exact producing slot
 * @param targetPath validated dotted property path
 * @param schemaRef absolute governed-schema URI with field fragment
 * @param operation requested closed write-model operation
 * @param before validated value from the facts snapshot
 * @param after validated proposed value
 * @param beforeDigest redacted digest of {@code before}
 * @param afterDigest redacted digest of {@code after}
 * @param reasonCode stable machine-readable business reason
 */
public record TypedTransformationProposal(
        String proposalKey,
        String bindingKey,
        String slotKey,
        String targetPath,
        String schemaRef,
        TransformationOperation operation,
        TransformationValue before,
        TransformationValue after,
        String beforeDigest,
        String afterDigest,
        String reasonCode) {

    /** Requires complete provenance and digest consistency. */
    public TypedTransformationProposal {
        proposalKey = Objects.requireNonNull(proposalKey, "proposalKey is required");
        bindingKey = Objects.requireNonNull(bindingKey, "bindingKey is required");
        slotKey = Objects.requireNonNull(slotKey, "slotKey is required");
        targetPath = Objects.requireNonNull(targetPath, "targetPath is required");
        schemaRef = Objects.requireNonNull(schemaRef, "schemaRef is required");
        operation = Objects.requireNonNull(operation, "operation is required");
        before = Objects.requireNonNull(before, "before is required");
        after = Objects.requireNonNull(after, "after is required");
        reasonCode = Objects.requireNonNull(reasonCode, "reasonCode is required");
        if (!before.digest().equals(beforeDigest) || !after.digest().equals(afterDigest)) {
            throw new IllegalArgumentException("Transformation digests must match before/after values");
        }
    }
}
