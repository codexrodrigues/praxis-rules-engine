package org.praxisplatform.rules.ontology;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.praxisplatform.rules.facts.PraxisFactTreeBuilder;
import org.praxisplatform.rules.facts.PraxisFactTreeBuilder.DateSerializationMode;
import org.praxisplatform.rules.jsonlogic.PraxisJsonLogicEngine;
import org.praxisplatform.rules.jsonlogic.model.JsonLogicEvaluationOptions;
import org.praxisplatform.rules.jsonlogic.model.JsonLogicLimits;
import org.praxisplatform.rules.ontology.PraxisJsonLogic.Expr;

class PraxisJsonLogicTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final PraxisJsonLogicEngine ENGINE = new PraxisJsonLogicEngine();

    @Test
    void buildsSafeJsonLogicAcrossFiveOntologicalRootsAndEvaluatesDeterministically() throws Exception {
        Expr rule = PraxisJsonLogic.nonDelete(
                PraxisJsonLogic.and(
                        PraxisJsonLogic.conceptActive("vacancy", "active"),
                        PraxisJsonLogic.zeroConflict("overlaps"),
                        PraxisJsonLogic.validDateRange("draft.startDate", "draft.endDate"),
                        PraxisJsonLogic.percentRange("draft.allocationPercent"),
                        PraxisJsonLogic.inValues(PraxisJsonLogic.draft("status"), "ACTIVE", "PENDING"),
                        PraxisJsonLogic.contains(PraxisJsonLogic.draft("tags"), "CORE"),
                        PraxisJsonLogic.notBlank(PraxisJsonLogic.draft("code")),
                        PraxisJsonLogic.whenOperation(
                                "UPDATE",
                                PraxisJsonLogic.strictEq(
                                        PraxisJsonLogic.draft("tenantKey"),
                                        PraxisJsonLogic.current("tenantKey")))));

        ObjectNode ast = rule.toJsonNode();
        assertEquals(ast, JSON.readTree(rule.toJson()));
        assertEquals(
                List.of("context", "vacancy", "neighborhood", "draft", "current"),
                PraxisFactTreeBuilder.extractReferencedRoots(ast));

        ObjectNode validFacts = PraxisFactTreeBuilder.create(DateSerializationMode.ISO_8601)
                .putDraft(d -> d.put("code", "ROLE-01")
                        .put("status", "ACTIVE")
                        .put("tenantKey", "ORG-1")
                        .put("allocationPercent", 80)
                        .put("startDate", LocalDate.of(2026, 1, 1))
                        .put("endDate", LocalDate.of(2026, 12, 31))
                        .put("tags", List.of("CORE", "HR")))
                .putCurrent(c -> c.put("tenantKey", "ORG-1"))
                .putConcept("vacancy", v -> v.put("present", true).put("active", true))
                .putNeighborhood(n -> n.put("overlaps", 0))
                .putContext(ctx -> ctx.put("operation", "UPDATE"))
                .build();

        JsonLogicEvaluationOptions opts = new JsonLogicEvaluationOptions(
                List.of("context", "vacancy", "neighborhood", "draft", "current"),
                null,
                false,
                "2026-11-16T00:00:00Z",
                "UTC",
                JsonLogicLimits.DEFAULT);
        assertTrue(ENGINE.evaluateResult(ast, validFacts, opts).truthy());

        // Also compatible with EPOCH_DAY dates
        ObjectNode epochFacts = PraxisFactTreeBuilder.create(DateSerializationMode.EPOCH_DAY)
                .putDraft(d -> d.put("code", "ROLE-01")
                        .put("status", "ACTIVE")
                        .put("tenantKey", "ORG-1")
                        .put("allocationPercent", 80)
                        .put("startDate", LocalDate.of(2026, 1, 1))
                        .put("endDate", LocalDate.of(2026, 12, 31))
                        .put("tags", List.of("CORE")))
                .putCurrent(c -> c.put("tenantKey", "ORG-1"))
                .putConcept("vacancy", v -> v.put("present", true).put("active", true))
                .putNeighborhood(n -> n.put("overlaps", 0))
                .putContext(ctx -> ctx.put("operation", "UPDATE"))
                .build();
        assertTrue(ENGINE.evaluateResult(ast, epochFacts, opts).truthy());

        // Violated date order + conflict fails
        ObjectNode invalidFacts = validFacts.deepCopy();
        ((ObjectNode) invalidFacts.get("draft")).put("startDate", "2027-01-01");
        assertFalse(ENGINE.evaluateResult(ast, invalidFacts, opts).truthy());

        // DELETE short-circuits cleanly
        ObjectNode deleteFacts = invalidFacts.deepCopy();
        ((ObjectNode) deleteFacts.get("context")).put("operation", "DELETE");
        assertTrue(ENGINE.evaluateResult(ast, deleteFacts, opts).truthy());
    }

    @Test
    void preventsJsonInjectionAndExposesNullMissingAndIteratorOperators() throws Exception {
        String untrustedInput = "\",{\"var\":\"secret\"}],\"==\":[1,1";
        Expr expr = PraxisJsonLogic.eq(PraxisJsonLogic.draft("label"), untrustedInput);
        JsonNode parsed = JSON.readTree(expr.toJson());
        assertEquals(untrustedInput, parsed.at("/==/1").asText());
        assertEquals(List.of("draft.label"), PraxisFactTreeBuilder.extractReferencedFactPaths(parsed));

        // Iterator local variables must not leak into external requiredFactPaths
        Expr iteratorExpr = PraxisJsonLogic.all(
                PraxisJsonLogic.neighborhood("items"),
                PraxisJsonLogic.gt(PraxisJsonLogic.localVar("days"), 0));
        assertEquals(
                List.of("neighborhood"),
                PraxisFactTreeBuilder.extractReferencedRoots(iteratorExpr.toJsonNode()));
        assertEquals(
                List.of("neighborhood.items"),
                PraxisFactTreeBuilder.extractReferencedFactPaths(iteratorExpr.toJsonNode()));

        ObjectNode iterFacts = PraxisFactTreeBuilder.create()
                .putNeighborhood(Map.of("items", List.of(Map.of("days", 5), Map.of("days", 10))))
                .build();
        assertTrue(ENGINE.evaluateResult(
                iteratorExpr.toJsonNode(),
                iterFacts,
                new JsonLogicEvaluationOptions(
                        List.of("neighborhood"), null, false, "2026-11-16T00:00:00Z", "UTC", JsonLogicLimits.DEFAULT))
                .truthy());

        // isNull and notNull treat 0, "", and false as non-null while null/missing are null
        ObjectNode scalarFacts = PraxisFactTreeBuilder.create()
                .putDraft(d -> d.put("zero", 0).put("empty", "").put("flag", false).put("absent", (String) null))
                .build();
        JsonLogicEvaluationOptions draftOpts = new JsonLogicEvaluationOptions(
                List.of("draft"), null, false, "2026-11-16T00:00:00Z", "UTC", JsonLogicLimits.DEFAULT);
        assertTrue(ENGINE.evaluateResult(PraxisJsonLogic.notNull("draft.zero").toJsonNode(), scalarFacts, draftOpts).truthy());
        assertTrue(ENGINE.evaluateResult(PraxisJsonLogic.notNull("draft.empty").toJsonNode(), scalarFacts, draftOpts).truthy());
        assertTrue(ENGINE.evaluateResult(PraxisJsonLogic.notNull("draft.flag").toJsonNode(), scalarFacts, draftOpts).truthy());
        assertTrue(ENGINE.evaluateResult(PraxisJsonLogic.isNull("draft.absent").toJsonNode(), scalarFacts, draftOpts).truthy());
        assertTrue(ENGINE.evaluateResult(PraxisJsonLogic.isBlank("draft.empty").toJsonNode(), scalarFacts, draftOpts).truthy());
    }

    @Test
    void rejectsFlattenedFactsRootAndMalformedPaths() {
        assertThrows(IllegalArgumentException.class, () -> PraxisJsonLogic.var("facts.code"));
        assertThrows(IllegalArgumentException.class, () -> PraxisJsonLogic.concept("facts", "code"));
        assertThrows(IllegalArgumentException.class, () -> PraxisJsonLogic.concept("a.b", "code"));
        assertThrows(IllegalArgumentException.class, () -> PraxisJsonLogic.var(""));
        assertThrows(IllegalArgumentException.class, () -> PraxisJsonLogic.var("draft..code"));
        assertThrows(IllegalArgumentException.class, () -> PraxisJsonLogic.draft(".code"));
        assertThrows(IllegalArgumentException.class, () -> PraxisJsonLogic.and());
        assertThrows(IllegalArgumentException.class, () -> PraxisJsonLogic.or());
        assertThrows(IllegalArgumentException.class, () -> PraxisJsonLogic.eq(PraxisJsonLogic.draft("x"), Double.NaN));
    }
}
