package org.praxisplatform.rules.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.ValueInstantiationException;
import org.junit.jupiter.api.Test;

class RuleExecutorRefJsonTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void javaExecutorRoundTripsThroughExplicitJsonNull() throws Exception {
        RuleExecutorRef original = RuleExecutorRef.java("benefits:amount", "1.0.0");

        String serialized = JSON.writeValueAsString(original);
        RuleExecutorRef restored = JSON.readValue(serialized, RuleExecutorRef.class);

        assertEquals(original, restored);
        assertNull(restored.expression());
    }

    @Test
    void javaExecutorStillRejectsAnExpressionObject() throws Exception {
        String invalid = """
                {
                  "type": "JAVA",
                  "implementationKey": "benefits:amount",
                  "implementationVersion": "1.0.0",
                  "expression": {"var": "request.amount"}
                }
                """;

        ValueInstantiationException exception = assertThrows(
                ValueInstantiationException.class,
                () -> JSON.readValue(invalid, RuleExecutorRef.class));
        assertEquals("JAVA executor cannot declare an expression", exception.getCause().getMessage());
    }
}
