package org.praxisplatform.rules.violation;

import java.util.Objects;
import org.praxisplatform.rules.contract.DecisionSource;
import org.praxisplatform.rules.contract.DecisionStage;
import org.praxisplatform.rules.contract.RuleDecision;

/**
 * Public-safe failure provenance; plan-level failures have no invented slot or source.
 *
 * @param code atomic reason
 *
 * @param slotKey optional slot identity
 *
 * @param bindingKey optional binding identity
 *
 * @param source optional governed owner
 *
 * @param stage optional execution stage
 *
 * @param decision original five-state outcome
 *
 * @param targetField optional public correction path
 *
 * @param messagePtBr public message from the catalog
 *
 * @param lineage optional public lineage
 * @param category public classification; defaults to BUSINESS_LOGIC
 */
public record SlotViolationDetail(String code, String slotKey, String bindingKey,
        DecisionSource source, DecisionStage stage, RuleDecision decision,
        String targetField, String messagePtBr, String lineage, String category) {
    /**
     * Retains the original constructor with the default business classification.
     * @param code atomic reason
     * @param slotKey optional slot identity
     * @param bindingKey optional binding identity
     * @param source optional governed owner
     * @param stage optional execution stage
     * @param decision original five-state outcome
     * @param targetField optional public correction path
     * @param messagePtBr public catalog message
     * @param lineage optional public lineage
     */
    public SlotViolationDetail(String code, String slotKey, String bindingKey,
            DecisionSource source, DecisionStage stage, RuleDecision decision,
            String targetField, String messagePtBr, String lineage) {
        this(code, slotKey, bindingKey, source, stage, decision, targetField, messagePtBr, lineage, "BUSINESS_LOGIC");
    }
    /** Requires a real rejection while permitting plan-level failures without slot provenance. */
    public SlotViolationDetail {
        if (code == null || code.isBlank()) throw new IllegalArgumentException("code is required");
        Objects.requireNonNull(decision, "decision");
        if (decision == RuleDecision.ALLOW) throw new IllegalArgumentException("ALLOW is not a violation");
        if (messagePtBr == null || messagePtBr.isBlank()) throw new IllegalArgumentException("messagePtBr is required");
        targetField = targetField == null || targetField.isBlank() ? null : targetField;
        category = category == null || category.isBlank() ? "BUSINESS_LOGIC" : category;
    }
}
