package org.praxisplatform.rules.snapshot;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.praxisplatform.rules.contract.DecisionAggregationPolicy;
import org.praxisplatform.rules.contract.DecisionBinding;
import org.praxisplatform.rules.contract.DecisionSlot;
import org.praxisplatform.rules.contract.DecisionSource;
import org.praxisplatform.rules.contract.DecisionStage;
import org.praxisplatform.rules.contract.OverridePolicy;
import org.praxisplatform.rules.contract.RuleDecision;
import org.praxisplatform.rules.contract.RuleExecutorRef;
import org.praxisplatform.rules.contract.RuleFailPolicy;
import org.praxisplatform.rules.contract.RuleParameterDefinition;
import org.praxisplatform.rules.contract.RuleParameterTemplate;
import org.praxisplatform.rules.contract.RuleParameterType;
import org.praxisplatform.rules.contract.RuleRuntimeCompatibility;
import org.praxisplatform.rules.contract.RuleSetDefinition;
import org.praxisplatform.rules.contract.RuleSetRef;
import org.praxisplatform.rules.contract.SlotCardinality;

class RuleSetProductAdmissionVerifierRoundTripTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void jsonRoundTrippedNumericNodesInProtectedBindingsAndParameterTemplatesAreAdmitted()
            throws Exception {
        long dayMs = 86_400_000L; // LongNode in-memory -> IntNode after JSON deserialization
        var expr =
                JSON.valueToTree(
                        Map.of(
                                "<=",
                                List.of(
                                        Map.of("/", List.of(Map.of("var", "draft.durationMs"), dayMs)),
                                        60)));
        var template =
                new RuleParameterTemplate(
                        "demo:bounds",
                        "1.0",
                        RuleExecutorRef.jsonLogic(expr),
                        RuleDecision.DENY,
                        "ERR-001",
                        List.of("draft.durationMs"),
                        List.of(
                                new RuleParameterDefinition(
                                        "maxDays",
                                        RuleParameterType.NUMBER,
                                        true,
                                        false,
                                        false,
                                        "days",
                                        JSON.valueToTree(60L),
                                        BigDecimal.ONE,
                                        new BigDecimal("365.00"),
                                        List.of(),
                                        List.of("/<=/1"))));
        var protectedSlot =
                new DecisionSlot(
                        "slot.guard",
                        DecisionStage.PROTECTED_GUARD,
                        SlotCardinality.SINGLE,
                        OverridePolicy.FORBIDDEN,
                        DecisionAggregationPolicy.SINGLE_RESULT,
                        null,
                        List.of());
        var paramSlot =
                new DecisionSlot(
                        "slot.param",
                        DecisionStage.DOMAIN_DECISION,
                        SlotCardinality.SINGLE,
                        OverridePolicy.PARAMETERIZABLE,
                        DecisionAggregationPolicy.SINGLE_RESULT,
                        template,
                        List.of());
        var guardBinding =
                new DecisionBinding(
                        "product.guard",
                        "slot.guard",
                        DecisionSource.PRODUCT,
                        null,
                        RuleExecutorRef.jsonLogic(expr),
                        List.of(),
                        10,
                        true,
                        RuleDecision.DENY,
                        "ERR-000",
                        List.of("draft.durationMs"),
                        null);
        var paramDefaultBinding =
                new DecisionBinding(
                        "product.param",
                        "slot.param",
                        DecisionSource.PRODUCT,
                        null,
                        RuleExecutorRef.jsonLogic(expr),
                        List.of(),
                        20,
                        true,
                        RuleDecision.DENY,
                        "ERR-001",
                        List.of("draft.durationMs"),
                        null);

        var product =
                RuleSetDefinition.withInferredRoots(
                        new RuleSetRef("demo", "ctx", "demo.ruleset", "demo.ruleset:create", 1),
                        List.of(protectedSlot, paramSlot),
                        List.of(guardBinding, paramDefaultBinding),
                        RuleRuntimeCompatibility.current(),
                        RuleFailPolicy.FAIL_CLOSED);

        String serialized = JSON.writeValueAsString(product);
        RuleSetDefinition deserializedCandidate = JSON.readValue(serialized, RuleSetDefinition.class);

        assertDoesNotThrow(
                () ->
                        RuleSetProductAdmissionVerifier.require(
                                product, deserializedCandidate, List.of("draft")));

        String tamperedJson = serialized.replace("86400000", "86400001");
        RuleSetDefinition tamperedCandidate = JSON.readValue(tamperedJson, RuleSetDefinition.class);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        RuleSetProductAdmissionVerifier.require(
                                product, tamperedCandidate, List.of("draft")));
    }
}
