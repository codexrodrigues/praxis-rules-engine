package org.praxisplatform.rules.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.praxisplatform.rules.contract.*;
import org.praxisplatform.rules.plan.*;
import org.praxisplatform.rules.violation.*;

/**
 * Host-owned convenience facade over the pure compiler/evaluator. The instance owns only a local
 * plan cache, never a tenant store, publication head, clock, facts or evaluation results.
 */
public final class GovernedRuleEngineTemplate {
    private record CacheKey(String ruleSetKey, String scopeKey) {
        private CacheKey {
            if (ruleSetKey == null || ruleSetKey.isBlank() || scopeKey == null || scopeKey.isBlank())
                throw new IllegalArgumentException("ruleSetKey and scopeKey are required");
        }
    }
    private final ConcurrentMap<CacheKey, RuleDecisionPlan> plans = new ConcurrentHashMap<>();
    private final PraxisRulePlanCompiler compiler;
    private final PraxisRuleSetEngine engine;
    private final RuleErrorCatalog catalog;

    /**
     * Creates an isolated facade using the host's immutable executable registry and catalog.
     * @param registry executable host registry
     * @param catalog public-safe error catalog
     */
    public GovernedRuleEngineTemplate(RuleBindingExecutorRegistry registry, RuleErrorCatalog catalog) {
        compiler = new PraxisRulePlanCompiler(Objects.requireNonNull(registry, "registry"));
        engine = new PraxisRuleSetEngine(registry);
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    /**
     * Reuses a plan only for identical immutable content in the exact host scope. A replacement is
     * compiled atomically before caching; invalid content throws and is never served as old content.
     * The host must invalidate retired scopes and enforce its governed activation order.
     *
     * @param definition already admitted host snapshot
     *
     * @param scopeKey explicit host scope, including any domain/operation isolation needed
     *
     * @return compiled plan
     */
    public RuleDecisionPlan compile(RuleSetDefinition definition, String scopeKey) {
        Objects.requireNonNull(definition, "definition");
        return plans.compute(new CacheKey(definition.ref().ruleSetKey(), scopeKey), (key, previous) ->
                previous != null && previous.definition().equals(definition) ? previous : compiler.compile(definition));
    }

    /**
     * Retires one host scope's cached plan.
     * @param ruleSetKey stable identity
     * @param scopeKey exact scope
     */
    public void invalidate(String ruleSetKey, String scopeKey) { plans.remove(new CacheKey(ruleSetKey, scopeKey)); }
    /**
     * Clears plans when the host retires this runtime's admitted snapshots.
     */
    public void clear() { plans.clear(); }

    /**
     * Evaluates once using the canonical runtime and caller-frozen temporal context.
     * @param plan compiled plan
     * @param facts frozen facts
     * @param nowUtc explicit instant
     * @param userTimeZone explicit zone
     * @return five-state result
     */
    public RuleEvaluationResult evaluate(RuleDecisionPlan plan, JsonNode facts, String nowUtc, String userTimeZone) {
        return engine.evaluate(plan, facts, nowUtc, userTimeZone);
    }

    /**
     * Requires an affirmative ALLOW before the host may continue with its operation.
     * @param plan compiled plan
     * @param facts frozen facts
     * @param nowUtc explicit instant
     * @param userTimeZone explicit zone
     * @return ALLOW result
     * @throws PraxisRuleViolationException for every other outcome
     */
    public RuleEvaluationResult enforce(RuleDecisionPlan plan, JsonNode facts, String nowUtc, String userTimeZone) {
        RuleEvaluationResult result = evaluate(plan, facts, nowUtc, userTimeZone);
        if (result.decision() != RuleDecision.ALLOW) throw new PraxisRuleViolationException(plan, result, catalog);
        return result;
    }

    /**
     * Projects diagnostics without exposing facts, outputs or executor failures.
     * @param plan evaluated plan
     * @param result exact evaluation
     * @return safe diagnostic projection without facts or outputs
     */
    public Map<String, Object> toDiagnosticPayload(RuleDecisionPlan plan, RuleEvaluationResult result) {
        PraxisRuleViolationException.requireMatchingResult(plan, result);
        if (result.decision() != RuleDecision.ALLOW)
            return new PraxisRuleViolationException(plan, result, catalog).toDiagnosticProperties();
        return Map.of("decision", result.decision().name(), "reasonCodes", result.reasonCodes(),
                "slotKeys", java.util.List.of(), "decisionSources", java.util.List.of(), "violations", java.util.List.of());
    }
}
