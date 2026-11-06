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
import org.praxisplatform.rules.contract.RuleImplementationRef;
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
    void rejectsPreviousRoundCorpusForPlansAndSnapshots() throws Exception {
        var current = snapshot(false, "snapshot-round", null, 1);
        var definition = current.ruleSet();
        var previous = new RuleRuntimeCompatibility(
                RuleRuntimeCompatibility.ENGINE_CONTRACT_VERSION,
                RuleRuntimeCompatibility.JSON_LOGIC_DIALECT_VERSION,
                "E890925C7306A2646CF86443C29ABDDEF0C0645C6228F1DE4A8740E45253D181");
        var oldDefinition = new RuleSetDefinition(definition.ref(), definition.availableRoots(),
                definition.slots(), definition.bindings(), previous, definition.failPolicy());
        var oldSnapshot = new PublishedRuleSnapshot(current.snapshotContractVersion(), current.snapshotKey(),
                current.tenantId(), current.environment(), current.ownerServiceKey(), current.publicationRevision(),
                current.publishedAtUtc(), current.supersedesSnapshotKey(), current.requiredHostContractVersion(),
                current.validFromUtc(), current.validUntilUtc(), current.sources(), current.approvals(), oldDefinition);
        var registry = RuleBindingExecutorRegistry.empty();
        var planFailure = assertThrows(org.praxisplatform.rules.plan.RulePlanException.class,
                () -> new org.praxisplatform.rules.plan.PraxisRulePlanCompiler(registry).compile(oldDefinition));
        assertEquals(org.praxisplatform.rules.plan.RulePlanIssueCode.PLAN_COMPATIBILITY_INVALID,
                planFailure.getCode());
        var snapshotFailure = assertThrows(org.praxisplatform.rules.plan.RulePlanException.class,
                () -> new PraxisRuleSnapshotCompiler(registry).compile(oldSnapshot, "quickstart/1.0"));
        assertEquals(org.praxisplatform.rules.plan.RulePlanIssueCode.PLAN_COMPATIBILITY_INVALID,
                snapshotFailure.getCode());
        assertEquals(previous, oldSnapshot.ruleSet().compatibility());
        var currentPlan = new PraxisRuleSnapshotCompiler(registry).compile(current, "quickstart/1.0").plan();
        var restoredOldPlan = new org.praxisplatform.rules.plan.RuleDecisionPlan(oldDefinition,
                currentPlan.orderedBindings(), currentPlan.slotsByKey(), currentPlan.implementationRefs(), HASH_A);
        var result = new org.praxisplatform.rules.runtime.PraxisRuleSetEngine(registry).evaluate(
                restoredOldPlan, JSON.createObjectNode(), "2026-11-04T03:00:00Z", "UTC");
        assertEquals(RuleDecision.TECHNICAL_ERROR, result.decision());
        assertEquals(List.of("PLAN_COMPATIBILITY_INVALID"), result.reasonCodes());
        assertEquals(List.of(), result.bindingResults());
    }

    @Test
    void customerDescriptorsSurviveRoundTripAndBindBothHashes() throws Exception {
        var compiler = new PraxisRuleSnapshotCompiler(RuleBindingExecutorRegistry.empty());
        var original = compiler.compile(withCustomerDescriptor("CUSTOMER_DENIAL", "First message"), "quickstart/1.0");
        var restored = compiler.compile(JSON.readValue(JSON.writeValueAsBytes(original.snapshot()),
                PublishedRuleSnapshot.class), "quickstart/1.0");
        var edited = compiler.compile(withCustomerDescriptor("CUSTOMER_DENIAL", "Edited message"), "quickstart/1.0");
        assertEquals(original, restored);
        assertNotEquals(original.snapshotContentHash(), edited.snapshotContentHash());
        assertNotEquals(original.plan().planDigest(), edited.plan().planDigest());
        var result = new org.praxisplatform.rules.runtime.PraxisRuleSetEngine(RuleBindingExecutorRegistry.empty())
                .evaluate(restored.plan(), JSON.createObjectNode().putObject("request"),
                        "2026-07-13T15:00:00Z", "UTC");
        var violation = new org.praxisplatform.rules.violation.PraxisRuleViolationException(
                restored.plan(), result, ignored -> java.util.Optional.empty());
        assertEquals("First message", violation.getViolations().getFirst().messagePtBr());
        assertThrows(org.praxisplatform.rules.plan.RulePlanException.class,
                () -> compiler.compile(withCustomerDescriptor("NOT_ELIGIBLE", "Collision"), "quickstart/1.0"));
    }

    private PublishedRuleSnapshot withCustomerDescriptor(String code, String message) throws Exception {
        var base = snapshot(false, "snapshot-local", null, 1);
        var definition = base.ruleSet();
        var slots = new java.util.ArrayList<>(definition.slots());
        slots.add(new DecisionSlot("local", DecisionStage.DOMAIN_DECISION, SlotCardinality.MULTIPLE,
                OverridePolicy.RESTRICT_ONLY, DecisionAggregationPolicy.DENY_OVERRIDES, null, java.util.List.of()));
        var bindings = new java.util.ArrayList<>(definition.bindings());
        bindings.add(new DecisionBinding("local", "local", DecisionSource.CUSTOMER,
                org.praxisplatform.rules.contract.CompositionPolicy.AUGMENT,
                RuleExecutorRef.jsonLogic(JSON.readTree("{\"===\":[true,false]}")), List.of(), 30, true,
                RuleDecision.DENY, code, List.of(), null));
        return new PublishedRuleSnapshot(base.snapshotContractVersion(), base.snapshotKey(), base.tenantId(),
                base.environment(), base.ownerServiceKey(), base.publicationRevision(), base.publishedAtUtc(), null,
                base.requiredHostContractVersion(), base.validFromUtc(), null, base.sources(), base.approvals(),
                new RuleSetDefinition(definition.ref(), definition.availableRoots(), slots, bindings,
                        definition.compatibility(), definition.failPolicy(), java.util.Map.of("local",
                        new org.praxisplatform.rules.violation.RuleErrorDescriptor(
                                code, "request", message, "tenant-policy", "BUSINESS_LOGIC"))));
    }

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

    @Test
    void planningOnlyRegistryValidatesCoordinatesButCannotExecuteHostCode() throws Exception {
        RuleBindingExecutorRegistry planningRegistry = RuleBindingExecutorRegistry.planning(List.of(
                new RuleImplementationRef("benefits:amount", "1.0.0")));
        DecisionSlot calculationSlot = new DecisionSlot(
                "calculation", DecisionStage.DOMAIN_DECISION, SlotCardinality.SINGLE,
                OverridePolicy.FORBIDDEN, DecisionAggregationPolicy.SINGLE_RESULT, null, java.util.List.of());
        DecisionBinding calculation = new DecisionBinding(
                "calculation", "calculation", DecisionSource.PRODUCT, null,
                RuleExecutorRef.java("benefits:amount", "1.0.0"), List.of(), 10, true,
                null, null, List.of(), null);
        PublishedRuleSnapshot base = snapshot(false, "snapshot-java", null, 1);
        PublishedRuleSnapshot javaSnapshot = new PublishedRuleSnapshot(
                base.snapshotContractVersion(), base.snapshotKey(), base.tenantId(), base.environment(),
                base.ownerServiceKey(), base.publicationRevision(), base.publishedAtUtc(), null,
                base.requiredHostContractVersion(), base.validFromUtc(), null, base.sources(), base.approvals(),
                new RuleSetDefinition(
                        base.ruleSet().ref(), List.of("request"), List.of(calculationSlot), List.of(calculation),
                        RuleRuntimeCompatibility.current(), RuleFailPolicy.FAIL_CLOSED));

        CompiledRuleSnapshot compiled = new PraxisRuleSnapshotCompiler(planningRegistry)
                .compile(javaSnapshot, "quickstart/1.0");
        var result = new org.praxisplatform.rules.runtime.PraxisRuleSetEngine(planningRegistry)
                .evaluate(compiled.plan(), JSON.createObjectNode().putObject("request"),
                        "2026-07-13T15:00:00Z", "UTC");

        assertEquals(RuleDecision.TECHNICAL_ERROR, result.decision());
        assertEquals(List.of("IMPLEMENTATION_UNAVAILABLE"), result.reasonCodes());
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
                OverridePolicy.FORBIDDEN, DecisionAggregationPolicy.SINGLE_RESULT, null, java.util.List.of());
        DecisionSlot eligibilitySlot = new DecisionSlot(
                "eligibility", DecisionStage.DOMAIN_DECISION, SlotCardinality.SINGLE,
                OverridePolicy.FORBIDDEN, DecisionAggregationPolicy.SINGLE_RESULT, null, java.util.List.of());
        DecisionBinding authorization = new DecisionBinding(
                "authorization", "authorization", DecisionSource.SECURITY, null,
                RuleExecutorRef.jsonLogic(JSON.readTree("{\"===\":[true,true]}")), List.of(), 10, true,
                RuleDecision.DENY, "NOT_AUTHORIZED", List.of(), null);
        DecisionBinding eligibility = new DecisionBinding(
                "eligibility", "eligibility", DecisionSource.PRODUCT, null,
                RuleExecutorRef.jsonLogic(JSON.readTree("{\"===\":[true,true]}")), List.of("authorization"), 20, true,
                RuleDecision.DENY, "NOT_ELIGIBLE", List.of(), null);
        return new RuleSetDefinition(
                new RuleSetRef("benefits", "grants", "extraordinary-grant", "evaluate", 1),
                List.of("request"),
                reverse ? List.of(eligibilitySlot, authorizationSlot) : List.of(authorizationSlot, eligibilitySlot),
                reverse ? List.of(eligibility, authorization) : List.of(authorization, eligibility),
                RuleRuntimeCompatibility.current(),
                RuleFailPolicy.FAIL_CLOSED);
    }
}
