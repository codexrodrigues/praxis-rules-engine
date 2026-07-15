package org.praxisplatform.rules.runtime;

import org.praxisplatform.rules.contract.RuleExecutorResult;
import org.praxisplatform.rules.contract.RuleExtensionTrust;

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
     * Returns verified trust evidence for a customer-provided Java extension.
     *
     * <p>Built-in product and security executors return {@code null}. A host must construct this
     * value only after verifying the exact artifact against its external allowlist and signature
     * policy.</p>
     *
     * @return immutable trust evidence or {@code null} for built-in code
     */
    default RuleExtensionTrust extensionTrust() {
        return null;
    }

    /**
     * Evaluates frozen facts without performing I/O or side effects.
     * @param context immutable evaluation context
     * @return pure binding result
     */
    RuleExecutorResult evaluate(RuleExecutorContext context);
}
