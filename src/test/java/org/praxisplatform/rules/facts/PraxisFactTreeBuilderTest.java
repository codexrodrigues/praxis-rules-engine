package org.praxisplatform.rules.facts;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class PraxisFactTreeBuilderTest {
    enum Status { ACTIVE }
    record Draft(LocalDate date, LocalDateTime timestamp, BigDecimal amount, Status status, List<String> labels) { }
    record Context(Draft draft, Object previousSnapshot) { }
    static class Node { Node child; }

    static class EmbeddedAddress {
        String city = "Recife";
        @jakarta.persistence.ManyToOne Object owner;
        public Object getOwner() { throw new AssertionError("Association getter must not run"); }
    }
    static class ScalarEntity {
        UUID id = UUID.fromString("c076146e-a72e-4d5f-8c1b-424bd5a0623c");
        LocalDate date = LocalDate.of(2026, 11, 2);
        BigDecimal amount = new BigDecimal("12.50");
        Status status = Status.ACTIVE;
        Boolean active = true;
        List<String> tags = List.of("one", "two");
        int[] counts = {1, 2};
        String absent;
        @jakarta.persistence.Embedded EmbeddedAddress address = new EmbeddedAddress();
        @jakarta.persistence.ManyToOne String associationWithScalarType;
        @jakarta.persistence.OneToMany List<String> children;
        @jakarta.persistence.OneToOne Object partner;
        @jakarta.persistence.ManyToMany List<String> peers;
        Object unknown;
        public String getAssociationWithScalarType() { throw new AssertionError("Association read"); }
        public List<String> getChildren() { throw new AssertionError("Association read"); }
        public Object getPartner() { throw new AssertionError("Association read"); }
        public List<String> getPeers() { throw new AssertionError("Association read"); }
        public Object getUnknown() { throw new AssertionError("Unknown graph read"); }
        @jakarta.persistence.ManyToOne public String getAnnotatedGetter() { throw new AssertionError("Association read"); }
    }

    @Test void scalarProjectionNeverInvokesAssociationOrUnknownObjectGetters() {
        var bean = new ScalarEntity();
        var result = PraxisFactTreeBuilder.toScalarObjectNode(bean);
        assertEquals(bean.id.toString(), result.path("id").asText());
        assertEquals("2026-11-02", result.path("date").asText());
        assertEquals(0, new BigDecimal("12.5").compareTo(result.path("amount").decimalValue()));
        assertEquals("ACTIVE", result.path("status").asText());
        assertTrue(result.path("active").booleanValue());
        assertEquals(2, result.path("tags").size());
        assertEquals(2, result.path("counts").size());
        assertTrue(result.path("absent").isNull());
        assertEquals("Recife", result.at("/address/city").asText());
        assertFalse(result.path("address").has("owner"));
        for (var omitted : List.of("children", "associationWithScalarType", "partner", "peers", "unknown", "annotatedGetter"))
            assertFalse(result.has(omitted), omitted);
        var builder = PraxisFactTreeBuilder.create().putScalarBean("entity", bean);
        result.removeAll();
        bean.address.city = "Natal";
        assertEquals("Recife", builder.build().at("/entity/address/city").asText());
    }

    @Test void validatesCombinedSizeAtBuildWithoutCopyingTheAccumulatedTreeAtEachPut() {
        var builder = PraxisFactTreeBuilder.create();
        for (int i = 0; i < 6; i++) builder.put("large" + i, "x".repeat(60_000));
        assertThrows(IllegalArgumentException.class, builder::build);
        assertThrows(IllegalArgumentException.class, () -> builder.put("invalid", Double.NaN));
    }

    @Test void serializesRecordsObjectsDatesDecimalsEnumsCollectionsAndNullsDeterministically() {
        var draft = new Draft(LocalDate.of(2026, 11, 1), LocalDateTime.of(2026, 11, 1, 9, 30),
                new BigDecimal("120.4500"), Status.ACTIVE, List.of("b", "a"));
        var context = new Context(draft, null);
        var first = PraxisFactTreeBuilder.from(context).put("members", new HashSet<>(List.of("b", "a"))).build();
        assertEquals("2026-11-01", first.at("/draft/date").asText());
        assertEquals("2026-11-01T09:30:00", first.at("/draft/timestamp").asText());
        assertEquals(0, new BigDecimal("120.45").compareTo(first.at("/draft/amount").decimalValue()));
        assertEquals("ACTIVE", first.at("/draft/status").asText());
        assertEquals("b", first.at("/draft/labels/0").asText());
        assertTrue(first.get("previousSnapshot").isNull());
        assertEquals("a", first.at("/members/0").asText());
        assertEquals(first.toString(), PraxisFactTreeBuilder.from(context)
                .put("members", new LinkedHashSet<>(List.of("a", "b"))).build().toString());
    }

    @Test void derivedFactsAndReturnedTreesNeverMutateInputsOrRetainedState() {
        var source = new LinkedHashMap<String, Object>();
        source.put("z", new ArrayList<>(List.of(1, 2)));
        source.put("a", null);
        var builder = PraxisFactTreeBuilder.create().put("draft", source)
                .derive("count", tree -> tree.at("/draft/z").size()).putIfNotNull("absent", null);
        source.clear();
        var result = builder.build();
        assertEquals(2, result.get("count").intValue());
        assertFalse(result.has("absent"));
        ((ObjectNode) result.get("draft")).removeAll();
        assertEquals(2, builder.build().at("/draft/z").size());
        var child = new Node();
        assertDoesNotThrow(() -> PraxisFactTreeBuilder.toObjectNode(Map.of("a", child, "b", child)));
    }

    @Test void rejectsCyclesNonFiniteValuesNonStringKeysUnsupportedJsonAndOversizedStructures() {
        var cycle = new Node(); cycle.child = cycle;
        assertThrows(IllegalArgumentException.class, () -> PraxisFactTreeBuilder.toObjectNode(cycle));
        assertThrows(IllegalArgumentException.class, () -> PraxisFactTreeBuilder.toJsonNode(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> PraxisFactTreeBuilder.toJsonNode(Map.of(1, "value")));
        assertThrows(IllegalArgumentException.class, () -> PraxisFactTreeBuilder.toObjectNode(List.of(1)));
        assertThrows(IllegalArgumentException.class, () -> PraxisFactTreeBuilder.toJsonNode("a".repeat(64_001)));
        assertThrows(IllegalArgumentException.class, () -> PraxisFactTreeBuilder.toJsonNode(Collections.nCopies(10_001, 1)));
        assertThrows(IllegalArgumentException.class, () -> PraxisFactTreeBuilder.toJsonNode(Collections.nCopies(10, "x".repeat(60_000))));
        Object nested = "value";
        for (int i = 0; i < 70; i++) nested = List.of(nested);
        Object tooDeep = nested;
        assertThrows(IllegalArgumentException.class, () -> PraxisFactTreeBuilder.toJsonNode(tooDeep));
    }

    @Test void temporalHelpersUseInclusiveBoundsAndExplicitOpenIntervals() {
        var start = LocalDate.of(2026, 1, 1); var end = start.plusDays(10);
        assertTrue(PraxisFactTreeBuilder.hasDateRangeOverlap(start, end, end, end.plusDays(10)));
        assertFalse(PraxisFactTreeBuilder.hasDateRangeOverlap(start, end, end.plusDays(1), null));
        assertTrue(PraxisFactTreeBuilder.hasDateRangeOverlap(null, null, start, end));
        assertTrue(PraxisFactTreeBuilder.isWithinDateRange(end, start, end));
        assertThrows(IllegalArgumentException.class, () -> PraxisFactTreeBuilder.hasDateRangeOverlap(end, start, null, null));
    }

    @Test void ontologicalConceptMethodsAndAstIntrospectionIgnoreIteratorLocalVariables() throws Exception {
        var tree = PraxisFactTreeBuilder.create()
                .putDraft(Map.of("inicio", LocalDate.of(2026, 5, 1)))
                .putCurrent(Map.of("inicio", LocalDate.of(2026, 1, 1)))
                .putConcept("vaga", Map.of("quantidadeVagas", 2))
                .putNeighborhood(Map.of("sobreposicoes", 0))
                .putContext(Map.of("empCodigo", 1))
                .build();
        assertEquals("2026-05-01", tree.at("/draft/inicio").asText());
        assertEquals("2026-01-01", tree.at("/current/inicio").asText());
        assertEquals(2, tree.at("/vaga/quantidadeVagas").intValue());
        assertEquals(0, tree.at("/neighborhood/sobreposicoes").intValue());
        assertEquals(1, tree.at("/context/empCodigo").intValue());

        var fluentTree = PraxisFactTreeBuilder.create()
                .putDraft(node -> node.put("inicio", LocalDate.of(2026, 5, 1)))
                .putCurrent(node -> node.put("inicio", LocalDate.of(2026, 1, 1)))
                .putConcept("vaga", node -> node.put("quantidadeVagas", 2))
                .putNeighborhood(node -> node.put("sobreposicoes", 0))
                .putContext(node -> node.put("empCodigo", 1))
                .build();
        assertEquals(tree, fluentTree);

        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var ast = mapper.readTree("""
                {"and":[
                  {"==":[{"var":"$.draft.empCodigo"},{"var":["current.empCodigo",{"var":"context.empCodigo"}]}]},
                  {"<":[{"var":"neighborhood.sobreposicoes"},{"var":"vaga.quantidadeVagas"}]},
                  {"some":[{"var":"neighborhood.items"},{"==":[{"var":"itemLocalStatus"},"ATIVO"]}]},
                  {"reduce":[{"var":"neighborhood.values"},{"+":[{"var":"accumulator"},{"var":"current"}]},{"var":"context.initialTotal"}]}
                ]}
                """);
        assertEquals(
                List.of("draft", "current", "context", "neighborhood", "vaga"),
                PraxisFactTreeBuilder.extractReferencedRoots(ast));
        assertEquals(
                List.of(
                        "draft.empCodigo",
                        "current.empCodigo",
                        "context.empCodigo",
                        "neighborhood.sobreposicoes",
                        "vaga.quantidadeVagas",
                        "neighborhood.items",
                        "neighborhood.values",
                        "context.initialTotal"),
                PraxisFactTreeBuilder.extractReferencedFactPaths(ast));

        var slot = new org.praxisplatform.rules.contract.DecisionSlot(
                "slot.test",
                org.praxisplatform.rules.contract.DecisionStage.PROTECTED_GUARD,
                org.praxisplatform.rules.contract.SlotCardinality.SINGLE,
                org.praxisplatform.rules.contract.OverridePolicy.FORBIDDEN,
                org.praxisplatform.rules.contract.DecisionAggregationPolicy.SINGLE_RESULT,
                null, java.util.List.of());
        var binding = new org.praxisplatform.rules.contract.DecisionBinding(
                "binding.test",
                "slot.test",
                org.praxisplatform.rules.contract.DecisionSource.PRODUCT,
                null,
                org.praxisplatform.rules.contract.RuleExecutorRef.jsonLogic(ast),
                List.of(),
                1,
                true,
                org.praxisplatform.rules.contract.RuleDecision.DENY,
                "ERR-001",
                PraxisFactTreeBuilder.extractReferencedRoots(ast),
                null);
        var definition = org.praxisplatform.rules.contract.RuleSetDefinition.withInferredRoots(
                new org.praxisplatform.rules.contract.RuleSetRef("domain", "ctx", "rules", "validate", 1),
                List.of(slot),
                List.of(binding),
                org.praxisplatform.rules.contract.RuleRuntimeCompatibility.current(),
                org.praxisplatform.rules.contract.RuleFailPolicy.FAIL_CLOSED);
        assertEquals(
                List.of("context", "current", "draft", "neighborhood", "vaga"),
                definition.availableRoots());
        assertEquals(
                List.of("vaga"),
                definition.conceptRoots());
        assertEquals(
                PraxisFactTreeBuilder.extractReferencedFactPaths(ast),
                definition.referencedFactPaths());
        assertEquals(
                Map.of(
                        "context", List.of("context.empCodigo", "context.initialTotal"),
                        "current", List.of("current.empCodigo"),
                        "draft", List.of("draft.empCodigo"),
                        "neighborhood", List.of("neighborhood.items", "neighborhood.sobreposicoes", "neighborhood.values"),
                        "vaga", List.of("vaga.quantidadeVagas")),
                definition.factPathsByRoot());
    }

    @Test
    void mergesSuccessiveFluentCallsOnTheSameOntologicalRoot() {
        var facts = PraxisFactTreeBuilder.create()
                .putDraft(d -> d.put("inicio", 100).put("fim", 200))
                .putDraft(d -> d.put("diasDireito", 90))
                .putConcept("politica", p -> p.put("permiteVenda", true))
                .putConcept("politica", p -> p.put("baseAbono", "D"))
                .putNeighborhood(n -> n.put("sobreposicoes", 0))
                .putNeighborhood(n -> n.put("consumoAcumulado", 10))
                .build();

        assertEquals(100, facts.path("draft").path("inicio").asInt());
        assertEquals(200, facts.path("draft").path("fim").asInt());
        assertEquals(90, facts.path("draft").path("diasDireito").asInt());
        assertTrue(facts.path("politica").path("permiteVenda").asBoolean());
        assertEquals("D", facts.path("politica").path("baseAbono").asText());
        assertEquals(0, facts.path("neighborhood").path("sobreposicoes").asInt());
        assertEquals(10, facts.path("neighborhood").path("consumoAcumulado").asInt());
    }
}
