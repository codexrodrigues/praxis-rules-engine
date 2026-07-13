package org.praxisplatform.rules.contract;

import java.util.List;
import java.util.Objects;

/**
 * Immutable runtime-neutral RuleSet content compiled by the engine.
 *
 * @param ref stable RuleSet identity
 * @param availableRoots fact roots visible to declarative bindings
 * @param slots stable composition slots
 * @param bindings executable bindings
 * @param compatibility required engine and JSON Logic baseline
 * @param failPolicy host-enforced policy for non-business failures
 */
public record RuleSetDefinition(
        RuleSetRef ref,
        List<String> availableRoots,
        List<DecisionSlot> slots,
        List<DecisionBinding> bindings,
        RuleRuntimeCompatibility compatibility,
        RuleFailPolicy failPolicy) {

    /** Copies all collections and rejects incomplete definitions. */
    public RuleSetDefinition {
        ref = Objects.requireNonNull(ref, "ref is required");
        availableRoots = normalizedRoots(availableRoots);
        slots = slots == null ? List.of() : List.copyOf(slots);
        bindings = bindings == null ? List.of() : List.copyOf(bindings);
        compatibility = Objects.requireNonNull(compatibility, "compatibility is required");
        failPolicy = Objects.requireNonNull(failPolicy, "failPolicy is required");
        if (availableRoots.isEmpty()) {
            throw new IllegalArgumentException("availableRoots must not be empty");
        }
        if (slots.isEmpty()) {
            throw new IllegalArgumentException("slots must not be empty");
        }
        if (bindings.isEmpty()) {
            throw new IllegalArgumentException("bindings must not be empty");
        }
    }

    private static List<String> normalizedRoots(List<String> roots) {
        if (roots == null) {
            return List.of();
        }
        List<String> normalized = roots.stream()
                .map(root -> {
                    Objects.requireNonNull(root, "availableRoots entry is required");
                    if (root.isBlank()) {
                        throw new IllegalArgumentException("availableRoots must not contain blanks");
                    }
                    return root.trim();
                })
                .distinct()
                .sorted()
                .toList();
        if (normalized.size() != roots.size()) {
            throw new IllegalArgumentException("availableRoots must not contain duplicates");
        }
        return normalized;
    }
}
