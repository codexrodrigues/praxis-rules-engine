package org.praxisplatform.rules.facts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.praxisplatform.rules.facts.OntologicalFactTreeMapper.ConceptBinding;
import org.praxisplatform.rules.facts.PraxisFactTreeBuilder.DateSerializationMode;
import org.praxisplatform.rules.jsonlogic.PraxisJsonLogicException;
import org.praxisplatform.rules.jsonlogic.model.JsonLogicLimits;

class OntologicalFactTreeMapperTest {

    static class LazyEntity {
        Long id = 10L;
        LocalDate startDate = LocalDate.of(2026, 5, 1);
        BigDecimal rate = new BigDecimal("12.50");
        @jakarta.persistence.ManyToOne Object parentAssociation;

        public Long getId() {
            return id;
        }

        public LocalDate getStartDate() {
            return startDate;
        }

        public BigDecimal getRate() {
            return rate;
        }

        public Object getParentAssociation() {
            throw new AssertionError("JPA association must never be traversed");
        }
    }

    record SiblingWindow(LocalDate start, LocalDate end) {}

    record NeighborhoodSummary(int overlaps, List<SiblingWindow> windows) {}

    @Test
    void buildsFiveOntologicalRootsDeterministicallyAcrossModesWithoutTouchingAssociations() {
        var draft = new LazyEntity();
        var conceptEntity = new LazyEntity();
        var neighborhood = new NeighborhoodSummary(
                0, List.of(new SiblingWindow(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 1))));
        var context = Map.<String, Object>of("operation", "CREATE", "tenantId", "org-1");

        Map<String, Object> roleDefaults = new LinkedHashMap<>();
        roleDefaults.put("active", false);
        roleDefaults.put("code", null);

        Map<String, ConceptBinding> concepts = Map.of(
                "role", ConceptBinding.of(conceptEntity, true, roleDefaults),
                "approvalDoc", ConceptBinding.absent(false, roleDefaults));

        ObjectNode isoTree = OntologicalFactTreeMapper.buildFiveRoots(
                draft, null, concepts, neighborhood, context, DateSerializationMode.ISO_8601);

        assertEquals(10L, isoTree.at("/draft/id").asLong());
        assertEquals("2026-05-01", isoTree.at("/draft/startDate").asText());
        assertFalse(isoTree.path("draft").has("parentAssociation"));
        assertTrue(isoTree.get("current").isNull());
        assertTrue(isoTree.at("/role/present").asBoolean());
        assertTrue(isoTree.at("/role/idPresentInDraft").asBoolean());
        assertEquals("2026-05-01", isoTree.at("/role/startDate").asText());
        assertFalse(isoTree.at("/approvalDoc/present").asBoolean());
        assertFalse(isoTree.at("/approvalDoc/idPresentInDraft").asBoolean());
        assertFalse(isoTree.at("/approvalDoc/active").asBoolean());
        assertTrue(isoTree.at("/approvalDoc/code").isNull());
        assertEquals(0, isoTree.at("/neighborhood/overlaps").asInt());
        assertEquals("2026-01-01", isoTree.at("/neighborhood/windows/0/start").asText());
        assertEquals("CREATE", isoTree.at("/context/operation").asText());

        // Stateless instance mapper in EPOCH_DAY mode
        OntologicalFactTreeMapper epochMapper = OntologicalFactTreeMapper.epochDay();
        ObjectNode epochTree = epochMapper.mapFiveRoots(draft, null, concepts, neighborhood, context);
        assertEquals(LocalDate.of(2026, 5, 1).toEpochDay(), epochTree.at("/draft/startDate").asLong());
        assertEquals(LocalDate.of(2026, 5, 1).toEpochDay(), epochTree.at("/role/startDate").asLong());
        assertEquals(LocalDate.of(2026, 1, 1).toEpochDay(), epochTree.at("/neighborhood/windows/0/start").asLong());

        // Returned tree is detached from mutating callers
        ((ObjectNode) isoTree.get("draft")).put("id", 999L);
        assertEquals(
                10L,
                OntologicalFactTreeMapper.iso8601()
                        .mapFiveRoots(draft, null, concepts, neighborhood, context)
                        .at("/draft/id")
                        .asLong());
    }

    @Test
    void enforcesReservedConceptKeysAndJsonLogicLimits() {
        assertThrows(
                IllegalArgumentException.class,
                () -> OntologicalFactTreeMapper.buildFiveRoots(
                        Map.of("x", 1),
                        null,
                        Map.of("draft", ConceptBinding.of(Map.of("x", 2))),
                        null,
                        Map.of(),
                        DateSerializationMode.ISO_8601));
        assertThrows(
                IllegalArgumentException.class,
                () -> OntologicalFactTreeMapper.buildFiveRoots(
                        Map.of("x", 1),
                        null,
                        Map.of("facts", ConceptBinding.of(Map.of("x", 2))),
                        null,
                        Map.of(),
                        DateSerializationMode.ISO_8601));

        JsonLogicLimits tightLimits = new JsonLogicLimits(4, 10, 64, 10, 32, 100, 64, 16);
        assertThrows(
                PraxisJsonLogicException.class,
                () -> OntologicalFactTreeMapper.builder(DateSerializationMode.ISO_8601, tightLimits)
                        .draft(Map.of("payload", "x".repeat(100)))
                        .build());
    }
}
