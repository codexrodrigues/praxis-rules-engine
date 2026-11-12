package org.praxisplatform.rules.facts;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.praxisplatform.rules.contract.*;
import org.praxisplatform.rules.plan.*;
import org.praxisplatform.rules.runtime.*;

class RuleFactSchemaTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    static ObjectNode schema() throws Exception {
        return (ObjectNode) JSON.readTree("""
            {"type":"object","properties":{
              "draft":{"type":"object","properties":{
                "dtini":{"type":"string","format":"date","maxLength":10},
                "count":{"type":"integer","minimum":0,"maximum":20}},
                "required":["dtini","count"],"additionalProperties":false,"maxProperties":2},
              "current":{"type":["object","null"],"properties":{
                "dtini":{"type":"string","format":"date","maxLength":10}},
                "required":["dtini"],"additionalProperties":false,"maxProperties":1},
              "neighborhood":{"type":"object","properties":{
                "rows":{"type":"array","minItems":0,"maxItems":5,"items":{
                  "type":"object","properties":{"days":{"type":"integer"}},
                  "required":["days"],"additionalProperties":false,"maxProperties":1}}},
                "required":["rows"],"additionalProperties":false,"maxProperties":1}},
              "required":["draft","current","neighborhood"],"additionalProperties":false,"maxProperties":3}
            """);
    }

    static ObjectNode facts() throws Exception {
        return (ObjectNode) JSON.readTree("""
            {"draft":{"dtini":"2024-02-29","count":2},"current":null,"neighborhood":{"rows":[{"days":2}]}}
            """);
    }

    @Test void nullableConceptAndEmptyPopulationRemainDistinctFromMissing() throws Exception {
        var compiled = RuleFactSchema.compile(schema());
        var value = facts();
        compiled.validate(value);
        assertTrue(compiled.hasNullAncestor(value, "current.dtini"));
        assertEquals("string", compiled.typeAt("current.dtini"));
        assertEquals("integer", compiled.typeAt("neighborhood.rows[0].days"));
        value.withArray("/neighborhood/rows").removeAll();
        compiled.validate(value);
        value.remove("current");
        assertThrows(RuleFactContractException.class, () -> compiled.validate(value));
        assertFalse(compiled.hasNullAncestor(value, "current.dtini"));
    }

    @TestFactory Stream<DynamicTest> rejectsInvalidProviderSnapshots() {
        return Stream.of("missing", "unknown", "date", "fraction", "range", "nested", "array", "type")
                .map(name -> DynamicTest.dynamicTest(name, () -> {
                    var value = facts();
                    switch (name) {
                        case "missing" -> value.withObject("/draft").remove("dtini");
                        case "unknown" -> value.withObject("/draft").put("inicio", "2024-01-01");
                        case "date" -> value.withObject("/draft").put("dtini", "2024-02-30");
                        case "fraction" -> value.withObject("/draft").put("count", 1.5);
                        case "range" -> value.withObject("/draft").put("count", 21);
                        case "nested" -> value.withArray("/neighborhood/rows").removeAll().addObject();
                        case "array" -> value.withArray("/neighborhood/rows").removeAll().add("flat");
                        case "type" -> value.withObject("/draft").put("count", "2");
                    }
                    var error = assertThrows(RuleFactContractException.class,
                            () -> RuleFactSchema.compile(schema()).validate(value));
                    assertEquals(RuleFactContractException.Code.FACTS_SCHEMA_INVALID, error.code());
                    assertFalse(error.getMessage().contains("2024-02-30"));
                }));
    }

    @TestFactory Stream<DynamicTest> rejectsUnknownOrUnboundedSchemaGrammar() {
        return Stream.of("ref", "anyOf", "open", "required", "unbounded", "invalid-enum", "inverted", "root-null")
                .map(name -> DynamicTest.dynamicTest(name, () -> {
                    var value = schema();
                    switch (name) {
                        case "ref" -> value.put("$ref", "https://invalid.example/schema");
                        case "anyOf" -> value.putArray("anyOf").addObject();
                        case "open" -> value.put("additionalProperties", true);
                        case "required" -> value.withArray("/required").add("ghost");
                        case "unbounded" -> value.withObject("/properties/draft/properties/dtini").remove("maxLength");
                        case "invalid-enum" -> value.withObject("/properties/draft/properties/dtini").putArray("enum").addNull();
                        case "inverted" -> value.withObject("/properties/neighborhood/properties/rows").put("minItems", 6);
                        case "root-null" -> value.putArray("type").add("object").add("null");
                    }
                    assertThrows(RuleFactContractException.class, () -> RuleFactSchema.compile(value));
                }));
    }

    @Test void compiledSchemasAreDetachedAndRejectCyclicInputBeforeCopying() throws Exception {
        var source = schema(); var compiled = RuleFactSchema.compile(source); String digest = compiled.digest();
        source.removeAll(); ((ObjectNode) compiled.schema()).removeAll();
        compiled.validate(facts()); assertEquals(digest, compiled.digest());
        var cyclic = schema(); cyclic.withObject("/properties").set("cycle", cyclic);
        assertEquals(RuleFactContractException.Code.SCHEMA_LIMIT_EXCEEDED,
                assertThrows(RuleFactContractException.class, () -> RuleFactSchema.compile(cyclic)).code());
    }

    @Test void nullOnlyEnumAndTypedAdditionalPropertiesAreEnforced() throws Exception {
        var source = schema(); var current = source.withObject("/properties/current");
        current.putObject("properties").putObject("date/~").putArray("type").add("string").add("null");
        var field = current.withObject("/properties/date~1~0"); field.put("maxLength", 10).putArray("enum").addNull();
        current.putArray("required").add("date/~");
        var compiled = RuleFactSchema.compile(source); var value = facts();
        value.putObject("current").putNull("date/~"); compiled.validate(value);
        value.withObject("/current").put("date/~", "anything");
        assertThrows(RuleFactContractException.class, () -> compiled.validate(value));
    }

    static RuleSetDefinition definition(String expression, List<String> required) throws Exception {
        return new RuleSetDefinition(new RuleSetRef("enterprise", "requests", "requests.limit", "create", 1),
                List.of("draft", "current", "neighborhood"), List.of(new DecisionSlot("limit", DecisionStage.DOMAIN_DECISION,
                SlotCardinality.SINGLE, OverridePolicy.FORBIDDEN, DecisionAggregationPolicy.SINGLE_RESULT, null, java.util.List.of())),
                List.of(new DecisionBinding("limit.product", "limit", DecisionSource.PRODUCT, null,
                RuleExecutorRef.jsonLogic(JSON.readTree(expression)), List.of(), 0, true, RuleDecision.DENY,
                "LIMIT", required, null)), RuleRuntimeCompatibility.current(), RuleFailPolicy.FAIL_CLOSED);
    }

    static RuleFactContract contract() throws Exception {
        return new RuleFactContract("test/2", "requests.create", "requests:2", RuleFactSchema.compile(schema()), List.of());
    }

    @TestFactory Stream<DynamicTest> rejectsGlobalLocalAndArithmeticDriftAtAdmission() {
        return Stream.of(
            "{\"var\":\"draft.inicio\"}",
            "{\"+\":[{\"var\":\"draft.dtini\"},1]}",
            "{\"all\":[{\"var\":\"neighborhood.rows\"},{\">\":[{\"var\":\"ghost\"},0]}]}",
            "{\"map\":[{\"var\":\"neighborhood.rows\"},{\"var\":\"draft.count\"}]}",
            "{\"map\":[{\"var\":\"draft.count\"},{\"var\":\"\"}]}",
            "{\"reduce\":[{\"var\":\"neighborhood.rows\"},{\"+\":[{\"var\":\"current.ghost\"},{\"var\":\"accumulator\"}]},0]}"
        ).map(expression -> DynamicTest.dynamicTest(expression, () ->
            assertThrows(RuleFactContractException.class, () -> TypedRuleDecisionPlan.compile(
                definition(expression, List.of()), RuleBindingExecutorRegistry.empty(), contract()))));
    }

    @Test void nestedIteratorsAndReduceResolveOnlyTheirLexicalFrame() throws Exception {
        for (String expression : List.of(
                "{\"all\":[{\"filter\":[{\"var\":\"neighborhood.rows\"},{\">=\":[{\"var\":\"days\"},0]}]},{\">=\":[{\"var\":\"days\"},0]}]}",
                "{\"==\":[{\"reduce\":[{\"var\":\"neighborhood.rows\"},{\"+\":[{\"var\":\"current.days\"},{\"var\":\"accumulator\"}]},0]},2]}")) {
            var typed = TypedRuleDecisionPlan.compile(definition(expression, List.of()), RuleBindingExecutorRegistry.empty(), contract());
            assertEquals(RuleDecision.ALLOW, evaluate(typed, facts()).decision(), expression);
        }
    }

    @Test void reduceMayOmitInitialAndLiteralCollectionsKeepTheirIndexBounds() throws Exception {
        for (String expression : List.of(
                "{\"isBlank\":[{\"reduce\":[{\"var\":\"neighborhood.rows\"},{\"var\":\"accumulator\"}]}]}",
                "{\"==\":[{\"+\":[{\"coalesce\":[null,2]},1]},3]}",
                "{\"all\":[[[1,2]],{\"==\":[{\"var\":\"0\"},1]}]}")) {
            var typed = TypedRuleDecisionPlan.compile(definition(expression, List.of()), RuleBindingExecutorRegistry.empty(), contract());
            assertEquals(RuleDecision.ALLOW, evaluate(typed, facts()).decision());
        }
        assertThrows(RuleFactContractException.class, () -> TypedRuleDecisionPlan.compile(
                definition("{\"all\":[[[1,2]],{\"var\":\"2\"}]}", List.of()), RuleBindingExecutorRegistry.empty(), contract()));
    }

    @Test void schemaDriftIsTechnicalBeforeAnyBindingAndNullAncestorDoesNotInventMissingFacts() throws Exception {
        var typed = TypedRuleDecisionPlan.compile(definition("{\"==\":[{\"var\":\"current.dtini\"},null]}",
                List.of("current.dtini")), RuleBindingExecutorRegistry.empty(), contract());
        assertEquals(RuleDecision.ALLOW, evaluate(typed, facts()).decision());
        var invalid = facts(); invalid.withObject("/draft").remove("count");
        var result = evaluate(typed, invalid);
        assertEquals(RuleDecision.TECHNICAL_ERROR, result.decision());
        assertEquals(List.of("FACTS_SCHEMA_INVALID"), result.reasonCodes()); assertTrue(result.bindingResults().isEmpty());
        assertThrows(RuleFactContractException.class, () -> new PraxisRuleSetEngine(RuleBindingExecutorRegistry.empty())
                .evaluate(typed, "test/2", "forged", "requests:2", facts(), "2026-11-05T12:00:00Z", "UTC"));
    }

    private static RuleEvaluationResult evaluate(TypedRuleDecisionPlan typed, ObjectNode facts) {
        return new PraxisRuleSetEngine(RuleBindingExecutorRegistry.empty()).evaluate(typed,
                "test/2", "requests.create", "requests:2", facts, "2026-11-05T12:00:00Z", "UTC").result();
    }
}
