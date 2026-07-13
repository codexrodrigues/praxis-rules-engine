package org.praxisplatform.rules.jsonlogic.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * Immutable inputs shared by native and extension operators during one evaluation.
 *
 * @param data business data evaluated by the rule
 * @param availableRoots root names that a rule may address explicitly
 * @param defaultRoot root used when an implicit path is allowed
 * @param allowImplicitRoot whether unqualified paths may resolve against the default root
 * @param nowUtc frozen UTC instant used by temporal operators
 * @param userTimeZone user time zone used when interpreting local temporal values
 * @param limits resource limits enforced for this evaluation
 */
public record JsonLogicEvaluationContext(
    JsonNode data,
    List<String> availableRoots,
    String defaultRoot,
    boolean allowImplicitRoot,
    String nowUtc,
    String userTimeZone,
    JsonLogicLimits limits
) {
    /** Normalizes nullable collections and limits into immutable runtime values. */
    public JsonLogicEvaluationContext {
        availableRoots = availableRoots == null ? List.of() : List.copyOf(availableRoots);
        limits = limits == null ? JsonLogicLimits.DEFAULT : limits;
    }
}
