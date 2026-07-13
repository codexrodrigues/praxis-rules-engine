package org.praxisplatform.rules.jsonlogic.model;

import java.util.List;

/**
 * Controls structural and semantic validation without executing a rule.
 *
 * @param availableRoots root names that a rule may address explicitly
 * @param defaultRoot root used when implicit addressing is enabled
 * @param allowImplicitRoot whether unqualified paths may resolve against the default root
 * @param nowUtc frozen UTC instant used to validate temporal context requirements
 * @param userTimeZone user time zone used for local temporal values
 * @param requireExpressionObject whether the top-level value must be a single-operator object
 * @param limits resource limits applied while traversing the rule
 */
public record JsonLogicValidationOptions(
    List<String> availableRoots,
    String defaultRoot,
    Boolean allowImplicitRoot,
    String nowUtc,
    String userTimeZone,
    Boolean requireExpressionObject,
    JsonLogicLimits limits
) {
    /**
     * Creates validation options with the platform default resource limits.
     * @param availableRoots root names that a rule may address explicitly
     * @param defaultRoot root used when implicit addressing is enabled
     * @param allowImplicitRoot whether unqualified paths may resolve against the default root
     * @param nowUtc frozen UTC instant used to validate temporal context requirements
     * @param userTimeZone user time zone used for local temporal values
     * @param requireExpressionObject whether the top-level value must be a single-operator object
     */
    public JsonLogicValidationOptions(List<String> availableRoots, String defaultRoot, Boolean allowImplicitRoot,
                                      String nowUtc, String userTimeZone, Boolean requireExpressionObject) {
        this(availableRoots, defaultRoot, allowImplicitRoot, nowUtc, userTimeZone, requireExpressionObject, JsonLogicLimits.DEFAULT);
    }

    /** Normalizes nullable collections and limits into immutable runtime values. */
    public JsonLogicValidationOptions {
        availableRoots = availableRoots == null ? List.of() : List.copyOf(availableRoots);
        limits = limits == null ? JsonLogicLimits.DEFAULT : limits;
    }
    /**
     * Projects the shared context controls into evaluation options.
     * @return evaluation options carrying the same roots, temporal context, and limits
     */
    public JsonLogicEvaluationOptions toEvaluationOptions() {
        return new JsonLogicEvaluationOptions(
            availableRoots,
            defaultRoot,
            allowImplicitRoot,
            nowUtc,
            userTimeZone,
            limits
        );
    }
}
