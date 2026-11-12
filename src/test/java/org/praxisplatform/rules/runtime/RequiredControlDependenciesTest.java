package org.praxisplatform.rules.runtime;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.praxisplatform.rules.contract.*;
import org.praxisplatform.rules.facts.PraxisFactTreeBuilder;
import org.praxisplatform.rules.plan.*;

class RequiredControlDependenciesTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final RuleBindingExecutorRegistry REGISTRY = RuleBindingExecutorRegistry.empty();
    private final PraxisRulePlanCompiler compiler = new PraxisRulePlanCompiler(REGISTRY);

    @Test void slotCopiesNormalizesAndProtectsItsControlIdentities() {
        var input = new ArrayList<>(List.of(" product.guard "));
        var slot = ruleSlot(input);
        input.clear();
        assertEquals(List.of("product.guard"), slot.requiredControlDependencies());
        assertThrows(UnsupportedOperationException.class, () -> slot.requiredControlDependencies().clear());
        assertThrows(IllegalArgumentException.class, () -> ruleSlot(List.of("a", " a ")));
        assertThrows(IllegalArgumentException.class, () -> ruleSlot(List.of(" ")));
        assertThrows(IllegalArgumentException.class, () -> ruleSlot(java.util.Arrays.asList((String) null)));
    }

    @Test void replacementCannotOmitTheMandatoryGuard() {
        failure(RulePlanIssueCode.PLAN_REQUIRED_CONTROL_MISSING,
                definition(ruleSlot(List.of("product.guard")), guard(true), customer(List.of())));
    }

    @Test void productBindingMustAlsoDeclareTheGuard() {
        var product = new DecisionBinding("product.rule", "rule", DecisionSource.PRODUCT, null,
                executor("draft.allowed"), List.of(), 2, true, RuleDecision.DENY, "DENIED",
                List.of("draft.allowed"), null);
        failure(RulePlanIssueCode.PLAN_REQUIRED_CONTROL_MISSING,
                definition(ruleSlot(List.of("product.guard")), guard(true), product));
    }

    @Test void aSingleSlotCannotLoseItsOnlyEnabledDecision() {
        var slot = ruleSlot(List.of("product.guard"));
        var disabled = customer(List.of("product.guard"));
        disabled = new DecisionBinding(disabled.bindingKey(), disabled.slotKey(), disabled.source(),
                disabled.compositionPolicy(), disabled.executor(), disabled.dependsOn(), disabled.order(), false,
                disabled.falseDecision(), disabled.falseReasonCode(), disabled.requiredFactPaths(), disabled.parameterization());
        failure(RulePlanIssueCode.PLAN_CARDINALITY_INVALID, definition(slot, guard(true)));
        failure(RulePlanIssueCode.PLAN_CARDINALITY_INVALID, definition(slot, guard(true), disabled));
        failure(RulePlanIssueCode.PLAN_CARDINALITY_INVALID,
                definition(List.of(ruleSlot(List.of())), List.of(disabled)));
    }

    @Test void optionalMultipleExtensionSlotsMayRemainEmpty() {
        var optional = new DecisionSlot("extension", DecisionStage.DOMAIN_DECISION, SlotCardinality.MULTIPLE,
                OverridePolicy.RESTRICT_ONLY, DecisionAggregationPolicy.DENY_OVERRIDES, null, List.of());
        var definition = definition(List.of(slot("guard", DecisionStage.PROTECTED_GUARD, OverridePolicy.FORBIDDEN, List.of()), optional),
                List.of(guard(true)));
        assertDoesNotThrow(() -> compiler.compile(definition));
    }

    @Test void missingAndDisabledGuardsAreRejected() {
        for (var bindings : List.of(List.of(customer(List.of("product.guard"))),
                List.of(guard(false), customer(List.of("product.guard"))))) {
            failure(RulePlanIssueCode.PLAN_REQUIRED_CONTROL_INVALID,
                    definition(ruleSlot(List.of("product.guard")), bindings.toArray(DecisionBinding[]::new)));
        }
    }

    @Test void customerAndUnprotectedBindingsCannotActAsMandatoryControls() {
        var customerGuard = new DecisionBinding("product.guard", "guard", DecisionSource.CUSTOMER,
                CompositionPolicy.REPLACE_EXACT, executor("context.applies"), List.of(), 0, true,
                RuleDecision.NOT_APPLICABLE, "OUTSIDE_SCOPE", List.of("context.applies"), null);
        failure(RulePlanIssueCode.PLAN_PROTECTED_GUARD_INVALID,
                definition(List.of(slot("guard", DecisionStage.PROTECTED_GUARD,
                                OverridePolicy.REPLACEABLE, List.of()), ruleSlot(List.of("product.guard"))),
                        List.of(customerGuard, customer(List.of("product.guard")))));
        var slots = List.of(slot("guard", DecisionStage.DOMAIN_DECISION, OverridePolicy.FORBIDDEN, List.of()),
                ruleSlot(List.of("product.guard")));
        failure(RulePlanIssueCode.PLAN_REQUIRED_CONTROL_INVALID,
                definition(slots, List.of(guard(true), customer(List.of("product.guard")))));
    }

    @Test void anInapplicableGuardSuppressesReplacementWithoutRequiringItsDraftFacts() throws Exception {
        var plan = validPlan();
        var result = evaluate(plan, "{\"context\":{\"applies\":false}}");
        assertEquals(RuleDecision.NOT_APPLICABLE, result.decision());
        assertTrue(result.reasonCodes().contains("DEPENDENCY_NOT_APPLICABLE"));
    }

    @Test void applicableReplacementRetainsBothAllowAndDeny() throws Exception {
        assertEquals(RuleDecision.ALLOW,
                evaluate(validPlan(), "{\"context\":{\"applies\":true},\"draft\":{\"allowed\":true}}").decision());
        assertEquals(RuleDecision.DENY,
                evaluate(validPlan(), "{\"context\":{\"applies\":true},\"draft\":{\"allowed\":false}}").decision());
    }

    @Test void unresolvedGuardCannotBeConvertedIntoImplicitPermission() throws Exception {
        assertEquals(RuleDecision.INCONCLUSIVE, evaluate(validPlan(), "{\"context\":{}}").decision());
    }

    @Test void previewPreservesTheTransitiveGuardClosureAndExcludesIndependentRules() throws Exception {
        var base = new DecisionBinding("product.base", "base", DecisionSource.PRODUCT, null,
                executor("context.applies"), List.of(), 0, true, RuleDecision.NOT_APPLICABLE,
                "OUTSIDE_SCOPE", List.of("context.applies"), null);
        var gate = new DecisionBinding("product.guard", "guard", DecisionSource.PRODUCT, null,
                executor("context.applies"), List.of("product.base"), 1, true,
                RuleDecision.NOT_APPLICABLE, "OUTSIDE_SCOPE", List.of("context.applies"), null);
        var other = new DecisionBinding("product.other", "other", DecisionSource.PRODUCT, null,
                executor("draft.other"), List.of(), 3, true, RuleDecision.DENY,
                "OTHER_DENIED", List.of("draft.other"), null);
        var slots = List.of(slot("base", DecisionStage.PROTECTED_GUARD, OverridePolicy.FORBIDDEN, List.of()),
                slot("guard", DecisionStage.PROTECTED_GUARD, OverridePolicy.FORBIDDEN, List.of("product.base")),
                ruleSlot(List.of("product.guard")),
                slot("other", DecisionStage.DOMAIN_DECISION, OverridePolicy.FORBIDDEN, List.of()));
        var source = definition(slots, List.of(base, gate, customer(List.of("product.guard")), other));
        var facts = "{\"context\":{\"applies\":false},\"draft\":{\"other\":false}}";
        assertEquals(RuleDecision.DENY, evaluate(compiler.compile(source), facts).decision());
        var preview = compiler.compilePreview(source, List.of("customer.rule"));
        assertEquals(List.of("product.base", "product.guard", "customer.rule"),
                preview.orderedBindings().stream().map(DecisionBinding::bindingKey).toList());
        assertEquals(RuleDecision.NOT_APPLICABLE, evaluate(preview, facts).decision());
        assertEquals("preview", preview.definition().ref().operationKey());
    }

    @Test void previewRefusesInvalidSourcesAndUnknownSelections() {
        var source = definition(ruleSlot(List.of("product.guard")), guard(true), customer(List.of("product.guard")));
        assertEquals(RulePlanIssueCode.PLAN_REFERENCE_MISSING,
                assertThrows(RulePlanException.class, () -> compiler.compilePreview(source, List.of("absent"))).getCode());
        assertThrows(IllegalArgumentException.class, () -> compiler.compilePreview(source, List.of()));
        var invalid = definition(ruleSlot(List.of("product.guard")), guard(true), customer(List.of()));
        assertEquals(RulePlanIssueCode.PLAN_REQUIRED_CONTROL_MISSING,
                assertThrows(RulePlanException.class, () -> compiler.compilePreview(invalid, List.of("product.guard"))).getCode());
    }

    @Test void previewRetainsTheSelectedCustomerErrorDescriptor() throws Exception {
        var base = definition(ruleSlot(List.of("product.guard")), guard(true), customer(List.of("product.guard")));
        var descriptor = new org.praxisplatform.rules.violation.RuleErrorDescriptor(
                "DENIED", "draft.allowed", "The approved replacement denies this operation.", "policy:R7", "BUSINESS_LOGIC");
        var source = new RuleSetDefinition(base.ref(), base.availableRoots(), base.slots(), base.bindings(),
                base.compatibility(), base.failPolicy(), Map.of("customer.rule", descriptor));
        var restored = JSON.readValue(JSON.writeValueAsBytes(source), RuleSetDefinition.class);
        var preview = compiler.compilePreview(restored, List.of("customer.rule"));
        assertEquals(Map.of("customer.rule", descriptor), preview.definition().errorDescriptors());
        assertEquals(List.of("DENIED"), evaluate(preview,
                "{\"context\":{\"applies\":true},\"draft\":{\"allowed\":false}}").reasonCodes());
    }

    @Test void parameterizationCannotOmitMandatoryControls() throws Exception {
        var template = new RuleParameterTemplate("review:allowed", "1", executor("draft.allowed"),
                RuleDecision.DENY, "DENIED", List.of("draft.allowed"), List.of(
                    new RuleParameterDefinition("expected", RuleParameterType.BOOLEAN, false, false, false,
                            null, JSON.readTree("true"), null, null, List.of(), List.of("/===/1"))));
        var slot = new DecisionSlot("rule", DecisionStage.DOMAIN_DECISION, SlotCardinality.SINGLE,
                OverridePolicy.PARAMETERIZABLE, DecisionAggregationPolicy.SINGLE_RESULT,
                template, List.of("product.guard"));
        var parameterization = new RuleParameterization("review:allowed", "1", JSON.createObjectNode());
        var missing = new DecisionBinding("customer.rule", "rule", DecisionSource.CUSTOMER,
                CompositionPolicy.PARAMETERIZE, null, List.of(), 2, true,
                null, null, List.of(), parameterization);
        failure(RulePlanIssueCode.PLAN_REQUIRED_CONTROL_MISSING, definition(slot, guard(true), missing));
        var complete = new DecisionBinding("customer.rule", "rule", DecisionSource.CUSTOMER,
                CompositionPolicy.PARAMETERIZE, null, List.of("product.guard"), 2, true,
                null, null, List.of(), parameterization);
        var preview = compiler.compilePreview(definition(slot, guard(true), complete), List.of("customer.rule"));
        assertNull(preview.definition().bindings().getLast().executor());
        assertEquals(RuleDecision.NOT_APPLICABLE, evaluate(preview, "{\"context\":{\"applies\":false}}").decision());
    }

    @Test void mandatoryCyclesAreRejectedAndGuardListsAreBounded() {
        var gate = new DecisionBinding("product.guard", "guard", DecisionSource.PRODUCT, null,
                executor("context.applies"), List.of("customer.rule"), 0, true,
                RuleDecision.NOT_APPLICABLE, "OUTSIDE_SCOPE", List.of("context.applies"), null);
        // A protected guard cannot point forward into a domain stage.
        failure(RulePlanIssueCode.PLAN_DEPENDENCY_INVALID,
                definition(ruleSlot(List.of("product.guard")), gate, customer(List.of("product.guard"))));
        var tooMany = java.util.stream.IntStream.range(0, 129).mapToObj(i -> "guard." + i).toList();
        failure(RulePlanIssueCode.PLAN_LIMIT_EXCEEDED, definition(ruleSlot(tooMany), guard(true), customer(List.of())));
    }

    @Test void mandatoryControlsCannotFormASameStageCycle() {
        var first = new DecisionBinding("product.first", "first", DecisionSource.PRODUCT, null,
                executor("context.applies"), List.of("product.second"), 0, true,
                RuleDecision.NOT_APPLICABLE, "OUTSIDE_SCOPE", List.of("context.applies"), null);
        var second = new DecisionBinding("product.second", "second", DecisionSource.PRODUCT, null,
                executor("context.applies"), List.of("product.first"), 1, true,
                RuleDecision.NOT_APPLICABLE, "OUTSIDE_SCOPE", List.of("context.applies"), null);
        failure(RulePlanIssueCode.PLAN_CYCLE, definition(List.of(
                slot("first", DecisionStage.PROTECTED_GUARD, OverridePolicy.FORBIDDEN, List.of("product.second")),
                slot("second", DecisionStage.PROTECTED_GUARD, OverridePolicy.FORBIDDEN, List.of("product.first"))),
                List.of(first, second)));
    }

    @Test void mandatoryControlDeclarationParticipatesInPlanIdentity() {
        var bindings = List.of(guard(true), customer(List.of("product.guard")));
        var ordinary = definition(ruleSlot(List.of()), bindings.toArray(DecisionBinding[]::new));
        var protectedSlot = definition(ruleSlot(List.of("product.guard")), bindings.toArray(DecisionBinding[]::new));
        assertNotEquals(compiler.compile(ordinary).planDigest(), compiler.compile(protectedSlot).planDigest());
    }

    @Test void roundtripPreservesControlGraphAndDigestAndOldCompatibilityIsRejected() throws Exception {
        var source = definition(ruleSlot(List.of("product.guard")), guard(true), customer(List.of("product.guard")));
        var restored = JSON.readValue(JSON.writeValueAsBytes(source), RuleSetDefinition.class);
        assertEquals(compiler.compile(source).planDigest(), compiler.compile(restored).planDigest());
        assertEquals(List.of("product.guard"), restored.slots().getLast().requiredControlDependencies());
        var old = new RuleSetDefinition(source.ref(), source.availableRoots(), source.slots(), source.bindings(),
                new RuleRuntimeCompatibility("1.6", RuleRuntimeCompatibility.JSON_LOGIC_DIALECT_VERSION,
                        RuleRuntimeCompatibility.JSON_LOGIC_CORPUS_SHA256), source.failPolicy());
        failure(RulePlanIssueCode.PLAN_COMPATIBILITY_INVALID, old);
    }

    @Test void emptyControlDeclarationsPreserveHistoricalSlotJsonShape() throws Exception {
        var legacy = JSON.readTree("""
                {"slotKey":"guard","stage":"PROTECTED_GUARD","cardinality":"SINGLE",
                 "overridePolicy":"FORBIDDEN","aggregationPolicy":"SINGLE_RESULT","parameterTemplate":null}
                """);
        var decoded = JSON.treeToValue(legacy, DecisionSlot.class);
        assertTrue(decoded.requiredControlDependencies().isEmpty());
        assertEquals(legacy, JSON.valueToTree(decoded));
    }

    private RuleDecisionPlan validPlan() {
        return compiler.compile(definition(ruleSlot(List.of("product.guard")), guard(true),
                customer(List.of("product.guard"))));
    }

    private void failure(RulePlanIssueCode code, RuleSetDefinition definition) {
        assertEquals(code, assertThrows(RulePlanException.class, () -> compiler.compile(definition)).getCode());
    }

    private static DecisionSlot ruleSlot(List<String> controls) {
        return slot("rule", DecisionStage.DOMAIN_DECISION, OverridePolicy.REPLACEABLE, controls);
    }

    private static DecisionSlot slot(String key, DecisionStage stage, OverridePolicy policy, List<String> controls) {
        return new DecisionSlot(key, stage, SlotCardinality.SINGLE, policy,
                DecisionAggregationPolicy.SINGLE_RESULT, null, controls);
    }

    private static RuleSetDefinition definition(DecisionSlot rule, DecisionBinding... bindings) {
        return definition(List.of(slot("guard", DecisionStage.PROTECTED_GUARD, OverridePolicy.FORBIDDEN, List.of()),
                rule), List.of(bindings));
    }

    private static RuleSetDefinition definition(List<DecisionSlot> slots, List<DecisionBinding> bindings) {
        return RuleSetDefinition.withInferredRoots(new RuleSetRef("review", "controls", "review.controls", "validate", 1),
                slots, bindings, RuleRuntimeCompatibility.current(), RuleFailPolicy.FAIL_CLOSED);
    }

    private static DecisionBinding guard(boolean enabled) {
        return new DecisionBinding("product.guard", "guard", DecisionSource.PRODUCT, null,
                executor("context.applies"), List.of(), 1, enabled, RuleDecision.NOT_APPLICABLE,
                "OUTSIDE_SCOPE", List.of("context.applies"), null);
    }

    private static DecisionBinding customer(List<String> dependencies) {
        return new DecisionBinding("customer.rule", "rule", DecisionSource.CUSTOMER, CompositionPolicy.REPLACE_EXACT,
                executor("draft.allowed"), dependencies, 2, true, RuleDecision.DENY, "DENIED",
                List.of("draft.allowed"), null);
    }

    private static RuleExecutorRef executor(String path) {
        return RuleExecutorRef.jsonLogic(PraxisFactTreeBuilder.create()
                .put("===", List.of(Map.of("var", path), true)).build());
    }

    private static RuleEvaluationResult evaluate(RuleDecisionPlan plan, String facts) throws Exception {
        return new PraxisRuleSetEngine(REGISTRY).evaluate(plan, JSON.readTree(facts),
                "2026-11-05T12:00:00Z", "UTC");
    }
}
