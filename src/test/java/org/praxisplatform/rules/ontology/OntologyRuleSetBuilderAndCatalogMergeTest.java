package org.praxisplatform.rules.ontology;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.praxisplatform.rules.contract.DecisionBinding;
import org.praxisplatform.rules.contract.DecisionSlot;
import org.praxisplatform.rules.contract.DecisionSource;
import org.praxisplatform.rules.contract.DecisionStage;
import org.praxisplatform.rules.contract.OverridePolicy;
import org.praxisplatform.rules.contract.RuleDecision;
import org.praxisplatform.rules.contract.RuleEvaluationResult;
import org.praxisplatform.rules.contract.RuleSetDefinition;
import org.praxisplatform.rules.contract.RuleSetRef;
import org.praxisplatform.rules.facts.PraxisFactTreeBuilder;
import org.praxisplatform.rules.ontology.OntologyRuleSetBuilder.Rule;
import org.praxisplatform.rules.ontology.OntologyRuleSetBuilder.RuleSetCatalogBundle;
import org.praxisplatform.rules.plan.PraxisRulePlanCompiler;
import org.praxisplatform.rules.runtime.PraxisRuleSetEngine;
import org.praxisplatform.rules.runtime.RuleBindingExecutorRegistry;
import org.praxisplatform.rules.violation.PraxisRuleViolationException;
import org.praxisplatform.rules.violation.RuleErrorCatalog;
import org.praxisplatform.rules.violation.RuleErrorDescriptor;

class OntologyRuleSetBuilderAndCatalogMergeTest {

    @Test
    void buildsOntologicalRuleSetAndExtractsCatalogAndFactPaths() {
        Rule r1 = new Rule(
                "WF-09001",
                "codigo",
                "Código da carreira é obrigatório.",
                "PKG_CAREERS.VALIDATE",
                "IF :NEW.CODE IS NULL THEN RAISE_ERROR(9001)",
                "{\"!=\":[{\"var\":\"draft.codigo\"},null]}");
        Rule r2 = Rule.replaceable(
                "WF-09002",
                "intersticioMeses",
                "Interstício deve ser positivo.",
                "PKG_CAREERS.VALIDATE",
                "IF :NEW.INTERSTICE_MONTHS <= 0 THEN RAISE_ERROR(9002)",
                OntologyRuleSetBuilder.gtVar("draft.intersticioMeses", 0));

        RuleSetDefinition def = OntologyRuleSetBuilder.requireReferencedFactPaths(
                OntologyRuleSetBuilder.definition(
                        new RuleSetRef("workforce.core", "administracao-pessoal.workforce", "workforce.careers", "create", 1),
                        "careers.create",
                        "customer.org2.",
                        List.of(r1, r2),
                        (Rule) null,
                        false,
                        Map.of()));

        assertEquals(List.of("draft"), def.availableRoots());
        assertEquals(2, def.bindings().size());
        assertEquals(List.of("draft.codigo"), def.bindings().get(0).requiredFactPaths());
        assertEquals(List.of("draft.intersticioMeses"), def.bindings().get(1).requiredFactPaths());

        RuleErrorCatalog catalog = OntologyRuleSetBuilder.catalogOf(List.of(r1, r2));
        assertTrue(catalog.find("WF-09001").isPresent());
        assertEquals("codigo", catalog.find("WF-09001").orElseThrow().targetField());
    }

    @Test
    void rejectsFlattenedScalarFactsRootAndInvalidJsonWithEnglishMessages() {
        IllegalStateException factsError = assertThrows(
                IllegalStateException.class,
                () -> OntologyRuleSetBuilder.binding(
                        "product.invalid",
                        "slot.invalid",
                        DecisionSource.PRODUCT,
                        null,
                        "{\"==\":[{\"var\":\"facts.codigo\"},\"A\"]}",
                        "WF-99999",
                        1));
        assertTrue(factsError.getMessage().contains("Flattened scalar root 'facts'"));

        IllegalStateException syntaxError = assertThrows(
                IllegalStateException.class,
                () -> OntologyRuleSetBuilder.binding(
                        "product.bad",
                        "slot.bad",
                        DecisionSource.PRODUCT,
                        null,
                        "{not-valid-json",
                        "WF-99998",
                        1));
        assertTrue(syntaxError.getMessage().contains("Invalid JSON Logic expression"));
    }

    @Test
    void mergesModularCatalogsAndFailsClosedOnConflict() {
        RuleErrorCatalog c1 = RuleErrorCatalog.of(Map.of(
                "WF-00001",
                new RuleErrorDescriptor("WF-00001", "nome", "Nome inválido.", "LIN-1", "CAT-1")));
        RuleErrorCatalog c2 = RuleErrorCatalog.of(Map.of(
                "WF-00002",
                new RuleErrorDescriptor("WF-00002", "cpf", "CPF inválido.", "LIN-2", "CAT-2")));

        RuleErrorCatalog merged = RuleErrorCatalog.merge(List.of(c1, c2));
        assertTrue(merged.find("WF-00001").isPresent());
        assertTrue(merged.find("WF-00002").isPresent());
        assertEquals(2, merged.asMap().size());

        RuleErrorCatalog conflicting = RuleErrorCatalog.of(Map.of(
                "WF-00001",
                new RuleErrorDescriptor("WF-00001", "outroCampo", "Mensagem divergente.", "LIN-1", "CAT-1")));
        assertThrows(
                IllegalArgumentException.class,
                () -> RuleErrorCatalog.merge(List.of(c1, conflicting)));
    }

    @Test
    void preservesCustomerErrorDescriptorsAcrossParameterizeAndRequireReferencedFactPathsAndCompiles() {
        Rule r1 = new Rule(
                "WF-09001",
                "intersticioMeses",
                "Interstício mínimo violado.",
                "PKG_CAREERS.VALIDATE",
                "IF :NEW.INTERSTICE_MONTHS < 24 THEN RAISE_ERROR(9001)",
                "{\">=\":[{\"var\":\"draft.intersticioMeses\"},24]}");
        Rule cust = new Rule(
                "CWF-09901",
                "documentoLegalId",
                "Ato normativo exigido pela unidade.",
                "EPB__CAREERS_DOC",
                "Customização organizacional: exige ato normativo.",
                "{\"!=\":[{\"var\":\"draft.documentoLegalId\"},null]}");
        var template = OntologyRuleSetBuilder.integerTemplate(
                "WF-09001",
                "{\">=\":[{\"var\":\"draft.intersticioMeses\"},24]}",
                "careers:interstice",
                "minMeses",
                24,
                120,
                "/>=/1",
                "meses");
        String customerBindingKey = "customer.org2.careers.create.cwf-09901";
        RuleSetDefinition base = OntologyRuleSetBuilder.withCustomerErrorDescriptor(
                OntologyRuleSetBuilder.definition(
                        new RuleSetRef("workforce.core", "administracao-pessoal.workforce", "workforce.careers", "create", 1),
                        "careers.create",
                        "customer.org2.",
                        List.of(r1),
                        cust,
                        true,
                        Map.of("WF-09001", template)),
                customerBindingKey,
                cust);
        RuleSetDefinition parameterized = OntologyRuleSetBuilder.requireReferencedFactPaths(
                OntologyRuleSetBuilder.parameterize(base, Map.of("WF-09001", 36)));

        assertTrue(parameterized.errorDescriptors().containsKey(customerBindingKey));
        var compiler = new PraxisRulePlanCompiler(RuleBindingExecutorRegistry.empty());
        var plan = compiler.compile(parameterized);
        assertEquals(customerBindingKey, plan.definition().errorDescriptors().keySet().iterator().next());
    }

    @Test
    void supportsMultipleCustomerRulesExplicitStageTypedTemplatesAndCatalogBundle() {
        Rule productGuard = new Rule(
                "WF-10001",
                "codigo",
                "Código obrigatório.",
                "PKG_CAREERS.GUARD",
                "Código obrigatório",
                PraxisJsonLogic.notBlank(PraxisJsonLogic.draft("codigo")));
        Rule productPost = new Rule(
                "WF-10002",
                "taxaReajuste",
                "Taxa fora da faixa permitida.",
                "PKG_CAREERS.TAX",
                "Taxa decimal >= 1.50",
                PraxisJsonLogic.gte(PraxisJsonLogic.draft("taxaReajuste"), new BigDecimal("1.50")),
                OverridePolicy.PARAMETERIZABLE,
                DecisionStage.POST_DECISION);
        Rule productString = new Rule(
                "WF-10003",
                "regime",
                "Regime incompatível.",
                "PKG_CAREERS.REGIME",
                "Regime padrão CLT",
                PraxisJsonLogic.strictEq(PraxisJsonLogic.draft("regime"), "CLT"),
                OverridePolicy.PARAMETERIZABLE);
        Rule productBool = new Rule(
                "WF-10004",
                "homologado",
                "Exige homologação prévia.",
                "PKG_CAREERS.HOMOL",
                "Homologação obrigatória",
                PraxisJsonLogic.strictEq(PraxisJsonLogic.draft("homologado"), true),
                OverridePolicy.PARAMETERIZABLE);

        Rule cust1 = new Rule(
                "CWF-10901",
                "atoLegal",
                "Ato legal exigido pela organização.",
                "ORG_POLICIES.DOC",
                "Exige ato legal",
                PraxisJsonLogic.notNull(PraxisJsonLogic.draft("atoLegal")));
        Rule cust2 = new Rule(
                "CWF-10902",
                "observacao",
                "Observação pós-decisão obrigatória.",
                "ORG_POLICIES.OBS",
                "Exige observação",
                PraxisJsonLogic.notBlank(PraxisJsonLogic.draft("observacao")),
                OverridePolicy.RESTRICT_ONLY,
                DecisionStage.POST_DECISION);

        var decimalTpl = OntologyRuleSetBuilder.decimalTemplate(
                "WF-10002",
                PraxisJsonLogic.gte(PraxisJsonLogic.draft("taxaReajuste"), new BigDecimal("1.50")),
                "careers:taxa",
                "taxaMinima",
                new BigDecimal("1.50"),
                new BigDecimal("0.50"),
                new BigDecimal("10.00"),
                "/>=/1",
                "percentual");
        var stringTpl = OntologyRuleSetBuilder.stringTemplate(
                "WF-10003",
                PraxisJsonLogic.strictEq(PraxisJsonLogic.draft("regime"), "CLT"),
                "careers:regime",
                "regimePermitido",
                "CLT",
                List.of("CLT", "ESTATUTARIO"),
                "/===/1",
                null);
        var boolTpl = OntologyRuleSetBuilder.booleanTemplate(
                "WF-10004",
                PraxisJsonLogic.strictEq(PraxisJsonLogic.draft("homologado"), true),
                "careers:homologado",
                "exigeHomologacao",
                true,
                "/===/1",
                null);

        List<Rule> productRules = List.of(productGuard, productPost, productString, productBool);
        List<Rule> customerRules = List.of(cust1, cust2);
        Map<String, org.praxisplatform.rules.contract.RuleParameterTemplate> templates = Map.of(
                "WF-10002", decimalTpl,
                "WF-10003", stringTpl,
                "WF-10004", boolTpl);

        RuleSetCatalogBundle bundle = OntologyRuleSetBuilder.definitionWithCatalog(
                new RuleSetRef("workforce.core", "administracao-pessoal.workforce", "workforce.careers", "update", 1),
                "careers.update",
                "customer.org2.",
                productRules,
                customerRules,
                true,
                templates);

        RuleSetDefinition parameterized = OntologyRuleSetBuilder.requireReferencedFactPaths(
                OntologyRuleSetBuilder.parameterizeValues(
                        bundle.definition(),
                        Map.of(
                                "WF-10002", new BigDecimal("2.25"),
                                "WF-10003", "ESTATUTARIO",
                                "WF-10004", false)));

        assertEquals(2, parameterized.errorDescriptors().size());
        assertTrue(parameterized.errorDescriptors().containsKey("customer.org2.careers.update.cwf-10901"));
        assertTrue(parameterized.errorDescriptors().containsKey("customer.org2.careers.update.cwf-10902"));

        DecisionSlot postSlot = parameterized.slots().stream()
                .filter(s -> s.slotKey().equals("careers.update.wf-10002"))
                .findFirst()
                .orElseThrow();
        assertEquals(DecisionStage.POST_DECISION, postSlot.stage());

        DecisionSlot cust2Slot = parameterized.slots().stream()
                .filter(s -> s.slotKey().equals("careers.update.cwf-10902"))
                .findFirst()
                .orElseThrow();
        assertEquals(DecisionStage.POST_DECISION, cust2Slot.stage());

        DecisionBinding cust2Binding = parameterized.bindings().stream()
                .filter(b -> b.bindingKey().equals("customer.org2.careers.update.cwf-10902"))
                .findFirst()
                .orElseThrow();
        assertEquals(List.of("draft.observacao"), cust2Binding.requiredFactPaths());

        var registry = RuleBindingExecutorRegistry.empty();
        var plan = new PraxisRulePlanCompiler(registry).compile(parameterized);
        var engine = new PraxisRuleSetEngine(registry);

        ObjectNode validFacts = PraxisFactTreeBuilder.create()
                .putDraft(d -> d.put("codigo", "CAR-01")
                        .put("taxaReajuste", new BigDecimal("2.50"))
                        .put("regime", "ESTATUTARIO")
                        .put("homologado", false)
                        .put("atoLegal", "PORT-12")
                        .put("observacao", "Atualização aprovada"))
                .build();
        RuleEvaluationResult allowResult = engine.evaluate(plan, validFacts, "2026-11-16T00:00:00Z", "UTC");
        assertEquals(RuleDecision.ALLOW, allowResult.decision());

        // 1. DOMAIN_DECISION customer rule (CWF-10901) short-circuits before POST_DECISION
        ObjectNode denyCust1Facts = PraxisFactTreeBuilder.create()
                .putDraft(d -> d.put("codigo", "CAR-01")
                        .put("taxaReajuste", new BigDecimal("2.00"))
                        .put("regime", "ESTATUTARIO")
                        .put("homologado", false)
                        .put("atoLegal", (String) null)
                        .put("observacao", "   "))
                .build();
        RuleEvaluationResult denyCust1 = engine.evaluate(plan, denyCust1Facts, "2026-11-16T00:00:00Z", "UTC");
        assertEquals(RuleDecision.DENY, denyCust1.decision());
        assertEquals(List.of("CWF-10901"), denyCust1.reasonCodes());
        PraxisRuleViolationException exCust1 =
                new PraxisRuleViolationException(plan, denyCust1, bundle.productCatalog());
        assertEquals(1, exCust1.getViolations().size());
        assertEquals("atoLegal", exCust1.getViolations().getFirst().targetField());

        // 2. When DOMAIN_DECISION passes, POST_DECISION parameterized decimal rule (WF-10002) denies via productCatalog
        ObjectNode denyDecimalFacts = validFacts.deepCopy();
        ((ObjectNode) denyDecimalFacts.get("draft")).put("taxaReajuste", new BigDecimal("2.00"));
        RuleEvaluationResult denyDecimal = engine.evaluate(plan, denyDecimalFacts, "2026-11-16T00:00:00Z", "UTC");
        assertEquals(RuleDecision.DENY, denyDecimal.decision());
        assertEquals(List.of("WF-10002"), denyDecimal.reasonCodes());
        PraxisRuleViolationException exDecimal =
                new PraxisRuleViolationException(plan, denyDecimal, bundle.productCatalog());
        assertEquals("taxaReajuste", exDecimal.getViolations().getFirst().targetField());

        // 3. When product POST_DECISION passes, second customer rule in POST_DECISION (CWF-10902) denies
        ObjectNode denyCust2Facts = validFacts.deepCopy();
        ((ObjectNode) denyCust2Facts.get("draft")).put("observacao", "   ");
        RuleEvaluationResult denyCust2 = engine.evaluate(plan, denyCust2Facts, "2026-11-16T00:00:00Z", "UTC");
        assertEquals(RuleDecision.DENY, denyCust2.decision());
        assertEquals(List.of("CWF-10902"), denyCust2.reasonCodes());
        PraxisRuleViolationException exCust2 =
                new PraxisRuleViolationException(plan, denyCust2, bundle.productCatalog());
        assertEquals("observacao", exCust2.getViolations().getFirst().targetField());

        assertTrue(bundle.combinedCatalog().find("CWF-10902").isPresent());
        assertFalse(bundle.productCatalog().find("CWF-10902").isPresent());

        // When includeCustomerBindings=false, errorDescriptors must stay empty so compiler succeeds
        RuleSetCatalogBundle productOnlyBundle = OntologyRuleSetBuilder.definitionWithCatalog(
                new RuleSetRef("workforce.core", "administracao-pessoal.workforce", "workforce.careers", "update", 1),
                "careers.update",
                "customer.org2.",
                productRules,
                customerRules,
                false,
                templates);
        assertTrue(productOnlyBundle.definition().errorDescriptors().isEmpty());
        new PraxisRulePlanCompiler(registry).compile(productOnlyBundle.definition());
    }
}
