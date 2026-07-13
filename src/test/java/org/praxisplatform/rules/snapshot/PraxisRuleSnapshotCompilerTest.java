package org.praxisplatform.rules.snapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.praxisplatform.rules.contract.DecisionAggregationPolicy;
import org.praxisplatform.rules.contract.DecisionBinding;
import org.praxisplatform.rules.contract.DecisionSlot;
import org.praxisplatform.rules.contract.DecisionSource;
import org.praxisplatform.rules.contract.DecisionStage;
import org.praxisplatform.rules.contract.OverridePolicy;
import org.praxisplatform.rules.contract.PublishedRuleSnapshot;
import org.praxisplatform.rules.contract.RuleDecision;
import org.praxisplatform.rules.contract.RuleExecutorRef;
import org.praxisplatform.rules.contract.RuleFailPolicy;
import org.praxisplatform.rules.contract.RuleRuntimeCompatibility;
import org.praxisplatform.rules.contract.RuleSetDefinition;
import org.praxisplatform.rules.contract.RuleSetRef;
import org.praxisplatform.rules.contract.RuleSnapshotApproval;
import org.praxisplatform.rules.contract.RuleSnapshotSource;
import org.praxisplatform.rules.contract.SlotCardinality;
import org.praxisplatform.rules.runtime.RuleBindingExecutorRegistry;

class PraxisRuleSnapshotCompilerTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String HASH_A = "A".repeat(64);
    private static final String HASH_B = "B".repeat(64);

    @Test
    void normalizesEquivalentDefinitionsAndProducesStableContentHash() throws Exception {
        PraxisRuleSnapshotCompiler compiler = new PraxisRuleSnapshotCompiler(RuleBindingExecutorRegistry.empty());

        CompiledRuleSnapshot first = compiler.compile(snapshot(false, "snapshot-1", null, 1), "quickstart/1.0");
        CompiledRuleSnapshot reordered = compiler.compile(snapshot(true, "snapshot-1", null, 1), "quickstart/1.0");

        assertEquals(first.snapshotContentHash(), reordered.snapshotContentHash());
        assertEquals(List.of("authorization", "eligibility"), first.plan().orderedBindings().stream()
                .map(DecisionBinding::bindingKey)
                .toList());
        assertEquals(List.of("definition-a", "definition-b"), first.snapshot().sources().stream()
                .map(RuleSnapshotSource::definitionKey)
                .toList());
    }

    @Test
    void contentHashChangesAcrossImmutablePublicationRevisions() throws Exception {
        PraxisRuleSnapshotCompiler compiler = new PraxisRuleSnapshotCompiler(RuleBindingExecutorRegistry.empty());

        String first = compiler.compile(snapshot(false, "snapshot-1", null, 1), "quickstart/1.0")
                .snapshotContentHash();
        String second = compiler.compile(snapshot(false, "snapshot-2", "snapshot-1", 2), "quickstart/1.0")
                .snapshotContentHash();

        assertNotEquals(first, second);
    }

    @Test
    void rejectsUnsupportedSnapshotAndHostContracts() throws Exception {
        PraxisRuleSnapshotCompiler compiler = new PraxisRuleSnapshotCompiler(RuleBindingExecutorRegistry.empty());
        PublishedRuleSnapshot valid = snapshot(false, "snapshot-1", null, 1);

        RuleSnapshotException host = assertThrows(
                RuleSnapshotException.class,
                () -> compiler.compile(valid, "quickstart/2.0"));
        assertEquals(RuleSnapshotIssueCode.SNAPSHOT_HOST_INCOMPATIBLE, host.getCode());

        PublishedRuleSnapshot unsupported = new PublishedRuleSnapshot(
                "2.0",
                valid.snapshotKey(),
                valid.tenantId(),
                valid.environment(),
                valid.ownerServiceKey(),
                valid.publicationRevision(),
                valid.publishedAtUtc(),
                valid.supersedesSnapshotKey(),
                valid.requiredHostContractVersion(),
                valid.validFromUtc(),
                valid.validUntilUtc(),
                valid.sources(),
                valid.approvals(),
                valid.ruleSet());
        RuleSnapshotException contract = assertThrows(
                RuleSnapshotException.class,
                () -> compiler.compile(unsupported, "quickstart/1.0"));
        assertEquals(RuleSnapshotIssueCode.SNAPSHOT_CONTRACT_INCOMPATIBLE, contract.getCode());
    }

    @Test
    void rejectsInvalidValiditySelfPredecessorAndDuplicateProvenance() throws Exception {
        PublishedRuleSnapshot valid = snapshot(false, "snapshot-1", null, 1);

        assertThrows(IllegalArgumentException.class, () -> new PublishedRuleSnapshot(
                valid.snapshotContractVersion(), "snapshot-1", valid.tenantId(), valid.environment(),
                valid.ownerServiceKey(), 1, valid.publishedAtUtc(), "snapshot-1",
                valid.requiredHostContractVersion(), valid.validFromUtc(), valid.validUntilUtc(),
                valid.sources(), valid.approvals(), valid.ruleSet()));
        assertThrows(IllegalArgumentException.class, () -> new PublishedRuleSnapshot(
                valid.snapshotContractVersion(), "snapshot-1", valid.tenantId(), valid.environment(),
                valid.ownerServiceKey(), 1, valid.publishedAtUtc(), null,
                valid.requiredHostContractVersion(), "2026-08-01T00:00:00Z", "2026-07-01T00:00:00Z",
                valid.sources(), valid.approvals(), valid.ruleSet()));

        RuleSnapshotSource duplicateHash = new RuleSnapshotSource("definition-3", "definition-c", 1, HASH_A);
        PublishedRuleSnapshot duplicate = new PublishedRuleSnapshot(
                valid.snapshotContractVersion(), "snapshot-1", valid.tenantId(), valid.environment(),
                valid.ownerServiceKey(), 1, valid.publishedAtUtc(), null,
                valid.requiredHostContractVersion(), valid.validFromUtc(), valid.validUntilUtc(),
                List.of(valid.sources().getFirst(), duplicateHash), valid.approvals(), valid.ruleSet());
        RuleSnapshotException provenance = assertThrows(
                RuleSnapshotException.class,
                () -> new PraxisRuleSnapshotCompiler(RuleBindingExecutorRegistry.empty())
                        .compile(duplicate, "quickstart/1.0"));
        assertEquals(RuleSnapshotIssueCode.SNAPSHOT_PROVENANCE_INVALID, provenance.getCode());
    }

    private PublishedRuleSnapshot snapshot(
            boolean reverseDefinition,
            String snapshotKey,
            String predecessor,
            int revision) throws Exception {
        RuleSnapshotSource a = new RuleSnapshotSource("definition-1", "definition-a", 1, HASH_A);
        RuleSnapshotSource b = new RuleSnapshotSource("definition-2", "definition-b", 2, HASH_B);
        RuleSnapshotApproval approvalA = new RuleSnapshotApproval(
                "approval-a", "DOMAIN_OWNER", "actor:domain-owner", "2026-07-13T14:00:00Z", HASH_A);
        RuleSnapshotApproval approvalB = new RuleSnapshotApproval(
                "approval-b", "SECURITY_REVIEWER", "actor:security-reviewer", "2026-07-13T14:30:00Z", HASH_B);
        return new PublishedRuleSnapshot(
                PublishedRuleSnapshot.SNAPSHOT_CONTRACT_VERSION,
                snapshotKey,
                "tenant-a",
                "staging",
                "praxis-api-quickstart",
                revision,
                revision == 1 ? "2026-07-13T15:00:00Z" : "2026-07-13T16:00:00Z",
                predecessor,
                "quickstart/1.0",
                "2026-07-13T00:00:00Z",
                null,
                reverseDefinition ? List.of(b, a) : List.of(a, b),
                reverseDefinition ? List.of(approvalB, approvalA) : List.of(approvalA, approvalB),
                definition(reverseDefinition));
    }

    private RuleSetDefinition definition(boolean reverse) throws Exception {
        DecisionSlot authorizationSlot = new DecisionSlot(
                "authorization", DecisionStage.PROTECTED_GUARD, SlotCardinality.SINGLE,
                OverridePolicy.FORBIDDEN, DecisionAggregationPolicy.SINGLE_RESULT);
        DecisionSlot eligibilitySlot = new DecisionSlot(
                "eligibility", DecisionStage.DOMAIN_DECISION, SlotCardinality.SINGLE,
                OverridePolicy.FORBIDDEN, DecisionAggregationPolicy.SINGLE_RESULT);
        DecisionBinding authorization = new DecisionBinding(
                "authorization", "authorization", DecisionSource.SECURITY, null,
                RuleExecutorRef.jsonLogic(JSON.readTree("{\"===\":[true,true]}")), List.of(), 10, true,
                RuleDecision.DENY, "NOT_AUTHORIZED", List.of());
        DecisionBinding eligibility = new DecisionBinding(
                "eligibility", "eligibility", DecisionSource.PRODUCT, null,
                RuleExecutorRef.jsonLogic(JSON.readTree("{\"===\":[true,true]}")), List.of("authorization"), 20, true,
                RuleDecision.DENY, "NOT_ELIGIBLE", List.of());
        return new RuleSetDefinition(
                new RuleSetRef("benefits", "grants", "extraordinary-grant", "evaluate", 1),
                List.of("request"),
                reverse ? List.of(eligibilitySlot, authorizationSlot) : List.of(authorizationSlot, eligibilitySlot),
                reverse ? List.of(eligibility, authorization) : List.of(authorization, eligibility),
                RuleRuntimeCompatibility.current(),
                RuleFailPolicy.FAIL_CLOSED);
    }
}
