package org.praxisplatform.rules.runtime;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Immutable registry of trusted Java binding implementations. */
public final class RuleBindingExecutorRegistry {
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
        this.executors = Map.copyOf(indexed);
    }

    /**
     * Creates an empty registry for JSON Logic-only RuleSets.
     * @return immutable empty registry
     */
    public static RuleBindingExecutorRegistry empty() {
        return new RuleBindingExecutorRegistry(java.util.List.of());
    }

    /**
     * Tests whether an implementation is present.
     * @param implementationKey namespaced key
     * @return whether the key is registered
     */
    public boolean contains(String implementationKey) {
        return executors.containsKey(implementationKey);
    }

    /**
     * Tests exact key and version compatibility.
     * @param implementationKey namespaced key
     * @param implementationVersion exact required version
     * @return whether an exact compatible implementation is registered
     */
    public boolean isCompatible(String implementationKey, String implementationVersion) {
        RuleBindingExecutor executor = executors.get(implementationKey);
        return executor != null && executor.implementationVersion().equals(implementationVersion);
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
