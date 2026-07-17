package org.praxisplatform.rules.jsonlogic;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.praxisplatform.rules.jsonlogic.model.*;
import org.praxisplatform.rules.jsonlogic.operators.*;

class PraxisJsonLogicEngineTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final PraxisJsonLogicEngine engine = new PraxisJsonLogicEngine();
    private static JsonLogicEvaluationOptions options() { return new JsonLogicEvaluationOptions(List.of("row","meta"),null,false,"2026-11-01T04:30:00Z","America/New_York"); }
    private Object eval(String expression,String data){try{return engine.evaluate(JSON.readTree(expression),JSON.readTree(data),options());}catch(java.io.IOException e){throw new AssertionError(e);}}

    @Test void distinguishesMissingFromNullForDefaultAndHasKey(){
        assertEquals("fallback",eval("{\"var\":[\"row.missing\",\"fallback\"]}","{\"row\":{\"present\":null}}"));
        assertNull(eval("{\"var\":[\"row.present\",\"fallback\"]}","{\"row\":{\"present\":null}}"));
        assertEquals(true,eval("{\"hasKey\":[{\"var\":\"row\"},\"present\"]}","{\"row\":{\"present\":null}}"));
    }

    @Test void normalizesNumbersAndUsesClosedLooseEquality(){
        assertEquals(true,eval("{\"===\":[1,1.0]}","{}"));
        assertEquals(true,eval("{\"==\":[1,\"1\"]}","{}"));
        assertEquals(false,eval("{\"===\":[1,\"1\"]}","{}"));
        assertEquals(true,eval("{\"===\":[[1], [1.0]]}","{}"));
    }

    @Test void shortCircuitsAndEvaluatesTemporalContextOnce(){
        assertEquals(false,eval("{\"and\":[false,{\"unknown\":[]}]}","{}"));
        assertEquals(true,eval("{\"or\":[true,{\"unknown\":[]}]}","{}"));
        assertEquals("ok",eval("{\"if\":[true,\"ok\",{\"unknown\":[]}]}","{}"));
        assertEquals(true,eval("{\"===\":[{\"now\":[]},{\"now\":[]}]}","{}"));
    }

    @Test void rejectsPathsRegexAndLimitsDeterministically() throws Exception {
        for(String path:List.of("a..b","a[*]","a[-1]","a[","$..a","a[?x]")){
            var result=engine.validateResult(JSON.readTree("{\"var\":\""+path.replace("\\","\\\\").replace("\"","\\\"")+"\"}"),new JsonLogicValidationOptions(List.of("row","meta"),null,false,null,null,true));
            assertEquals(JsonLogicIssueCode.RULE_PATH_INVALID,result.issues().get(0).code(),path);
        }
        PraxisJsonLogicException regex=assertThrows(PraxisJsonLogicException.class,()->eval("{\"matches\":[\"aaaa\",\"(a+)+\"]}","{}"));
        assertEquals(JsonLogicIssueCode.RULE_REGEX_INVALID,regex.getCode());
        JsonLogicLimits tiny=new JsonLogicLimits(2,3,100,2,10,2,10,2);
        assertEquals(JsonLogicIssueCode.RULE_LIMIT_EXCEEDED,assertThrows(PraxisJsonLogicException.class,()->engine.evaluate(JSON.readTree("{\"and\":[true,true,true]}"),JSON.createObjectNode(),new JsonLogicEvaluationOptions(List.of(),null,true,null,null,tiny))).getCode());
        JsonLogicLimits shortStrings=new JsonLogicLimits(10,20,100,10,3,20,10,2);
        assertEquals(JsonLogicIssueCode.RULE_LIMIT_EXCEEDED,engine.validateResult(JSON.readTree("{\"===\":[\"long\",\"long\"]}"),new JsonLogicValidationOptions(List.of(),null,true,null,null,true,shortStrings)).issues().get(0).code());
    }

    @Test void returnsStructuredErrorsForInvalidRuntimeArguments() {
        PraxisJsonLogicException division=assertThrows(PraxisJsonLogicException.class,()->eval("{\"/\":[1,0]}","{}"));
        assertEquals(JsonLogicIssueCode.RULE_ARGUMENT_TYPE_INVALID,division.getCode());
        assertEquals("/",division.getOperator());
        PraxisJsonLogicException substring=assertThrows(PraxisJsonLogicException.class,()->eval("{\"substr\":[\"abc\",\"x\"]}","{}"));
        assertEquals(JsonLogicIssueCode.RULE_ARGUMENT_TYPE_INVALID,substring.getCode());
        assertEquals("substr",substring.getOperator());
    }

    @Test void validatesVarArityAndEvaluatesItsDefaultExpressionLazily() throws Exception {
        JsonLogicValidationOptions validationOptions = new JsonLogicValidationOptions(
                List.of("row", "meta"), null, false, null, null, true);
        JsonNode valid = JSON.readTree("{\"var\":[\"row.missing\",{\"cat\":[\"a\",\"b\"]}]}");
        assertTrue(engine.validateResult(valid, validationOptions).valid());
        assertEquals("ab", engine.evaluate(valid, JSON.readTree("{\"row\":{}}"), options()));
        assertEquals("present", engine.evaluate(
                valid, JSON.readTree("{\"row\":{\"missing\":\"present\"}}"), options()));

        JsonLogicValidationResult invalidDefault = engine.validateResult(
                JSON.readTree("{\"var\":[\"row.present\",{\"unknown\":[]}]}") , validationOptions);
        assertFalse(invalidDefault.valid());
        assertEquals(JsonLogicIssueCode.RULE_OPERATOR_UNKNOWN, invalidDefault.issues().getFirst().code());
        assertEquals("$.var[1]", invalidDefault.issues().getFirst().path());

        for (String invalid : List.of("{\"var\":[]}", "{\"var\":[\"row.value\",1,2]}")) {
            JsonLogicValidationResult result = engine.validateResult(JSON.readTree(invalid), validationOptions);
            assertFalse(result.valid(), invalid);
            assertEquals(JsonLogicIssueCode.RULE_ARITY_INVALID, result.issues().getFirst().code(), invalid);
        }
        JsonNode invalidPathType = JSON.readTree("{\"var\":[1,\"fallback\"]}");
        assertEquals(JsonLogicIssueCode.RULE_ARGUMENT_TYPE_INVALID,
                engine.validateResult(invalidPathType, validationOptions).issues().getFirst().code());
        PraxisJsonLogicException runtimeType = assertThrows(
                PraxisJsonLogicException.class,
                () -> engine.evaluate(invalidPathType, JSON.createObjectNode(), options()));
        assertEquals(JsonLogicIssueCode.RULE_ARGUMENT_TYPE_INVALID, runtimeType.getCode());
    }

    @Test void reportsGiantPathIndexesAndRegexBoundsAsStructuredErrors() throws Exception {
        String giant = "9".repeat(100);
        JsonLogicValidationOptions validationOptions = new JsonLogicValidationOptions(
                List.of("row", "meta"), null, false, null, null, true);
        JsonLogicValidationResult path = engine.validateResult(
                JSON.readTree("{\"var\":\"row[" + giant + "]\"}"), validationOptions);
        assertFalse(path.valid());
        assertEquals(JsonLogicIssueCode.RULE_PATH_INVALID, path.issues().getFirst().code());
        PraxisJsonLogicException pathRuntime = assertThrows(
                PraxisJsonLogicException.class,
                () -> eval("{\"var\":\"row[" + giant + "]\"}", "{\"row\":[]}"));
        assertEquals(JsonLogicIssueCode.RULE_PATH_INVALID, pathRuntime.getCode());
        String quotedNumericKey = "{\"var\":\"row[\\\"" + giant + "\\\"]\"}";
        assertTrue(engine.validateResult(JSON.readTree(quotedNumericKey), validationOptions).valid());
        assertEquals("kept", eval(quotedNumericKey, "{\"row\":{\"" + giant + "\":\"kept\"}}"));

        String expression = "{\"matches\":[\"a\",\"a{" + giant + "}\"]}";
        JsonLogicValidationResult regex = engine.validateResult(JSON.readTree(expression), validationOptions);
        assertFalse(regex.valid());
        assertEquals(JsonLogicIssueCode.RULE_REGEX_INVALID, regex.issues().getFirst().code());
        PraxisJsonLogicException regexRuntime = assertThrows(
                PraxisJsonLogicException.class, () -> eval(expression, "{}"));
        assertEquals(JsonLogicIssueCode.RULE_REGEX_INVALID, regexRuntime.getCode());
    }

    @Test void reduceAcceptsNullItemsAndValidationTraversesLiteralObjects() throws Exception {
        assertEquals(2,((Number)eval("{\"reduce\":[[null,2],{\"+\":[{\"var\":\"accumulator\"},{\"if\":[{\"===\":[{\"var\":\"current\"},null]},0,{\"var\":\"current\"}]}]},0]}","{}")).intValue());
        JsonLogicValidationResult invalid=engine.validateResult(JSON.readTree("{\"===\":[{\"left\":{\"unknown\":[]},\"right\":1},1]}"),new JsonLogicValidationOptions(List.of(),null,true,null,null,true));
        assertFalse(invalid.valid());
        assertEquals(JsonLogicIssueCode.RULE_OPERATOR_UNKNOWN,invalid.issues().get(0).code());
    }

    @Test void rejectsUnknownTemporalUnits() {
        PraxisJsonLogicException error=assertThrows(PraxisJsonLogicException.class,()->eval("{\"inLast\":[\"2026-10-31\",2,\"fortnights\"]}","{}"));
        assertEquals(JsonLogicIssueCode.RULE_TEMPORAL_INPUT_INVALID,error.getCode());
    }

    @Test void rejectsExpandedResultsAndUnboundedRegex() throws Exception {
        JsonLogicLimits resultLimit=new JsonLogicLimits(20,100,1000,3,100,100,100,20);
        PraxisJsonLogicException expanded=assertThrows(PraxisJsonLogicException.class,()->engine.evaluate(JSON.readTree("{\"merge\":[[1,2],[3,4]]}"),JSON.createObjectNode(),new JsonLogicEvaluationOptions(List.of(),null,true,null,null,resultLimit)));
        assertEquals(JsonLogicIssueCode.RULE_LIMIT_EXCEEDED,expanded.getCode());
        for(String pattern:List.of("a+","(a|aa)+","a{1,257}")){
            PraxisJsonLogicException unsafe=assertThrows(PraxisJsonLogicException.class,()->eval("{\"matches\":[\"aaaa\",\""+pattern+"\"]}","{}"));
            assertEquals(JsonLogicIssueCode.RULE_REGEX_INVALID,unsafe.getCode(),pattern);
        }
        assertEquals(true,eval("{\"matches\":[\"AAAA\",\"^[A-Z]{1,16}$\"]}","{}"));
    }

    @Test void isDeterministicAcrossLocaleTimezoneAndThreads() throws Exception {
        Locale oldLocale=Locale.getDefault(); TimeZone oldZone=TimeZone.getDefault();
        try{
            Object expected=eval("{\"isToday\":[\"2026-11-01T05:30:00Z\"]}","{}");
            Locale.setDefault(Locale.JAPAN); TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Auckland"));
            assertEquals(expected,eval("{\"isToday\":[\"2026-11-01T05:30:00Z\"]}","{}"));
            try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
                var tasks=java.util.stream.IntStream.range(0,100).mapToObj(i->pool.submit(()->eval("{\"isToday\":[\"2026-11-01T05:30:00Z\"]}","{}"))).toList();
                for(var task:tasks)assertEquals(expected,task.get());
            }
        }finally{Locale.setDefault(oldLocale);TimeZone.setDefault(oldZone);}
    }

    @Test void exposesGovernedRegistryAndRejectsHostConflicts(){
        assertEquals(54,engine.listOperatorDescriptors().size());
        PraxisJsonLogicOperator host=operator("corp:isAllowed");
        assertEquals(55,engine.withHostOperators(List.of(host)).listOperatorDescriptors().size());
        assertEquals(JsonLogicIssueCode.RULE_OPERATOR_CONFLICT,assertThrows(PraxisJsonLogicException.class,()->engine.withHostOperators(List.of(operator("and")))).getCode());
        assertEquals(JsonLogicIssueCode.RULE_OPERATOR_CONFLICT,assertThrows(PraxisJsonLogicException.class,()->engine.withHostOperators(List.of(operator("isAllowed")))).getCode());
    }

    @Test void neutralConsumerCanValidateEvaluateMultiRootTemporalAndInspectRegistry() throws Exception {
        JsonNode expression=JSON.readTree("{\"and\":[{\"===\":[{\"var\":\"row.status\"},\"OPEN\"]},{\"isToday\":[{\"var\":\"meta.at\"}]}]}");
        assertTrue(engine.validateResult(expression,new JsonLogicValidationOptions(List.of("row","meta"),null,false,"2026-11-01T04:30:00Z","America/New_York",true)).valid());
        assertTrue(engine.evaluateResult(expression,JSON.readTree("{\"row\":{\"status\":\"OPEN\"},\"meta\":{\"at\":\"2026-11-01\"}}"),options()).truthy());
        assertNotNull(engine.listOperatorDescriptors().stream().filter(d->d.operator().equals("isToday")).findFirst().orElseThrow());
        assertEquals(JsonLogicIssueCode.RULE_CONTEXT_AMBIGUOUS,assertThrows(PraxisJsonLogicException.class,()->eval("{\"var\":\"status\"}","{}" )).getCode());
    }

    private static PraxisJsonLogicOperator operator(String name){return new PraxisJsonLogicOperator(){public JsonLogicOperatorDescriptor descriptor(){return new JsonLogicOperatorDescriptor(name,"corp",0,0,"boolean","pure",false,"host","1.0");}public Object evaluate(List<Object>a,JsonLogicEvaluationContext c,PraxisJsonLogicEngine e){return true;}};}
}
