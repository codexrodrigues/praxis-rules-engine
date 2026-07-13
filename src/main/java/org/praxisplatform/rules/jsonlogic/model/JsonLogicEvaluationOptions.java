package org.praxisplatform.rules.jsonlogic.model;

import java.util.List;

/**
 * Caller-provided controls for deterministic rule evaluation.
 *
 * @param availableRoots root names that a rule may address explicitly
 * @param defaultRoot root used when implicit addressing is enabled
 * @param allowImplicitRoot whether unqualified paths may resolve against the default root
 * @param nowUtc frozen UTC instant required by temporal operators
 * @param userTimeZone user time zone used for local temporal values
 * @param limits resource limits applied to untrusted rule input
 */
public record JsonLogicEvaluationOptions(
    List<String> availableRoots,
    String defaultRoot,
    Boolean allowImplicitRoot,
    String nowUtc,
    String userTimeZone,
    JsonLogicLimits limits
) {
    /**
     * Creates options with the platform default resource limits.
     * @param availableRoots root names that a rule may address explicitly
     * @param defaultRoot root used when implicit addressing is enabled
     * @param allowImplicitRoot whether unqualified paths may resolve against the default root
     * @param nowUtc frozen UTC instant required by temporal operators
     * @param userTimeZone user time zone used for local temporal values
     */
    public JsonLogicEvaluationOptions(List<String> availableRoots, String defaultRoot, Boolean allowImplicitRoot,
                                      String nowUtc, String userTimeZone) {
        this(availableRoots, defaultRoot, allowImplicitRoot, nowUtc, userTimeZone, JsonLogicLimits.DEFAULT);
    }

    /** Normalizes nullable collections and limits into immutable runtime values. */
    public JsonLogicEvaluationOptions {
        availableRoots = availableRoots == null ? List.of() : List.copyOf(availableRoots);
        limits = limits == null ? JsonLogicLimits.DEFAULT : limits;
    }
}
