package org.praxisplatform.rules.facts;

import java.util.Objects;
import org.praxisplatform.rules.contract.RuleEvaluationResult;

/**
 * Evaluation evidence bound to the executable provider and its admitted schema.
 * @param result canonical decision and facts digest
 * @param admissionDigest digest of effective plan and complete fact contract
 * @param factContractDigest digest of the trusted contract
 * @param hostContractVersion evaluated host coordinate
 * @param operationKey registered invocation key
 * @param providerRef registered provider revision
 */
public record TypedRuleEvaluationResult(RuleEvaluationResult result, String admissionDigest, String factContractDigest,
        String hostContractVersion, String operationKey, String providerRef) {
    /** Requires all evidence identities. */
    public TypedRuleEvaluationResult {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(hostContractVersion, "hostContractVersion");
        Objects.requireNonNull(operationKey, "operationKey");
        Objects.requireNonNull(providerRef, "providerRef");
        if (admissionDigest == null || !admissionDigest.matches("[A-F0-9]{64}"))
            throw new IllegalArgumentException("Admission digest required");
        if (factContractDigest == null || !factContractDigest.matches("[A-F0-9]{64}"))
            throw new IllegalArgumentException("Fact contract digest required");
    }
}
