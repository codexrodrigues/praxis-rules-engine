package org.praxisplatform.rules.contract;

import java.util.List;
import java.util.Objects;

/**
 * Product-owned immutable executable mold; parameterization changes only its scalar literals.
 * @param templateKey stable namespaced product template identity
 * @param templateVersion exact template revision
 * @param executor product JSON Logic expression; Java parameterization is unsupported
 * @param falseDecision product-owned false outcome
 * @param falseReasonCode product-owned false reason
 * @param requiredFactPaths product-owned required facts
 * @param parameters product-owned restrictions and literal binding sites
 */
public record RuleParameterTemplate(
        String templateKey,
        String templateVersion,
        RuleExecutorRef executor,
        RuleDecision falseDecision,
        String falseReasonCode,
        List<String> requiredFactPaths,
        List<RuleParameterDefinition> parameters) {
    /** Captures immutable template data; the resolver validates the complete restriction. */
    public RuleParameterTemplate {
        Objects.requireNonNull(templateKey, "templateKey is required");
        Objects.requireNonNull(templateVersion, "templateVersion is required");
        Objects.requireNonNull(executor, "executor is required");
        requiredFactPaths = requiredFactPaths == null ? List.of() : List.copyOf(requiredFactPaths);
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
    }
}
