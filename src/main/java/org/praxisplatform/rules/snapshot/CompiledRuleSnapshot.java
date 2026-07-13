package org.praxisplatform.rules.snapshot;

import java.util.Objects;
import org.praxisplatform.rules.contract.PublishedRuleSnapshot;
import org.praxisplatform.rules.plan.RuleDecisionPlan;

/**
 * Prepared last-known-good candidate ready for an atomic host activation.
 *
 * @param snapshot normalized immutable snapshot
 * @param plan compiled deterministic execution plan
 * @param snapshotContentHash SHA-256 of the canonical normalized snapshot
 */
public record CompiledRuleSnapshot(
        PublishedRuleSnapshot snapshot,
        RuleDecisionPlan plan,
        String snapshotContentHash) {

    /** Requires a complete prepared candidate. */
    public CompiledRuleSnapshot {
        snapshot = Objects.requireNonNull(snapshot, "snapshot is required");
        plan = Objects.requireNonNull(plan, "plan is required");
        if (snapshotContentHash == null || !snapshotContentHash.matches("[A-F0-9]{64}")) {
            throw new IllegalArgumentException("snapshotContentHash must be an uppercase SHA-256 digest");
        }
    }
}
