package org.praxisplatform.rules.violation;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.praxisplatform.rules.contract.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.praxisplatform.rules.plan.*;
import org.praxisplatform.rules.runtime.*;

class PraxisRuleViolationExceptionTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static DecisionBinding binding(String key, RuleDecision decision, String code, int order, List<String> dependencies) throws Exception {
        return new DecisionBinding(key, key, DecisionSource.PRODUCT, null,
                new RuleExecutorRef(RuleExecutorType.JSON_LOGIC, null, "1.0", JSON.readTree("{\"==\":[1,2]}")),
                dependencies, order, true, decision, code, List.of(), null);
    }
    private static RuleDecisionPlan plan(DecisionBinding... bindings) {
        var slots = Arrays.stream(bindings).map(binding -> new DecisionSlot(binding.slotKey(), DecisionStage.DOMAIN_DECISION,
                SlotCardinality.SINGLE, OverridePolicy.FORBIDDEN, DecisionAggregationPolicy.SINGLE_RESULT, null, java.util.List.of())).toList();
        return new PraxisRulePlanCompiler(RuleBindingExecutorRegistry.empty()).compile(new RuleSetDefinition(
                new RuleSetRef("review", "requests", "review.requests", "create", 1), List.of("draft"), slots,
                List.of(bindings), RuleRuntimeCompatibility.current(), RuleFailPolicy.FAIL_CLOSED));
    }
    private static org.praxisplatform.rules.contract.RuleEvaluationResult evaluate(RuleDecisionPlan plan) {
        return new PraxisRuleSetEngine(RuleBindingExecutorRegistry.empty()).evaluate(plan,
                JSON.createObjectNode().set("draft", JSON.createObjectNode()), "2026-11-01T12:00:00Z", "UTC");
    }
    private static RuleErrorCatalog catalog() {
        return RuleErrorCatalog.of(Map.of(
                "OUT_OF_SCOPE", new RuleErrorDescriptor("OUT_OF_SCOPE", "category", "Optional branch", null, null),
                "LIMIT", new RuleErrorDescriptor("LIMIT", "amount", "Limit exceeded", null, null),
                "MISSING", new RuleErrorDescriptor("MISSING", "evidence", "Missing evidence", null, null)));
    }

    @Test void resolvesCatalogMessageCategoryAndConflictStatus() throws Exception {
        var plan = plan(binding("limit", RuleDecision.DENY, "LIMIT", 0, List.of()));
        var result = evaluate(plan);
        var catalog = RuleErrorCatalog.of(Map.of("LIMIT", new RuleErrorDescriptor(
                "LIMIT", "amount", "Limite indisponível para esta operação.", "policy:limit", "cOnFlIcT")));
        var ex = new PraxisRuleViolationException(plan, result, catalog);
        assertEquals(409, ex.getHttpStatus());
        assertEquals("Limite indisponível para esta operação.", ex.getMessage());
        assertEquals("cOnFlIcT", ex.getViolations().getFirst().category());
        assertEquals("cOnFlIcT", JSON.valueToTree(ex.toDiagnosticProperties()).at("/violations/0/category").asText());
        var business = new PraxisRuleViolationException(plan, result, catalog());
        assertEquals(422, business.getHttpStatus());
        assertEquals("Limit exceeded", business.getMessage());
        assertEquals("BUSINESS_LOGIC", business.getViolations().getFirst().category());
        var fallback = new PraxisRuleViolationException(plan, result, RuleErrorCatalog.of(Map.of()));
        assertEquals("A operação não foi autorizada pelas regras de negócio.", fallback.getMessage());
        assertEquals(422, fallback.getHttpStatus());
    }

    @Test void legacySlotConstructorDefaultsToBusinessLogicAndDirectConflictRemains409() {
        var legacy = new SlotViolationDetail("LIMIT", null, null, null, null, RuleDecision.DENY, null, "Limite.", null);
        assertEquals("BUSINESS_LOGIC", legacy.category());
        assertEquals(422, new PraxisRuleViolationException(legacy).getHttpStatus());
        var conflict = new SlotViolationDetail("STATE", null, null, null, null, RuleDecision.DENY, null, "Estado incompatível.", null, "CONFLICT");
        assertEquals(409, new PraxisRuleViolationException(conflict).getHttpStatus());
    }

    @Test void projectsOnlyTheConsolidatedDenialAfterOtherNonAllowBranches() throws Exception {
        for (var prior : List.of(RuleDecision.NOT_APPLICABLE, RuleDecision.INCONCLUSIVE)) {
            var plan = plan(binding("scope", prior, "OUT_OF_SCOPE", 0, List.of()),
                    binding("limit", RuleDecision.DENY, "LIMIT", 1, List.of()));
            var result = evaluate(plan);
            var violation = new PraxisRuleViolationException(plan, result, catalog());
            assertEquals(List.of("LIMIT"), result.reasonCodes());
            assertEquals(result.reasonCodes(), violation.getReasonCodes());
            assertEquals(List.of("limit"), violation.getSlotKeys());
            assertEquals("amount", violation.getViolations().getFirst().targetField());
            assertEquals(List.of(RuleDecision.DENY), violation.getViolations().stream().map(SlotViolationDetail::decision).toList());
        }
    }

    @Test void doesNotPublishBlockedDependencyReasonsAsFieldViolations() throws Exception {
        var plan = plan(binding("producer", RuleDecision.INCONCLUSIVE, "MISSING", 0, List.of()),
                binding("consumer", RuleDecision.DENY, "LIMIT", 1, List.of("producer")));
        var result = evaluate(plan);
        assertEquals(RuleDecision.INCONCLUSIVE, result.decision());
        assertEquals(2, result.bindingResults().size());
        var violation = new PraxisRuleViolationException(plan, result, catalog());
        assertEquals(result.reasonCodes(), violation.getReasonCodes());
        assertEquals(List.of("producer"), violation.getSlotKeys());
        assertEquals(List.of("MISSING"), violation.getViolations().stream().map(SlotViolationDetail::code).toList());
    }

    @Test void preservesOnlyTerminalReasonsForConsolidatedNotApplicable() throws Exception {
        var plan = plan(binding("producer", RuleDecision.NOT_APPLICABLE, "OUT_OF_SCOPE", 0, List.of()),
                binding("consumer", RuleDecision.NOT_APPLICABLE, "LIMIT", 1, List.of("producer")));
        var result = evaluate(plan);
        assertEquals(RuleDecision.NOT_APPLICABLE, result.decision());
        var violation = new PraxisRuleViolationException(plan, result, catalog());
        assertEquals(result.reasonCodes(), violation.getReasonCodes());
        assertEquals(List.of("consumer"), violation.getSlotKeys());
    }

    @Test void catalogIsImmutableAndRejectsMismatchedIdentity() {
        var error = new RuleErrorDescriptor("LIMIT", "amount", "Limite excedido.", "policy:limit", "LIMIT");
        Map<String, RuleErrorDescriptor> source = new HashMap<>(Map.of("LIMIT", error));
        var catalog = RuleErrorCatalog.of(source);
        source.clear();
        assertEquals(error, catalog.find("LIMIT").orElseThrow());
        assertTrue(catalog.find("missing").isEmpty());
        assertTrue(catalog.find(null).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> RuleErrorCatalog.of(Map.of("OTHER", error)));
    }

    @Test void directViolationRetainsCustomerProvenanceWithoutMutableAliases() {
        var detail = new SlotViolationDetail("LIMIT", "limit", "tenant.limit", DecisionSource.CUSTOMER,
                DecisionStage.DOMAIN_DECISION, RuleDecision.DENY, "amount", "Limite excedido.", "policy:limit");
        var exception = new PraxisRuleViolationException(detail);
        assertEquals(List.of("LIMIT"), exception.getReasonCodes());
        assertEquals(List.of(DecisionSource.CUSTOMER), exception.getDecisionSources());
        assertEquals(List.of("CUSTOMER"), exception.toDiagnosticProperties().get("decisionSources"));
        assertThrows(UnsupportedOperationException.class, () -> exception.getViolations().clear());
        assertThrows(IllegalArgumentException.class, () -> new SlotViolationDetail("ALLOW", null, null, null, null,
                RuleDecision.ALLOW, null, "Allowed", null));
    }

    @Test void hostAdapterPreservesMultipleRejectionsAndCanonicalStatus() {
        var conflict = new SlotViolationDetail("STATE", "state", "product.state", DecisionSource.PRODUCT,
                DecisionStage.DOMAIN_DECISION, RuleDecision.DENY, "state", "Estado incompatível.", null, "CONFLICT");
        var business = new SlotViolationDetail("LIMIT", "limit", "customer.limit", DecisionSource.CUSTOMER,
                DecisionStage.DOMAIN_DECISION, RuleDecision.DENY, "amount", "Limite excedido.", null);
        class HostRejection extends PraxisRuleViolationException {
            HostRejection(List<SlotViolationDetail> details) { super(details); }
        }
        var source = new ArrayList<>(List.of(conflict, business));
        var rejection = new HostRejection(source);
        source.clear();
        assertEquals(List.of(conflict, business), rejection.getViolations());
        assertEquals(List.of("STATE", "LIMIT"), rejection.getReasonCodes());
        assertEquals(List.of(DecisionSource.PRODUCT, DecisionSource.CUSTOMER), rejection.getDecisionSources());
        assertEquals("Estado incompatível.", rejection.getMessage());
        assertEquals(422, rejection.getHttpStatus());
        assertEquals(409, new HostRejection(List.of(conflict)).getHttpStatus());
        assertThrows(UnsupportedOperationException.class, () -> rejection.getViolations().clear());
        assertThrows(IllegalArgumentException.class, () -> new HostRejection(List.of()));
        var inconclusive = new SlotViolationDetail("MISSING", null, null, null, null,
                RuleDecision.INCONCLUSIVE, null, "Fato ausente.", null);
        assertThrows(IllegalArgumentException.class, () -> new HostRejection(List.of(conflict, inconclusive)));
    }
}
