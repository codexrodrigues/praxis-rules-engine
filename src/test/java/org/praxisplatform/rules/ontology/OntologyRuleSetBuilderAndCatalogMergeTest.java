package org.praxisplatform.rules.ontology;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.praxisplatform.rules.contract.RuleSetDefinition;
import org.praxisplatform.rules.contract.RuleSetRef;
import org.praxisplatform.rules.ontology.OntologyRuleSetBuilder.Rule;
import org.praxisplatform.rules.violation.RuleErrorCatalog;
import org.praxisplatform.rules.violation.RuleErrorDescriptor;

class OntologyRuleSetBuilderAndCatalogMergeTest {

    @Test
    void buildsOntologicalRuleSetAndExtractsCatalogAndFactPaths() {
        Rule r1 = new Rule(
                "ERG-09001",
                "codigo",
                "Código da carreira é obrigatório.",
                "PCK_CARREIRAS.MAIN_PRE",
                "IF :NEW.CODIGO IS NULL THEN INSERE_ERRO(9001)",
                "{\"!=\":[{\"var\":\"draft.codigo\"},null]}");
        Rule r2 = Rule.replaceable(
                "ERG-09002",
                "intersticioMeses",
                "Interstício deve ser positivo.",
                "PCK_CARREIRAS.MAIN_PRE",
                "IF :NEW.INTERSTICIO_MESES <= 0 THEN INSERE_ERRO(9002)",
                OntologyRuleSetBuilder.gtVar("draft.intersticioMeses", 0));

        RuleSetDefinition def = OntologyRuleSetBuilder.requireReferencedFactPaths(
                OntologyRuleSetBuilder.definition(
                        new RuleSetRef("praxis-ergon-showcase", "administracao-pessoal", "ergon.carreiras", "create", 1),
                        "carreiras.create",
                        "customer.orgao2.",
                        List.of(r1, r2),
                        null,
                        false,
                        Map.of()));

        assertEquals(List.of("draft"), def.availableRoots());
        assertEquals(2, def.bindings().size());
        assertEquals(List.of("draft.codigo"), def.bindings().get(0).requiredFactPaths());
        assertEquals(List.of("draft.intersticioMeses"), def.bindings().get(1).requiredFactPaths());

        RuleErrorCatalog catalog = OntologyRuleSetBuilder.catalogOf(List.of(r1, r2));
        assertTrue(catalog.find("ERG-09001").isPresent());
        assertEquals("codigo", catalog.find("ERG-09001").orElseThrow().targetField());
    }

    @Test
    void rejectsFlattenedScalarFactsRoot() {
        assertThrows(
                IllegalStateException.class,
                () -> OntologyRuleSetBuilder.binding(
                        "product.invalid",
                        "slot.invalid",
                        org.praxisplatform.rules.contract.DecisionSource.PRODUCT,
                        null,
                        "{\"==\":[{\"var\":\"facts.codigo\"},\"A\"]}",
                        "ERG-99999",
                        1));
    }

    @Test
    void mergesModularCatalogsAndFailsClosedOnConflict() {
        RuleErrorCatalog c1 = RuleErrorCatalog.of(Map.of(
                "ERG-00001",
                new RuleErrorDescriptor("ERG-00001", "nome", "Nome inválido.", "LIN-1", "CAT-1")));
        RuleErrorCatalog c2 = RuleErrorCatalog.of(Map.of(
                "ERG-00002",
                new RuleErrorDescriptor("ERG-00002", "cpf", "CPF inválido.", "LIN-2", "CAT-2")));

        RuleErrorCatalog merged = RuleErrorCatalog.merge(List.of(c1, c2));
        assertTrue(merged.find("ERG-00001").isPresent());
        assertTrue(merged.find("ERG-00002").isPresent());
        assertEquals(2, merged.asMap().size());

        RuleErrorCatalog conflicting = RuleErrorCatalog.of(Map.of(
                "ERG-00001",
                new RuleErrorDescriptor("ERG-00001", "outroCampo", "Mensagem divergente.", "LIN-1", "CAT-1")));
        assertThrows(
                IllegalArgumentException.class,
                () -> RuleErrorCatalog.merge(List.of(c1, conflicting)));
    }

    @Test
    void preservesCustomerErrorDescriptorsAcrossParameterizeAndRequireReferencedFactPathsAndCompiles() {
        Rule r1 = new Rule(
                "ERG-09001",
                "intersticioMeses",
                "Interstício mínimo violado.",
                "PCK_CARREIRAS.MAIN_PRE",
                "IF :NEW.INTERSTICIO_MESES < 24 THEN INSERE_ERRO(9001)",
                "{\">=\":[{\"var\":\"draft.intersticioMeses\"},24]}");
        Rule cust = new Rule(
                "CERG-09901",
                "documentoLegalId",
                "Ato normativo exigido pelo órgão.",
                "EPB__CARREIRAS_DOC",
                "Customização do órgão: exige ato normativo.",
                "{\"!=\":[{\"var\":\"draft.documentoLegalId\"},null]}");
        var template = OntologyRuleSetBuilder.integerTemplate(
                "ERG-09001",
                "{\">=\":[{\"var\":\"draft.intersticioMeses\"},24]}",
                "carreiras:intersticio",
                "minMeses",
                24,
                120,
                "/>=/1",
                "meses");
        String customerBindingKey = "customer.orgao2.carreiras.create.cerg-09901";
        RuleSetDefinition base = OntologyRuleSetBuilder.withCustomerErrorDescriptor(
                OntologyRuleSetBuilder.definition(
                        new RuleSetRef("workforce.core", "administracao-pessoal.carreiras", "ergon.carreiras", "create", 1),
                        "carreiras.create",
                        "customer.orgao2.",
                        List.of(r1),
                        cust,
                        true,
                        Map.of("ERG-09001", template)),
                customerBindingKey,
                cust);
        RuleSetDefinition parameterized = OntologyRuleSetBuilder.requireReferencedFactPaths(
                OntologyRuleSetBuilder.parameterize(base, Map.of("ERG-09001", 36)));

        assertTrue(parameterized.errorDescriptors().containsKey(customerBindingKey));
        var compiler = new org.praxisplatform.rules.plan.PraxisRulePlanCompiler(
                org.praxisplatform.rules.runtime.RuleBindingExecutorRegistry.empty());
        var plan = compiler.compile(parameterized);
        assertEquals(customerBindingKey, plan.definition().errorDescriptors().keySet().iterator().next());
    }
}
