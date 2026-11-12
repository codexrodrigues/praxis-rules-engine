package org.praxisplatform.rules.facts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class RuleFactCatalogLoaderTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    enum SampleOperation {
        CREATE("CREATE"),
        UPDATE("UPDATE");

        private final String key;

        SampleOperation(String key) {
            this.key = key;
        }

        String key() {
            return key;
        }
    }

    @Test
    void parsesCatalogCompilesSchemasExtractsValueTypesAndBuildsContracts() throws Exception {
        String catalogJson =
                """
                {
                  "schemaVersion": "praxis.domain-rule-fact-catalog.v2",
                  "operations": [
                    {
                      "operationKey": "CREATE",
                      "snapshotSchema": {
                        "type": "object",
                        "additionalProperties": false,
                        "maxProperties": 2,
                        "required": ["draft", "neighborhood"],
                        "properties": {
                          "draft": {
                            "type": "object",
                            "additionalProperties": false,
                            "maxProperties": 4,
                            "required": ["dtini", "cpf", "ativo"],
                            "properties": {
                              "dtini": { "type": "string", "format": "date", "maxLength": 10 },
                              "cpf": { "type": ["string", "null"], "maxLength": 14 },
                              "ativo": { "type": "boolean" },
                              "codigos": {
                                "type": "array",
                                "minItems": 0,
                                "maxItems": 10,
                                "items": { "type": "string", "maxLength": 20 }
                              }
                            }
                          },
                          "neighborhood": {
                            "type": "object",
                            "additionalProperties": false,
                            "maxProperties": 1,
                            "required": ["periods"],
                            "properties": {
                              "periods": {
                                "type": "array",
                                "minItems": 0,
                                "maxItems": 20,
                                "items": {
                                  "type": "object",
                                  "additionalProperties": false,
                                  "maxProperties": 1,
                                  "required": ["days"],
                                  "properties": {
                                    "days": { "type": "integer", "minimum": 0, "maximum": 365 }
                                  }
                                }
                              }
                            }
                          }
                        }
                      },
                      "facts": [
                        {
                          "schemaPointer": "#/properties/draft/properties/dtini",
                          "labels": { "pt-BR": "Data de início", "en-US": "Start date" },
                          "descriptions": {
                            "pt-BR": "Data civil de início de vigência do ato funcional.",
                            "en-US": "Civil effective start date."
                          },
                          "providerRef": "SampleFactSource#v1",
                          "evidenceRefs": ["PCK_SAMPLE.P_VALIDA", "Lei 8.112/1990"],
                          "sensitivity": "NON_SENSITIVE",
                          "redaction": "NONE"
                        },
                        {
                          "schemaPointer": "#/properties/draft/properties/cpf",
                          "labels": { "pt-BR": "CPF do servidor", "en-US": "Employee CPF" },
                          "descriptions": {
                            "pt-BR": "Cadastro de Pessoa Física sob proteção da LGPD.",
                            "en-US": "Taxpayer ID under LGPD."
                          },
                          "providerRef": "SampleFactSource#v1",
                          "evidenceRefs": ["LGPD, art. 5º, I"],
                          "sensitivity": "SENSITIVE",
                          "redaction": "OMIT"
                        },
                        {
                          "schemaPointer": "#/properties/draft/properties/codigos",
                          "labels": { "pt-BR": "Códigos", "en-US": "Codes" },
                          "descriptions": { "pt-BR": "Lista de códigos associados." },
                          "providerRef": "SampleFactSource#v1",
                          "evidenceRefs": ["PCK_SAMPLE.P_CODIGOS"],
                          "sensitivity": "SECRET",
                          "redaction": "HASH"
                        },
                        {
                          "schemaPointer": "#/properties/neighborhood/properties/periods/items",
                          "labels": { "pt-BR": "Período vizinho" },
                          "descriptions": { "pt-BR": "Elemento da coleção de períodos." },
                          "providerRef": "SampleFactSource#v1",
                          "evidenceRefs": ["PCK_SAMPLE.P_CONTAGEM"],
                          "sensitivity": "NON_SENSITIVE",
                          "redaction": "NONE"
                        },
                        {
                          "schemaPointer": "#/properties/neighborhood/properties/periods/items/properties/days",
                          "labels": { "pt-BR": "Dias no período", "en-US": "Days in period" },
                          "descriptions": {
                            "pt-BR": "Quantidade de dias computados no período vizinho.",
                            "en-US": "Number of days in sibling period."
                          },
                          "providerRef": "SampleFactSource#v1",
                          "evidenceRefs": ["PCK_SAMPLE.P_CONTAGEM"],
                          "sensitivity": "NON_SENSITIVE",
                          "redaction": "NONE"
                        }
                      ]
                    },
                    {
                      "operationKey": "UPDATE",
                      "snapshotSchema": {
                        "type": "object",
                        "additionalProperties": false,
                        "maxProperties": 1,
                        "required": ["draft"],
                        "properties": {
                          "draft": {
                            "type": "object",
                            "additionalProperties": false,
                            "maxProperties": 1,
                            "required": ["dtini"],
                            "properties": {
                              "dtini": { "type": "string", "format": "date", "maxLength": 10 }
                            }
                          }
                        }
                      },
                      "facts": [
                        {
                          "schemaPointer": "#/properties/draft/properties/dtini",
                          "labels": { "pt-BR": "Data de início", "en-US": "Start date" },
                          "descriptions": {
                            "pt-BR": "Data civil de início de vigência na retificação.",
                            "en-US": "Civil effective start date on update."
                          },
                          "providerRef": "SampleFactSource#v1",
                          "evidenceRefs": ["PCK_SAMPLE.P_VALIDA"],
                          "sensitivity": "NON_SENSITIVE",
                          "redaction": "NONE"
                        }
                      ]
                    }
                  ]
                }
                """;

        JsonNode root = MAPPER.readTree(catalogJson);
        RuleFactCatalogDocument document = RuleFactCatalogLoader.parse(root);

        assertEquals(RuleFactCatalogDocument.SCHEMA_VERSION_V2, document.schemaVersion());
        assertEquals(2, document.operations().size());

        RuleFactDescriptor dtini =
                document.findFact("CREATE", "draft.dtini").orElseThrow();
        assertEquals("#/properties/draft/properties/dtini", dtini.schemaPointer());
        assertEquals("date", dtini.valueType());
        assertFalse(dtini.nullable());
        assertEquals("Data de início", dtini.label("pt-BR"));
        assertEquals("Start date", dtini.label("en-US"));
        assertEquals("NON_SENSITIVE", dtini.canonicalSensitivity());
        assertEquals("NONE", dtini.redaction());

        RuleFactDescriptor cpf =
                document.findFact("CREATE", "#/properties/draft/properties/cpf").orElseThrow();
        assertEquals("draft.cpf", cpf.factPath());
        assertEquals("string", cpf.valueType());
        assertTrue(cpf.nullable());
        assertEquals("SENSITIVE", cpf.canonicalSensitivity());
        assertEquals("OMIT", cpf.redaction());

        RuleFactDescriptor codigos =
                document.findFact("CREATE", "draft.codigos").orElseThrow();
        assertEquals("string-array", codigos.valueType());
        assertTrue(codigos.nullable());
        assertEquals("SECRET", codigos.canonicalSensitivity());

        // Even un-curated paths in snapshotSchema resolve exact valueType/nullable
        RuleFactOperationCatalog createOp = document.requireOperation("CREATE");
        assertEquals("boolean", createOp.inferValueType("draft.ativo"));
        assertFalse(createOp.isNullable("draft.ativo"));

        // Array item pointer converts to STABLE_FACT_PATH-compliant identifier
        RuleFactDescriptor periodItem =
                document.findFact("CREATE", "neighborhood.periods:items").orElseThrow();
        String stablePath = RuleFactDescriptor.toStableFactPath(periodItem.factPath());
        assertTrue(RuleFactDescriptor.STABLE_FACT_PATH_PATTERN.matcher(stablePath).matches());

        RuleFactDescriptor nestedDays =
                document.findFactAcrossOperations("neighborhood.periods.days").orElseThrow();
        assertEquals("number", nestedDays.valueType());
        assertFalse(nestedDays.nullable());

        // Extended DTO aliases normalize cleanly to canonical sensitivity set
        assertEquals("SENSITIVE", RuleFactDescriptor.normalizeSensitivity("FINANCIAL"));
        assertEquals("SENSITIVE", RuleFactDescriptor.normalizeSensitivity("SENSITIVE_PERSONAL"));
        assertEquals("SECRET", RuleFactDescriptor.normalizeSensitivity("CREDENTIAL"));

        Map<SampleOperation, RuleFactContract> contracts =
                document.buildContracts(
                        SampleOperation.class,
                        SampleOperation::key,
                        "sample-facts/v1",
                        "SampleFactSource#v1");

        assertEquals(2, contracts.size());
        RuleFactContract createContract = contracts.get(SampleOperation.CREATE);
        assertNotNull(createContract);
        assertEquals("CREATE", createContract.operationKey());
        assertEquals("string", createContract.schema().typeAt("draft.dtini"));
    }

    @Test
    void parsesAllRealShowcaseFactCatalogsWhenPresentInMonorepo() throws Exception {
        Path showcaseFactsDir =
                Path.of("../praxis-ergon-showcase/src/main/resources/rules/facts").normalize();
        Assumptions.assumeTrue(
                Files.isDirectory(showcaseFactsDir),
                "Monorepo showcase facts directory not present; skipping cross-repo smoke");

        int fileCount = 0;
        int totalOperations = 0;
        int totalFacts = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(showcaseFactsDir, "*.json")) {
            for (Path file : stream) {
                fileCount++;
                try (InputStream in = Files.newInputStream(file)) {
                    RuleFactCatalogDocument doc =
                            RuleFactCatalogLoader.parse(in, file.getFileName().toString());
                    assertEquals(RuleFactCatalogDocument.SCHEMA_VERSION_V2, doc.schemaVersion());
                    for (RuleFactOperationCatalog op : doc.operations().values()) {
                        totalOperations++;
                        for (RuleFactDescriptor fact : op.facts()) {
                            totalFacts++;
                            assertTrue(
                                    RuleFactDescriptor.CANONICAL_SENSITIVITIES.contains(
                                            fact.canonicalSensitivity()),
                                    () ->
                                            "Non-canonical sensitivity in "
                                                    + file.getFileName()
                                                    + ": "
                                                    + fact.sensitivity());
                            assertTrue(
                                    RuleFactDescriptor.ALLOWED_VALUE_TYPES.contains(
                                            fact.valueType()));
                            String stable = RuleFactDescriptor.toStableFactPath(fact.factPath());
                            assertTrue(
                                    RuleFactDescriptor.STABLE_FACT_PATH_PATTERN
                                            .matcher(stable)
                                            .matches(),
                                    () ->
                                            "Unstable fact path '"
                                                    + stable
                                                    + "' for "
                                                    + fact.schemaPointer()
                                                    + " in "
                                                    + file.getFileName());
                        }
                    }
                }
            }
        }
        assertTrue(fileCount >= 10, "Expected at least 10 corporate fact catalog files");
        assertTrue(totalOperations >= 60, "Expected at least 60 operations across catalogs");
        assertTrue(totalFacts >= 4000, "Expected > 4,000 curated facts across catalogs");
    }

    @Test
    void typedObjectNodeHelpersPreserveNullAndFormatting() {
        ObjectNode node = MAPPER.createObjectNode();
        PraxisFactTreeBuilder.putInt(node, "intVal", 42);
        PraxisFactTreeBuilder.putInt(node, "intNull", null);
        PraxisFactTreeBuilder.putLong(node, "longVal", 99L);
        PraxisFactTreeBuilder.putDecimal(node, "decVal", new BigDecimal("123.45"));
        PraxisFactTreeBuilder.putBool(node, "boolVal", true);
        PraxisFactTreeBuilder.putText(node, "textVal", "ATIVO");
        PraxisFactTreeBuilder.putDate(node, "dateVal", LocalDate.of(2026, 4, 10));
        PraxisFactTreeBuilder.putStringList(node, "tags", List.of("A", "B"));

        assertEquals(42, node.get("intVal").intValue());
        assertTrue(node.get("intNull").isNull());
        assertEquals(99L, node.get("longVal").longValue());
        assertEquals(new BigDecimal("123.45"), node.get("decVal").decimalValue());
        assertTrue(node.get("boolVal").booleanValue());
        assertEquals("ATIVO", node.get("textVal").textValue());
        assertEquals("2026-04-10", node.get("dateVal").textValue());
        assertEquals(2, node.get("tags").size());
    }

    @Test
    void rejectsInvalidSensitivityOrMissingEvidence() throws Exception {
        String invalidJson =
                """
                {
                  "schemaVersion": "praxis.domain-rule-fact-catalog.v2",
                  "operations": [
                    {
                      "operationKey": "CREATE",
                      "snapshotSchema": {
                        "type": "object",
                        "additionalProperties": false,
                        "maxProperties": 1,
                        "required": ["draft"],
                        "properties": {
                          "draft": { "type": "boolean" }
                        }
                      },
                      "facts": [
                        {
                          "schemaPointer": "#/properties/draft",
                          "labels": { "pt-BR": "Rascunho" },
                          "descriptions": { "pt-BR": "Rascunho." },
                          "providerRef": "Sample#v1",
                          "evidenceRefs": [],
                          "sensitivity": "PUBLIC",
                          "redaction": "NONE"
                        }
                      ]
                    }
                  ]
                }
                """;
        JsonNode root = MAPPER.readTree(invalidJson);
        assertThrows(IllegalArgumentException.class, () -> RuleFactCatalogLoader.parse(root));
    }
}
