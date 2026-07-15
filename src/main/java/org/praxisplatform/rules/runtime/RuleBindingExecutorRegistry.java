package org.praxisplatform.rules.runtime;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.praxisplatform.rules.contract.RuleImplementationRef;

/** Immutable registry of trusted Java implementation coordinates and optional executors. */
public final class RuleBindingExecutorRegistry {
    private final Map<String, RuleImplementationRef> implementations;
    private final Map<String, RuleBindingExecutor> executors;

    /**
     * Builds a registry while rejecting blank, unnamespaced, or duplicate keys.
     * @param executors trusted pure implementations
     */
    public RuleBindingExecutorRegistry(Collection<? extends RuleBindingExecutor> executors) {
        Map<String, RuleBindingExecutor> indexed = new LinkedHashMap<>();
        for (RuleBindingExecutor executor : executors == null ? java.util.List.<RuleBindingExecutor>of() : executors) {
            Objects.requireNonNull(executor, "executor must not be null");
            String key = executor.implementationKey();
            if (key == null || key.isBlank() || !key.contains(":")) {
                throw new IllegalArgumentException("Java executor keys must be namespaced");
            }
            if (executor.implementationVersion() == null || executor.implementationVersion().isBlank()) {
                throw new IllegalArgumentException("Java executor versions must not be blank");
            }
            if (indexed.putIfAbsent(key.trim(), executor) != null) {
                throw new IllegalArgumentException("Duplicate Java executor key: " + key);
            }
        }
        Map<String, RuleImplementationRef> coordinates = new LinkedHashMap<>();
        indexed.forEach((key, executor) -> coordinates.put(
                key, new RuleImplementationRef(
                        key, executor.implementationVersion(), executor.extensionTrust())));
        this.implementations = Map.copyOf(coordinates);
        this.executors = Map.copyOf(indexed);
    }

    private RuleBindingExecutorRegistry(Map<String, RuleImplementationRef> implementations) {
        this.implementations = Map.copyOf(implementations);
        this.executors = Map.of();
    }

    /**
     * Creates an empty registry for JSON Logic-only RuleSets.
     * @return immutable empty registry
     */
    public static RuleBindingExecutorRegistry empty() {
        return new RuleBindingExecutorRegistry(java.util.List.of());
    }

    /**
     * Creates a planning-only registry from published implementation coordinates.
     *
     * <p>This registry can validate a plan in a control plane but intentionally cannot execute
     * Java bindings. Domain hosts must build an executable registry from trusted implementations.</p>
     *
     * @param declarations exact Java coordinates declared by the published RuleSet
     * @return immutable non-executable registry for structural planning
     */
    public static RuleBindingExecutorRegistry planning(Collection<RuleImplementationRef> declarations) {
        Map<String, RuleImplementationRef> indexed = new LinkedHashMap<>();
        for (RuleImplementationRef declaration
                : declarations == null ? java.util.List.<RuleImplementationRef>of() : declarations) {
            Objects.requireNonNull(declaration, "implementation declaration must not be null");
            if (indexed.putIfAbsent(declaration.implementationKey(), declaration) != null) {
                throw new IllegalArgumentException(
                        "Duplicate Java implementation declaration: " + declaration.implementationKey());
            }
        }
        return new RuleBindingExecutorRegistry(indexed);
    }

    /**
     * Tests whether an implementation is present.
     * @param implementationKey namespaced key
     * @return whether the key is registered
     */
    public boolean contains(String implementationKey) {
        return implementations.containsKey(implementationKey);
    }

    /**
     * Tests exact key and version compatibility.
     * @param implementationKey namespaced key
     * @param implementationVersion exact required version
     * @return whether an exact compatible implementation is registered
     */
    public boolean isCompatible(String implementationKey, String implementationVersion) {
        RuleImplementationRef implementation = implementations.get(implementationKey);
        return implementation != null && implementation.implementationVersion().equals(implementationVersion);
    }

    /**
     * Tests whether an exact implementation carries verified customer-extension evidence.
     * @param implementationKey namespaced key
     * @param implementationVersion exact required version
     * @return whether the exact coordinate is externally attested
     */
    public boolean isTrustedExtension(String implementationKey, String implementationVersion) {
        RuleImplementationRef implementation = implementations.get(implementationKey);
        return implementation != null
                && implementation.implementationVersion().equals(implementationVersion)
                && implementation.extensionTrust() != null;
    }

    /**
     * Returns the immutable registered coordinate and its optional trust evidence.
     * @param implementationKey namespaced key
     * @return registered coordinate or {@code null}
     */
    public RuleImplementationRef implementationRef(String implementationKey) {
        return implementations.get(implementationKey);
    }

    /**
     * Resolves an implementation by exact key, returning {@code null} when absent.
     * @param implementationKey namespaced key
     * @return registered implementation or {@code null}
     */
    public RuleBindingExecutor get(String implementationKey) {
        return executors.get(implementationKey);
    }
}
