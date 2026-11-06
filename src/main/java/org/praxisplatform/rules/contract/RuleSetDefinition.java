package org.praxisplatform.rules.contract;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.praxisplatform.rules.facts.PraxisFactTreeBuilder;
import org.praxisplatform.rules.jsonlogic.internal.PraxisPath;
import org.praxisplatform.rules.violation.RuleErrorDescriptor;

/**
 * Immutable runtime-neutral RuleSet content compiled by the engine.
 *
 * @param ref stable RuleSet identity
 * @param availableRoots fact roots visible to declarative bindings
 * @param slots stable composition slots
 * @param bindings executable bindings
 * @param compatibility required engine and JSON Logic baseline
 * @param failPolicy host-enforced policy for non-business failures
 * @param errorDescriptors public customer error descriptions keyed by binding identity
 */
public record RuleSetDefinition(
        RuleSetRef ref,
        List<String> availableRoots,
        List<DecisionSlot> slots,
        List<DecisionBinding> bindings,
        RuleRuntimeCompatibility compatibility,
        RuleFailPolicy failPolicy,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<String, RuleErrorDescriptor> errorDescriptors) {

    /** Creates a definition that uses only the host's product error catalog. */
    public RuleSetDefinition(RuleSetRef ref, List<String> availableRoots, List<DecisionSlot> slots,
            List<DecisionBinding> bindings, RuleRuntimeCompatibility compatibility, RuleFailPolicy failPolicy) {
        this(ref, availableRoots, slots, bindings, compatibility, failPolicy, Map.of());
    }

    /** Copies all collections and rejects incomplete definitions. */
    public RuleSetDefinition {
        ref = Objects.requireNonNull(ref, "ref is required");
        availableRoots = normalizedRoots(availableRoots);
        slots = slots == null ? List.of() : List.copyOf(slots);
        bindings = bindings == null ? List.of() : List.copyOf(bindings);
        errorDescriptors = errorDescriptors == null ? Map.of() : Map.copyOf(errorDescriptors);
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

    /**
     * Creates a {@link RuleSetDefinition} whose {@link #availableRoots()} are inferred deterministically
     * from the ASTs and required fact paths of its bindings and parameter templates.
     *
     * @param ref stable RuleSet identity
     * @param slots stable composition slots
     * @param bindings executable bindings
     * @param compatibility required engine and JSON Logic baseline
     * @param failPolicy host-enforced policy for non-business failures
     * @return immutable RuleSet definition with inferred ontological roots
     */
    public static RuleSetDefinition withInferredRoots(
            RuleSetRef ref,
            List<DecisionSlot> slots,
            List<DecisionBinding> bindings,
            RuleRuntimeCompatibility compatibility,
            RuleFailPolicy failPolicy) {
        return new RuleSetDefinition(
                ref,
                inferAvailableRoots(slots, bindings),
                slots,
                bindings,
                compatibility,
                failPolicy);
    }

    private static final Set<String> STRUCTURAL_ROOTS = Set.of(
            PraxisFactTreeBuilder.ROOT_DRAFT,
            PraxisFactTreeBuilder.ROOT_CURRENT,
            PraxisFactTreeBuilder.ROOT_NEIGHBORHOOD,
            PraxisFactTreeBuilder.ROOT_CONTEXT,
            "tenant",
            "facts",
            "derived",
            "scope");

    /**
     * Returns all distinct top-level and dotted fact paths referenced across declarative bindings
     * and parameter templates in this definition, preserving first-seen order.
     *
     * @return immutable list of referenced fact paths (e.g. {@code "draft.inicio"}, {@code "vaga.situacao"})
     */
    public List<String> referencedFactPaths() {
        Map<String, DecisionSlot> slotsByKey = new LinkedHashMap<>();
        for (DecisionSlot slot : slots) {
            slotsByKey.put(slot.slotKey(), slot);
        }
        Set<String> paths = new LinkedHashSet<>();
        for (DecisionBinding binding : bindings) {
            if (!binding.enabled()) {
                continue;
            }
            DecisionSlot slot = slotsByKey.get(binding.slotKey());
            RuleParameterTemplate template = slot != null ? slot.parameterTemplate() : null;
            if (binding.executor() != null && binding.executor().expression() != null) {
                paths.addAll(PraxisFactTreeBuilder.extractReferencedFactPaths(
                        binding.executor().expression()));
            } else if (template != null && template.executor() != null) {
                paths.addAll(PraxisFactTreeBuilder.extractReferencedFactPaths(
                        template.executor().expression()));
            }
            if (!binding.requiredFactPaths().isEmpty()) {
                collectDeclaredDottedPaths(binding.requiredFactPaths(), paths);
            } else if (template != null) {
                collectDeclaredDottedPaths(template.requiredFactPaths(), paths);
            }
        }
        return List.copyOf(paths);
    }

    /**
     * Returns the 1-hop domain concept roots declared in {@link #availableRoots()}, excluding
     * canonical structural/context roots ({@code draft}, {@code current}, {@code neighborhood},
     * {@code context}, {@code tenant}, {@code derived}, {@code scope}) and sibling collection
     * roots ({@code existing*}, {@code active*}).
     *
     * @return immutable list of 1-hop domain concept root names
     */
    public List<String> conceptRoots() {
        return availableRoots.stream()
                .filter(root -> !STRUCTURAL_ROOTS.contains(root)
                        && !root.startsWith("existing")
                        && !root.startsWith("active"))
                .toList();
    }

    /**
     * Returns an immutable dictionary mapping each ontological root in {@link #availableRoots()}
     * to the sorted list of dotted fact paths referenced under that root across enabled bindings
     * and parameter templates.
     *
     * @return immutable map from ontological root to its sorted list of referenced fact paths
     */
    public Map<String, List<String>> factPathsByRoot() {
        Map<String, Set<String>> grouped = new LinkedHashMap<>();
        for (String root : availableRoots) {
            grouped.put(root, new TreeSet<>());
        }
        for (String path : referencedFactPaths()) {
            List<String> segments = PraxisPath.parse(path);
            if (segments.size() > 1) {
                grouped.computeIfAbsent(segments.get(0), ignored -> new TreeSet<>()).add(path);
            }
        }
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : grouped.entrySet()) {
            result.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return Collections.unmodifiableMap(result);
    }

    private static void collectDeclaredDottedPaths(List<String> paths, Set<String> target) {
        if (paths == null) {
            return;
        }
        for (String path : paths) {
            if (path == null || path.isBlank()) {
                continue;
            }
            List<String> segments = PraxisPath.parse(path);
            if (segments.size() > 1) {
                target.add(String.join(".", segments));
            }
        }
    }

    private static List<String> inferAvailableRoots(
            List<DecisionSlot> slots, List<DecisionBinding> bindings) {
        Set<String> roots = new LinkedHashSet<>();
        if (slots != null) {
            for (DecisionSlot slot : slots) {
                if (slot != null && slot.parameterTemplate() != null) {
                    collectDeclaredRoots(slot.parameterTemplate().requiredFactPaths(), roots);
                    if (slot.parameterTemplate().executor() != null) {
                        roots.addAll(PraxisFactTreeBuilder.extractReferencedRoots(
                                slot.parameterTemplate().executor().expression()));
                    }
                }
            }
        }
        if (bindings != null) {
            for (DecisionBinding binding : bindings) {
                if (binding != null) {
                    collectDeclaredRoots(binding.requiredFactPaths(), roots);
                    if (binding.executor() != null && binding.executor().expression() != null) {
                        roots.addAll(PraxisFactTreeBuilder.extractReferencedRoots(
                                binding.executor().expression()));
                    }
                }
            }
        }
        return List.copyOf(roots);
    }

    private static void collectDeclaredRoots(List<String> paths, Set<String> roots) {
        if (paths == null) {
            return;
        }
        for (String path : paths) {
            if (path == null || path.isBlank()) {
                continue;
            }
            List<String> segments = PraxisPath.parse(path);
            if (!segments.isEmpty()) {
                roots.add(segments.get(0));
            }
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
