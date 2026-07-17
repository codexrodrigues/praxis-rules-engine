package org.praxisplatform.rules.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.praxisplatform.rules.contract.DecisionAggregationPolicy;
import org.praxisplatform.rules.contract.DecisionBinding;
import org.praxisplatform.rules.contract.DecisionSlot;
import org.praxisplatform.rules.contract.DecisionSource;
import org.praxisplatform.rules.contract.DecisionStage;
import org.praxisplatform.rules.contract.OverridePolicy;
import org.praxisplatform.rules.contract.RuleDecision;
import org.praxisplatform.rules.contract.RuleExecutorRef;
import org.praxisplatform.rules.contract.RuleExecutorResult;
import org.praxisplatform.rules.contract.RuleFailPolicy;
import org.praxisplatform.rules.contract.RuleRuntimeCompatibility;
import org.praxisplatform.rules.contract.RuleSetDefinition;
import org.praxisplatform.rules.contract.RuleSetRef;
import org.praxisplatform.rules.contract.SlotCardinality;
import org.praxisplatform.rules.contract.TransformationDraft;
import org.praxisplatform.rules.contract.TransformationOperation;
import org.praxisplatform.rules.contract.TransformationValue;
import org.praxisplatform.rules.contract.TransformationValueType;
import org.praxisplatform.rules.plan.PraxisRulePlanCompiler;
import org.praxisplatform.rules.plan.RulePlanException;

class TypedTransformationContractTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String NOW = "2026-07-15T12:00:00Z";
    private static final String ZONE = "America/Sao_Paulo";

    @Test
    void producesTypedProposalWithBindingProvenanceWithoutMutatingFacts() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        RuleBindingExecutorRegistry registry = registry(executions, List.of(draft("normalize.amount", "2000.00")));
        var plan = new PraxisRulePlanCompiler(registry).compile(definition());
        JsonNode facts = JSON.readTree("""
                {"request":{"recommendedAmount":2500.00}}
                """);
        JsonNode original = facts.deepCopy();

        var result = new PraxisRuleSetEngine(registry).evaluate(plan, facts, NOW, ZONE);

        assertEquals(RuleDecision.ALLOW, result.decision());
        assertEquals(1, executions.get());
        assertEquals(original, facts);
        assertEquals(1, result.transformationProposals().size());
        var proposal = result.transformationProposals().getFirst();
        assertEquals("normalize.amount", proposal.proposalKey());
        assertEquals("request.amount-normalization", proposal.bindingKey());
        assertEquals("request.recommendedAmount", proposal.targetPath());
        assertEquals(TransformationValueType.NUMBER, proposal.after().type());
        assertNotEquals(proposal.beforeDigest(), proposal.afterDigest());
        assertEquals(proposal, result.bindingResults().get(1).transformations().getFirst());
    }

    @Test
    void rejectsTransformationDraftsOutsideTheTransformationStage() throws Exception {
        RuleBindingExecutorRegistry registry = registryForStage(
                DecisionStage.DOMAIN_DECISION, List.of(draft("normalize.amount", "2000.00")));
        var plan = new PraxisRulePlanCompiler(registry).compile(javaOnlyDefinition(DecisionStage.DOMAIN_DECISION));

        var result = new PraxisRuleSetEngine(registry).evaluate(
                plan, JSON.readTree("{\"request\":{\"recommendedAmount\":2500}}"), NOW, ZONE);

        assertEquals(RuleDecision.TECHNICAL_ERROR, result.decision());
        assertEquals(List.of("TRANSFORMATION_STAGE_INVALID"), result.reasonCodes());
        assertEquals(List.of(), result.transformationProposals());
    }

    @Test
    void rejectsProposalsWhenTransformationDoesNotApply() throws Exception {
        RuleBindingExecutorRegistry registry = registryForDecision(
                RuleDecision.NOT_APPLICABLE, List.of(draft("normalize.amount", "2000.00")));
        var plan = new PraxisRulePlanCompiler(registry).compile(definition());

        var result = new PraxisRuleSetEngine(registry).evaluate(
                plan, JSON.readTree("{\"request\":{\"recommendedAmount\":2500}}"), NOW, ZONE);

        assertEquals(RuleDecision.TECHNICAL_ERROR, result.decision());
        assertEquals(List.of("TRANSFORMATION_DECISION_INVALID"), result.reasonCodes());
        assertEquals(List.of(), result.transformationProposals());
    }

    @Test
    void rejectsDuplicateTargetsAndOversizedValuesAtTheEngineBoundary() throws Exception {
        var duplicate = draft("normalize.amount", "2000.00");
        var duplicateTarget = new TransformationDraft(
                "normalize.amount-again",
                duplicate.targetPath(),
                duplicate.schemaRef(),
                TransformationOperation.SET,
                duplicate.before(),
                duplicate.after(),
                "AMOUNT_NORMALIZED_AGAIN");
        RuleBindingExecutorRegistry duplicateRegistry = registry(
                new AtomicInteger(), List.of(duplicate, duplicateTarget));
        var duplicateResult = new PraxisRuleSetEngine(duplicateRegistry).evaluate(
                new PraxisRulePlanCompiler(duplicateRegistry).compile(definition()),
                JSON.readTree("{\"request\":{\"recommendedAmount\":2500}}"), NOW, ZONE);
        assertEquals(RuleDecision.TECHNICAL_ERROR, duplicateResult.decision());
        assertEquals(List.of("TRANSFORMATION_PROPOSAL_INVALID"), duplicateResult.reasonCodes());

        TransformationDraft oversized = new TransformationDraft(
                "normalize.notes",
                "request.notes",
                "urn:praxis:rule-lab:request:v1#/notes",
                TransformationOperation.SET,
                TransformationValue.absent(),
                TransformationValue.of(TransformationValueType.STRING, JSON.getNodeFactory()
                        .textNode("x".repeat(64_001))),
                "NOTES_NORMALIZED");
        RuleBindingExecutorRegistry oversizedRegistry = registry(new AtomicInteger(), List.of(oversized));
        var oversizedResult = new PraxisRuleSetEngine(oversizedRegistry).evaluate(
                new PraxisRulePlanCompiler(oversizedRegistry).compile(definition()),
                JSON.readTree("{\"request\":{\"recommendedAmount\":2500}}"), NOW, ZONE);
        assertEquals(RuleDecision.TECHNICAL_ERROR, oversizedResult.decision());
        assertEquals(List.of("TRANSFORMATION_PROPOSAL_INVALID"), oversizedResult.reasonCodes());
    }

    @Test
    void rejectsStaleBeforeValueAndTargetOutsideTheRuleSetRoots() throws Exception {
        TransformationDraft stale = new TransformationDraft(
                "normalize.amount",
                "request.recommendedAmount",
                "urn:praxis:rule-lab:request:v1#/recommendedAmount",
                TransformationOperation.SET,
                TransformationValue.of(TransformationValueType.NUMBER,
                        JSON.getNodeFactory().numberNode(9999)),
                TransformationValue.of(TransformationValueType.NUMBER,
                        JSON.getNodeFactory().numberNode(2000)),
                "AMOUNT_NORMALIZED");
        RuleBindingExecutorRegistry staleRegistry = registry(new AtomicInteger(), List.of(stale));
        var staleResult = new PraxisRuleSetEngine(staleRegistry).evaluate(
                new PraxisRulePlanCompiler(staleRegistry).compile(definition()),
                JSON.readTree("{\"request\":{\"recommendedAmount\":2500}}"), NOW, ZONE);
        assertEquals(RuleDecision.TECHNICAL_ERROR, staleResult.decision());
        assertEquals(List.of("TRANSFORMATION_PROPOSAL_INVALID"), staleResult.reasonCodes());

        TransformationDraft foreignRoot = new TransformationDraft(
                "normalize.amount",
                "account.recommendedAmount",
                "urn:praxis:rule-lab:account:v1#/recommendedAmount",
                TransformationOperation.SET,
                TransformationValue.absent(),
                TransformationValue.of(TransformationValueType.NUMBER,
                        JSON.getNodeFactory().numberNode(2000)),
                "AMOUNT_NORMALIZED");
        RuleBindingExecutorRegistry rootRegistry = registry(new AtomicInteger(), List.of(foreignRoot));
        var rootResult = new PraxisRuleSetEngine(rootRegistry).evaluate(
                new PraxisRulePlanCompiler(rootRegistry).compile(definition()),
                JSON.readTree("{\"request\":{\"recommendedAmount\":2500}}"), NOW, ZONE);
        assertEquals(RuleDecision.TECHNICAL_ERROR, rootResult.decision());
        assertEquals(List.of("TRANSFORMATION_PROPOSAL_INVALID"), rootResult.reasonCodes());
    }

    @Test
    void plannerRequiresTrustedJavaExecutorAndExplicitDecisionDependency() throws Exception {
        DecisionSlot slot = slot("request.transform", DecisionStage.TRANSFORMATION_INTENT);
        DecisionBinding jsonBinding = new DecisionBinding(
                "request.transform", "request.transform", DecisionSource.PRODUCT, null,
                RuleExecutorRef.jsonLogic(JSON.readTree("{\"===\":[true,true]}")),
                List.of(), 10, true, RuleDecision.NOT_APPLICABLE, "NO_TRANSFORM", List.of());
        RuleSetDefinition definition = baseDefinition(List.of(slot), List.of(jsonBinding));

        RulePlanException failure = assertThrows(RulePlanException.class,
                () -> new PraxisRulePlanCompiler(RuleBindingExecutorRegistry.empty()).compile(definition));

        assertEquals("PLAN_STAGE_INVALID", failure.getCode().name());
        assertTrue(failure.getMessage().contains("trusted Java executor"));

        DecisionBinding javaBinding = new DecisionBinding(
                "request.transform", "request.transform", DecisionSource.PRODUCT, null,
                RuleExecutorRef.java("benefits:amount-transform", "1.0.0"),
                List.of(), 10, true, null, null, List.of());
        RuleSetDefinition missingDependency = baseDefinition(List.of(slot), List.of(javaBinding));
        RuleBindingExecutorRegistry registry = registry(new AtomicInteger(), List.of(draft(
                "normalize.amount", "2000.00")));

        RulePlanException dependencyFailure = assertThrows(RulePlanException.class,
                () -> new PraxisRulePlanCompiler(registry).compile(missingDependency));

        assertEquals("PLAN_STAGE_INVALID", dependencyFailure.getCode().name());
        assertTrue(dependencyFailure.getMessage().contains("explicit decision dependency"));
    }

    @Test
    void distinguishesAbsentFromNullAndRejectsDeclaredTypeMismatch() {
        TransformationValue absent = TransformationValue.absent();
        TransformationValue explicitNull = TransformationValue.of(
                TransformationValueType.NULL, JSON.getNodeFactory().nullNode());

        assertNotEquals(absent.digest(), explicitNull.digest());
        assertThrows(IllegalArgumentException.class, () -> TransformationValue.of(
                TransformationValueType.NUMBER, JSON.getNodeFactory().textNode("2000")));
        assertThrows(IllegalArgumentException.class, () -> new TransformationDraft(
                "normalize.amount", "request[0].amount", "urn:test", TransformationOperation.SET,
                absent, explicitNull, "INVALID_PATH"));
        assertThrows(IllegalArgumentException.class, () -> new TransformationDraft(
                "normalize.amount", "request.amount", "urn:test", TransformationOperation.SET,
                absent, explicitNull, "INVALID_SCHEMA"));
        assertThrows(IllegalArgumentException.class, () -> new TransformationDraft(
                "normalize.amount", "request.amount", "urn:test#/amount", TransformationOperation.SET,
                explicitNull, explicitNull, "NO_CHANGE"));
    }

    @Test
    void publishesTheDeterministicBoundaryContractAsEngineBaselineOnePointFour() {
        assertEquals("1.4", RuleRuntimeCompatibility.ENGINE_CONTRACT_VERSION);
    }

    @Test
    void boundsTheAggregatedTransformationCollectionBeforeEnrichment() throws Exception {
        List<TransformationDraft> excessiveDrafts = java.util.stream.IntStream.range(0, 257)
                .mapToObj(index -> draft("normalize.amount." + index, Integer.toString(2_000 + index)))
                .toList();
        RuleBindingExecutorRegistry registry = registry(new AtomicInteger(), excessiveDrafts);
        var plan = new PraxisRulePlanCompiler(registry).compile(definition());
        JsonNode facts = JSON.readTree("""
                {"request":{"recommendedAmount":2500.00}}
                """);

        var result = new PraxisRuleSetEngine(registry).evaluate(plan, facts, NOW, ZONE);

        assertEquals(RuleDecision.TECHNICAL_ERROR, result.decision());
        assertEquals(List.of("IMPLEMENTATION_RESULT_LIMIT_EXCEEDED"), result.reasonCodes());
        assertTrue(result.transformationProposals().isEmpty());
    }

    private RuleBindingExecutorRegistry registry(AtomicInteger executions, List<TransformationDraft> drafts) {
        return registryForStage(executions, "benefits:amount-transform", drafts);
    }

    private RuleBindingExecutorRegistry registryForDecision(
            RuleDecision decision,
            List<TransformationDraft> drafts) {
        return new RuleBindingExecutorRegistry(List.of(new RuleBindingExecutor() {
            @Override
            public String implementationKey() {
                return "benefits:amount-transform";
            }

            @Override
            public String implementationVersion() {
                return "1.0.0";
            }

            @Override
            public RuleExecutorResult evaluate(RuleExecutorContext context) {
                return new RuleExecutorResult(decision, List.of("NO_NORMALIZATION"), null, drafts);
            }
        }));
    }

    private RuleBindingExecutorRegistry registryForStage(
            DecisionStage ignored,
            List<TransformationDraft> drafts) {
        return registryForStage(new AtomicInteger(), "benefits:stage-transform", drafts);
    }

    private RuleBindingExecutorRegistry registryForStage(
            AtomicInteger executions,
            String implementationKey,
            List<TransformationDraft> drafts) {
        return new RuleBindingExecutorRegistry(List.of(new RuleBindingExecutor() {
            @Override
            public String implementationKey() {
                return implementationKey;
            }

            @Override
            public String implementationVersion() {
                return "1.0.0";
            }

            @Override
            public RuleExecutorResult evaluate(RuleExecutorContext context) {
                executions.incrementAndGet();
                return new RuleExecutorResult(RuleDecision.ALLOW, List.of(), null, drafts);
            }
        }));
    }

    private RuleSetDefinition definition() throws Exception {
        DecisionSlot decisionSlot = slot("request.eligible", DecisionStage.DOMAIN_DECISION);
        DecisionSlot transformSlot = slot("request.amount-normalization", DecisionStage.TRANSFORMATION_INTENT);
        DecisionBinding decision = new DecisionBinding(
                "request.eligible", "request.eligible", DecisionSource.PRODUCT, null,
                RuleExecutorRef.jsonLogic(JSON.readTree("{\"===\":[true,true]}")),
                List.of(), 10, true, RuleDecision.DENY, "NOT_ELIGIBLE", List.of());
        DecisionBinding transform = new DecisionBinding(
                "request.amount-normalization", "request.amount-normalization", DecisionSource.PRODUCT, null,
                RuleExecutorRef.java("benefits:amount-transform", "1.0.0"),
                List.of("request.eligible"), 20, true, null, null, List.of("request.recommendedAmount"));
        return baseDefinition(List.of(decisionSlot, transformSlot), List.of(decision, transform));
    }

    private RuleSetDefinition javaOnlyDefinition(DecisionStage stage) {
        DecisionSlot slot = slot("request.transform", stage);
        DecisionBinding binding = new DecisionBinding(
                "request.transform", "request.transform", DecisionSource.PRODUCT, null,
                RuleExecutorRef.java("benefits:stage-transform", "1.0.0"),
                List.of(), 10, true, null, null, List.of());
        return baseDefinition(List.of(slot), List.of(binding));
    }

    private RuleSetDefinition baseDefinition(List<DecisionSlot> slots, List<DecisionBinding> bindings) {
        return new RuleSetDefinition(
                new RuleSetRef("benefits", "extraordinary", "grant", "evaluate", 1),
                List.of("request"), slots, bindings, RuleRuntimeCompatibility.current(), RuleFailPolicy.FAIL_CLOSED);
    }

    private DecisionSlot slot(String key, DecisionStage stage) {
        return new DecisionSlot(
                key, stage, SlotCardinality.SINGLE,
                OverridePolicy.FORBIDDEN, DecisionAggregationPolicy.SINGLE_RESULT);
    }

    private TransformationDraft draft(String key, String after) {
        return new TransformationDraft(
                key,
                "request.recommendedAmount",
                "urn:praxis:rule-lab:extraordinary-benefit-request:v1#/recommendedAmount",
                TransformationOperation.SET,
                TransformationValue.of(TransformationValueType.NUMBER,
                        JSON.getNodeFactory().numberNode(2500)),
                TransformationValue.of(TransformationValueType.NUMBER,
                        JSON.getNodeFactory().numberNode(new java.math.BigDecimal(after))),
                "AMOUNT_NORMALIZED");
    }
}
