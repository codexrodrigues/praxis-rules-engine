package org.praxisplatform.rules.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.praxisplatform.rules.contract.DecisionBinding;
import org.praxisplatform.rules.contract.DecisionSlot;
import org.praxisplatform.rules.contract.DecisionStage;
import org.praxisplatform.rules.contract.RuleBindingResult;
import org.praxisplatform.rules.contract.RuleDecision;
import org.praxisplatform.rules.contract.RuleEvaluationResult;
import org.praxisplatform.rules.contract.RuleImplementationRef;
import org.praxisplatform.rules.contract.RuleExecutorResult;
import org.praxisplatform.rules.contract.RuleExecutorType;
import org.praxisplatform.rules.jsonlogic.PraxisJsonLogicEngine;
import org.praxisplatform.rules.jsonlogic.PraxisJsonLogicException;
import org.praxisplatform.rules.jsonlogic.internal.PraxisPath;
import org.praxisplatform.rules.jsonlogic.model.JsonLogicEvaluationOptions;
import org.praxisplatform.rules.jsonlogic.model.JsonLogicLimits;
import org.praxisplatform.rules.plan.RuleDecisionPlan;
import org.praxisplatform.rules.digest.PraxisCanonicalJson;

/** Stateless evaluator for an already validated and deterministically compiled RuleSet plan. */
public final class PraxisRuleSetEngine {
    private final PraxisJsonLogicEngine jsonLogicEngine;
    private final RuleBindingExecutorRegistry executorRegistry;

    /**
     * Creates an evaluator using the canonical JSON Logic engine and supplied Java registry.
     * @param executorRegistry trusted Java executor registry
     */
    public PraxisRuleSetEngine(RuleBindingExecutorRegistry executorRegistry) {
        this(new PraxisJsonLogicEngine(), executorRegistry);
    }

    /**
     * Creates an evaluator from explicit pure runtime collaborators.
     * @param jsonLogicEngine canonical declarative engine
     * @param executorRegistry trusted Java executor registry
     */
    public PraxisRuleSetEngine(
            PraxisJsonLogicEngine jsonLogicEngine,
            RuleBindingExecutorRegistry executorRegistry) {
        this.jsonLogicEngine = Objects.requireNonNull(jsonLogicEngine, "jsonLogicEngine is required");
        this.executorRegistry = Objects.requireNonNull(executorRegistry, "executorRegistry is required");
    }

    /**
     * Evaluates one frozen plan against host-resolved facts.
     *
     * @param plan validated immutable plan
     * @param facts complete facts snapshot for this evaluation
     * @param nowUtc frozen UTC instant
     * @param userTimeZone explicit time zone
     * @return deterministic consolidated result
     */
    public RuleEvaluationResult evaluate(
            RuleDecisionPlan plan,
            JsonNode facts,
            String nowUtc,
            String userTimeZone) {
        Objects.requireNonNull(plan, "plan is required");
        if (facts == null || !facts.isObject()) {
            return result(
                    plan,
                    RuleDecision.TECHNICAL_ERROR,
                    List.of(),
                    List.of("FACTS_INVALID"),
                    null);
        }
        try {
            jsonLogicEngine.validateResultValue(facts, JsonLogicLimits.DEFAULT);
        } catch (PraxisJsonLogicException exception) {
            return result(
                    plan,
                    RuleDecision.TECHNICAL_ERROR,
                    List.of(),
                    List.of("FACTS_LIMIT_EXCEEDED"),
                    null);
        }
        String factsDigest = PraxisCanonicalJson.sha256(facts);
        if (!validTemporalContext(nowUtc, userTimeZone)) {
            return result(
                    plan,
                    RuleDecision.TECHNICAL_ERROR,
                    List.of(),
                    List.of("TEMPORAL_CONTEXT_INVALID"),
                    factsDigest);
        }

        List<RuleBindingResult> bindingResults = new ArrayList<>();
        Map<String, RuleBindingResult> resultsByBinding = new LinkedHashMap<>();
        boolean anyInconclusive = false;
        for (DecisionBinding binding : plan.orderedBindings()) {
            DecisionSlot slot = plan.slotsByKey().get(binding.slotKey());
            RuleBindingResult bindingResult = effectGateResult(
                    slot, binding, anyInconclusive);
            if (bindingResult == null) {
                bindingResult = dependencyResult(slot, binding, resultsByBinding);
            }
            if (bindingResult == null) {
                bindingResult = evaluateBinding(
                        plan,
                        slot,
                        binding,
                        facts,
                        nowUtc,
                        userTimeZone);
            }
            bindingResults.add(bindingResult);
            resultsByBinding.put(binding.bindingKey(), bindingResult);
            switch (bindingResult.decision()) {
                case ALLOW -> {
                    // Consolidated from terminal bindings after all branches finish.
                }
                case NOT_APPLICABLE -> {
                    // Continue so another binding can establish applicability.
                }
                case INCONCLUSIVE -> anyInconclusive = true;
                case DENY, TECHNICAL_ERROR -> {
                    return result(
                            plan,
                            bindingResult.decision(),
                            bindingResults,
                            bindingResult.reasonCodes(),
                            factsDigest);
                }
            }
        }
        java.util.Set<String> dependencyKeys = plan.orderedBindings().stream()
                .flatMap(binding -> binding.dependsOn().stream())
                .collect(java.util.stream.Collectors.toSet());
        boolean terminalAllow = plan.orderedBindings().stream()
                .filter(binding -> !dependencyKeys.contains(binding.bindingKey()))
                .map(binding -> resultsByBinding.get(binding.bindingKey()))
                .anyMatch(bindingResult -> bindingResult.decision() == RuleDecision.ALLOW);
        return result(
                plan,
                anyInconclusive
                        ? RuleDecision.INCONCLUSIVE
                        : terminalAllow ? RuleDecision.ALLOW : RuleDecision.NOT_APPLICABLE,
                bindingResults,
                anyInconclusive
                        ? bindingResults.stream()
                                .filter(item -> item.decision() == RuleDecision.INCONCLUSIVE)
                                .flatMap(item -> item.reasonCodes().stream())
                                .filter(code -> !code.equals("DEPENDENCY_INCONCLUSIVE"))
                                .toList()
                        : List.of(),
                factsDigest);
    }

    private RuleBindingResult effectGateResult(
            DecisionSlot slot,
            DecisionBinding binding,
            boolean anyInconclusive) {
        if (slot.stage() != DecisionStage.EFFECT_INTENT) {
            return null;
        }
        if (anyInconclusive) {
            return bindingResult(
                    slot,
                    binding,
                    RuleDecision.INCONCLUSIVE,
                    List.of("PRIOR_DECISION_INCONCLUSIVE"),
                    null);
        }
        return null;
    }

    private RuleBindingResult dependencyResult(
            DecisionSlot slot,
            DecisionBinding binding,
            Map<String, RuleBindingResult> resultsByBinding) {
        boolean notApplicable = false;
        for (String dependencyKey : binding.dependsOn()) {
            RuleBindingResult dependency = resultsByBinding.get(dependencyKey);
            if (dependency != null && dependency.decision() == RuleDecision.INCONCLUSIVE) {
                return bindingResult(
                        slot,
                        binding,
                        RuleDecision.INCONCLUSIVE,
                        List.of("DEPENDENCY_INCONCLUSIVE"),
                        null);
            }
            if (dependency != null && dependency.decision() == RuleDecision.NOT_APPLICABLE) {
                notApplicable = true;
            }
        }
        if (notApplicable) {
            return bindingResult(
                    slot,
                    binding,
                    RuleDecision.NOT_APPLICABLE,
                    List.of("DEPENDENCY_NOT_APPLICABLE"),
                    null);
        }
        return null;
    }

    private RuleBindingResult evaluateBinding(
            RuleDecisionPlan plan,
            DecisionSlot slot,
            DecisionBinding binding,
            JsonNode facts,
            String nowUtc,
            String userTimeZone) {
        for (String requiredPath : binding.requiredFactPaths()) {
            if (!pathExists(facts, requiredPath)) {
                return bindingResult(
                        slot,
                        binding,
                        RuleDecision.INCONCLUSIVE,
                        List.of("FACT_REQUIRED_MISSING"),
                        null);
            }
        }
        if (binding.executor().type() == RuleExecutorType.JSON_LOGIC) {
            try {
                boolean matches = jsonLogicEngine.evaluateResult(
                        binding.executor().expression(),
                        facts,
                        new JsonLogicEvaluationOptions(
                                plan.definition().availableRoots(),
                                null,
                                false,
                                nowUtc,
                                userTimeZone))
                        .truthy();
                return bindingResult(
                        slot,
                        binding,
                        matches ? RuleDecision.ALLOW : binding.falseDecision(),
                        matches ? List.of() : List.of(binding.falseReasonCode()),
                        null);
            } catch (PraxisJsonLogicException exception) {
                return bindingResult(
                        slot,
                        binding,
                        RuleDecision.TECHNICAL_ERROR,
                        List.of("JSON_LOGIC_" + exception.getCode().name()),
                        null);
            }
        }
        RuleBindingExecutor executor = executorRegistry.get(binding.executor().implementationKey());
        if (executor == null) {
            return bindingResult(
                    slot,
                    binding,
                    RuleDecision.TECHNICAL_ERROR,
                    List.of("IMPLEMENTATION_UNAVAILABLE"),
                    null);
        }
        try {
            RuleExecutorResult executorResult = executor.evaluate(new RuleExecutorContext(
                    plan.definition().ref(),
                    binding,
                    facts,
                    nowUtc,
                    userTimeZone));
            if (executorResult == null) {
                return bindingResult(
                        slot,
                        binding,
                        RuleDecision.TECHNICAL_ERROR,
                        List.of("IMPLEMENTATION_RESULT_INVALID"),
                        null);
            }
            if (slot.stage() == DecisionStage.EFFECT_INTENT
                    && executorResult.decision() == RuleDecision.DENY) {
                return bindingResult(
                        slot,
                        binding,
                        RuleDecision.TECHNICAL_ERROR,
                        List.of("EFFECT_INTENT_DECISION_INVALID"),
                        null);
            }
            try {
                jsonLogicEngine.validateResultValue(executorResult.output(), JsonLogicLimits.DEFAULT);
            } catch (PraxisJsonLogicException exception) {
                return bindingResult(
                        slot,
                        binding,
                        RuleDecision.TECHNICAL_ERROR,
                        List.of("IMPLEMENTATION_RESULT_LIMIT_EXCEEDED"),
                        null);
            }
            return bindingResult(
                    slot,
                    binding,
                    executorResult.decision(),
                    executorResult.reasonCodes(),
                    executorResult.output());
        } catch (RuntimeException exception) {
            return bindingResult(
                    slot,
                    binding,
                    RuleDecision.TECHNICAL_ERROR,
                    List.of("IMPLEMENTATION_EXECUTION_FAILED"),
                    null);
        }
    }

    private RuleBindingResult bindingResult(
            DecisionSlot slot,
            DecisionBinding binding,
            RuleDecision decision,
            List<String> reasonCodes,
            JsonNode output) {
        return new RuleBindingResult(
                binding.bindingKey(),
                binding.slotKey(),
                slot.stage(),
                decision,
                normalizedReasonCodes(reasonCodes),
                output);
    }

    private RuleEvaluationResult result(
            RuleDecisionPlan plan,
            RuleDecision decision,
            List<RuleBindingResult> bindingResults,
            List<String> reasonCodes,
            String factsDigest) {
        return new RuleEvaluationResult(
                decision,
                plan.definition().ref(),
                plan.planDigest(),
                bindingResults,
                normalizedReasonCodes(reasonCodes),
                factsDigest,
                plan.definition().compatibility(),
                implementationRefs(plan),
                plan.definition().failPolicy());
    }

    private List<RuleImplementationRef> implementationRefs(RuleDecisionPlan plan) {
        return plan.orderedBindings().stream()
                .map(DecisionBinding::executor)
                .filter(executor -> executor.type() == RuleExecutorType.JAVA)
                .map(executor -> new RuleImplementationRef(
                        executor.implementationKey(), executor.implementationVersion()))
                .distinct()
                .sorted(java.util.Comparator
                        .comparing(RuleImplementationRef::implementationKey)
                        .thenComparing(RuleImplementationRef::implementationVersion))
                .toList();
    }

    private List<String> normalizedReasonCodes(List<String> reasonCodes) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (reasonCodes != null) {
            reasonCodes.stream()
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(code -> !code.isEmpty())
                    .forEach(normalized::add);
        }
        return List.copyOf(normalized);
    }

    private boolean pathExists(JsonNode facts, String path) {
        JsonNode current = facts;
        for (String segment : PraxisPath.parse(path)) {
            if (current == null || current.isNull()) {
                return false;
            }
            if (current.isArray()) {
                if (!segment.matches("\\d+")) {
                    return false;
                }
                int index = Integer.parseInt(segment);
                if (index >= current.size()) {
                    return false;
                }
                current = current.get(index);
            } else if (current.isObject()) {
                if (!current.has(segment)) {
                    return false;
                }
                current = current.get(segment);
            } else {
                return false;
            }
        }
        return current != null;
    }

    private boolean validTemporalContext(String nowUtc, String userTimeZone) {
        if (nowUtc == null || nowUtc.isBlank() || userTimeZone == null || userTimeZone.isBlank()) {
            return false;
        }
        try {
            Instant.parse(nowUtc);
            ZoneId.of(userTimeZone);
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }
}
