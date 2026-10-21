package org.praxisplatform.rules.parameters;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.POJONode;
import java.math.BigDecimal;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.praxisplatform.rules.contract.*;
import org.praxisplatform.rules.plan.*;
import org.praxisplatform.rules.runtime.*;
import org.praxisplatform.rules.snapshot.*;

class RuleParameterizationContractTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final RuleBindingExecutorRegistry EMPTY = RuleBindingExecutorRegistry.empty();

    @Test
    void admittedValuesActuallyChangeDecisionUnderIdenticalFacts() throws Exception {
        var low = plan(template(), values("{\"limit\":10}"));
        var high = plan(template(), values("{\"limit\":20}"));
        var runtime = new PraxisRuleSetEngine(EMPTY);
        assertEquals(RuleDecision.ALLOW, runtime.evaluate(low, json("{\"request\":{\"amount\":15}}"),
                "2026-10-21T00:00:00Z", "UTC").decision());
        assertEquals(RuleDecision.DENY, runtime.evaluate(high, json("{\"request\":{\"amount\":15}}"),
                "2026-10-21T00:00:00Z", "UTC").decision());
        assertEquals("BELOW_LIMIT", runtime.evaluate(high, json("{\"request\":{\"amount\":15}}"),
                "2026-10-21T00:00:00Z", "UTC").reasonCodes().getFirst());
        assertEquals(RuleDecision.INCONCLUSIVE, runtime.evaluate(high, json("{\"request\":{}}"),
                "2026-10-21T00:00:00Z", "UTC").decision());
        assertNotEquals(low.planDigest(), high.planDigest());
        assertNull(low.definition().bindings().getFirst().executor());
        assertNotNull(low.orderedBindings().getFirst().executor());
    }

    @Test
    void preservesExplicitZeroFalseAndNullAndRecordsDefaults() throws Exception {
        var expression = json("{\"and\":[0,false,null]}");
        var params = List.of(
                parameter("number", RuleParameterType.INTEGER, json("0"), false, false, false, "/and/0"),
                parameter("flag", RuleParameterType.BOOLEAN, json("false"), false, false, false, "/and/1"),
                parameter("nullable", RuleParameterType.STRING, json("null"), false, true, false, "/and/2"));
        var template = template(expression, params);
        var implicit = RuleParameterResolver.resolve(template, values("{}"));
        var explicit = RuleParameterResolver.resolve(template,
                values("{\"number\":0,\"flag\":false,\"nullable\":null}"));
        assertEquals(implicit.effectiveValues(), explicit.effectiveValues());
        assertEquals(RuleParameterValueOrigin.DEFAULT, implicit.origins().get("nullable"));
        assertEquals(RuleParameterValueOrigin.DECLARED, explicit.origins().get("nullable"));
        assertTrue(explicit.effectiveValues().has("nullable"));
        assertTrue(explicit.effectiveValues().get("nullable").isNull());
        assertNotEquals(plan(template, values("{}")).planDigest(),
                plan(template, explicit.effectiveValues()).planDigest());
        assertEquals(RuleRuntimeCompatibility.JSON_LOGIC_DIALECT_VERSION,
                explicit.executor().implementationVersion());
    }

    @Test
    void requiredParametersWithoutDefaultMustBeDeclared() throws Exception {
        var t = template(json("{\">\":[{\"var\":\"request.amount\"},10]}"), List.of(
                parameter("limit", RuleParameterType.NUMBER, null, true, false, false, "/>/1")));
        assertFailure(RulePlanIssueCode.PLAN_PARAMETER_VALUES_INVALID,
                () -> RuleParameterResolver.resolve(t, values("{}")));
        assertNotNull(RuleParameterResolver.resolve(t, values("{\"limit\":11}")));
    }

    @Test
    void validatesTypeBoundsEnumsNullUnknownAndReadOnly() throws Exception {
        for (String invalid : List.of("{\"limit\":9}", "{\"limit\":31}", "{\"limit\":10.5}",
                "{\"limit\":\"10\"}", "{\"limit\":false}", "{\"limit\":null}",
                "{\"limit\":{\">\":[1,0]}}", "{\"limit\":[]}", "{\"other\":10}")) {
            assertFailure(RulePlanIssueCode.PLAN_PARAMETER_VALUES_INVALID,
                    () -> RuleParameterResolver.resolve(template(), values(invalid)));
        }
        assertNotNull(RuleParameterResolver.resolve(template(), values("{\"limit\":10.0}")));
        var enumParameter = new RuleParameterDefinition("limit", RuleParameterType.INTEGER,
                false, false, false, "units", json("10"), BigDecimal.TEN, new BigDecimal("30"),
                List.of(json("10"), json("20")), List.of("/>/1"));
        var enumTemplate = template(template().executor().expression(), List.of(enumParameter));
        assertFailure(RulePlanIssueCode.PLAN_PARAMETER_VALUES_INVALID,
                () -> RuleParameterResolver.resolve(enumTemplate, values("{\"limit\":15}")));
        var readOnly = template(template().executor().expression(), List.of(
                parameter("limit", RuleParameterType.INTEGER, json("10"), false, false, true, "/>/1")));
        assertNotNull(RuleParameterResolver.resolve(readOnly, values("{}")));
        assertNotNull(RuleParameterResolver.resolve(readOnly, values("{\"limit\":10.0}")));
        assertFailure(RulePlanIssueCode.PLAN_PARAMETER_VALUES_INVALID,
                () -> RuleParameterResolver.resolve(readOnly, values("{\"limit\":20}")));
    }

    @Test
    void rejectsMissingOrAmbiguousSchemasAndDefaults() throws Exception {
        for (RuleParameterDefinition invalid : List.of(
                parameter("limit", RuleParameterType.INTEGER, null, false, false, false, "/>/1"),
                parameter("limit", RuleParameterType.INTEGER, null, true, false, true, "/>/1"),
                parameter("limit", RuleParameterType.INTEGER, json("20"), false, false, false, "/>/1"),
                parameter("limit", RuleParameterType.BOOLEAN, json("10"), false, false, false, "/>/1"),
                parameter("limit", RuleParameterType.INTEGER, json("null"), false, false, false, "/>/1"),
                new RuleParameterDefinition("limit", RuleParameterType.STRING, false, false, false,
                        "units", json("\"a\""), BigDecimal.ZERO, null, List.of(), List.of("/>/1")),
                new RuleParameterDefinition("limit", RuleParameterType.INTEGER, false, false, false,
                        "units", json("10"), new BigDecimal("30"), BigDecimal.TEN, List.of(), List.of("/>/1")),
                new RuleParameterDefinition("limit", RuleParameterType.INTEGER, false, false, false,
                        "units", json("10"), null, null, List.of(json("10"), json("10.0")), List.of("/>/1")),
                new RuleParameterDefinition("limit", RuleParameterType.INTEGER, false, false, false,
                        " ", json("10"), null, null, List.of(), List.of("/>/1")))) {
            assertFailure(RulePlanIssueCode.PLAN_PARAMETER_TEMPLATE_INVALID,
                    () -> RuleParameterResolver.validateTemplate(template(template().executor().expression(), List.of(invalid))));
        }
        assertFailure(RulePlanIssueCode.PLAN_PARAMETER_TEMPLATE_INVALID,
                () -> RuleParameterResolver.validateTemplate(template(template().executor().expression(), List.of())));
        assertFailure(RulePlanIssueCode.PLAN_PARAMETER_TEMPLATE_INVALID,
                () -> RuleParameterResolver.validateTemplate(template(template().executor().expression(),
                        List.of(template().parameters().getFirst(), template().parameters().getFirst()))));
    }

    @Test
    void rejectsPointersToMissingContainerRootOrVariableMetadata() throws Exception {
        for (String pointer : List.of("", "/>", "/>/9", "/>/01", "/>/0/var", "/>/0/var/0",
                "/>/~9", "/>/-", "/>/99999999999999999999999999999", "/>/1/child")) {
            var param = parameter("limit", RuleParameterType.INTEGER, json("10"), false, false, false, pointer);
            assertFailure(RulePlanIssueCode.PLAN_PARAMETER_TEMPLATE_INVALID,
                    () -> RuleParameterResolver.validateTemplate(template(template().executor().expression(), List.of(param))));
        }
        var duplicate = new RuleParameterDefinition("limit", RuleParameterType.INTEGER, false, false, false,
                null, json("10"), null, null, List.of(), List.of("/>/1", "/>/1"));
        assertFailure(RulePlanIssueCode.PLAN_PARAMETER_TEMPLATE_INVALID,
                () -> RuleParameterResolver.validateTemplate(template(template().executor().expression(), List.of(duplicate))));
        var other = parameter("other", RuleParameterType.INTEGER, json("10"), false, false, false, "/>/1");
        assertFailure(RulePlanIssueCode.PLAN_PARAMETER_TEMPLATE_INVALID,
                () -> RuleParameterResolver.validateTemplate(template(template().executor().expression(),
                        List.of(template().parameters().getFirst(), other))));
    }

    @Test
    void bindsEveryDeclaredLiteralSiteIncludingEscapedOperatorNames() throws Exception {
        var parameter = new RuleParameterDefinition("limit", RuleParameterType.INTEGER, false, false, false,
                "units", json("10"), null, null, List.of(), List.of("/and/0/>/1", "/and/1/</1"));
        var t = template(json("{\"and\":[{\">\":[{\"var\":\"request.amount\"},10]},{\"<\":[{\"var\":\"request.amount\"},10]}]}"),
                List.of(parameter));
        var result = RuleParameterResolver.resolve(t, values("{\"limit\":20}"));
        assertEquals(20, result.executor().expression().at("/and/0/>/1").intValue());
        assertEquals(20, result.executor().expression().at("/and/1/</1").intValue());
        var divide = template(json("{\"/\":[10,2]}"), List.of(
                parameter("limit", RuleParameterType.INTEGER, json("10"), false, false, false, "/~1/0")));
        assertEquals(20, RuleParameterResolver.resolve(divide, values("{\"limit\":20}"))
                .executor().expression().get("/").get(0).intValue());
    }

    @Test
    void rejectsArbitraryExecutorsTemplateIdentityAndCallerOwnedOutcomes() throws Exception {
        var arbitrary = new DecisionBinding("customer", "threshold", DecisionSource.CUSTOMER,
                CompositionPolicy.PARAMETERIZE, RuleExecutorRef.jsonLogic(json("true")), List.of(), 0,
                true, RuleDecision.DENY, "ARBITRARY", List.of(),
                new RuleParameterization("neutral:threshold", "1", values("{\"limit\":10}")));
        assertFailure(RulePlanIssueCode.PLAN_PARAMETERIZATION_INVALID,
                () -> new PraxisRulePlanCompiler(EMPTY).compile(definition(template(), arbitrary)));
        for (String key : List.of("wrong:threshold", "neutral:threshold")) {
            var ref = new RuleParameterization(key, "wrong", values("{\"limit\":10}"));
            assertFailure(RulePlanIssueCode.PLAN_PARAMETERIZATION_INVALID,
                    () -> new PraxisRulePlanCompiler(EMPTY).compile(definition(template(), binding(ref))));
        }
        assertFailure(RulePlanIssueCode.PLAN_PARAMETERIZATION_INVALID,
                () -> new PraxisRulePlanCompiler(EMPTY).compile(definition(template(), binding(null))));
        var withoutSchema = new RuleSetDefinition(ref(), List.of("request"), List.of(slot(null)),
                List.of(arbitrary), RuleRuntimeCompatibility.current(), RuleFailPolicy.FAIL_CLOSED);
        assertFailure(RulePlanIssueCode.PLAN_PARAMETER_TEMPLATE_INVALID,
                () -> new PraxisRulePlanCompiler(EMPTY).compile(withoutSchema));
        var javaTemplate = new RuleParameterTemplate("neutral:threshold", "1", RuleExecutorRef.java("host:check", "1"),
                RuleDecision.DENY, "BELOW_LIMIT", List.of(), template().parameters());
        assertFailure(RulePlanIssueCode.PLAN_PARAMETER_TEMPLATE_INVALID,
                () -> RuleParameterResolver.resolve(javaTemplate, values("{}")));
    }

    @Test
    void rejectsParameterizationOnOtherCompositionsAndProtectedSlots() throws Exception {
        var input = new RuleParameterization("neutral:threshold", "1", values("{}"));
        var product = new DecisionBinding("product", "threshold", DecisionSource.PRODUCT, null,
                RuleExecutorRef.jsonLogic(json("true")), List.of(), 0, true,
                RuleDecision.DENY, "FALSE", List.of(), input);
        assertFailure(RulePlanIssueCode.PLAN_PARAMETERIZATION_INVALID,
                () -> new PraxisRulePlanCompiler(EMPTY).compile(definition(template(), product)));
        var forbidden = new DecisionSlot("threshold", DecisionStage.PROTECTED_GUARD, SlotCardinality.SINGLE,
                OverridePolicy.FORBIDDEN, DecisionAggregationPolicy.SINGLE_RESULT, null);
        var def = new RuleSetDefinition(ref(), List.of("request"), List.of(forbidden),
                List.of(binding(input)), RuleRuntimeCompatibility.current(), RuleFailPolicy.FAIL_CLOSED);
        assertFailure(RulePlanIssueCode.PLAN_OVERRIDE_INVALID,
                () -> new PraxisRulePlanCompiler(EMPTY).compile(def));
    }

    @Test
    void enforcesCountDepthNativeJsonAndCombinedExpansionBudgets() throws Exception {
        var excessive = new ArrayList<RuleParameterDefinition>();
        for (int i = 0; i < 129; i++) excessive.add(parameter("p" + i, RuleParameterType.INTEGER,
                json("10"), false, false, false, "/>/1"));
        assertFailure(RulePlanIssueCode.PLAN_PARAMETER_TEMPLATE_INVALID,
                () -> RuleParameterResolver.validateTemplate(template(template().executor().expression(), excessive)));
        for (JsonNode invalid : List.of(DoubleNode.valueOf(Double.NaN), new POJONode(Map.of("x", 1)),
                DecimalNode.valueOf(new BigDecimal("1E+999999999")))) {
            var object = JSON.createObjectNode().set("limit", invalid);
            assertFailure(RulePlanIssueCode.PLAN_PARAMETER_VALUES_INVALID,
                    () -> RuleParameterResolver.resolve(template(), (ObjectNode) object));
        }
        var depth = values("{}");
        ObjectNode cursor = depth;
        for (int i = 0; i < 66; i++) cursor = cursor.putObject("nested");
        ObjectNode deepValues = depth;
        assertFailure(RulePlanIssueCode.PLAN_PARAMETER_VALUES_INVALID,
                () -> RuleParameterResolver.resolve(template(), deepValues));
        var expression = JSON.createObjectNode();
        var array = expression.putArray("and");
        var sites = new ArrayList<String>();
        for (int i = 0; i < 8; i++) { array.add("x"); sites.add("/and/" + i); }
        var text = new RuleParameterDefinition("text", RuleParameterType.STRING, false, false, false,
                null, json("\"x\""), null, null, List.of(), sites);
        var t = template(expression, List.of(text));
        assertFailure(RulePlanIssueCode.PLAN_PARAMETER_VALUES_INVALID,
                () -> RuleParameterResolver.resolve(t, JSON.createObjectNode().put("text", "x".repeat(40_000))));
    }

    @Test
    void capsCombinedEnumerationSchemaBeforeAggregateSerialization() throws Exception {
        var allowed = new ArrayList<JsonNode>();
        for (int i = 0; i < 8; i++) allowed.add(JSON.getNodeFactory().textNode("x".repeat(40_000) + i));
        var p = new RuleParameterDefinition("text", RuleParameterType.STRING, false, false, false,
                null, allowed.getFirst(), null, null, allowed, List.of("/cat/0"));
        var expression = JSON.createObjectNode();
        expression.putArray("cat").add(allowed.getFirst());
        assertFailure(RulePlanIssueCode.PLAN_PARAMETER_TEMPLATE_INVALID,
                () -> RuleParameterResolver.validateTemplate(template(expression, List.of(p))));
        var sites = new ArrayList<String>();
        var arrayExpression = JSON.createObjectNode();
        var array = arrayExpression.putArray("and");
        for (int i = 0; i < 257; i++) { array.add(10); sites.add("/and/" + i); }
        var excessiveSites = new RuleParameterDefinition("limit", RuleParameterType.INTEGER, false, false, false,
                null, json("10"), null, null, List.of(), sites);
        assertFailure(RulePlanIssueCode.PLAN_PARAMETER_TEMPLATE_INVALID,
                () -> RuleParameterResolver.validateTemplate(template(arrayExpression, List.of(excessiveSites))));
    }

    @Test
    void snapshotPreservesSourceAndRecompilesSameConstraintAndDigests() throws Exception {
        var definition = definition(template(), binding(new RuleParameterization("neutral:threshold", "1", values("{}"))));
        var snapshot = snapshot(definition);
        var compiler = new PraxisRuleSnapshotCompiler(EMPTY);
        var compiled = compiler.compile(snapshot, "neutral/1");
        assertNull(compiled.snapshot().ruleSet().bindings().getFirst().executor());
        assertNotNull(compiled.plan().orderedBindings().getFirst().executor());
        var restored = JSON.readValue(JSON.writeValueAsBytes(compiled.snapshot()), PublishedRuleSnapshot.class);
        var recompiled = compiler.compile(restored, "neutral/1");
        assertEquals(compiled.snapshotContentHash(), recompiled.snapshotContentHash());
        assertEquals(compiled.plan().planDigest(), recompiled.plan().planDigest());
        var changed = compiler.compile(snapshot(definition(template(), binding(new RuleParameterization(
                "neutral:threshold", "1", values("{\"limit\":20}"))))), "neutral/1");
        assertNotEquals(compiled.snapshotContentHash(), changed.snapshotContentHash());
        assertNotEquals(compiled.plan().planDigest(), changed.plan().planDigest());
    }

    @Test
    void digestsBindBoundsUnitsAndTemplateIdentityAndNormalizeNumbers() throws Exception {
        var first = plan(template(), values("{\"limit\":20}"));
        assertEquals(first.planDigest(), plan(template(), values("{\"limit\":20.0}")).planDigest());
        var p = template().parameters().getFirst();
        for (var changed : List.of(
                new RuleParameterDefinition(p.name(), p.type(), p.required(), p.nullable(), p.readOnly(), "other-unit",
                        p.defaultValue(), p.minimum(), p.maximum(), p.allowedValues(), p.expressionPointers()),
                new RuleParameterDefinition(p.name(), p.type(), p.required(), p.nullable(), p.readOnly(), p.unit(),
                        p.defaultValue(), p.minimum(), new BigDecimal("40"), p.allowedValues(), p.expressionPointers()))) {
            assertNotEquals(first.planDigest(), plan(template(template().executor().expression(), List.of(changed)),
                    values("{\"limit\":20}")).planDigest());
        }
        var t = new RuleParameterTemplate("neutral:threshold", "2", template().executor(),
                template().falseDecision(), template().falseReasonCode(), template().requiredFactPaths(), template().parameters());
        assertNotEquals(first.planDigest(), plan(t, values("{\"limit\":20}")).planDigest());
    }

    @Test
    void capturesMutableInputsAndSupportsConcurrentResolution() throws Exception {
        var values = values("{\"limit\":20}");
        var parameterization = new RuleParameterization("neutral:threshold", "1", values);
        values.put("limit", 30);
        parameterization.values().put("limit", 30);
        assertEquals(20, parameterization.values().get("limit").intValue());
        var t = template();
        var tasks = new ArrayList<Callable<String>>();
        for (int i = 0; i < 32; i++) tasks.add(() -> plan(t, parameterization.values()).planDigest());
        try (var executor = Executors.newFixedThreadPool(4)) {
            var results = executor.invokeAll(tasks);
            var digest = results.getFirst().get();
            for (var result : results) assertEquals(digest, result.get());
        }
        assertEquals(10, t.executor().expression().at("/>/1").intValue());
        var resolved = RuleParameterResolver.resolve(t, parameterization.values());
        resolved.effectiveValues().put("limit", 99);
        assertEquals(20, resolved.effectiveValues().get("limit").intValue());
        assertThrows(UnsupportedOperationException.class,
                () -> resolved.origins().put("x", RuleParameterValueOrigin.DEFAULT));
    }

    @Test
    void serializationDistinguishesAbsentDefaultFromExplicitNull() throws Exception {
        var absent = parameter("p", RuleParameterType.STRING, null, true, true, false, "/and/0");
        var explicit = parameter("p", RuleParameterType.STRING, json("null"), false, true, false, "/and/0");
        JsonNode absentJson = JSON.valueToTree(absent);
        JsonNode explicitJson = JSON.valueToTree(explicit);
        assertFalse(absentJson.has("defaultValue"));
        assertTrue(explicitJson.has("defaultValue"));
        assertTrue(explicitJson.get("defaultValue").isNull());
        assertNull(JSON.treeToValue(absentJson, RuleParameterDefinition.class).defaultValue());
        assertTrue(JSON.treeToValue(explicitJson, RuleParameterDefinition.class).defaultValue().isNull());
    }

    @Test
    void rejectsInvalidDialectTemplatesBeforeAnyRuleSetOrPersistence() throws Exception {
        for (String expression : List.of("{\"unknown\":[10]}", "{\">\":[10]}", "[10]",
                "{\"host:unregistered\":[10]}")) {
            var t = template(json(expression), List.of(
                    parameter("limit", RuleParameterType.INTEGER, json("10"), false, false, false, "/>/0")));
            assertFailure(RulePlanIssueCode.PLAN_PARAMETER_TEMPLATE_INVALID,
                    () -> RuleParameterResolver.resolve(t, values("{}")));
        }
        var source = new DecisionBinding("customer", "threshold", DecisionSource.CUSTOMER,
                CompositionPolicy.PARAMETERIZE, null, List.of(), 0, true, RuleDecision.NOT_APPLICABLE,
                "OVERRIDE", List.of(), new RuleParameterization("neutral:threshold", "1", values("{}")));
        assertFailure(RulePlanIssueCode.PLAN_PARAMETERIZATION_INVALID,
                () -> new PraxisRulePlanCompiler(EMPTY).compile(definition(template(), source)));
        var factsOverride = new DecisionBinding("customer", "threshold", DecisionSource.CUSTOMER,
                CompositionPolicy.PARAMETERIZE, null, List.of(), 0, true, null, null,
                List.of("request.other"), new RuleParameterization("neutral:threshold", "1", values("{}")));
        assertFailure(RulePlanIssueCode.PLAN_PARAMETERIZATION_INVALID,
                () -> new PraxisRulePlanCompiler(EMPTY).compile(definition(template(), factsOverride)));
    }

    @Test
    void canonicalDigestsSurviveDecimalSchemaDefaultsAndJacksonRoundtrip() throws Exception {
        var p = new RuleParameterDefinition("limit", RuleParameterType.NUMBER, false, false, false,
                "units", json("10.00"), new BigDecimal("10.000"), new BigDecimal("30.00"),
                List.of(json("10.0"), json("20.000")), List.of("/>/1"));
        var original = template(template().executor().expression(), List.of(p));
        var restored = JSON.readValue(JSON.writeValueAsBytes(original), RuleParameterTemplate.class);
        var treeRestored = JSON.treeToValue(JSON.valueToTree(original), RuleParameterTemplate.class);
        String digest = plan(original, values("{\"limit\":20.00}")).planDigest();
        assertEquals(digest, plan(restored, values("{\"limit\":20.0}")).planDigest());
        assertEquals(digest, plan(treeRestored, values("{\"limit\":20}")).planDigest());
        var compiler = new PraxisRuleSnapshotCompiler(EMPTY);
        var compiled = compiler.compile(snapshot(definition(original, binding(new RuleParameterization(
                original.templateKey(), original.templateVersion(), values("{\"limit\":20.0}"))))), "neutral/1");
        var snapshotRestored = JSON.treeToValue(JSON.valueToTree(compiled.snapshot()), PublishedRuleSnapshot.class);
        var recompiled = compiler.compile(snapshotRestored, "neutral/1");
        assertEquals(compiled.snapshotContentHash(), recompiled.snapshotContentHash());
        assertEquals(compiled.plan().planDigest(), recompiled.plan().planDigest());
    }

    private static RuleParameterTemplate template() throws Exception {
        return template(json("{\">\":[{\"var\":\"request.amount\"},10]}"), List.of(
                new RuleParameterDefinition("limit", RuleParameterType.INTEGER, false, false, false, "units",
                        json("10"), BigDecimal.TEN, new BigDecimal("30"), List.of(), List.of("/>/1"))));
    }

    private static RuleParameterTemplate template(JsonNode expression, List<RuleParameterDefinition> parameters) {
        return new RuleParameterTemplate("neutral:threshold", "1", RuleExecutorRef.jsonLogic(expression),
                RuleDecision.DENY, "BELOW_LIMIT", List.of("request.amount"), parameters);
    }

    private static RuleParameterDefinition parameter(String key, RuleParameterType type, JsonNode defaultValue,
            boolean required, boolean nullable, boolean readOnly, String pointer) {
        return new RuleParameterDefinition(key, type, required, nullable, readOnly, null, defaultValue,
                null, null, List.of(), List.of(pointer));
    }

    private static RuleDecisionPlan plan(RuleParameterTemplate template, ObjectNode values) {
        return new PraxisRulePlanCompiler(EMPTY).compile(definition(template, binding(
                new RuleParameterization(template.templateKey(), template.templateVersion(), values))));
    }

    private static RuleSetDefinition definition(RuleParameterTemplate template, DecisionBinding binding) {
        return new RuleSetDefinition(ref(), List.of("request"), List.of(slot(template)), List.of(binding),
                RuleRuntimeCompatibility.current(), RuleFailPolicy.FAIL_CLOSED);
    }

    private static DecisionSlot slot(RuleParameterTemplate template) {
        return new DecisionSlot("threshold", DecisionStage.DOMAIN_DECISION, SlotCardinality.SINGLE,
                OverridePolicy.PARAMETERIZABLE, DecisionAggregationPolicy.SINGLE_RESULT, template);
    }

    private static DecisionBinding binding(RuleParameterization parameterization) {
        return new DecisionBinding("customer", "threshold", DecisionSource.CUSTOMER, CompositionPolicy.PARAMETERIZE,
                null, List.of(), 0, true, null, null, List.of(), parameterization);
    }

    private static RuleSetRef ref() {
        return new RuleSetRef("neutral", "threshold", "parameters", "check", 1);
    }

    private static PublishedRuleSnapshot snapshot(RuleSetDefinition definition) {
        return new PublishedRuleSnapshot(PublishedRuleSnapshot.SNAPSHOT_CONTRACT_VERSION, "snapshot", "tenant", "test",
                "neutral", 1, "2026-10-21T00:00:00Z", null, "neutral/1", "2026-10-21T00:00:00Z", null,
                List.of(new RuleSnapshotSource("source", "threshold", 1, "A".repeat(64))),
                List.of(new RuleSnapshotApproval("approved", "reviewer", "operator", "2026-10-21T00:00:00Z", "B".repeat(64))), definition);
    }

    private static void assertFailure(RulePlanIssueCode code, org.junit.jupiter.api.function.Executable action) {
        assertEquals(code, assertThrows(RulePlanException.class, action).getCode());
    }

    private static JsonNode json(String value) throws Exception { return JSON.readTree(value); }
    private static ObjectNode values(String value) throws Exception { return (ObjectNode) json(value); }
}
