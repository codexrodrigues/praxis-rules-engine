package org.praxisplatform.rules.violation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.praxisplatform.rules.contract.*;
import org.praxisplatform.rules.plan.RuleDecisionPlan;

/**
 * Runtime-neutral fail-closed rejection, without facts, executor outputs or private causes.
 */
public class PraxisRuleViolationException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    /** Original five-state rejection. */
    private final RuleDecision decision;
    /** Distinct atomic reasons in evaluation order. */
    private final List<String> reasonCodes;
    /** Immutable public provenance, without executor data. */
    private final List<SlotViolationDetail> violations;

    /**
     * Projects the consolidated rejection causes through a public-safe catalog.
     *
     * @param plan evaluated plan
     *
     * @param result result belonging to that exact plan
     *
     * @param catalog host's public error catalog
     */
    public PraxisRuleViolationException(RuleDecisionPlan plan, RuleEvaluationResult result, RuleErrorCatalog catalog) {
        this(result, resolveViolations(plan, result, catalog));
    }

    private static List<SlotViolationDetail> resolveViolations(RuleDecisionPlan plan, RuleEvaluationResult result, RuleErrorCatalog catalog) {
        requireMatchingResult(plan, result);
        Objects.requireNonNull(catalog, "catalog");
        if (result.decision() == RuleDecision.ALLOW) throw new IllegalArgumentException("ALLOW is not a violation");
        return describe(plan, result, catalog);
    }

    private PraxisRuleViolationException(RuleEvaluationResult result, List<SlotViolationDetail> resolved) {
        super(resolved.isEmpty() ? "A operação não foi autorizada pelas regras de negócio." : resolved.getFirst().messagePtBr());
        decision = result.decision();
        violations = resolved;
        reasonCodes = List.copyOf(result.reasonCodes());
    }

    /**
     * Creates a direct slot rejection from an already curated public description.
     * @param violation direct public-safe slot rejection
     */
    public PraxisRuleViolationException(SlotViolationDetail violation) {
        this(List.of(Objects.requireNonNull(violation, "violation")));
    }

    /**
     * Creates a group of curated rejections for a host transport adapter.
     * All details must share the consolidated decision. Adapters may extend this exception
     * while retaining the canonical diagnostics and status classification.
     * @param details non-empty public-safe rejections, in evaluation order
     */
    public PraxisRuleViolationException(List<SlotViolationDetail> details) {
        super(firstDetail(details).messagePtBr());
        violations = List.copyOf(details);
        decision = violations.getFirst().decision();
        if (violations.stream().anyMatch(detail -> detail.decision() != decision))
            throw new IllegalArgumentException("Direct violations must share the consolidated decision");
        reasonCodes = violations.stream().map(SlotViolationDetail::code).distinct().toList();
    }

    private static SlotViolationDetail firstDetail(List<SlotViolationDetail> details) {
        Objects.requireNonNull(details, "details");
        if (details.isEmpty()) throw new IllegalArgumentException("At least one violation is required");
        return Objects.requireNonNull(details.getFirst(), "violation");
    }

    /**
     * Rejects accidental pairing of diagnostics with another plan.
     * @param plan evaluated plan
     * @param result claimed evaluation of that plan
     */
    public static void requireMatchingResult(RuleDecisionPlan plan, RuleEvaluationResult result) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(result, "result");
        if (!plan.planDigest().equals(result.planDigest()) || !plan.definition().ref().equals(result.ruleSetRef()))
            throw new IllegalArgumentException("Evaluation result does not belong to plan");
    }

    private static List<SlotViolationDetail> describe(RuleDecisionPlan plan, RuleEvaluationResult result, RuleErrorCatalog catalog) {
        Map<String, DecisionBinding> bindings = new LinkedHashMap<>();
        plan.orderedBindings().forEach(binding -> bindings.put(binding.bindingKey(), binding));
        var dependencyKeys = plan.orderedBindings().stream().flatMap(binding -> binding.dependsOn().stream())
                .collect(java.util.stream.Collectors.toSet());
        var consolidatedCodes = new java.util.HashSet<>(result.reasonCodes());
        List<SlotViolationDetail> details = new ArrayList<>();
        for (RuleBindingResult item : result.bindingResults()) {
            // Intermediate applicability and blocked branches are execution diagnostics. They do
            // not replace the terminal/consolidated causes selected by the canonical evaluator.
            if (item.decision() != result.decision()) continue;
            if (result.decision() == RuleDecision.NOT_APPLICABLE && dependencyKeys.contains(item.bindingKey())) continue;
            DecisionBinding binding = bindings.get(item.bindingKey());
            if (binding == null || !binding.slotKey().equals(item.slotKey())
                    || plan.slotsByKey().get(item.slotKey()).stage() != item.stage())
                throw new IllegalArgumentException("Binding result does not belong to plan");
            RuleErrorDescriptor approved = plan.definition().errorDescriptors().get(item.bindingKey());
            for (String code : item.reasonCodes().stream().filter(consolidatedCodes::contains).toList()) {
                details.add(detail(code, item.slotKey(), item.bindingKey(), binding.source(),
                    item.stage(), item.decision(), approved != null && approved.code().equals(code)
                        ? ignored -> java.util.Optional.of(approved) : catalog));
            }
        }
        for (String code : result.reasonCodes()) {
            if (details.stream().noneMatch(detail -> detail.code().equals(code)))
                details.add(detail(code, null, null, null, null, result.decision(), catalog));
        }
        // Synthetic transport fallback must not invent a reason or a correction field via catalog.
        if (details.isEmpty()) details.add(new SlotViolationDetail("RULE_" + result.decision().name(),
                null, null, null, null, result.decision(), null,
                "A operação não foi autorizada pelas regras de negócio.", null));
        return List.copyOf(details);
    }

    private static SlotViolationDetail detail(String code, String slot, String binding, DecisionSource source,
            DecisionStage stage, RuleDecision decision, RuleErrorCatalog catalog) {
        RuleErrorDescriptor descriptor = catalog.find(code).orElse(null);
        if (descriptor != null && !descriptor.code().equals(code)) throw new IllegalArgumentException("Catalog returned another code");
        return new SlotViolationDetail(code, slot, binding, source, stage, decision,
                descriptor == null ? null : descriptor.targetField(),
                descriptor == null ? "A operação não foi autorizada pelas regras de negócio." : descriptor.messagePtBr(),
                descriptor == null ? null : descriptor.lineage(),
                descriptor == null ? "BUSINESS_LOGIC" : descriptor.category());
    }

    /**
     * Preserves the evaluated decision.
     * @return the original consolidated outcome, never coerced to DENY
     */
    public RuleDecision getDecision() { return decision; }
    /**
     * Exposes the atomic rejection codes.
     * @return immutable distinct reason codes
     */
    public List<String> getReasonCodes() { return reasonCodes; }
    /**
     * Exposes the rejection provenance.
     * @return immutable individual reasons and their public provenance
     */
    public List<SlotViolationDetail> getViolations() { return violations; }
    /**
     * Suggests the transport status without depending on an HTTP framework.
     * Mixed rejection categories remain unprocessable business input.
     * @return 409 when every violation is a conflict, otherwise 422
     */
    public int getHttpStatus() {
        return !violations.isEmpty() && violations.stream().allMatch(v -> "CONFLICT".equalsIgnoreCase(v.category())) ? 409 : 422;
    }
    /**
     * Identifies the slots that rejected execution.
     * @return distinct known slots, omitting plan-level failures
     */
    public List<String> getSlotKeys() { return violations.stream().map(SlotViolationDetail::slotKey).filter(Objects::nonNull).distinct().toList(); }
    /**
     * Identifies the governed owners of evaluated rejections.
     * @return distinct known governed owners, without inventing a technical-failure owner
     */
    public List<DecisionSource> getDecisionSources() { return violations.stream().map(SlotViolationDetail::source).filter(Objects::nonNull).distinct().toList(); }

    /**
     * Projects only declared public rejection data for a transport adapter.
     * @return immutable allowlisted properties suitable for an adapter; contains no facts or outputs
     */
    public Map<String, Object> toDiagnosticProperties() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("decision", decision.name());
        properties.put("reasonCodes", reasonCodes);
        properties.put("slotKeys", getSlotKeys());
        properties.put("decisionSources", getDecisionSources().stream().map(Enum::name).toList());
        properties.put("violations", violations);
        return Collections.unmodifiableMap(properties);
    }
}
