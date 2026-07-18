package org.praxisplatform.rules.jsonlogic;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.InputStream;
import java.security.MessageDigest;
import org.junit.jupiter.api.Test;
import org.praxisplatform.rules.contract.RuleRuntimeCompatibility;
import org.praxisplatform.rules.jsonlogic.conformance.JsonLogicConformanceFixtureLoader;
import org.praxisplatform.rules.jsonlogic.model.JsonLogicEvaluationOptions;
import org.praxisplatform.rules.jsonlogic.model.JsonLogicEvaluationResult;
import org.praxisplatform.rules.jsonlogic.model.JsonLogicIssueCode;
import org.praxisplatform.rules.jsonlogic.model.JsonLogicValidationOptions;
import org.praxisplatform.rules.jsonlogic.model.JsonLogicValidationResult;

class PraxisJsonLogicConformanceTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final PraxisJsonLogicEngine engine = new PraxisJsonLogicEngine();

    @Test
    void packagedCorpusHashMustMatchRuntimeCompatibility() throws Exception {
        assertEquals(
                RuleRuntimeCompatibility.JSON_LOGIC_CORPUS_SHA256,
                HexFormat.of().withUpperCase().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(packagedCorpus())));
    }

    @Test
    void packagedCorpusMustMatchAngularCanonicalCorpusWhenBuiltInPlatformWorkspace() throws IOException {
        String configuredPath = System.getProperty("praxis.angular.corpus.path");
        Path angular = configuredPath == null || configuredPath.isBlank()
                ? Path.of("..", "praxis-ui-angular", "docs", "json-logic-conformance", "conformance-fixtures.json").normalize()
                : Path.of(configuredPath).normalize();
        if (!Files.exists(angular)) return; // isolated Maven builds prove only the packaged resource
        assertArrayEquals(
                Files.readAllBytes(angular),
                packagedCorpus(),
                "packaged corpus drifted byte-for-byte from the normative Angular corpus");
    }

    @Test
    void shouldPassEvaluationCasesFromSharedCorpus() throws IOException {
        JsonNode corpus = JsonLogicConformanceFixtureLoader.loadSharedCorpus();
        for (JsonNode testCase : corpus.path("evaluationCases")) {
            JsonLogicEvaluationResult result = engine.evaluateResult(
                testCase.path("expression"),
                testCase.path("data"),
                evaluationOptions(testCase.path("options"))
            );
            assertTrue(canonicalJsonEquals(testCase.path("expect").path("value"), MAPPER.valueToTree(result.value())), testCase.path("id").asText());
            assertEquals(
                testCase.path("expect").path("truthy").asBoolean(),
                result.truthy(),
                testCase.path("id").asText()
            );
        }
    }

    @Test
    void shouldPublishExactlyTheCanonicalOperatorCatalog() throws IOException {
        JsonNode expected = JsonLogicConformanceFixtureLoader.loadSharedCorpus().path("operatorCatalog");
        var descriptors = engine.listOperatorDescriptors();
        assertEquals(expected.size(), descriptors.size());
        for (int index = 0; index < descriptors.size(); index++) {
            var descriptor = descriptors.get(index);
            JsonNode row = expected.get(index);
            assertEquals(row.get(0).asText(), descriptor.operator());
            assertEquals(row.get(1).asText(), descriptor.source());
            assertEquals(row.get(2).asInt(), descriptor.minArgs());
            assertEquals(row.get(3).isNull() ? null : row.get(3).asInt(), descriptor.maxArgs());
        }
    }

    @Test
    void shouldPassValidationCasesFromSharedCorpus() throws IOException {
        JsonNode corpus = JsonLogicConformanceFixtureLoader.loadSharedCorpus();
        for (JsonNode testCase : corpus.path("validationCases")) {
            JsonLogicValidationResult result = engine.validateResult(
                testCase.path("expression"),
                validationOptions(testCase.path("options"))
            );
            if (testCase.path("expect").path("valid").asBoolean()) {
                assertTrue(result.valid(), testCase.path("id").asText());
                continue;
            }
            assertFalse(result.valid(), testCase.path("id").asText());
            JsonLogicIssueCode expected = JsonLogicIssueCode.valueOf(testCase.path("expect").path("codes").get(0).asText());
            assertEquals(expected, result.issues().get(0).code(), testCase.path("id").asText());
        }
    }

    @Test
    void shouldReturnStructuredErrorsFromSharedCorpus() throws IOException {
        JsonNode corpus = JsonLogicConformanceFixtureLoader.loadSharedCorpus();
        for (JsonNode testCase : corpus.path("evaluationErrorCases")) {
            PraxisJsonLogicException error = assertThrows(PraxisJsonLogicException.class, () -> engine.evaluate(
                testCase.path("expression"), testCase.path("data"), evaluationOptions(testCase.path("options"))
            ), testCase.path("id").asText());
            assertEquals(JsonLogicIssueCode.valueOf(testCase.path("expect").path("code").asText()), error.getCode());
            assertEquals(testCase.path("expect").path("operator").asText(null), error.getOperator());
        }
    }

    private JsonLogicEvaluationOptions evaluationOptions(JsonNode node) {
        return new JsonLogicEvaluationOptions(
            textArray(node.path("availableRoots")),
            node.path("defaultRoot").isMissingNode() ? null : node.path("defaultRoot").asText(null),
            node.path("allowImplicitRoot").isMissingNode() ? null : node.path("allowImplicitRoot").asBoolean(),
            node.path("nowUtc").isMissingNode() ? null : node.path("nowUtc").asText(null),
            node.path("userTimeZone").isMissingNode() ? null : node.path("userTimeZone").asText(null)
        );
    }

    private JsonLogicValidationOptions validationOptions(JsonNode node) {
        return new JsonLogicValidationOptions(
            textArray(node.path("availableRoots")),
            node.path("defaultRoot").isMissingNode() ? null : node.path("defaultRoot").asText(null),
            node.path("allowImplicitRoot").isMissingNode() ? null : node.path("allowImplicitRoot").asBoolean(),
            node.path("nowUtc").isMissingNode() ? null : node.path("nowUtc").asText(null),
            node.path("userTimeZone").isMissingNode() ? null : node.path("userTimeZone").asText(null),
            node.path("requireExpressionObject").isMissingNode() ? null : node.path("requireExpressionObject").asBoolean()
        );
    }

    private List<String> textArray(JsonNode node) {
        if (!node.isArray()) {
            return null;
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            values.add(item.asText());
        }
        return values;
    }

    private boolean canonicalJsonEquals(JsonNode left, JsonNode right) {
        if (left.isNumber() && right.isNumber()) return left.decimalValue().compareTo(right.decimalValue()) == 0;
        if (left.isArray() && right.isArray()) {
            if (left.size() != right.size()) return false;
            for (int index = 0; index < left.size(); index++) if (!canonicalJsonEquals(left.get(index), right.get(index))) return false;
            return true;
        }
        if (left.isObject() && right.isObject()) {
            if (left.size() != right.size()) return false;
            for (var field : left.properties()) if (!right.has(field.getKey()) || !canonicalJsonEquals(field.getValue(), right.get(field.getKey()))) return false;
            return true;
        }
        return left.equals(right);
    }

    private byte[] packagedCorpus() throws IOException {
        try (InputStream packaged = getClass().getResourceAsStream(
                "/org/praxisplatform/rules/jsonlogic/conformance-fixtures.json")) {
            assertNotNull(packaged);
            return packaged.readAllBytes();
        }
    }
}
