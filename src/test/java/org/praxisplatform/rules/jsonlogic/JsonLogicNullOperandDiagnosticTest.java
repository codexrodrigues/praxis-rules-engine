package org.praxisplatform.rules.jsonlogic;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.praxisplatform.rules.jsonlogic.model.*;
import static org.junit.jupiter.api.Assertions.*;

class JsonLogicNullOperandDiagnosticTest {
    private final ObjectMapper json = new ObjectMapper();
    private final PraxisJsonLogicEngine engine = new PraxisJsonLogicEngine();
    private final JsonLogicEvaluationOptions options = new JsonLogicEvaluationOptions(
            List.of("value","draft"),null,false,null,null);

    @Test void reportsParsedNullVariableOriginsWithoutChangingTheErrorCode() throws Exception {
        for (String path : List.of("value","$.value")) {
            var failure = failure("{\"<=\":[{\"var\":\""+path+"\"},{\"var\":\"draft.limit\"}]}",
                    "{\"value\":null,\"draft\":{\"limit\":10}}");
            assertEquals(JsonLogicIssueCode.RULE_ARGUMENT_TYPE_INVALID,failure.getCode());
            assertEquals(List.of(List.of("value")),failure.getNullOperandPaths());
            assertThrows(UnsupportedOperationException.class, () -> failure.getNullOperandPaths().getFirst().add("changed"));
        }
    }
    @Test void doesNotCertifyOtherInvalidOperandsOrZeroDivisors() throws Exception {
        for (String expression : List.of(
                "{\"+\":[{\"var\":\"value\"},\"wrong\"]}",
                "{\"/\":[{\"var\":\"value\"},0]}",
                "{\"<=\":[{\"var\":\"value\"},[]]}",
                "{\"/\":[{\"var\":\"draft.limit\"},0]}")) {
            assertTrue(failure(expression,"{\"value\":null,\"draft\":{\"limit\":10}}").getNullOperandPaths().isEmpty());
        }
    }
    @Test void doesNotAttributeIteratorLocalVariablesToOuterRoots() throws Exception {
        var failure = failure("{\"all\":[{\"var\":\"draft.rows\"},{\"<\":[{\"var\":\"value\"},10]}]}",
                "{\"value\":null,\"draft\":{\"rows\":[{\"value\":null}]}}");
        assertEquals(JsonLogicIssueCode.RULE_ARGUMENT_TYPE_INVALID,failure.getCode());
        assertTrue(failure.getNullOperandPaths().isEmpty());
    }
    @Test void preservesAllNullOriginsInsteadOfBlamingOnlyOneField() throws Exception {
        assertEquals(List.of(List.of("value"),List.of("draft","limit")),failure(
                "{\"<\":[{\"var\":\"value\"},{\"var\":\"draft.limit\"}]}",
                "{\"value\":null,\"draft\":{\"limit\":null}}").getNullOperandPaths());
    }
    private PraxisJsonLogicException failure(String expression,String facts) throws Exception {
        var tree=json.readTree(expression); var data=json.readTree(facts);
        return assertThrows(PraxisJsonLogicException.class, () -> engine.evaluate(tree,data,options));
    }
}
