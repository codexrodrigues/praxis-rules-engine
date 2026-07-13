package org.praxisplatform.rules.snapshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Comparator;
import java.util.HashSet;
import org.praxisplatform.rules.contract.PublishedRuleSnapshot;
import org.praxisplatform.rules.contract.RuleSetDefinition;
import org.praxisplatform.rules.digest.PraxisCanonicalJson;
import org.praxisplatform.rules.plan.PraxisRulePlanCompiler;
import org.praxisplatform.rules.plan.RuleDecisionPlan;
import org.praxisplatform.rules.runtime.RuleBindingExecutorRegistry;

/** Validates, normalizes and compiles immutable published snapshots outside the evaluation path. */
public final class PraxisRuleSnapshotCompiler {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final PraxisRulePlanCompiler planCompiler;

    /**
     * Creates a compiler backed by the host's exact Java executor registry.
     * @param executorRegistry trusted implementations available in this host build
     */
    public PraxisRuleSnapshotCompiler(RuleBindingExecutorRegistry executorRegistry) {
        this.planCompiler = new PraxisRulePlanCompiler(executorRegistry);
    }

    /**
     * Prepares a snapshot for atomic host activation.
     *
     * @param snapshot immutable published envelope
     * @param hostContractVersion exact host contract implemented by the consumer
     * @return normalized snapshot, deterministic plan and canonical content hash
     */
    public CompiledRuleSnapshot compile(PublishedRuleSnapshot snapshot, String hostContractVersion) {
        java.util.Objects.requireNonNull(snapshot, "snapshot is required");
        if (!PublishedRuleSnapshot.SNAPSHOT_CONTRACT_VERSION.equals(snapshot.snapshotContractVersion())) {
            throw failure(
                    RuleSnapshotIssueCode.SNAPSHOT_CONTRACT_INCOMPATIBLE,
                    "Snapshot contract version is not supported");
        }
        if (hostContractVersion == null
                || !snapshot.requiredHostContractVersion().equals(hostContractVersion.trim())) {
            throw failure(
                    RuleSnapshotIssueCode.SNAPSHOT_HOST_INCOMPATIBLE,
                    "Snapshot requires an incompatible host contract");
        }
        if (new HashSet<>(snapshot.sources().stream().map(source -> source.sourceHash()).toList()).size()
                != snapshot.sources().size()) {
            throw failure(
                    RuleSnapshotIssueCode.SNAPSHOT_PROVENANCE_INVALID,
                    "Snapshot sources must have distinct content hashes");
        }

        RuleDecisionPlan plan = planCompiler.compile(snapshot.ruleSet());
        RuleSetDefinition normalizedDefinition = new RuleSetDefinition(
                plan.definition().ref(),
                plan.definition().availableRoots(),
                plan.definition().slots().stream()
                        .sorted(Comparator.comparing(slot -> slot.slotKey()))
                        .toList(),
                plan.orderedBindings(),
                plan.definition().compatibility(),
                plan.definition().failPolicy());
        PublishedRuleSnapshot normalized = new PublishedRuleSnapshot(
                snapshot.snapshotContractVersion(),
                snapshot.snapshotKey(),
                snapshot.tenantId(),
                snapshot.environment(),
                snapshot.ownerServiceKey(),
                snapshot.publicationRevision(),
                snapshot.publishedAtUtc(),
                snapshot.supersedesSnapshotKey(),
                snapshot.requiredHostContractVersion(),
                snapshot.validFromUtc(),
                snapshot.validUntilUtc(),
                snapshot.sources(),
                snapshot.approvals(),
                normalizedDefinition);
        String contentHash = PraxisCanonicalJson.sha256(JSON.valueToTree(normalized));
        return new CompiledRuleSnapshot(normalized, planCompiler.compile(normalizedDefinition), contentHash);
    }

    private RuleSnapshotException failure(RuleSnapshotIssueCode code, String message) {
        return new RuleSnapshotException(code, message);
    }
}
