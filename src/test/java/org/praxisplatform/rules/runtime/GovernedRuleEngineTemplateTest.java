package org.praxisplatform.rules.runtime;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.praxisplatform.rules.contract.*;
import org.praxisplatform.rules.plan.*;
import org.praxisplatform.rules.violation.*;

class GovernedRuleEngineTemplateTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String NOW = "2026-11-01T12:00:00Z";
    private final RuleErrorCatalog catalog = RuleErrorCatalog.of(Map.of("LIMIT", new RuleErrorDescriptor(
            "LIMIT", "amount", "O valor ultrapassa o limite permitido.", "policy:limit", "ELIGIBILITY")));
    private final GovernedRuleEngineTemplate template = new GovernedRuleEngineTemplate(RuleBindingExecutorRegistry.empty(), catalog);

    private static RuleSetDefinition definition(int version, RuleDecision falseDecision) throws Exception {
        return new RuleSetDefinition(new RuleSetRef("enterprise", "requests", "requests.limit", "create", version),
                List.of("draft"), List.of(new DecisionSlot("limit", DecisionStage.DOMAIN_DECISION,
                SlotCardinality.SINGLE, OverridePolicy.FORBIDDEN, DecisionAggregationPolicy.SINGLE_RESULT, null, java.util.List.of())),
                List.of(new DecisionBinding("limit.product", "limit", DecisionSource.PRODUCT, null,
                new RuleExecutorRef(RuleExecutorType.JSON_LOGIC, null, RuleRuntimeCompatibility.JSON_LOGIC_DIALECT_VERSION,
                        JSON.readTree("{\"==\":[{\"var\":\"draft.valid\"},true]}")),
                List.of(), 0, true, falseDecision, "LIMIT", List.of("draft.valid"), null)),
                RuleRuntimeCompatibility.current(), RuleFailPolicy.FAIL_CLOSED);
    }

    @Test void cachesIdenticalContentByExactScopeAndInvalidatesRetiredPlans() throws Exception {
        var definition = definition(1, RuleDecision.DENY);
        var first = template.compile(definition, "tenant-a");
        assertSame(first, template.compile(definition(1, RuleDecision.DENY), "tenant-a"));
        assertNotSame(first, template.compile(definition, "tenant-b"));
        assertNotEquals(first.planDigest(), template.compile(definition(2, RuleDecision.DENY), "tenant-a").planDigest());
        var changed = template.compile(definition(2, RuleDecision.INCONCLUSIVE), "tenant-a");
        assertNotEquals(first.planDigest(), changed.planDigest());
        template.invalidate("requests.limit", "tenant-a");
        assertNotSame(changed, template.compile(definition(2, RuleDecision.INCONCLUSIVE), "tenant-a"));
        template.clear();
        assertThrows(IllegalArgumentException.class, () -> template.compile(definition, " "));
    }

    @Test void concurrentCompilationAndEvaluationNeverShareFactsOrResults() throws Exception {
        var definition = definition(1, RuleDecision.DENY);
        var facts = JSON.readTree("{\"draft\":{\"valid\":true}}");
        try (var executor = Executors.newFixedThreadPool(8)) {
            var tasks = java.util.stream.IntStream.range(0, 40).<Callable<RuleDecisionPlan>>mapToObj(index -> () -> {
                var plan = template.compile(definition, "tenant-a");
                assertEquals(RuleDecision.ALLOW, template.enforce(plan, facts, NOW, "UTC").decision());
                return plan;
            }).toList();
            var results = executor.invokeAll(tasks);
            for (var result : results) assertSame(results.getFirst().get(), result.get());
        }
    }

    @Test void enforcesAllFiveStatesWithoutConvertingTechnicalOutcomesToDenial() throws Exception {
        var allow = template.compile(definition(1, RuleDecision.DENY), "a");
        assertEquals("ALLOW", template.toDiagnosticPayload(allow,
                template.enforce(allow, JSON.readTree("{\"draft\":{\"valid\":true}}"), NOW, "UTC")).get("decision"));
        for (var decision : List.of(RuleDecision.DENY, RuleDecision.NOT_APPLICABLE, RuleDecision.INCONCLUSIVE)) {
            var plan = template.compile(definition(1, decision), decision.name());
            var exception = assertThrows(PraxisRuleViolationException.class, () -> template.enforce(plan,
                    JSON.readTree("{\"draft\":{\"valid\":false}}"), NOW, "UTC"));
            assertEquals(decision, exception.getDecision());
            assertEquals(List.of("LIMIT"), exception.getReasonCodes());
            assertEquals(List.of("limit"), exception.getSlotKeys());
            assertEquals(List.of(DecisionSource.PRODUCT), exception.getDecisionSources());
            assertEquals("amount", exception.getViolations().getFirst().targetField());
        }
        var technical = assertThrows(PraxisRuleViolationException.class, () -> template.enforce(allow, null, NOW, "UTC"));
        assertEquals(RuleDecision.TECHNICAL_ERROR, technical.getDecision());
        assertEquals(List.of("FACTS_INVALID"), technical.getReasonCodes());
        assertTrue(technical.getSlotKeys().isEmpty());
        assertTrue(technical.getDecisionSources().isEmpty());
        assertNull(technical.getViolations().getFirst().targetField());
        var missingTime = template.evaluate(allow, JSON.readTree("{\"draft\":{\"valid\":true}}"), null, null);
        assertEquals(RuleDecision.TECHNICAL_ERROR, missingTime.decision());
    }

    @Test void invalidReplacementsFailAndDiagnosticsRejectAnotherPlan() throws Exception {
        var original = definition(1, RuleDecision.DENY);
        var plan = template.compile(original, "a");
        var invalid = new RuleSetDefinition(original.ref(), original.availableRoots(), original.slots(), original.bindings(),
                new RuleRuntimeCompatibility("unsupported", "1.0", RuleRuntimeCompatibility.JSON_LOGIC_CORPUS_SHA256), original.failPolicy());
        assertThrows(RulePlanException.class, () -> template.compile(invalid, "a"));
        var result = template.evaluate(plan, JSON.readTree("{\"draft\":{\"valid\":false}}"), NOW, "UTC");
        var other = template.compile(definition(2, RuleDecision.DENY), "b");
        assertThrows(IllegalArgumentException.class, () -> template.toDiagnosticPayload(other, result));
        var payload = template.toDiagnosticPayload(plan, result);
        assertFalse(payload.containsKey("facts"));
        assertFalse(payload.containsKey("bindingResults"));
        assertThrows(UnsupportedOperationException.class, () -> payload.put("facts", "private"));
    }
}
