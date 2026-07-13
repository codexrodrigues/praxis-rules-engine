package org.praxisplatform.rules.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.praxisplatform.rules.contract.CompositionPolicy;
import org.praxisplatform.rules.contract.DecisionBinding;
import org.praxisplatform.rules.contract.DecisionAggregationPolicy;
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
import org.praxisplatform.rules.plan.PraxisRulePlanCompiler;
import org.praxisplatform.rules.plan.RuleDecisionPlan;
import org.praxisplatform.rules.plan.RulePlanException;
import org.praxisplatform.rules.plan.RulePlanIssueCode;

class PraxisRuleSetEngineTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String NOW = "2026-07-13T15:00:00Z";
    private static final String ZONE = "America/Sao_Paulo";

    @Test
    void compilesDeterministicallyAndEvaluatesTheAllowPath() throws Exception {
        AtomicInteger calculations = new AtomicInteger();
        RuleBindingExecutorRegistry registry = registry(calculations, false);
        PraxisRulePlanCompiler compiler = new PraxisRulePlanCompiler(registry);

        RuleDecisionPlan first = compiler.compile(definition(false));
        RuleDecisionPlan second = compiler.compile(definition(true));

        assertEquals(first.planDigest(), second.planDigest());
        assertEquals(
                List.of("request.authorization", "worker.eligibility", "grant.calculation", "budget.availability"),
                first.orderedBindings().stream().map(DecisionBinding::bindingKey).toList());

        var result = new PraxisRuleSetEngine(registry).evaluate(first, eligibleFacts(), NOW, ZONE);

        assertEquals(RuleDecision.ALLOW, result.decision());
        assertEquals(4, result.bindingResults().size());
        assertEquals(1, calculations.get());
        assertEquals(RuleRuntimeCompatibility.current(), result.compatibility());
        assertEquals(
                List.of("benefits:amount-calculation@1.0.0"),
                result.implementationRefs().stream()
                        .map(ref -> ref.implementationKey() + "@" + ref.implementationVersion())
                        .toList());
        assertEquals("BRL", result.bindingResults().get(2).output().path("currency").asText());
        ObjectNode callerCopy = (ObjectNode) result.bindingResults().get(2).output();
        callerCopy.put("currency", "USD");
        assertEquals("BRL", result.bindingResults().get(2).output().path("currency").asText());

        ObjectNode reorderedFacts = (ObjectNode) JSON.readTree("""
                {
                  "budget": {"availableAmount": 100000.000},
                  "worker": {"status": "ACTIVE"},
                  "actor": {"permissions": ["benefit:request"]},
                  "request": {"requestedAmount": 2500.0}
                }
                """);
        var reorderedResult = new PraxisRuleSetEngine(registry).evaluate(first, reorderedFacts, NOW, ZONE);
        assertEquals(result.factsDigest(), reorderedResult.factsDigest());
    }

    @Test
    void protectedDenyShortCircuitsBeforeCalculation() throws Exception {
        AtomicInteger calculations = new AtomicInteger();
        RuleBindingExecutorRegistry registry = registry(calculations, false);
        RuleDecisionPlan plan = new PraxisRulePlanCompiler(registry).compile(definition(false));
        ObjectNode facts = eligibleFacts();
        facts.withObject("actor").putArray("permissions").add("benefit:read");

        var result = new PraxisRuleSetEngine(registry).evaluate(plan, facts, NOW, ZONE);

        assertEquals(RuleDecision.DENY, result.decision());
        assertEquals(List.of("REQUEST_NOT_AUTHORIZED"), result.reasonCodes());
        assertEquals(1, result.bindingResults().size());
        assertEquals(0, calculations.get());
    }

    @Test
    void missingRequiredFactIsInconclusiveAndNullRemainsPresent() throws Exception {
        RuleBindingExecutorRegistry registry = registry(new AtomicInteger(), false);
        RuleDecisionPlan plan = new PraxisRulePlanCompiler(registry).compile(definition(false));
        ObjectNode missing = eligibleFacts();
        missing.withObject("actor").remove("permissions");

        var missingResult = new PraxisRuleSetEngine(registry).evaluate(plan, missing, NOW, ZONE);
        assertEquals(RuleDecision.INCONCLUSIVE, missingResult.decision());
        assertEquals(List.of("FACT_REQUIRED_MISSING"), missingResult.reasonCodes());

        ObjectNode explicitNull = eligibleFacts();
        explicitNull.withObject("actor").set("permissions", JSON.nullNode());
        var nullResult = new PraxisRuleSetEngine(registry).evaluate(plan, explicitNull, NOW, ZONE);
        assertEquals(RuleDecision.DENY, nullResult.decision());
        assertEquals(List.of("REQUEST_NOT_AUTHORIZED"), nullResult.reasonCodes());
    }

    @Test
    void rejectsCycleMissingReferenceAndLaterStageDependency() throws Exception {
        RuleBindingExecutorRegistry registry = RuleBindingExecutorRegistry.empty();
        PraxisRulePlanCompiler compiler = new PraxisRulePlanCompiler(registry);
        DecisionSlot first = slot("first", DecisionStage.DOMAIN_DECISION, OverridePolicy.FORBIDDEN);
        DecisionSlot second = slot("second", DecisionStage.DOMAIN_DECISION, OverridePolicy.FORBIDDEN);
        DecisionBinding a = jsonBinding("a", "first", List.of("b"), RuleDecision.DENY, "A_FALSE");
        DecisionBinding b = jsonBinding("b", "second", List.of("a"), RuleDecision.DENY, "B_FALSE");

        RulePlanException cycle = assertThrows(
                RulePlanException.class,
                () -> compiler.compile(simpleDefinition(List.of(first, second), List.of(a, b))));
        assertEquals(RulePlanIssueCode.PLAN_CYCLE, cycle.getCode());

        DecisionBinding missing = jsonBinding("a", "first", List.of("unknown"), RuleDecision.DENY, "A_FALSE");
        RulePlanException missingReference = assertThrows(
                RulePlanException.class,
                () -> compiler.compile(simpleDefinition(List.of(first), List.of(missing))));
        assertEquals(RulePlanIssueCode.PLAN_REFERENCE_MISSING, missingReference.getCode());

        DecisionSlot post = slot("post", DecisionStage.POST_DECISION, OverridePolicy.FORBIDDEN);
        DecisionBinding postBinding = jsonBinding("post", "post", List.of(), RuleDecision.DENY, "POST_FALSE");
        DecisionBinding earlier = jsonBinding("earlier", "first", List.of("post"), RuleDecision.DENY, "EARLY_FALSE");
        RulePlanException laterStage = assertThrows(
                RulePlanException.class,
                () -> compiler.compile(simpleDefinition(List.of(first, post), List.of(earlier, postBinding))));
        assertEquals(RulePlanIssueCode.PLAN_DEPENDENCY_INVALID, laterStage.getCode());
    }

    @Test
    void rejectsInvalidCustomerOverrideAndUnavailableJavaImplementation() throws Exception {
        DecisionSlot forbidden = slot("protected", DecisionStage.DOMAIN_DECISION, OverridePolicy.FORBIDDEN);
        DecisionBinding customer = new DecisionBinding(
                "customer",
                "protected",
                DecisionSource.CUSTOMER,
                CompositionPolicy.RESTRICT,
                RuleExecutorRef.jsonLogic(expression("true")),
                List.of(),
                10,
                true,
                RuleDecision.DENY,
                "CUSTOMER_FALSE",
                List.of());
        RulePlanException override = assertThrows(
                RulePlanException.class,
                () -> new PraxisRulePlanCompiler(RuleBindingExecutorRegistry.empty())
                        .compile(simpleDefinition(List.of(forbidden), List.of(customer))));
        assertEquals(RulePlanIssueCode.PLAN_OVERRIDE_INVALID, override.getCode());

        DecisionBinding javaBinding = new DecisionBinding(
                "calculation",
                "protected",
                DecisionSource.PRODUCT,
                null,
                RuleExecutorRef.java("benefits:missing", "1.0.0"),
                List.of(),
                10,
                true,
                null,
                null,
                List.of());
        RulePlanException implementation = assertThrows(
                RulePlanException.class,
                () -> new PraxisRulePlanCompiler(RuleBindingExecutorRegistry.empty())
                        .compile(simpleDefinition(List.of(forbidden), List.of(javaBinding))));
        assertEquals(RulePlanIssueCode.PLAN_IMPLEMENTATION_UNAVAILABLE, implementation.getCode());

        DecisionSlot extensible = new DecisionSlot(
                "extensible",
                DecisionStage.DOMAIN_DECISION,
                SlotCardinality.MULTIPLE,
                OverridePolicy.RESTRICT_ONLY,
                DecisionAggregationPolicy.DENY_OVERRIDES);
        DecisionBinding augmentation = new DecisionBinding(
                "customer.augmentation",
                "extensible",
                DecisionSource.CUSTOMER,
                CompositionPolicy.AUGMENT,
                RuleExecutorRef.jsonLogic(expression("true")),
                List.of(),
                10,
                true,
                RuleDecision.DENY,
                "CUSTOMER_FALSE",
                List.of());
        new PraxisRulePlanCompiler(RuleBindingExecutorRegistry.empty())
                .compile(simpleDefinition(List.of(extensible), List.of(augmentation)));
    }

    @Test
    void rejectsIncompatibleRuntimeExecutorVersionAndUnknownExpressionRoot() throws Exception {
        DecisionSlot slot = slot("decision", DecisionStage.DOMAIN_DECISION, OverridePolicy.FORBIDDEN);
        DecisionBinding binding = jsonBinding(
                "decision", "decision", List.of(), RuleDecision.DENY, "DECISION_FALSE");
        RuleSetDefinition incompatible = new RuleSetDefinition(
                ref(),
                List.of("request"),
                List.of(slot),
                List.of(binding),
                new RuleRuntimeCompatibility(
                        "2.0",
                        RuleRuntimeCompatibility.JSON_LOGIC_DIALECT_VERSION,
                        RuleRuntimeCompatibility.JSON_LOGIC_CORPUS_SHA256),
                RuleFailPolicy.FAIL_CLOSED);
        RulePlanException runtime = assertThrows(
                RulePlanException.class,
                () -> new PraxisRulePlanCompiler(RuleBindingExecutorRegistry.empty()).compile(incompatible));
        assertEquals(RulePlanIssueCode.PLAN_COMPATIBILITY_INVALID, runtime.getCode());

        RuleBindingExecutorRegistry registry = registry(new AtomicInteger(), false);
        DecisionBinding wrongVersion = new DecisionBinding(
                "decision",
                "decision",
                DecisionSource.PRODUCT,
                null,
                RuleExecutorRef.java("benefits:amount-calculation", "2.0.0"),
                List.of(),
                10,
                true,
                null,
                null,
                List.of());
        RulePlanException executor = assertThrows(
                RulePlanException.class,
                () -> new PraxisRulePlanCompiler(registry)
                        .compile(simpleDefinition(List.of(slot), List.of(wrongVersion))));
        assertEquals(RulePlanIssueCode.PLAN_COMPATIBILITY_INVALID, executor.getCode());

        DecisionBinding unknownRoot = new DecisionBinding(
                "decision",
                "decision",
                DecisionSource.PRODUCT,
                null,
                RuleExecutorRef.jsonLogic(JSON.readTree(
                        "{\"===\":[{\"var\":\"worker.status\"},\"ACTIVE\"]}")),
                List.of(),
                10,
                true,
                RuleDecision.DENY,
                "DECISION_FALSE",
                List.of());
        RulePlanException root = assertThrows(
                RulePlanException.class,
                () -> new PraxisRulePlanCompiler(RuleBindingExecutorRegistry.empty())
                        .compile(simpleDefinition(List.of(slot), List.of(unknownRoot))));
        assertEquals(RulePlanIssueCode.PLAN_REFERENCE_MISSING, root.getCode());
    }

    @Test
    void acceptsCollectionLocalPathsButRejectsThemAtTheFactsRoot() throws Exception {
        DecisionSlot slot = slot("collection", DecisionStage.DOMAIN_DECISION, OverridePolicy.FORBIDDEN);
        DecisionBinding collection = new DecisionBinding(
                "collection",
                "collection",
                DecisionSource.PRODUCT,
                null,
                RuleExecutorRef.jsonLogic(JSON.readTree("""
                        {"some":[
                          {"var":"request.items"},
                          {"===":[{"var":"status"},"ACTIVE"]}
                        ]}
                        """)),
                List.of(),
                10,
                true,
                RuleDecision.DENY,
                "NO_ACTIVE_ITEM",
                List.of("request.items"));
        RuleDecisionPlan plan = new PraxisRulePlanCompiler(RuleBindingExecutorRegistry.empty())
                .compile(simpleDefinition(List.of(slot), List.of(collection)));
        JsonNode facts = JSON.readTree("""
                {"request":{"items":[{"status":"INACTIVE"},{"status":"ACTIVE"}]}}
                """);

        var result = new PraxisRuleSetEngine(RuleBindingExecutorRegistry.empty())
                .evaluate(plan, facts, NOW, ZONE);

        assertEquals(RuleDecision.ALLOW, result.decision());
    }

    @Test
    void independentDenyTakesPrecedenceOverAnEarlierInconclusiveBranch() throws Exception {
        DecisionSlot missingSlot = slot("missing", DecisionStage.DOMAIN_DECISION, OverridePolicy.FORBIDDEN);
        DecisionSlot denyingSlot = slot("denying", DecisionStage.DOMAIN_DECISION, OverridePolicy.FORBIDDEN);
        DecisionBinding missing = new DecisionBinding(
                "missing",
                "missing",
                DecisionSource.PRODUCT,
                null,
                RuleExecutorRef.jsonLogic(expression("true")),
                List.of(),
                10,
                true,
                RuleDecision.DENY,
                "MISSING_FALSE",
                List.of("request.requiredValue"));
        DecisionBinding denying = new DecisionBinding(
                "denying",
                "denying",
                DecisionSource.PRODUCT,
                null,
                RuleExecutorRef.jsonLogic(expression("false")),
                List.of(),
                20,
                true,
                RuleDecision.DENY,
                "INDEPENDENT_DENY",
                List.of());
        RuleDecisionPlan plan = new PraxisRulePlanCompiler(RuleBindingExecutorRegistry.empty())
                .compile(simpleDefinition(List.of(missingSlot, denyingSlot), List.of(missing, denying)));

        var result = new PraxisRuleSetEngine(RuleBindingExecutorRegistry.empty())
                .evaluate(plan, JSON.createObjectNode().putObject("request"), NOW, ZONE);

        assertEquals(RuleDecision.DENY, result.decision());
        assertEquals(List.of("INDEPENDENT_DENY"), result.reasonCodes());
        assertEquals(2, result.bindingResults().size());
    }

    @Test
    void separatesTechnicalFailureAndNotApplicableFromBusinessDeny() throws Exception {
        RuleBindingExecutorRegistry failingRegistry = registry(new AtomicInteger(), true);
        RuleDecisionPlan failingPlan = new PraxisRulePlanCompiler(failingRegistry).compile(definition(false));
        var failed = new PraxisRuleSetEngine(failingRegistry).evaluate(failingPlan, eligibleFacts(), NOW, ZONE);
        assertEquals(RuleDecision.TECHNICAL_ERROR, failed.decision());
        assertEquals(List.of("IMPLEMENTATION_EXECUTION_FAILED"), failed.reasonCodes());
        assertNotEquals(RuleDecision.DENY, failed.decision());

        DecisionSlot slot = slot("applicability", DecisionStage.DOMAIN_DECISION, OverridePolicy.FORBIDDEN);
        DecisionBinding binding = jsonBinding(
                "applicability", "applicability", List.of(), RuleDecision.NOT_APPLICABLE, "PROGRAM_NOT_APPLICABLE");
        RuleDecisionPlan notApplicablePlan = new PraxisRulePlanCompiler(RuleBindingExecutorRegistry.empty())
                .compile(simpleDefinition(List.of(slot), List.of(binding)));
        var notApplicable = new PraxisRuleSetEngine(RuleBindingExecutorRegistry.empty())
                .evaluate(notApplicablePlan, JSON.createObjectNode(), NOW, ZONE);
        assertEquals(RuleDecision.NOT_APPLICABLE, notApplicable.decision());
        assertEquals(List.of("PROGRAM_NOT_APPLICABLE"), notApplicable.bindingResults().getFirst().reasonCodes());
    }

    @Test
    void rejectsOversizedTrustedImplementationOutputAtTheEngineBoundary() {
        RuleBindingExecutorRegistry registry = new RuleBindingExecutorRegistry(List.of(new RuleBindingExecutor() {
            @Override
            public String implementationKey() {
                return "benefits:oversized-output";
            }

            @Override
            public String implementationVersion() {
                return "1.0.0";
            }

            @Override
            public RuleExecutorResult evaluate(RuleExecutorContext context) {
                return new RuleExecutorResult(
                        RuleDecision.ALLOW,
                        List.of(),
                        JSON.createObjectNode().put("value", "x".repeat(64_001)));
            }
        }));
        DecisionSlot slot = slot("calculation", DecisionStage.DOMAIN_DECISION, OverridePolicy.FORBIDDEN);
        DecisionBinding binding = new DecisionBinding(
                "calculation",
                "calculation",
                DecisionSource.PRODUCT,
                null,
                RuleExecutorRef.java("benefits:oversized-output", "1.0.0"),
                List.of(),
                10,
                true,
                null,
                null,
                List.of());
        RuleDecisionPlan plan = new PraxisRulePlanCompiler(registry)
                .compile(simpleDefinition(List.of(slot), List.of(binding)));

        var result = new PraxisRuleSetEngine(registry)
                .evaluate(plan, JSON.createObjectNode().putObject("request"), NOW, ZONE);

        assertEquals(RuleDecision.TECHNICAL_ERROR, result.decision());
        assertEquals(List.of("IMPLEMENTATION_RESULT_LIMIT_EXCEEDED"), result.reasonCodes());
    }

    @Test
    void effectIntentDoesNotExecuteBeforeAnAllowConsolidation() throws Exception {
        AtomicInteger effects = new AtomicInteger();
        RuleBindingExecutorRegistry registry = new RuleBindingExecutorRegistry(List.of(new RuleBindingExecutor() {
            @Override
            public String implementationKey() {
                return "benefits:effect-plan";
            }

            @Override
            public String implementationVersion() {
                return "1.0.0";
            }

            @Override
            public RuleExecutorResult evaluate(RuleExecutorContext context) {
                effects.incrementAndGet();
                return RuleExecutorResult.allow();
            }
        }));
        DecisionSlot decisionSlot = slot(
                "decision", DecisionStage.DOMAIN_DECISION, OverridePolicy.FORBIDDEN);
        DecisionSlot effectSlot = slot(
                "effect", DecisionStage.EFFECT_INTENT, OverridePolicy.FORBIDDEN);
        DecisionBinding inconclusive = new DecisionBinding(
                "decision",
                "decision",
                DecisionSource.PRODUCT,
                null,
                RuleExecutorRef.jsonLogic(expression("true")),
                List.of(),
                10,
                true,
                RuleDecision.DENY,
                "DECISION_FALSE",
                List.of("request.requiredValue"));
        DecisionBinding effect = new DecisionBinding(
                "effect",
                "effect",
                DecisionSource.PRODUCT,
                null,
                RuleExecutorRef.java("benefits:effect-plan", "1.0.0"),
                List.of(),
                20,
                true,
                null,
                null,
                List.of());
        RuleDecisionPlan plan = new PraxisRulePlanCompiler(registry)
                .compile(simpleDefinition(List.of(decisionSlot, effectSlot), List.of(inconclusive, effect)));

        var result = new PraxisRuleSetEngine(registry)
                .evaluate(plan, JSON.createObjectNode().putObject("request"), NOW, ZONE);

        assertEquals(RuleDecision.INCONCLUSIVE, result.decision());
        assertEquals(0, effects.get());
        assertEquals(List.of("PRIOR_DECISION_INCONCLUSIVE"), result.bindingResults().get(1).reasonCodes());
    }

    @Test
    void returnsTechnicalErrorForInvalidTemporalContextAndIsThreadSafe() throws Exception {
        RuleBindingExecutorRegistry registry = registry(new AtomicInteger(), false);
        RuleDecisionPlan plan = new PraxisRulePlanCompiler(registry).compile(definition(false));
        PraxisRuleSetEngine engine = new PraxisRuleSetEngine(registry);

        var invalidTime = engine.evaluate(plan, eligibleFacts(), "local-time", ZONE);
        assertEquals(RuleDecision.TECHNICAL_ERROR, invalidTime.decision());
        assertEquals(List.of("TEMPORAL_CONTEXT_INVALID"), invalidTime.reasonCodes());

        ObjectNode oversizedFacts = eligibleFacts();
        oversizedFacts.put("oversized", "x".repeat(64_001));
        var invalidFacts = engine.evaluate(plan, oversizedFacts, NOW, ZONE);
        assertEquals(RuleDecision.TECHNICAL_ERROR, invalidFacts.decision());
        assertEquals(List.of("FACTS_LIMIT_EXCEEDED"), invalidFacts.reasonCodes());

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = java.util.stream.IntStream.range(0, 50)
                    .mapToObj(index -> executor.submit(() -> engine.evaluate(plan, eligibleFacts(), NOW, ZONE)))
                    .toList();
            for (var task : tasks) {
                assertEquals(RuleDecision.ALLOW, task.get().decision());
                assertEquals(plan.planDigest(), task.get().planDigest());
            }
        }
    }

    private RuleSetDefinition definition(boolean reverseInputOrder) throws Exception {
        List<DecisionSlot> slots = List.of(
                slot("request.authorization", DecisionStage.PROTECTED_GUARD, OverridePolicy.FORBIDDEN),
                slot("worker.eligibility", DecisionStage.PROTECTED_GUARD, OverridePolicy.FORBIDDEN),
                slot("grant.calculation", DecisionStage.DOMAIN_DECISION, OverridePolicy.FORBIDDEN),
                slot("budget.availability", DecisionStage.POST_DECISION, OverridePolicy.RESTRICT_ONLY));
        DecisionBinding authorization = new DecisionBinding(
                "request.authorization",
                "request.authorization",
                DecisionSource.SECURITY,
                null,
                RuleExecutorRef.jsonLogic(JSON.readTree(
                        "{\"and\":[{\"!==\":[{\"var\":\"actor.permissions\"},null]},"
                                + "{\"in\":[\"benefit:request\",{\"var\":\"actor.permissions\"}]}]}")),
                List.of(),
                10,
                true,
                RuleDecision.DENY,
                "REQUEST_NOT_AUTHORIZED",
                List.of("actor.permissions"));
        DecisionBinding worker = new DecisionBinding(
                "worker.eligibility",
                "worker.eligibility",
                DecisionSource.PRODUCT,
                null,
                RuleExecutorRef.jsonLogic(JSON.readTree(
                        "{\"===\":[{\"var\":\"worker.status\"},\"ACTIVE\"]}")),
                List.of("request.authorization"),
                20,
                true,
                RuleDecision.DENY,
                "WORKER_NOT_ELIGIBLE",
                List.of("worker.status"));
        DecisionBinding calculation = new DecisionBinding(
                "grant.calculation",
                "grant.calculation",
                DecisionSource.PRODUCT,
                null,
                RuleExecutorRef.java("benefits:amount-calculation", "1.0.0"),
                List.of("worker.eligibility"),
                30,
                true,
                null,
                null,
                List.of("request.requestedAmount"));
        DecisionBinding budget = new DecisionBinding(
                "budget.availability",
                "budget.availability",
                DecisionSource.PRODUCT,
                null,
                RuleExecutorRef.jsonLogic(JSON.readTree(
                        "{\">=\":[{\"var\":\"budget.availableAmount\"},{\"var\":\"request.requestedAmount\"}]}")),
                List.of("grant.calculation"),
                40,
                true,
                RuleDecision.DENY,
                "BUDGET_INSUFFICIENT",
                List.of("budget.availableAmount", "request.requestedAmount"));
        List<DecisionBinding> bindings = reverseInputOrder
                ? List.of(budget, calculation, worker, authorization)
                : List.of(authorization, worker, calculation, budget);
        List<DecisionSlot> inputSlots = reverseInputOrder ? slots.reversed() : slots;
        return new RuleSetDefinition(
                ref(),
                List.of("request", "actor", "worker", "budget"),
                inputSlots,
                bindings,
                RuleRuntimeCompatibility.current(),
                RuleFailPolicy.FAIL_CLOSED);
    }

    private RuleSetDefinition simpleDefinition(List<DecisionSlot> slots, List<DecisionBinding> bindings) {
        return new RuleSetDefinition(
                ref(),
                List.of("request"),
                slots,
                bindings,
                RuleRuntimeCompatibility.current(),
                RuleFailPolicy.FAIL_CLOSED);
    }

    private RuleSetRef ref() {
        return new RuleSetRef(
                "workforce-benefits",
                "extraordinary-assistance",
                "extraordinary-grant-eligibility",
                "evaluate-extraordinary-grant",
                1);
    }

    private DecisionSlot slot(String key, DecisionStage stage, OverridePolicy policy) {
        return new DecisionSlot(
                key,
                stage,
                SlotCardinality.SINGLE,
                policy,
                DecisionAggregationPolicy.SINGLE_RESULT);
    }

    private DecisionBinding jsonBinding(
            String key,
            String slot,
            List<String> dependencies,
            RuleDecision falseDecision,
            String reason) throws Exception {
        return new DecisionBinding(
                key,
                slot,
                DecisionSource.PRODUCT,
                null,
                RuleExecutorRef.jsonLogic(expression("false")),
                dependencies,
                10,
                true,
                falseDecision,
                reason,
                List.of());
    }

    private JsonNode expression(String value) throws Exception {
        return JSON.readTree("{\"===\":[" + value + ",true]}");
    }

    private ObjectNode eligibleFacts() throws Exception {
        return (ObjectNode) JSON.readTree("""
                {
                  "request": {"requestedAmount": 2500.00},
                  "actor": {"permissions": ["benefit:request"]},
                  "worker": {"status": "ACTIVE"},
                  "budget": {"availableAmount": 100000.00}
                }
                """);
    }

    private RuleBindingExecutorRegistry registry(AtomicInteger calculations, boolean fail) {
        return new RuleBindingExecutorRegistry(List.of(new RuleBindingExecutor() {
            @Override
            public String implementationKey() {
                return "benefits:amount-calculation";
            }

            @Override
            public String implementationVersion() {
                return "1.0.0";
            }

            @Override
            public RuleExecutorResult evaluate(RuleExecutorContext context) {
                calculations.incrementAndGet();
                if (fail) {
                    throw new IllegalStateException("fixture failure");
                }
                BigDecimal requested = context.facts().path("request").path("requestedAmount").decimalValue();
                ObjectNode output = JSON.createObjectNode();
                output.put("amount", requested);
                output.put("currency", "BRL");
                return new RuleExecutorResult(RuleDecision.ALLOW, List.of(), output);
            }
        }));
    }
}
