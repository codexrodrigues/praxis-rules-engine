package org.praxisplatform.rules.facts;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.praxisplatform.rules.contract.*;
import org.praxisplatform.rules.plan.*;
import org.praxisplatform.rules.runtime.*;
import org.praxisplatform.rules.snapshot.*;

class RuleFactAdmissionTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String NOW = "2026-11-05T12:00:00Z";
    private static final String KEY = "test:count";

    private static RuleSetDefinition javaDefinition() throws Exception {
        var source = RuleFactSchemaTest.definition("true", List.of("draft.count"));
        var binding = source.bindings().getFirst();
        return new RuleSetDefinition(source.ref(), source.availableRoots(), source.slots(),
                List.of(new DecisionBinding(binding.bindingKey(), binding.slotKey(), binding.source(), null,
                        RuleExecutorRef.java(KEY, "2"), List.of(), 0, true, null,
                        null, binding.requiredFactPaths(), null)), source.compatibility(), source.failPolicy());
    }

    private static RuleBindingExecutorRegistry registry(AtomicInteger calls) {
        return new RuleBindingExecutorRegistry(List.of(new RuleBindingExecutor() {
            public String implementationKey() { return KEY; }
            public String implementationVersion() { return "2"; }
            public RuleExecutorResult evaluate(RuleExecutorContext context) {
                calls.incrementAndGet();
                return new RuleExecutorResult(RuleDecision.ALLOW, List.of(), null);
            }
        }));
    }

    private static RuleFactContract contract(String version, Set<String> types) throws Exception {
        var base = RuleFactSchemaTest.contract();
        return new RuleFactContract(base.hostContractVersion(), base.operationKey(), base.providerRef(), base.schema(),
                List.of(new RuleFactContract.JavaInputs(KEY, version, Map.of("draft.count", types))));
    }

    @Test void controlPlaneAndHostShareAnalysisButHostRequiresExecutableRegistry() throws Exception {
        var registry = registry(new AtomicInteger()); var contract = contract("2", Set.of("integer"));
        var planning = RuleBindingExecutorRegistry.planning(List.of(registry.implementationRef(KEY)));
        var compiled = new PraxisRulePlanCompiler(planning).compile(javaDefinition());
        contract.validate(compiled);
        assertThrows(RuleFactContractException.class, () -> TypedRuleDecisionPlan.compile(javaDefinition(), planning, contract));
        assertNotNull(TypedRuleDecisionPlan.compile(javaDefinition(), registry, contract));
        assertThrows(RuleFactContractException.class, () -> TypedRuleDecisionPlan.compile(javaDefinition(), registry, contract("1", Set.of("integer"))));
        assertThrows(RuleFactContractException.class, () -> TypedRuleDecisionPlan.compile(javaDefinition(), registry, contract("2", Set.of("string"))));
        assertThrows(RuleFactContractException.class, () -> TypedRuleDecisionPlan.compile(javaDefinition(), registry, RuleFactSchemaTest.contract()));
    }

    @Test void providerDriftPreventsJavaExecutionAndKeepsTechnicalOutcome() throws Exception {
        var calls = new AtomicInteger(); var registry = registry(calls); var contract = contract("2", Set.of("integer"));
        var typed = TypedRuleDecisionPlan.compile(javaDefinition(), registry, contract);
        var invalid = RuleFactSchemaTest.facts(); invalid.withObject("/draft").put("count", "2");
        var engine = new PraxisRuleSetEngine(registry);
        var rejected = engine.evaluate(typed, "test/2", "requests.create", "requests:2", invalid, NOW, "UTC");
        assertEquals(0, calls.get()); assertEquals(RuleDecision.TECHNICAL_ERROR, rejected.result().decision());
        assertEquals(typed.admissionDigest(), rejected.admissionDigest());
        assertEquals(contract.digest(), rejected.factContractDigest());
        var accepted = engine.evaluate(typed, "test/2", "requests.create", "requests:2", RuleFactSchemaTest.facts(), NOW, "UTC");
        assertEquals(RuleDecision.ALLOW, accepted.result().decision()); assertEquals(1, calls.get());
        assertThrows(RuleFactContractException.class, () -> engine.evaluate(typed, "test/1", "requests.create", "requests:2",
                RuleFactSchemaTest.facts(), NOW, "UTC")); assertEquals(1, calls.get());
    }

    @Test void javaInputsMustAcceptNullFromLeavesAndNullableAncestors() throws Exception {
        var registry = registry(new AtomicInteger());
        for (String nullablePointer : List.of("/properties/draft", "/properties/draft/properties/count")) {
            var source = RuleFactSchemaTest.schema();
            var field = source.withObject(nullablePointer);
            String base = field.path("type").asText();
            field.putArray("type").add(base).add("null");
            var schema = RuleFactSchema.compile(source);
            assertTrue(schema.nullableAt("draft.count"));
            var rejected = new RuleFactContract("test/2", "requests.create", "requests:2", schema,
                    List.of(new RuleFactContract.JavaInputs(KEY, "2", Map.of("draft.count", Set.of("integer")))));
            assertThrows(RuleFactContractException.class, () -> TypedRuleDecisionPlan.compile(javaDefinition(), registry, rejected));
            var admitted = new RuleFactContract("test/2", "requests.create", "requests:2", schema,
                    List.of(new RuleFactContract.JavaInputs(KEY, "2", Map.of("draft.count", Set.of("integer", "null")))));
            assertNotNull(TypedRuleDecisionPlan.compile(javaDefinition(), registry, admitted));
        }
    }

    @Test void admissionDigestIncludesContractCoordinatesAndSortedJavaTypes() throws Exception {
        var a = contract("2", Set.of("number", "integer"));
        var b = contract("2", new java.util.LinkedHashSet<>(List.of("integer", "number")));
        assertEquals(a.digest(), b.digest());
        assertNotEquals(a.digest(), contract("2", Set.of("integer")).digest());
        var changed = new RuleFactContract("test/2", "requests.update", a.providerRef(), a.schema(), a.javaExecutors());
        assertNotEquals(a.digest(), changed.digest());
        assertThrows(IllegalArgumentException.class, () -> new RuleFactContract("test/2\ninjected", "x", "y", a.schema(), List.of()));
        var inputs = new java.util.HashMap<String, Set<String>>(); var types = new java.util.HashSet<>(Set.of("integer"));
        inputs.put("draft.count", types); var declared = new RuleFactContract.JavaInputs(KEY, "2", inputs);
        types.add("string"); inputs.clear(); assertEquals(Map.of("draft.count", Set.of("integer")), declared.paths());
    }

    @Test void snapshotsRejectWrongHostOrOldLeavesEvenWhenCandidateDeclaresNewToken() throws Exception {
        var contract = RuleFactSchemaTest.contract(); var compiler = new PraxisRuleSnapshotCompiler(RuleBindingExecutorRegistry.empty());
        var definition = RuleFactSchemaTest.definition("{\">\":[{\"var\":\"draft.count\"},0]}", List.of("draft.count"));
        var admitted = compiler.compileForOperation(snapshot(definition, "test/2"), contract);
        assertEquals(admitted.snapshot().plan().planDigest(), admitted.typedPlan().plan().planDigest());
        var oldHost = compiler.compile(snapshot(definition, "test/1"), "test/1");
        assertThrows(IllegalArgumentException.class, () -> new TypedCompiledRuleSnapshot(oldHost, admitted.typedPlan()));
        assertThrows(RuleSnapshotException.class, () -> compiler.compileForOperation(snapshot(definition, "test/1"), contract));
        var old = RuleFactSchemaTest.definition("{\"var\":\"draft.inicio\"}", List.of("draft.inicio"));
        assertThrows(RuleFactContractException.class, () -> compiler.compileForOperation(snapshot(old, "test/2"), contract));
    }

    private static PublishedRuleSnapshot snapshot(RuleSetDefinition definition, String hostVersion) {
        return new PublishedRuleSnapshot(PublishedRuleSnapshot.SNAPSHOT_CONTRACT_VERSION, "candidate", "2", "stage", "test",
                1, NOW, null, hostVersion, NOW, null,
                List.of(new RuleSnapshotSource("source", "source", 1, "A".repeat(64))),
                List.of(new RuleSnapshotApproval("approval", "reviewer", "actor", NOW, "B".repeat(64))), definition);
    }

    @Test void optionalRequiredPathsAndImpossibleIndexesFailBeforeEvaluation() throws Exception {
        var source = RuleFactSchemaTest.schema(); source.withObject("/properties/draft").putArray("required").add("dtini");
        var contract = new RuleFactContract("test/2", "requests.create", "requests:2", RuleFactSchema.compile(source), List.of());
        assertThrows(RuleFactContractException.class, () -> TypedRuleDecisionPlan.compile(
                RuleFactSchemaTest.definition("{\"==\":[1,1]}", List.of("draft.count")), RuleBindingExecutorRegistry.empty(), contract));
        assertThrows(RuleFactContractException.class, () -> contract.schema().typeAt("neighborhood.rows[9].days"));
    }

    @Test void productTemplateIsAnalyzedEvenWhenEffectiveReplacementDoesNotReferenceItsBrokenLeaf() throws Exception {
        var base = RuleFactSchemaTest.definition("{\"==\":[1,1]}", List.of());
        var parameter = new RuleParameterDefinition("limit", RuleParameterType.NUMBER, true, false, false, "count",
                JSON.readTree("5"), java.math.BigDecimal.ZERO, java.math.BigDecimal.TEN, List.of(), List.of("/<=/1"));
        var template = new RuleParameterTemplate("test:template", "1", RuleExecutorRef.jsonLogic(JSON.readTree("{\"<=\":[{\"var\":\"draft.oldLeaf\"},5]}")),
                RuleDecision.DENY, "LIMIT", List.of("draft.oldLeaf"), List.of(parameter));
        var slot = new DecisionSlot("limit", DecisionStage.DOMAIN_DECISION, SlotCardinality.SINGLE,
                OverridePolicy.PARAMETERIZABLE, DecisionAggregationPolicy.SINGLE_RESULT, template, java.util.List.of());
        var candidate = new RuleSetDefinition(base.ref(), base.availableRoots(), List.of(slot), base.bindings(), base.compatibility(), base.failPolicy());
        assertThrows(RuleFactContractException.class, () -> TypedRuleDecisionPlan.compile(candidate, RuleBindingExecutorRegistry.empty(), RuleFactSchemaTest.contract()));
    }

    @Test void oldAndNewProviderPlanPairsRequireActualStructuralCompatibility() throws Exception {
        var source = RuleFactSchemaTest.schema();
        var properties = source.withObject("/properties/draft/properties");
        properties.set("legacyCount", properties.remove("count"));
        source.withObject("/properties/draft").putArray("required").add("dtini").add("legacyCount");
        var oldContract = new RuleFactContract("test/1", "requests.create", "requests:1", RuleFactSchema.compile(source), List.of());
        var currentContract = RuleFactSchemaTest.contract();
        var oldPlan = RuleFactSchemaTest.definition("{\">\":[{\"var\":\"draft.legacyCount\"},0]}", List.of("draft.legacyCount"));
        var currentPlan = RuleFactSchemaTest.definition("{\">\":[{\"var\":\"draft.count\"},0]}", List.of("draft.count"));
        var registry = RuleBindingExecutorRegistry.empty();
        assertNotNull(TypedRuleDecisionPlan.compile(oldPlan, registry, oldContract));
        assertNotNull(TypedRuleDecisionPlan.compile(currentPlan, registry, currentContract));
        assertThrows(RuleFactContractException.class, () -> TypedRuleDecisionPlan.compile(oldPlan, registry, currentContract));
        assertThrows(RuleFactContractException.class, () -> TypedRuleDecisionPlan.compile(currentPlan, registry, oldContract));
    }

    @Test void legacyDefensiveMissingFactOutcomeNeverCountsAsTypedAdmission() throws Exception {
        var definition = RuleFactSchemaTest.definition("{\">\":[{\"var\":\"draft.count\"},0]}", List.of("draft.count"));
        var registry = RuleBindingExecutorRegistry.empty();
        var legacy = new PraxisRulePlanCompiler(registry).compile(definition);
        var typed = TypedRuleDecisionPlan.compile(definition, registry, RuleFactSchemaTest.contract());
        var facts = RuleFactSchemaTest.facts(); facts.withObject("/draft").remove("count");
        var engine = new PraxisRuleSetEngine(registry);
        var defensive = engine.evaluate(legacy, facts, NOW, "UTC");
        assertEquals(RuleDecision.INCONCLUSIVE, defensive.decision());
        assertTrue(defensive.reasonCodes().contains("FACT_REQUIRED_MISSING"));
        var rejected = engine.evaluate(typed, "test/2", "requests.create", "requests:2", facts, NOW, "UTC");
        assertEquals(RuleDecision.TECHNICAL_ERROR, rejected.result().decision());
        assertEquals(List.of("FACTS_SCHEMA_INVALID"), rejected.result().reasonCodes());
        assertTrue(rejected.result().bindingResults().isEmpty());
    }
}
