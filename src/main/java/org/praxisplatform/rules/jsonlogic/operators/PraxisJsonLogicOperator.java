package org.praxisplatform.rules.jsonlogic.operators;

import java.util.List;
import org.praxisplatform.rules.jsonlogic.PraxisJsonLogicEngine;
import org.praxisplatform.rules.jsonlogic.model.JsonLogicEvaluationContext;

/** Trusted host or Praxis operator extension evaluated by the deterministic engine. */
public interface PraxisJsonLogicOperator {
    /**
     * Returns the contract published for discovery and arity validation.
     * @return stable descriptor published by the registry
     */
    JsonLogicOperatorDescriptor descriptor();
    /**
     * Returns the name used in JSON Logic expression objects.
     * @return canonical operator name
     */
    default String operator() { return descriptor().operator(); }
    /**
     * Returns the lower arity boundary.
     * @return minimum accepted argument count
     */
    default int minArgs() { return descriptor().minArgs(); }
    /**
     * Returns the upper arity boundary.
     * @return maximum accepted argument count, or {@code null} when unbounded
     */
    default Integer maxArgs() { return descriptor().maxArgs(); }
    /**
     * Evaluates already-resolved arguments.
     * @param args evaluated arguments
     * @param context frozen evaluation context
     * @param engine engine helpers shared with native semantics
     * @return internal runtime value
     */
    Object evaluate(List<Object> args, JsonLogicEvaluationContext context, PraxisJsonLogicEngine engine);
}
