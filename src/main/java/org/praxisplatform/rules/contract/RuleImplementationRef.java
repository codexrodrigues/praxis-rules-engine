package org.praxisplatform.rules.contract;

/**
 * Exact trusted Java implementation coordinate used by an evaluated plan.
 * @param implementationKey stable namespaced registry key
 * @param implementationVersion exact implementation version
 * @param extensionTrust verified customer-extension evidence, or {@code null} for built-in code
 */
public record RuleImplementationRef(
        String implementationKey,
        String implementationVersion,
        RuleExtensionTrust extensionTrust) {
    /** Validates a namespaced key and exact nonblank version. */
    public RuleImplementationRef {
        if (implementationKey == null || implementationKey.isBlank() || !implementationKey.contains(":")) {
            throw new IllegalArgumentException("implementationKey must be namespaced");
        }
        if (implementationVersion == null || implementationVersion.isBlank()) {
            throw new IllegalArgumentException("implementationVersion must not be blank");
        }
        implementationKey = implementationKey.trim();
        implementationVersion = implementationVersion.trim();
    }

    /**
     * Creates a built-in implementation coordinate without customer-extension evidence.
     * @param implementationKey stable namespaced registry key
     * @param implementationVersion exact implementation version
     */
    public RuleImplementationRef(String implementationKey, String implementationVersion) {
        this(implementationKey, implementationVersion, null);
    }
}
