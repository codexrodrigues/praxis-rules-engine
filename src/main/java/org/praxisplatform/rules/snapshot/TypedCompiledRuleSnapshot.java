package org.praxisplatform.rules.snapshot;

import java.util.Objects;
import org.praxisplatform.rules.plan.TypedRuleDecisionPlan;

/**
 * Immutable published candidate and executable admission evidence for one host operation.
 * @param snapshot canonically normalized published snapshot and content hash
 * @param typedPlan schema-bound executable plan
 */
public record TypedCompiledRuleSnapshot(CompiledRuleSnapshot snapshot, TypedRuleDecisionPlan typedPlan) {
    /** Requires the same canonical plan identity and host contract in both envelopes. */
    public TypedCompiledRuleSnapshot {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(typedPlan, "typedPlan");
        if (!snapshot.plan().planDigest().equals(typedPlan.plan().planDigest())
                || !snapshot.snapshot().requiredHostContractVersion().equals(typedPlan.contract().hostContractVersion()))
            throw new IllegalArgumentException("Snapshot and typed plan differ");
    }
}
