package org.praxisplatform.rules.runtime;

import org.praxisplatform.rules.contract.RuleExecutorResult;

/** Trusted, pure, namespaced Java implementation supplied by a domain host. */
public interface RuleBindingExecutor {
    /**
     * Returns the stable namespaced key referenced by published bindings.
     * @return namespaced implementation key
     */
    String implementationKey();

    /**
     * Returns the exact implementation version required by a binding reference.
     * @return exact implementation version
     */
    String implementationVersion();

    /**
     * Evaluates frozen facts without performing I/O or side effects.
     * @param context immutable evaluation context
     * @return pure binding result
     */
    RuleExecutorResult evaluate(RuleExecutorContext context);
}
