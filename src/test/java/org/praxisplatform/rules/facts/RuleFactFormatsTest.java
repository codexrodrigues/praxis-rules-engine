package org.praxisplatform.rules.facts;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

class RuleFactFormatsTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    record Format(String name, String valid, List<String> invalid) { }

    @TestFactory Stream<DynamicTest> formatsPreserveCalendarOffsetCivilCoordinatesAndExactDecimals() {
        return Stream.of(
            new Format("date", "2024-02-29", List.of("2023-02-29", "2024-02-30", "2024-02-29T00:00:00Z")),
            new Format("date-time", "2024-02-29T23:10:25.123-03:00", List.of("2024-02-29T23:10:25", "2024-02-30T00:00:00Z", "2024-02-29T24:00:00Z")),
            new Format("praxis-local-date-time", "2024-02-29T23:10:25.123456789", List.of("2024-02-29T23:10:25Z", "2024-02-30T00:00:00", "2024-02-29T23:10")),
            new Format("praxis-local-time", "23:10:25.123", List.of("24:00", "12:30Z", "12:30:00-03:00")),
            new Format("praxis-duration", "-PT15M", List.of("P1M", "P1Y", "15m")),
            new Format("praxis-zone-id", "America/Sao_Paulo", List.of("Mars/Nowhere", "", "Brazil/Invented")),
            new Format("praxis-decimal", "12345678901234567890.1234567890", List.of("01.00", "1e2", " 1.00"))
        ).map(format -> DynamicTest.dynamicTest(format.name(), () -> {
            var schema = JSON.createObjectNode().put("type", "object").put("additionalProperties", false).put("maxProperties", 1);
            schema.putArray("required").add("value");
            schema.putObject("properties").putObject("value").put("type", "string").put("maxLength", 128).put("format", format.name());
            var compiled = RuleFactSchema.compile(schema);
            compiled.validate(JSON.createObjectNode().put("value", format.valid()));
            for (String invalid : format.invalid()) assertThrows(RuleFactContractException.class,
                () -> compiled.validate(JSON.createObjectNode().put("value", invalid)), format.name() + " " + invalid);
        }));
    }

    @Test void structuralBudgetsRejectPojoNonfiniteAndOversizedProviderValues() throws Exception {
        var schema = RuleFactSchema.compile(RuleFactSchemaTest.schema());
        var pojo = RuleFactSchemaTest.facts(); pojo.putPOJO("other", new Object());
        assertThrows(IllegalArgumentException.class, () -> schema.validate(pojo));
        var invalidNumber = RuleFactSchemaTest.facts(); invalidNumber.withObject("/draft").put("count", Double.NaN);
        assertThrows(IllegalArgumentException.class, () -> schema.validate(invalidNumber));
        var huge = RuleFactSchemaTest.facts(); huge.withObject("/draft").put("dtini", "x".repeat(64_001));
        assertThrows(IllegalArgumentException.class, () -> schema.validate(huge));
        var recursive = RuleFactSchemaTest.facts(); recursive.set("loop", recursive);
        assertThrows(IllegalArgumentException.class, () -> schema.validate(recursive));
    }

    @Test void typedMapValuesAreValidatedUsingTheirOwnShapeAndEscapedFieldIdentity() throws Exception {
        var schema = JSON.readTree("""
            {"type":"object","properties":{},"required":[],"maxProperties":3,
             "additionalProperties":{"type":"string","format":"date","maxLength":10}}
            """);
        var compiled = RuleFactSchema.compile(schema);
        compiled.validate(JSON.createObjectNode().put("date/~", "2024-02-29"));
        var failure = assertThrows(RuleFactContractException.class,
                () -> compiled.validate(JSON.createObjectNode().put("date/~", "2024-02-30")));
        assertEquals("$/*", failure.path());
        assertFalse(failure.getMessage().contains("date/~"));
    }
}
