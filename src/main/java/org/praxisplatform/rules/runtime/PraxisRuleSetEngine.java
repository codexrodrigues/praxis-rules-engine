package org.praxisplatform.rules.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
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
import org.praxisplatform.rules.contract.RuleEngineLimits;
import org.praxisplatform.rules.contract.RuleImplementationRef;
import org.praxisplatform.rules.contract.RuleExecutorResult;
import org.praxisplatform.rules.contract.RuleExecutorType;
import org.praxisplatform.rules.contract.TransformationDraft;
import org.praxisplatform.rules.contract.TypedTransformationProposal;
import org.praxisplatform.rules.jsonlogic.PraxisJsonLogicEngine;
import org.praxisplatform.rules.jsonlogic.PraxisJsonLogicException;
import org.praxisplatform.rules.jsonlogic.internal.PraxisPath;
import org.praxisplatform.rules.jsonlogic.model.JsonLogicEvaluationOptions;
import org.praxisplatform.rules.jsonlogic.model.JsonLogicLimits;
import org.praxisplatform.rules.plan.RuleDecisionPlan;
import org.praxisplatform.rules.digest.PraxisCanonicalJson;

/** Stateless evaluator for an already validated and deterministically compiled RuleSet plan. */
public final class PraxisRuleSetEngine {
    private static final ObjectMapper JSON = new ObjectMapper();
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
        java.util.Set<String> transformationKeys = new java.util.HashSet<>();
        java.util.Set<String> transformationTargets = new java.util.HashSet<>();
        boolean anyInconclusive = false;
        int aggregatedBytes = 0;
        int aggregatedReasonCodes = 0;
        int aggregatedTransformations = 0;
        for (DecisionBinding binding : plan.orderedBindings()) {
            DecisionSlot slot = plan.slotsByKey().get(binding.slotKey());
            RuleBindingResult bindingResult = intentGateResult(
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
            boolean transformationConflict = bindingResult.transformations().stream().anyMatch(proposal ->
                    !transformationKeys.add(proposal.proposalKey())
                            || !transformationTargets.add(proposal.targetPath()));
            if (transformationConflict) {
                bindingResult = bindingResult(
                        slot,
                        binding,
                        RuleDecision.TECHNICAL_ERROR,
                        List.of("TRANSFORMATION_CONFLICT"),
                        null);
            }
            aggregatedReasonCodes += bindingResult.reasonCodes().size();
            aggregatedTransformations += bindingResult.transformations().size();
            aggregatedBytes += estimatedBytes(bindingResult);
            if (aggregatedReasonCodes > RuleEngineLimits.MAX_AGGREGATED_REASON_CODES
                    || aggregatedTransformations > RuleEngineLimits.MAX_AGGREGATED_TRANSFORMATIONS
                    || aggregatedBytes > RuleEngineLimits.MAX_PUBLIC_RESULT_BYTES) {
                return result(
                        plan,
                        RuleDecision.TECHNICAL_ERROR,
                        List.of(),
                        List.of("EVALUATION_RESULT_LIMIT_EXCEEDED"),
                        factsDigest);
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
        List<String> terminalNotApplicableReasons = plan.orderedBindings().stream()
                .filter(binding -> !dependencyKeys.contains(binding.bindingKey()))
                .map(binding -> resultsByBinding.get(binding.bindingKey()))
                .filter(bindingResult -> bindingResult.decision() == RuleDecision.NOT_APPLICABLE)
                .flatMap(bindingResult -> bindingResult.reasonCodes().stream())
                .toList();
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
                        : terminalAllow ? List.of() : terminalNotApplicableReasons,
                factsDigest);
    }

    private RuleBindingResult intentGateResult(
            DecisionSlot slot,
            DecisionBinding binding,
            boolean anyInconclusive) {
        if (slot.stage() != DecisionStage.EFFECT_INTENT
                && slot.stage() != DecisionStage.TRANSFORMATION_INTENT) {
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
            if (dependency == null) {
                return bindingResult(
                        slot,
                        binding,
                        RuleDecision.TECHNICAL_ERROR,
                        List.of("DEPENDENCY_RESULT_MISSING"),
                        null);
            }
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
        RuleImplementationRef expectedImplementation = plan.implementationRefs().stream()
                .filter(reference -> reference.implementationKey().equals(
                        binding.executor().implementationKey()))
                .findFirst()
                .orElse(null);
        RuleImplementationRef actualImplementation = executorRegistry.implementationRef(
                binding.executor().implementationKey());
        if (expectedImplementation == null || !expectedImplementation.equals(actualImplementation)) {
            return bindingResult(
                    slot,
                    binding,
                    RuleDecision.TECHNICAL_ERROR,
                    List.of("IMPLEMENTATION_TRUST_MISMATCH"),
                    null);
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
            if (!boundedExecutorCollections(executorResult)) {
                return bindingResult(
                        slot,
                        binding,
                        RuleDecision.TECHNICAL_ERROR,
                        List.of("IMPLEMENTATION_RESULT_LIMIT_EXCEEDED"),
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
            if (slot.stage() == DecisionStage.TRANSFORMATION_INTENT
                    && (executorResult.decision() == RuleDecision.DENY
                            || executorResult.decision() == RuleDecision.TECHNICAL_ERROR)) {
                return bindingResult(
                        slot,
                        binding,
                        RuleDecision.TECHNICAL_ERROR,
                        List.of("TRANSFORMATION_DECISION_INVALID"),
                        null);
            }
            if (slot.stage() == DecisionStage.TRANSFORMATION_INTENT
                    && executorResult.decision() != RuleDecision.ALLOW
                    && !executorResult.transformations().isEmpty()) {
                return bindingResult(
                        slot,
                        binding,
                        RuleDecision.TECHNICAL_ERROR,
                        List.of("TRANSFORMATION_DECISION_INVALID"),
                        null);
            }
            if (slot.stage() != DecisionStage.TRANSFORMATION_INTENT
                    && !executorResult.transformations().isEmpty()) {
                return bindingResult(
                        slot,
                        binding,
                        RuleDecision.TECHNICAL_ERROR,
                        List.of("TRANSFORMATION_STAGE_INVALID"),
                        null);
            }
            if (slot.stage() == DecisionStage.TRANSFORMATION_INTENT
                    && executorResult.output() != null) {
                return bindingResult(
                        slot,
                        binding,
                        RuleDecision.TECHNICAL_ERROR,
                        List.of("TRANSFORMATION_OUTPUT_INVALID"),
                        null);
            }
            if (slot.stage() == DecisionStage.TRANSFORMATION_INTENT
                    && executorResult.decision() == RuleDecision.ALLOW
                    && executorResult.transformations().isEmpty()) {
                return bindingResult(
                        slot,
                        binding,
                        RuleDecision.TECHNICAL_ERROR,
                        List.of("TRANSFORMATION_PROPOSAL_REQUIRED"),
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
            List<TypedTransformationProposal> transformations;
            try {
                transformations = enrichTransformations(
                        plan, binding, facts, executorResult.transformations());
            } catch (IllegalArgumentException | PraxisJsonLogicException exception) {
                return bindingResult(
                        slot,
                        binding,
                        RuleDecision.TECHNICAL_ERROR,
                        List.of("TRANSFORMATION_PROPOSAL_INVALID"),
                        null);
            }
            return bindingResult(
                    slot,
                    binding,
                    executorResult.decision(),
                    executorResult.reasonCodes(),
                    executorResult.output(),
                    transformations);
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
        return bindingResult(slot, binding, decision, reasonCodes, output, List.of());
    }

    private RuleBindingResult bindingResult(
            DecisionSlot slot,
            DecisionBinding binding,
            RuleDecision decision,
            List<String> reasonCodes,
            JsonNode output,
            List<TypedTransformationProposal> transformations) {
        return new RuleBindingResult(
                binding.bindingKey(),
                binding.slotKey(),
                slot.stage(),
                decision,
                normalizedReasonCodes(reasonCodes),
                output,
                transformations);
    }

    private List<TypedTransformationProposal> enrichTransformations(
            RuleDecisionPlan plan,
            DecisionBinding binding,
            JsonNode facts,
            List<TransformationDraft> drafts) {
        java.util.Set<String> keys = new java.util.HashSet<>();
        java.util.Set<String> targets = new java.util.HashSet<>();
        List<TypedTransformationProposal> proposals = new ArrayList<>();
        for (TransformationDraft draft : drafts) {
            String targetRoot = draft.targetPath().split("\\.", 2)[0];
            if (!plan.definition().availableRoots().contains(targetRoot)) {
                throw new IllegalArgumentException("Transformation target root is not available to the RuleSet");
            }
            if (!keys.add(draft.proposalKey()) || !targets.add(draft.targetPath())) {
                throw new IllegalArgumentException("Transformation proposal identity or target is duplicated");
            }
            if (draft.before().present()) {
                jsonLogicEngine.validateResultValue(draft.before().value(), JsonLogicLimits.DEFAULT);
            }
            if (draft.after().present()) {
                jsonLogicEngine.validateResultValue(draft.after().value(), JsonLogicLimits.DEFAULT);
            }
            validateBeforeSnapshot(draft, facts);
            proposals.add(new TypedTransformationProposal(
                    draft.proposalKey(),
                    binding.bindingKey(),
                    binding.slotKey(),
                    draft.targetPath(),
                    draft.schemaRef(),
                    draft.operation(),
                    draft.before(),
                    draft.after(),
                    draft.before().digest(),
                    draft.after().digest(),
                    draft.reasonCode()));
        }
        return List.copyOf(proposals);
    }

    private void validateBeforeSnapshot(TransformationDraft draft, JsonNode facts) {
        JsonNode current = facts;
        boolean present = true;
        for (String segment : PraxisPath.parse(draft.targetPath())) {
            if (current == null || !current.isObject() || !current.has(segment)) {
                present = false;
                current = null;
                break;
            }
            current = current.get(segment);
        }
        if (draft.before().present() != present
                || (present && !sameSnapshotValue(draft.before().value(), current))) {
            throw new IllegalArgumentException("Transformation before value does not match the facts snapshot");
        }
    }

    private boolean sameSnapshotValue(JsonNode expected, JsonNode current) {
        if (expected.isNumber() && current.isNumber()) {
            return expected.decimalValue().compareTo(current.decimalValue()) == 0;
        }
        return expected.equals(current);
    }

    private RuleEvaluationResult result(
            RuleDecisionPlan plan,
            RuleDecision decision,
            List<RuleBindingResult> bindingResults,
            List<String> reasonCodes,
            String factsDigest) {
        RuleEvaluationResult candidate = new RuleEvaluationResult(
                decision,
                plan.definition().ref(),
                plan.planDigest(),
                bindingResults,
                normalizedReasonCodes(reasonCodes),
                factsDigest,
                plan.definition().compatibility(),
                implementationRefs(plan),
                plan.definition().failPolicy(),
                decision == RuleDecision.ALLOW
                        ? bindingResults.stream()
                                .flatMap(item -> item.transformations().stream())
                                .toList()
                        : List.of());
        try {
            jsonLogicEngine.validateResultValue(JSON.valueToTree(candidate), JsonLogicLimits.DEFAULT);
            return candidate;
        } catch (PraxisJsonLogicException exception) {
            return new RuleEvaluationResult(
                    RuleDecision.TECHNICAL_ERROR,
                    plan.definition().ref(),
                    plan.planDigest(),
                    List.of(),
                    List.of("EVALUATION_RESULT_LIMIT_EXCEEDED"),
                    factsDigest,
                    plan.definition().compatibility(),
                    implementationRefs(plan),
                    plan.definition().failPolicy(),
                    List.of());
        }
    }

    private boolean boundedExecutorCollections(RuleExecutorResult result) {
        if (result.reasonCodes().size() > RuleEngineLimits.MAX_AGGREGATED_REASON_CODES
                || result.transformations().size() > RuleEngineLimits.MAX_AGGREGATED_TRANSFORMATIONS) {
            return false;
        }
        return result.reasonCodes().stream().allMatch(code -> code != null
                && code.length() <= JsonLogicLimits.DEFAULT.maxStringLength());
    }

    private int estimatedBytes(RuleBindingResult result) {
        return JSON.valueToTree(result).toString().getBytes(StandardCharsets.UTF_8).length;
    }

    private List<RuleImplementationRef> implementationRefs(RuleDecisionPlan plan) {
        return plan.implementationRefs();
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
                int index;
                try {
                    index = Integer.parseInt(segment);
                } catch (NumberFormatException exception) {
                    return false;
                }
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
