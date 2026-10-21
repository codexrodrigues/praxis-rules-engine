package org.praxisplatform.rules.runtime;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.praxisplatform.rules.contract.*;
import org.praxisplatform.rules.digest.PraxisCanonicalJson;
import org.praxisplatform.rules.plan.*;

class DirectDependencyOutputsTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String NOW = "2026-10-20T12:00:00Z";
    private static final RuleSetRef REF = new RuleSetRef("product", "calculation", "chain", "evaluate", 1);

    @Test
    void chainsOnceWithOnlyDirectOutputsAndOriginalFactsInKeyOrder() {
        AtomicInteger calls = new AtomicInteger();
        var registry = registry(
                executor("a", context -> {
                    calls.incrementAndGet();
                    assertTrue(context.directDependencyOutputs().isEmpty());
                    return allow(JSON.createObjectNode().put("net", 42));
                }),
                executor("b", context -> {
                    calls.incrementAndGet();
                    assertEquals(42, context.directDependencyOutputs().get("a").path("net").intValue());
                    assertFalse(context.facts().has("a"));
                    return allow(JSON.createObjectNode().put("date", java.time.LocalDate.of(2026, 10, 20)
                            .plusDays(context.directDependencyOutputs().get("a").path("net").intValue() / 42).toString()));
                }),
                executor("c", context -> {
                    calls.incrementAndGet();
                    assertEquals(List.of("b", "z"), new ArrayList<>(context.directDependencyOutputs().keySet()));
                    assertFalse(context.directDependencyOutputs().containsKey("a"));
                    assertEquals(7, context.facts().path("request").intValue());
                    return RuleExecutorResult.allow();
                }),
                executor("z", context -> allow(JSON.nullNode())));
        var bindings = List.of(binding("c", List.of("z", "b")), binding("b", List.of("a")),
                binding("z", List.of()), binding("a", List.of()));
        var plan = compile(registry, bindings);
        assertEquals(List.of("b", "z"), plan.directDependencyOutputKeys("c"));
        assertEquals(List.of(), plan.directDependencyOutputKeys("unknown"));
        var result = evaluate(registry, plan, JSON.createObjectNode().put("request", 7));
        assertEquals(RuleDecision.ALLOW, result.decision());
        assertEquals(3, calls.get());
        assertEquals("2026-10-21", result.bindingResults().stream()
                .filter(item -> item.bindingKey().equals("b")).findFirst().orElseThrow().output().path("date").textValue());
        var reordered = compile(registry, bindings.reversed());
        assertEquals(plan.planDigest(), reordered.planDigest());
        assertEquals(result, evaluate(registry, reordered, JSON.createObjectNode().put("request", 7)));
    }

    @Test
    void distinguishesAbsentJavaOutputFromExplicitJsonNull() {
        var registry = registry(executor("absent", context -> RuleExecutorResult.allow()),
                executor("null", context -> allow(JSON.nullNode())), executor("consumer", context -> {
                    assertEquals(List.of("null"), new ArrayList<>(context.directDependencyOutputs().keySet()));
                    assertTrue(context.directDependencyOutputs().get("null").isNull());
                    return RuleExecutorResult.allow();
                }));
        assertEquals(RuleDecision.ALLOW, evaluate(registry, compile(registry, List.of(
                binding("absent", List.of()), binding("null", List.of()),
                binding("consumer", List.of("absent", "null")))), JSON.createObjectNode()).decision());
    }

    @Test
    void defensiveCopiesProtectInputsSiblingsResultsAndConcurrentEvaluations() throws Exception {
        ObjectNode output = JSON.createObjectNode();
        output.putObject("nested").putArray("values").add(42);
        var registry = registry(executor("a", context -> allow(output)),
                executor("b", context -> {
                    Map<String, JsonNode> exposed = context.directDependencyOutputs();
                    assertThrows(UnsupportedOperationException.class, () -> exposed.clear());
                    ((com.fasterxml.jackson.databind.node.ArrayNode) exposed.get("a").path("nested").path("values")).set(0, JSON.getNodeFactory().numberNode(-1));
                    ((ObjectNode) context.facts()).put("request", -1);
                    assertEquals(42, context.directDependencyOutputs().get("a").path("nested").path("values").get(0).intValue());
                    assertEquals(7, context.facts().path("request").intValue());
                    return RuleExecutorResult.allow();
                }), executor("c", context -> {
                    assertEquals(42, context.directDependencyOutputs().get("a").path("nested").path("values").get(0).intValue());
                    return RuleExecutorResult.allow();
                }));
        var plan = compile(registry, List.of(binding("a", List.of()), binding("b", List.of("a")), binding("c", List.of("a"))));
        ObjectNode facts = JSON.createObjectNode().put("request", 7);
        var engine = new PraxisRuleSetEngine(registry);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = java.util.stream.IntStream.range(0, 50)
                    .mapToObj(index -> pool.submit(() -> engine.evaluate(plan, facts, NOW, "UTC"))).toList();
            for (var task : tasks) {
                var result = task.get();
                assertEquals(RuleDecision.ALLOW, result.decision());
                ((com.fasterxml.jackson.databind.node.ArrayNode) result.bindingResults().getFirst().output()
                        .path("nested").path("values")).set(0, JSON.getNodeFactory().numberNode(-2));
                assertEquals(42, result.bindingResults().getFirst().output().path("nested").path("values").get(0).intValue());
            }
        }
        assertEquals(7, facts.path("request").intValue());
        assertEquals(42, output.path("nested").path("values").get(0).intValue());
        Map<String, JsonNode> inputs = new LinkedHashMap<>();
        inputs.put("a", output);
        var context = new RuleExecutorContext(REF, binding("b", List.of("a")), facts, inputs, NOW, "UTC");
        ((com.fasterxml.jackson.databind.node.ArrayNode) output.path("nested").path("values"))
                .set(0, JSON.getNodeFactory().numberNode(-3));
        inputs.clear();
        assertEquals(42, context.directDependencyOutputs().get("a").path("nested").path("values").get(0).intValue());
    }

    @Test
    void preservesAllFiveStatesAndBlocksConsumersExceptAfterAllow() {
        for (RuleDecision decision : RuleDecision.values()) {
            AtomicInteger consumers = new AtomicInteger();
            AtomicInteger independent = new AtomicInteger();
            var registry = registry(executor("a", context -> new RuleExecutorResult(decision,
                    decision == RuleDecision.ALLOW ? List.of() : List.of("PRODUCER_STATE"), JSON.createObjectNode().put("secret", 42))),
                    executor("b", context -> { consumers.incrementAndGet(); return RuleExecutorResult.allow(); }),
                    executor("z", context -> { independent.incrementAndGet(); return RuleExecutorResult.allow(); }));
            var result = evaluate(registry, compile(registry, List.of(binding("a", List.of()),
                    binding("b", List.of("a")), binding("z", List.of()))), JSON.createObjectNode());
            assertEquals(decision == RuleDecision.NOT_APPLICABLE ? RuleDecision.ALLOW : decision, result.decision());
            assertEquals(decision == RuleDecision.ALLOW ? 1 : 0, consumers.get());
            assertEquals(decision == RuleDecision.DENY || decision == RuleDecision.TECHNICAL_ERROR ? 0 : 1, independent.get());
            if (decision == RuleDecision.NOT_APPLICABLE || decision == RuleDecision.INCONCLUSIVE) {
                assertEquals(decision, result.bindingResults().get(1).decision());
            }
        }
    }

    @Test
    void exposesOnlyProductJavaDomainAndPostEdgesEvenForAttestedCustomerCode() {
        for (DecisionSource source : DecisionSource.values()) {
            for (DecisionStage stage : DecisionStage.values()) {
                // Intent producers cannot feed an earlier decision, and transformation outputs
                // are already prohibited. Test them as consumers, plus every legal producer.
                assertBoundary(source, stage, false);
                if (stage == DecisionStage.DOMAIN_DECISION || stage == DecisionStage.POST_DECISION
                        || stage == DecisionStage.PROTECTED_GUARD) {
                    assertBoundary(source, stage, true);
                }
            }
        }
    }

    private void assertBoundary(DecisionSource source, DecisionStage stage, boolean specialProducer) {
        if (source == DecisionSource.CUSTOMER && stage == DecisionStage.PROTECTED_GUARD) {
            return; // Existing protected-guard rejection is proved by ProtectedExtensionTrustContractTest.
        }
        DecisionBinding special = binding("special", specialProducer ? List.of() : List.of("ordinary"), source);
        DecisionBinding ordinary = binding("ordinary", specialProducer ? List.of("special") : List.of());
        var specialSlot = slot("special", stage, source);
        var ordinarySlot = slot("ordinary", !specialProducer && stage == DecisionStage.PROTECTED_GUARD
                ? DecisionStage.PROTECTED_GUARD : specialProducer && stage == DecisionStage.POST_DECISION
                ? DecisionStage.POST_DECISION : DecisionStage.DOMAIN_DECISION, DecisionSource.PRODUCT);
        boolean visible = source == DecisionSource.PRODUCT
                && (stage == DecisionStage.DOMAIN_DECISION || stage == DecisionStage.POST_DECISION);
        var registry = registry(executor("special", context -> {
            if (!specialProducer) {
                assertEquals(visible, context.directDependencyOutputs().containsKey("ordinary"));
            }
            if (stage == DecisionStage.TRANSFORMATION_INTENT) {
                return RuleExecutorResult.of(RuleDecision.NOT_APPLICABLE, "NO_TRANSFORMATION");
            }
            return allow(JSON.createObjectNode().put("secret", 42));
        }, source == DecisionSource.CUSTOMER), executor("ordinary", context -> {
            if (specialProducer) {
                assertEquals(visible, context.directDependencyOutputs().containsKey("special"));
            }
            return allow(JSON.createObjectNode().put("value", 7));
        }));
        var plan = new PraxisRulePlanCompiler(registry).compile(definition(List.of(specialSlot, ordinarySlot), List.of(special, ordinary)));
        assertEquals(visible ? List.of(specialProducer ? "special" : "ordinary") : List.of(),
                plan.directDependencyOutputKeys(specialProducer ? "ordinary" : "special"));
        assertNotEquals(RuleDecision.TECHNICAL_ERROR, evaluate(registry, plan, JSON.createObjectNode()).decision());
    }

    @Test
    void jsonLogicReadsOnlyOriginalFactsDespiteDirectJavaDependency() throws Exception {
        var registry = registry(executor("a", context -> allow(JSON.createObjectNode().put("value", 42))));
        var json = new DecisionBinding("b", "b", DecisionSource.PRODUCT, null,
                RuleExecutorRef.jsonLogic(JSON.readTree("{\"===\":[{\"var\":[\"request.a\",\"absent\"]},\"absent\"]}")),
                List.of("a"), 1, true, RuleDecision.DENY, "LEAK", List.of(), null);
        var plan = compile(registry, List.of(binding("a", List.of()), json));
        assertEquals(List.of(), plan.directDependencyOutputKeys("b"));
        assertEquals(RuleDecision.ALLOW, evaluate(registry, plan, JSON.createObjectNode()).decision());
    }

    @Test
    void rejectsHistorical14CompatibilityWithoutRewritingIt() {
        var definition = definition(List.of(slot("a", DecisionStage.DOMAIN_DECISION, DecisionSource.PRODUCT)), List.of(binding("a", List.of())));
        var historical = new RuleSetDefinition(definition.ref(), definition.availableRoots(), definition.slots(), definition.bindings(),
                new RuleRuntimeCompatibility("1.4", RuleRuntimeCompatibility.JSON_LOGIC_DIALECT_VERSION,
                        RuleRuntimeCompatibility.JSON_LOGIC_CORPUS_SHA256), definition.failPolicy());
        var exception = assertThrows(RulePlanException.class, () -> new PraxisRulePlanCompiler(
                registry(executor("a", context -> RuleExecutorResult.allow()))).compile(historical));
        assertEquals(RulePlanIssueCode.PLAN_COMPATIBILITY_INVALID, exception.getCode());
        assertEquals("1.4", historical.compatibility().engineContractVersion());
        assertEquals("1.6", RuleRuntimeCompatibility.current().engineContractVersion());
    }

    @Test
    void transportAcceptsExactCanonicalContextLimitAndRejectsOneMoreByteBeforeInvocation() {
        for (int extra = 0; extra <= 1; extra++) {
            AtomicInteger consumers = new AtomicInteger();
            JsonNode output = DecimalNode.valueOf(new BigDecimal("1E+" + (255_993 + extra)));
            var registry = registry(executor("a", context -> allow(output)), executor("b", context -> {
                consumers.incrementAndGet();
                return RuleExecutorResult.allow();
            }));
            var result = evaluate(registry, compile(registry, List.of(binding("a", List.of()), binding("b", List.of("a")))), JSON.createObjectNode());
            if (extra == 0) {
                assertEquals(RuleDecision.ALLOW, result.decision());
                assertEquals(1, consumers.get());
            } else {
                assertTransportLimit(result);
                assertEquals(0, consumers.get());
            }
        }
    }

    @Test
    void chargesFanOutAgainAndCumulativeMapsEvenWhenEachContextFits() {
        AtomicInteger consumers = new AtomicInteger();
        var registry = registry(executor("a", context -> allow(DecimalNode.valueOf(new BigDecimal("1E+128000")))),
                executor("b", context -> { consumers.incrementAndGet(); return RuleExecutorResult.allow(); }),
                executor("c", context -> { consumers.incrementAndGet(); return RuleExecutorResult.allow(); }));
        var result = evaluate(registry, compile(registry, List.of(binding("a", List.of()), binding("b", List.of("a")),
                binding("c", List.of("a")))), JSON.createObjectNode());
        assertTransportLimit(result);
        assertEquals(1, consumers.get());
    }

    @Test
    void boundedFanOutChargesEveryDeliveredCopyAndStillRejectsExcessiveGraphFanOut() {
        AtomicInteger calls = new AtomicInteger();
        var registry = registry(executor("a", context -> allow(JSON.getNodeFactory().textNode("x".repeat(2_000)))),
                executor("consumer", context -> { calls.incrementAndGet(); return RuleExecutorResult.allow(); }));
        List<DecisionBinding> bindings = new ArrayList<>();
        bindings.add(binding("a", List.of()));
        for (int index = 0; index < 128; index++) {
            String key = "consumer." + index;
            bindings.add(new DecisionBinding(key, key, DecisionSource.PRODUCT, null,
                    RuleExecutorRef.java("test:consumer", "1"), List.of("a"), 0, true, null, null, List.of(), null));
        }
        var result = evaluate(registry, compile(registry, bindings), JSON.createObjectNode());
        assertTransportLimit(result);
        assertEquals(127, calls.get()); // 127 * 2008 fits; the 128th delivery would exceed 256000.
        bindings.add(new DecisionBinding("consumer.extra", "consumer.extra", DecisionSource.PRODUCT, null,
                RuleExecutorRef.java("test:consumer", "1"), List.of("a"), 0, true, null, null, List.of(), null));
        assertEquals(RulePlanIssueCode.PLAN_LIMIT_EXCEEDED,
                assertThrows(RulePlanException.class, () -> compile(registry, bindings)).getCode());
    }

    @Test
    void factsAndTrustGatesDoNotConsumeTransportBudget() {
        AtomicInteger consumers = new AtomicInteger();
        var registry = registry(executor("a", context -> allow(DecimalNode.valueOf(new BigDecimal("1E+255993")))),
                executor("b", context -> fail("missing facts must gate invocation")),
                executor("c", context -> { consumers.incrementAndGet(); return RuleExecutorResult.allow(); }));
        DecisionBinding missing = new DecisionBinding("b", "b", DecisionSource.PRODUCT, null,
                RuleExecutorRef.java("test:b", "1"), List.of("a"), 0, true, null, null, List.of("request.required"), null);
        var result = evaluate(registry, compile(registry, List.of(binding("a", List.of()), missing, binding("c", List.of("a")))), JSON.createObjectNode());
        assertEquals(RuleDecision.INCONCLUSIVE, result.decision());
        assertEquals(1, consumers.get());
        // A substituted implementation fails trust before it receives any output context.
        var plan = compile(registry, List.of(binding("a", List.of()), binding("b", List.of("a"))));
        var substituted = registry(executor("a", context -> allow(DecimalNode.valueOf(new BigDecimal("1E+255994")))),
                new RuleBindingExecutor() {
                    public String implementationKey() { return "test:b"; }
                    public String implementationVersion() { return "2"; }
                    public RuleExecutorResult evaluate(RuleExecutorContext context) { return fail("trust gate"); }
                });
        var denied = evaluate(substituted, plan, JSON.createObjectNode());
        assertEquals(List.of("IMPLEMENTATION_TRUST_MISMATCH"), denied.reasonCodes());
    }

    @Test
    void invalidNonJsonTransportFailsBeforeConsumerWithoutChangingControlOnlyEdges() {
        var invalidValues = List.<JsonNode>of(
                JSON.getNodeFactory().binaryNode(new byte[] {1, 2}),
                JSON.getNodeFactory().pojoNode(new java.util.ArrayList<>(List.of("mutable"))),
                JSON.getNodeFactory().missingNode(),
                JSON.getNodeFactory().numberNode(Double.NaN),
                JSON.getNodeFactory().numberNode(Double.POSITIVE_INFINITY),
                JSON.getNodeFactory().numberNode(Float.NEGATIVE_INFINITY));
        for (JsonNode value : invalidValues) {
            for (boolean nested : List.of(false, true)) {
                JsonNode output = nested ? JSON.createObjectNode().set("nested", value) : value;
                AtomicInteger calls = new AtomicInteger();
                var registry = registry(executor("a", context -> allow(output)), executor("b", context -> {
                    calls.incrementAndGet();
                    assertTrue(context.directDependencyOutputs().isEmpty());
                    return RuleExecutorResult.allow();
                }));
                var plan = compile(registry, List.of(binding("a", List.of()), binding("b", List.of("a"))));
                var result = evaluate(registry, plan, JSON.createObjectNode());
                assertEquals(RuleDecision.TECHNICAL_ERROR, result.decision(), value.getNodeType().name());
                assertEquals(List.of("DEPENDENCY_OUTPUT_INVALID"), result.reasonCodes());
                assertTrue(result.bindingResults().isEmpty());
                assertEquals(0, calls.get());
                assertThrows(IllegalArgumentException.class, () -> new RuleExecutorContext(REF,
                        binding("b", List.of("a")), JSON.createObjectNode(), Map.of("a", output), NOW, "UTC"));
                // SECURITY Java is still a legal control dependency and supplies no output.
                var control = new DecisionBinding("a", "a", DecisionSource.SECURITY, null,
                        RuleExecutorRef.java("test:a", "1"), List.of(), 0, true, null, null, List.of(), null);
                var controlResult = evaluate(registry, compile(registry, List.of(control, binding("b", List.of("a")))), JSON.createObjectNode());
                assertEquals(RuleDecision.ALLOW, controlResult.decision());
                assertEquals(1, calls.get());
            }
        }
    }

    @Test
    void retainedPublicResultLimitStillWinsBeforeFurtherTransport() {
        var large = JSON.createArrayNode().add("x".repeat(60_000)).add("x".repeat(60_000)).add("x".repeat(20_000));
        var registry = registry(executor("a", context -> allow(large)), executor("b", context -> allow(large)),
                executor("c", context -> fail("retained result must fail before consumer")));
        var result = evaluate(registry, compile(registry, List.of(binding("a", List.of()), binding("b", List.of()),
                binding("c", List.of("a", "b")))), JSON.createObjectNode());
        assertEquals(RuleDecision.TECHNICAL_ERROR, result.decision());
        assertEquals(List.of("EVALUATION_RESULT_LIMIT_EXCEEDED"), result.reasonCodes());
        assertTrue(result.bindingResults().isEmpty());
    }

    @Test
    void cumulativeBudgetIncludesDifferentProducerMapsAndEmptyContextsCostZero() {
        AtomicInteger calls = new AtomicInteger();
        var registry = registry(executor("a", context -> allow(DecimalNode.valueOf(new BigDecimal("1E+128000")))),
                executor("b", context -> { calls.incrementAndGet(); return RuleExecutorResult.allow(); }),
                executor("c", context -> allow(DecimalNode.valueOf(new BigDecimal("1E+128000")))),
                executor("d", context -> { calls.incrementAndGet(); return RuleExecutorResult.allow(); }));
        var result = evaluate(registry, compile(registry, List.of(binding("a", List.of()), binding("b", List.of("a")),
                binding("c", List.of()), binding("d", List.of("c")))), JSON.createObjectNode());
        assertTransportLimit(result);
        assertEquals(1, calls.get());
    }

    @Test
    void cappedCounterMatchesCanonicalUtf8IncludingEscapesUnicodeNumbersAndEmptyMaps() throws Exception {
        var values = List.of(JSON.readTree("{\"z\":[null,true,false,1.2300,-0.001,1E+3],\"a\":\"é中😀\\n\\\\\\\"\\u0001\"}"),
                JSON.getNodeFactory().textNode("\uD800"), JSON.getNodeFactory().textNode("\uDC00"));
        for (JsonNode value : values) {
            var envelope = JSON.createObjectNode().set("é\n😀", value);
            int bytes = PraxisCanonicalJson.canonicalize(envelope).getBytes(StandardCharsets.UTF_8).length;
            Map<String, JsonNode> outputs = Map.of("é\n😀", value);
            RuleJsonBudget exact = budgetWithRemaining(bytes);
            exact.reserve(outputs);
            assertThrows(RuleJsonBudget.LimitExceeded.class, () -> budgetWithRemaining(bytes - 1).reserve(outputs));
            exact.reserve(Map.of()); // empty context remains free even at cumulative exhaustion
        }
        assertThrows(RuleJsonBudget.LimitExceeded.class, () -> new RuleJsonBudget()
                .reserve(Map.of("a", DecimalNode.valueOf(new BigDecimal("1E+2147483647")))));
        assertThrows(RuleJsonBudget.LimitExceeded.class, () -> new RuleJsonBudget()
                .reserve(Map.of("a", DecimalNode.valueOf(new BigDecimal("1E-2147483647")))));
        var finiteExtreme = DecimalNode.valueOf(new BigDecimal(java.math.BigInteger.TEN, Integer.MIN_VALUE));
        assertDoesNotThrow(() -> new RuleExecutorContext(REF, binding("b", List.of("a")),
                JSON.createObjectNode(), Map.of("a", finiteExtreme), NOW, "UTC"));
        assertThrows(RuleJsonBudget.LimitExceeded.class, () -> new RuleJsonBudget()
                .reserve(Map.of("a", finiteExtreme)));
        var extremeRegistry = registry(executor("a", context -> allow(finiteExtreme)),
                executor("b", context -> fail("oversized finite transport must gate invocation")));
        assertTransportLimit(evaluate(extremeRegistry, compile(extremeRegistry,
                List.of(binding("a", List.of()), binding("b", List.of("a")))), JSON.createObjectNode()));
    }

    @Test
    void invalidOutputDiagnosticIsIndependentOfObjectInsertionOrderAndBudget() {
        ObjectNode first = JSON.createObjectNode();
        first.set("large", DecimalNode.valueOf(new BigDecimal("1E+255993")));
        first.set("invalid", JSON.getNodeFactory().binaryNode(new byte[] {1}));
        ObjectNode reversed = JSON.createObjectNode();
        reversed.set("invalid", first.get("invalid"));
        reversed.set("large", first.get("large"));
        assertEquals(first, reversed);
        for (ObjectNode value : List.of(first, reversed)) {
            var registry = registry(executor("a", context -> allow(value)),
                    executor("b", context -> fail("invalid transport must not invoke consumer")));
            var result = evaluate(registry, compile(registry, List.of(binding("a", List.of()),
                    binding("b", List.of("a")))), JSON.createObjectNode());
            assertEquals(RuleDecision.TECHNICAL_ERROR, result.decision());
            assertEquals(List.of("DEPENDENCY_OUTPUT_INVALID"), result.reasonCodes());
            assertTrue(result.bindingResults().isEmpty());
        }
    }

    @Test
    void freezesCallerFactsOnceForJavaJsonLogicAndDigest() throws Exception {
        ObjectNode callerFacts = JSON.createObjectNode().put("request", 7);
        String expectedDigest = PraxisCanonicalJson.sha256(callerFacts);
        var registry = registry(executor("a", context -> {
                    // Deterministic interleaving: the caller updates its own draft after capture.
                    callerFacts.put("request", 99);
                    return allow(context.facts().get("request"));
                }), executor("b", context -> allow(context.facts().get("request"))));
        var check = new DecisionBinding("z", "z", DecisionSource.PRODUCT, null,
                RuleExecutorRef.jsonLogic(JSON.readTree("{\"===\":[{\"var\":\"request\"},7]}")),
                List.of("b"), 0, true, RuleDecision.DENY, "FACTS_CHANGED", List.of(), null);
        var result = evaluate(registry, compile(registry, List.of(binding("a", List.of()),
                binding("b", List.of("a")), check)), callerFacts);
        assertEquals(RuleDecision.ALLOW, result.decision());
        assertEquals(expectedDigest, result.factsDigest());
        assertEquals(7, result.bindingResults().get(0).output().intValue());
        assertEquals(7, result.bindingResults().get(1).output().intValue());
        assertEquals(99, callerFacts.get("request").intValue());
    }

    @Test
    void rejectsRestoredPlanWithIncompatibleCoordinatesBeforeInvokingExecutors() {
        AtomicInteger calls = new AtomicInteger();
        var registry = registry(executor("a", context -> {
            calls.incrementAndGet();
            return RuleExecutorResult.allow();
        }));
        var current = compile(registry, List.of(binding("a", List.of())));
        var accepted = RuleRuntimeCompatibility.current();
        for (var incompatible : List.of(
                new RuleRuntimeCompatibility("1.4", accepted.jsonLogicDialectVersion(), accepted.jsonLogicCorpusSha256()),
                new RuleRuntimeCompatibility(accepted.engineContractVersion(), "9.0", accepted.jsonLogicCorpusSha256()),
                new RuleRuntimeCompatibility(accepted.engineContractVersion(), accepted.jsonLogicDialectVersion(), "A".repeat(64)))) {
            var definition = new RuleSetDefinition(current.definition().ref(), current.definition().availableRoots(),
                    current.definition().slots(), current.definition().bindings(), incompatible, current.definition().failPolicy());
            var restored = new RuleDecisionPlan(definition, current.orderedBindings(), current.slotsByKey(),
                    current.implementationRefs(), current.planDigest());
            var result = evaluate(registry, restored, JSON.createObjectNode());
            assertEquals(RuleDecision.TECHNICAL_ERROR, result.decision());
            assertEquals(List.of("PLAN_COMPATIBILITY_INVALID"), result.reasonCodes());
            assertEquals(accepted, result.compatibility());
            assertTrue(result.bindingResults().isEmpty());
            assertNull(result.factsDigest());
            assertEquals(0, calls.get());
        }
    }

    @Test
    void rejectsNonNativeOrNonFiniteFactsBeforeHashingOrInvokingExecutors() {
        var registry = registry(executor("a", context -> fail("invalid facts must not execute")));
        var plan = compile(registry, List.of(binding("a", List.of())));
        for (JsonNode value : List.of(JSON.getNodeFactory().binaryNode(new byte[] {1}),
                JSON.getNodeFactory().pojoNode(new java.util.ArrayList<>(List.of("mutable"))),
                JSON.getNodeFactory().pojoNode(new Object()),
                JSON.getNodeFactory().missingNode(), JSON.getNodeFactory().numberNode(Double.NaN),
                JSON.getNodeFactory().numberNode(Double.POSITIVE_INFINITY))) {
            var facts = JSON.createObjectNode().set("request", value);
            var result = assertDoesNotThrow(() -> evaluate(registry, plan, facts));
            assertEquals(RuleDecision.TECHNICAL_ERROR, result.decision());
            assertEquals(List.of("FACTS_INVALID"), result.reasonCodes());
            assertNull(result.factsDigest());
            assertTrue(result.bindingResults().isEmpty());
            assertThrows(IllegalArgumentException.class, () -> new RuleExecutorContext(REF,
                    binding("a", List.of()), facts, Map.of(), NOW, "UTC"));
        }
    }

    @Test
    void boundsCanonicalFactsBeforeHashExpansionWithoutChargingOutputTransportBudget() {
        AtomicInteger calls = new AtomicInteger();
        var registry = registry(executor("a", context -> {
                    calls.incrementAndGet();
                    return allow(DecimalNode.valueOf(new BigDecimal("1E+255993")));
                }), executor("b", context -> { calls.incrementAndGet(); return RuleExecutorResult.allow(); }));
        var plan = compile(registry, List.of(binding("a", List.of()), binding("b", List.of("a"))));
        // {"request":1E+n} has n + 13 bytes when the number is in canonical plain form.
        var exact = JSON.createObjectNode().set("request", DecimalNode.valueOf(new BigDecimal("1E+255987")));
        var result = evaluate(registry, plan, exact);
        assertEquals(RuleDecision.ALLOW, result.decision());
        assertEquals(2, calls.get());
        assertEquals(PraxisCanonicalJson.sha256(exact), result.factsDigest());
        for (BigDecimal value : List.of(new BigDecimal("1E+255988"), new BigDecimal("1E+30000000"),
                new BigDecimal(java.math.BigInteger.TEN, Integer.MIN_VALUE))) {
            var facts = JSON.createObjectNode().set("request", DecimalNode.valueOf(value));
            var rejected = assertDoesNotThrow(() -> evaluate(registry, plan, facts));
            assertEquals(RuleDecision.TECHNICAL_ERROR, rejected.decision());
            assertEquals(List.of("FACTS_LIMIT_EXCEEDED"), rejected.reasonCodes());
            assertNull(rejected.factsDigest());
            assertTrue(rejected.bindingResults().isEmpty());
            assertEquals(2, calls.get());
        }
    }

    @Test
    void isolatesDistinctConcurrentRequestsWithoutClaimingHostTenantAuthorization() throws Exception {
        var registry = registry(executor("a", context -> allow(context.facts().get("request"))),
                executor("b", context -> allow(context.directDependencyOutputs().get("a"))));
        var plan = compile(registry, List.of(binding("a", List.of()), binding("b", List.of("a"))));
        var engine = new PraxisRuleSetEngine(registry);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = java.util.stream.IntStream.range(0, 64).mapToObj(index -> pool.submit(() -> {
                ObjectNode facts = JSON.createObjectNode();
                facts.putObject("request").put("scope", "synthetic-" + index).put("value", index);
                var result = engine.evaluate(plan, facts, NOW, "UTC");
                assertEquals(RuleDecision.ALLOW, result.decision());
                assertEquals(PraxisCanonicalJson.sha256(facts), result.factsDigest());
                assertEquals(facts.get("request"), result.bindingResults().get(1).output());
                return result.factsDigest();
            })).toList();
            java.util.Set<String> digests = new java.util.HashSet<>();
            for (var task : tasks) digests.add(task.get());
            assertEquals(64, digests.size());
        }
    }

    private static RuleJsonBudget budgetWithRemaining(int remaining) {
        var budget = new RuleJsonBudget();
        int used = RuleEngineLimits.MAX_PUBLIC_RESULT_BYTES - remaining;
        // {"a":1E+n} has n+7 canonical bytes.
        budget.reserve(Map.of("a", DecimalNode.valueOf(new BigDecimal("1E+" + (used - 7)))));
        return budget;
    }

    private static void assertTransportLimit(RuleEvaluationResult result) {
        assertEquals(RuleDecision.TECHNICAL_ERROR, result.decision());
        assertEquals(List.of("DEPENDENCY_OUTPUT_LIMIT_EXCEEDED"), result.reasonCodes());
        assertTrue(result.bindingResults().isEmpty());
        assertTrue(result.transformationProposals().isEmpty());
    }

    private static RuleExecutorResult allow(JsonNode output) {
        return new RuleExecutorResult(RuleDecision.ALLOW, List.of(), output);
    }

    private static RuleBindingExecutorRegistry registry(RuleBindingExecutor... executors) {
        return new RuleBindingExecutorRegistry(List.of(executors));
    }

    private static RuleBindingExecutor executor(String key, Function<RuleExecutorContext, RuleExecutorResult> callback) {
        return executor(key, callback, false);
    }

    private static RuleBindingExecutor executor(String key, Function<RuleExecutorContext, RuleExecutorResult> callback, boolean attested) {
        return new RuleBindingExecutor() {
            public String implementationKey() { return "test:" + key; }
            public String implementationVersion() { return "1"; }
            public RuleExtensionTrust extensionTrust() {
                return attested ? new RuleExtensionTrust("A".repeat(64), "sigstore:customer", "policy:customer", "B".repeat(64)) : null;
            }
            public RuleExecutorResult evaluate(RuleExecutorContext context) { return callback.apply(context); }
        };
    }

    private static DecisionBinding binding(String key, List<String> dependencies) {
        return binding(key, dependencies, DecisionSource.PRODUCT);
    }

    private static DecisionBinding binding(String key, List<String> dependencies, DecisionSource source) {
        return new DecisionBinding(key, key, source, source == DecisionSource.CUSTOMER ? CompositionPolicy.REPLACE_EXACT : null,
                RuleExecutorRef.java("test:" + key, "1"), dependencies, 0, true, null, null, List.of(), null);
    }

    private static DecisionSlot slot(String key, DecisionStage stage, DecisionSource source) {
        return new DecisionSlot(key, stage, SlotCardinality.SINGLE,
                source == DecisionSource.CUSTOMER ? OverridePolicy.REPLACEABLE : OverridePolicy.FORBIDDEN,
                DecisionAggregationPolicy.SINGLE_RESULT, null);
    }

    private static RuleSetDefinition definition(List<DecisionSlot> slots, List<DecisionBinding> bindings) {
        return new RuleSetDefinition(REF, List.of("request"), slots, bindings, RuleRuntimeCompatibility.current(), RuleFailPolicy.FAIL_CLOSED);
    }

    private static RuleDecisionPlan compile(RuleBindingExecutorRegistry registry, List<DecisionBinding> bindings) {
        return new PraxisRulePlanCompiler(registry).compile(definition(bindings.stream()
                .map(binding -> slot(binding.slotKey(), DecisionStage.DOMAIN_DECISION, binding.source())).toList(), bindings));
    }

    private static RuleEvaluationResult evaluate(RuleBindingExecutorRegistry registry, RuleDecisionPlan plan, JsonNode facts) {
        return new PraxisRuleSetEngine(registry).evaluate(plan, facts, NOW, "UTC");
    }
}
