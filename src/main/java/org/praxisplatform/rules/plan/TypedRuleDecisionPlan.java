package org.praxisplatform.rules.plan;

import java.util.Objects;
import org.praxisplatform.rules.contract.RuleExecutorRef;
import org.praxisplatform.rules.contract.RuleExecutorType;
import org.praxisplatform.rules.contract.RuleSetDefinition;
import org.praxisplatform.rules.digest.PraxisCanonicalJson;
import org.praxisplatform.rules.facts.RuleFactContract;
import org.praxisplatform.rules.facts.RuleFactContractException;
import org.praxisplatform.rules.runtime.RuleBindingExecutorRegistry;

/** An executable plan admitted against one trusted operation/provider schema. */
public final class TypedRuleDecisionPlan {
    private final RuleDecisionPlan plan;
    private final RuleFactContract contract;
    private final String admissionDigest;

    private TypedRuleDecisionPlan(RuleDecisionPlan plan, RuleFactContract contract) {
        this.plan = plan;
        this.contract = contract;
        this.admissionDigest = PraxisCanonicalJson.sha256Utf8(plan.planDigest() + ":" + contract.digest());
    }

    /**
     * Compiles effective composition, product templates and exact executable Java requirements.
     * @param definition complete candidate RuleSet
     * @param registry executable registry from the consuming host
     * @param contract trusted operation/provider contract from the host registry
     * @return immutable admitted executable candidate
     */
    public static TypedRuleDecisionPlan compile(RuleSetDefinition definition,
            RuleBindingExecutorRegistry registry, RuleFactContract contract) {
        Objects.requireNonNull(contract, "contract");
        RuleDecisionPlan plan = new PraxisRulePlanCompiler(registry).compile(definition);
        contract.validate(plan);
        for (var binding : plan.orderedBindings()) {
            if (binding.executor().type() == RuleExecutorType.JAVA && registry.get(binding.executor().implementationKey()) == null)
                throw new RuleFactContractException(RuleFactContractException.Code.EXECUTOR_CONTRACT_INVALID, "$executor");
        }
        return new TypedRuleDecisionPlan(plan, contract);
    }

    /** @return canonical ordered plan */
    public RuleDecisionPlan plan() { return plan; }
    /** @return trusted fact contract captured during admission */
    public RuleFactContract contract() { return contract; }
    /** @return digest binding effective plan and provider schema coordinates */
    public String admissionDigest() { return admissionDigest; }
}
