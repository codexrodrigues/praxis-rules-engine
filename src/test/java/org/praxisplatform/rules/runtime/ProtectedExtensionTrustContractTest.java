package org.praxisplatform.rules.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.praxisplatform.rules.contract.CompositionPolicy;
import org.praxisplatform.rules.contract.DecisionAggregationPolicy;
import org.praxisplatform.rules.contract.DecisionBinding;
import org.praxisplatform.rules.contract.DecisionSlot;
import org.praxisplatform.rules.contract.DecisionSource;
import org.praxisplatform.rules.contract.DecisionStage;
import org.praxisplatform.rules.contract.OverridePolicy;
import org.praxisplatform.rules.contract.RuleDecision;
import org.praxisplatform.rules.contract.RuleExecutorRef;
import org.praxisplatform.rules.contract.RuleExecutorResult;
import org.praxisplatform.rules.contract.RuleExtensionTrust;
import org.praxisplatform.rules.contract.RuleFailPolicy;
import org.praxisplatform.rules.contract.RuleImplementationRef;
import org.praxisplatform.rules.contract.RuleRuntimeCompatibility;
import org.praxisplatform.rules.contract.RuleSetDefinition;
import org.praxisplatform.rules.contract.RuleSetRef;
import org.praxisplatform.rules.contract.SlotCardinality;
import org.praxisplatform.rules.plan.PraxisRulePlanCompiler;
import org.praxisplatform.rules.plan.RulePlanException;
import org.praxisplatform.rules.plan.RulePlanIssueCode;

class ProtectedExtensionTrustContractTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String IMPLEMENTATION_KEY = "customer:benefit-eligibility";
    private static final String IMPLEMENTATION_VERSION = "1.0.0";
    private static final RuleExtensionTrust TRUST = trust("A", "B");

    @Test
    void rejectsUnsignedCustomerJavaExtensionByDefault() {
        RulePlanException exception = assertThrows(
                RulePlanException.class,
                () -> new PraxisRulePlanCompiler(registry(null)).compile(definition(domainSlot())));

        assertEquals(RulePlanIssueCode.PLAN_EXTENSION_TRUST_INVALID, exception.getCode());
        assertEquals("customer.extension", exception.getBindingKey());
    }

    @Test
    void acceptsAttestedExtensionAndCarriesEvidenceInDeterministicResult() {
        RuleBindingExecutorRegistry registry = registry(TRUST);
        var plan = new PraxisRulePlanCompiler(registry).compile(definition(domainSlot()));
        var result = new PraxisRuleSetEngine(registry)
                .evaluate(plan, JSON.createObjectNode().putObject("request"), "2026-07-15T12:00:00Z", "UTC");

        assertEquals(RuleDecision.ALLOW, result.decision());
        assertEquals(1, result.implementationRefs().size());
        assertEquals(TRUST, result.implementationRefs().getFirst().extensionTrust());
    }

    @Test
    void trustEvidenceParticipatesInPlanDigest() {
        String first = new PraxisRulePlanCompiler(registry(TRUST))
                .compile(definition(domainSlot()))
                .planDigest();
        String changedArtifact = new PraxisRulePlanCompiler(registry(trust("C", "B")))
                .compile(definition(domainSlot()))
                .planDigest();
        String changedVerification = new PraxisRulePlanCompiler(registry(trust("A", "D")))
                .compile(definition(domainSlot()))
                .planDigest();

        assertNotEquals(first, changedArtifact);
        assertNotEquals(first, changedVerification);
    }

    @Test
    void signedCustomerExtensionCannotTargetProtectedGuard() {
        RulePlanException exception = assertThrows(
                RulePlanException.class,
                () -> new PraxisRulePlanCompiler(registry(TRUST)).compile(definition(protectedSlot())));

        assertEquals(RulePlanIssueCode.PLAN_OVERRIDE_INVALID, exception.getCode());
    }

    @Test
    void rejectsRelabelingAttestedCustomerCodeAsProductCode() {
        DecisionSlot slot = new DecisionSlot(
                "benefit.eligibility",
                DecisionStage.DOMAIN_DECISION,
                SlotCardinality.SINGLE,
                OverridePolicy.FORBIDDEN,
                DecisionAggregationPolicy.SINGLE_RESULT);
        DecisionBinding binding = new DecisionBinding(
                "product.extension",
                slot.slotKey(),
                DecisionSource.PRODUCT,
                null,
                RuleExecutorRef.java(IMPLEMENTATION_KEY, IMPLEMENTATION_VERSION),
                List.of(), 10, true, null, null, List.of());
        RuleSetDefinition definition = definition(slot, binding);

        RulePlanException exception = assertThrows(
                RulePlanException.class,
                () -> new PraxisRulePlanCompiler(registry(TRUST)).compile(definition));

        assertEquals(RulePlanIssueCode.PLAN_EXTENSION_TRUST_INVALID, exception.getCode());
    }

    @Test
    void planningRegistryAppliesTheSameTrustGateWithoutLoadingHostCode() {
        RuleBindingExecutorRegistry registry = RuleBindingExecutorRegistry.planning(List.of(
                new RuleImplementationRef(IMPLEMENTATION_KEY, IMPLEMENTATION_VERSION, TRUST)));
        var plan = new PraxisRulePlanCompiler(registry).compile(definition(domainSlot()));
        var result = new PraxisRuleSetEngine(registry)
                .evaluate(plan, JSON.createObjectNode().putObject("request"), "2026-07-15T12:00:00Z", "UTC");

        assertEquals(RuleDecision.TECHNICAL_ERROR, result.decision());
        assertEquals(TRUST, result.implementationRefs().getFirst().extensionTrust());
    }

    @Test
    void refusesArtifactSubstitutionBetweenCompilationAndEvaluation() {
        RuleBindingExecutorRegistry compilationRegistry = registry(TRUST);
        var plan = new PraxisRulePlanCompiler(compilationRegistry).compile(definition(domainSlot()));
        RuleBindingExecutorRegistry substitutedRegistry = registry(trust("C", "D"));

        var result = new PraxisRuleSetEngine(substitutedRegistry)
                .evaluate(plan, JSON.createObjectNode().putObject("request"), "2026-07-15T12:00:00Z", "UTC");

        assertEquals(RuleDecision.TECHNICAL_ERROR, result.decision());
        assertEquals(List.of("IMPLEMENTATION_TRUST_MISMATCH"), result.reasonCodes());
        assertEquals(TRUST, result.implementationRefs().getFirst().extensionTrust());
    }

    @Test
    void rejectsAmbiguousTrustEvidence() {
        assertThrows(IllegalArgumentException.class, () -> new RuleExtensionTrust(
                "not-a-digest", "sigstore:tenant-a", "policy:customer-extension-v1", "B".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> new RuleExtensionTrust(
                "A".repeat(64), "tenant-a", "policy:customer-extension-v1", "B".repeat(64)));
    }

    private static RuleSetDefinition definition(DecisionSlot slot) {
        DecisionBinding binding = new DecisionBinding(
                "customer.extension",
                slot.slotKey(),
                DecisionSource.CUSTOMER,
                CompositionPolicy.REPLACE_EXACT,
                RuleExecutorRef.java(IMPLEMENTATION_KEY, IMPLEMENTATION_VERSION),
                List.of(),
                10,
                true,
                null,
                null,
                List.of());
        return definition(slot, binding);
    }

    private static RuleSetDefinition definition(DecisionSlot slot, DecisionBinding binding) {
        return new RuleSetDefinition(
                new RuleSetRef("benefits", "grants", "customer-extension", "evaluate", 1),
                List.of("request"),
                List.of(slot),
                List.of(binding),
                RuleRuntimeCompatibility.current(),
                RuleFailPolicy.FAIL_CLOSED);
    }

    private static DecisionSlot domainSlot() {
        return new DecisionSlot(
                "benefit.eligibility",
                DecisionStage.DOMAIN_DECISION,
                SlotCardinality.SINGLE,
                OverridePolicy.REPLACEABLE,
                DecisionAggregationPolicy.SINGLE_RESULT);
    }

    private static DecisionSlot protectedSlot() {
        return new DecisionSlot(
                "request.authorization",
                DecisionStage.PROTECTED_GUARD,
                SlotCardinality.SINGLE,
                OverridePolicy.FORBIDDEN,
                DecisionAggregationPolicy.SINGLE_RESULT);
    }

    private static RuleBindingExecutorRegistry registry(RuleExtensionTrust trust) {
        return new RuleBindingExecutorRegistry(List.of(new RuleBindingExecutor() {
            @Override
            public String implementationKey() {
                return IMPLEMENTATION_KEY;
            }

            @Override
            public String implementationVersion() {
                return IMPLEMENTATION_VERSION;
            }

            @Override
            public RuleExtensionTrust extensionTrust() {
                return trust;
            }

            @Override
            public RuleExecutorResult evaluate(RuleExecutorContext context) {
                return RuleExecutorResult.allow();
            }
        }));
    }

    private static RuleExtensionTrust trust(String artifactHex, String evidenceHex) {
        return new RuleExtensionTrust(
                artifactHex.repeat(64),
                "sigstore:tenant-a-release",
                "policy:customer-extension-v1",
                evidenceHex.repeat(64));
    }
}
