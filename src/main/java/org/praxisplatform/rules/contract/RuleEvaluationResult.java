package org.praxisplatform.rules.contract;

import java.util.List;
import java.util.Objects;

/**
 * Deterministic consolidated RuleSet evaluation result.
 *
 * @param decision consolidated five-state outcome
 * @param ruleSetRef exact evaluated version
 * @param planDigest digest of the compiled plan
 * @param bindingResults ordered binding outcomes
 * @param reasonCodes consolidated stable reasons
 * @param factsDigest canonical SHA-256 of the valid facts snapshot, or {@code null} for invalid input
 * @param compatibility exact engine and dialect baseline used
 * @param implementationRefs exact trusted Java implementations used by the plan
 * @param failPolicy host-enforced failure policy
 * @param transformationProposals ordered typed proposals; the engine never applies them
 */
public record RuleEvaluationResult(
        RuleDecision decision,
        RuleSetRef ruleSetRef,
        String planDigest,
        List<RuleBindingResult> bindingResults,
        List<String> reasonCodes,
        String factsDigest,
        RuleRuntimeCompatibility compatibility,
        List<RuleImplementationRef> implementationRefs,
        RuleFailPolicy failPolicy,
        List<TypedTransformationProposal> transformationProposals) {

    /** Copies ordered outcomes and requires the complete evaluated identity. */
    public RuleEvaluationResult {
        decision = Objects.requireNonNull(decision, "decision is required");
        ruleSetRef = Objects.requireNonNull(ruleSetRef, "ruleSetRef is required");
        if (planDigest == null || planDigest.isBlank()) {
            throw new IllegalArgumentException("planDigest must not be blank");
        }
        bindingResults = bindingResults == null ? List.of() : List.copyOf(bindingResults);
        reasonCodes = reasonCodes == null ? List.of() : List.copyOf(reasonCodes);
        if (factsDigest != null && !factsDigest.matches("[A-F0-9]{64}")) {
            throw new IllegalArgumentException("factsDigest must be an uppercase SHA-256 hex digest");
        }
        compatibility = Objects.requireNonNull(compatibility, "compatibility is required");
        implementationRefs = implementationRefs == null ? List.of() : List.copyOf(implementationRefs);
        failPolicy = Objects.requireNonNull(failPolicy, "failPolicy is required");
        transformationProposals = transformationProposals == null
                ? List.of()
                : List.copyOf(transformationProposals);
    }

    /**
     * Creates an evaluation result without typed transformation proposals.
     * @param decision consolidated five-state outcome
     * @param ruleSetRef exact evaluated version
     * @param planDigest digest of the compiled plan
     * @param bindingResults ordered binding outcomes
     * @param reasonCodes consolidated stable reasons
     * @param factsDigest canonical facts digest, or {@code null} for invalid input
     * @param compatibility exact engine and dialect baseline used
     * @param implementationRefs exact trusted Java implementations used by the plan
     * @param failPolicy host-enforced failure policy
     */
    public RuleEvaluationResult(
            RuleDecision decision,
            RuleSetRef ruleSetRef,
            String planDigest,
            List<RuleBindingResult> bindingResults,
            List<String> reasonCodes,
            String factsDigest,
            RuleRuntimeCompatibility compatibility,
            List<RuleImplementationRef> implementationRefs,
            RuleFailPolicy failPolicy) {
        this(decision, ruleSetRef, planDigest, bindingResults, reasonCodes, factsDigest,
                compatibility, implementationRefs, failPolicy, List.of());
    }
}
