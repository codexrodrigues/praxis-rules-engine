package org.praxisplatform.rules.contract;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Objects;

/**
 * Evaluated outcome of one binding in its resolved plan position.
 *
 * @param bindingKey stable binding identity
 * @param slotKey stable slot identity
 * @param stage resolved execution stage
 * @param decision binding outcome
 * @param reasonCodes stable machine-readable reasons
 * @param output optional pure calculation or intent data
 */
public record RuleBindingResult(
        String bindingKey,
        String slotKey,
        DecisionStage stage,
        RuleDecision decision,
        List<String> reasonCodes,
        JsonNode output) {

    /** Copies all result values before they cross the engine boundary. */
    public RuleBindingResult {
        bindingKey = Objects.requireNonNull(bindingKey, "bindingKey is required");
        slotKey = Objects.requireNonNull(slotKey, "slotKey is required");
        stage = Objects.requireNonNull(stage, "stage is required");
        decision = Objects.requireNonNull(decision, "decision is required");
        reasonCodes = reasonCodes == null ? List.of() : List.copyOf(reasonCodes);
        output = output == null ? null : output.deepCopy();
    }

    /**
     * Returns a defensive copy of binding output.
     * @return copied output or {@code null}
     */
    @Override
    public JsonNode output() {
        return output == null ? null : output.deepCopy();
    }
}
