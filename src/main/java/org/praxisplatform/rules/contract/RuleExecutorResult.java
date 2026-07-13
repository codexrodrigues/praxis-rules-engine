package org.praxisplatform.rules.contract;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Objects;

/**
 * Pure result returned by a trusted Java binding implementation.
 *
 * @param decision binding-level outcome
 * @param reasonCodes stable machine-readable reason codes
 * @param output optional typed calculation or intent data
 */
public record RuleExecutorResult(
        RuleDecision decision,
        List<String> reasonCodes,
        JsonNode output) {

    /** Copies mutable values and requires a complete decision. */
    public RuleExecutorResult {
        decision = Objects.requireNonNull(decision, "decision is required");
        reasonCodes = reasonCodes == null ? List.of() : List.copyOf(reasonCodes);
        output = output == null ? null : output.deepCopy();
    }

    /**
     * Returns a defensive copy of executor output.
     * @return copied output or {@code null}
     */
    @Override
    public JsonNode output() {
        return output == null ? null : output.deepCopy();
    }

    /**
     * Creates an allow result without output.
     * @return allow result
     */
    public static RuleExecutorResult allow() {
        return new RuleExecutorResult(RuleDecision.ALLOW, List.of(), null);
    }

    /**
     * Creates a result with one stable reason code.
     * @param decision binding decision
     * @param reasonCode stable machine-readable reason
     * @return immutable result
     */
    public static RuleExecutorResult of(RuleDecision decision, String reasonCode) {
        return new RuleExecutorResult(decision, List.of(reasonCode), null);
    }
}
