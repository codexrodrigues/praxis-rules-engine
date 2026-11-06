package org.praxisplatform.rules.parametric;

import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import org.praxisplatform.rules.facts.PraxisFactTreeBuilder;

/**
 * Pure selector with ordered specificity dimensions, inclusive validity and explicit tie-breaking.
 * @param <T> host-owned preloaded matrix row
 */
public final class EffectiveDatedMatrixMatcher<T> {
    /**
     * One dimension: a null candidate value is global; a null requested value matches only global.
     * @param <T> host matrix row
     * @param value pure dimension accessor
     * @param requested requested dimension value, or null for global only
     */
    public record Criterion<T>(Function<T, ?> value, Object requested) {
        /** Requires a dimension accessor; the requested value may be global. */
        public Criterion { Objects.requireNonNull(value, "value"); }
    }
    private final Function<T, LocalDate> start;
    private final Function<T, LocalDate> end;
    private final List<Criterion<T>> criteria;
    private final Comparator<T> tieBreaker;

    /**
     * Defines the matrix selection order without inspecting host property names.
     * @param start inclusive start, null means unbounded
     *
     * @param end inclusive end, null means unbounded
     *
     * @param criteria dimensions from highest to lowest priority
     *
     * @param tieBreaker stable final comparator (for example immutable row identity); equal best
     *        candidates are rejected rather than selecting by input order
     */
    public EffectiveDatedMatrixMatcher(Function<T, LocalDate> start, Function<T, LocalDate> end,
            List<Criterion<T>> criteria, Comparator<T> tieBreaker) {
        this.start = Objects.requireNonNull(start, "start");
        this.end = Objects.requireNonNull(end, "end");
        this.criteria = List.copyOf(criteria);
        this.tieBreaker = Objects.requireNonNull(tieBreaker, "tieBreaker");
    }

    /**
     * Selects the best eligible row, rejecting an unresolved tie.
     * @param candidates host-preloaded eligible rows
     * @param reference required reference date
     * @return best matching row
     */
    public Optional<T> match(Collection<T> candidates, LocalDate reference) {
        Objects.requireNonNull(reference, "reference");
        List<T> matches = Objects.requireNonNull(candidates, "candidates").stream()
                .map(candidate -> Objects.requireNonNull(candidate, "candidate"))
                .filter(candidate -> PraxisFactTreeBuilder.isWithinDateRange(reference, start.apply(candidate), end.apply(candidate)))
                .filter(this::matchesScope).sorted(this::compare).toList();
        if (matches.size() > 1 && compare(matches.get(0), matches.get(1)) == 0)
            throw new IllegalArgumentException("Ambiguous effective matrix candidates");
        return matches.stream().findFirst();
    }

    private boolean matchesScope(T candidate) {
        return criteria.stream().allMatch(criterion -> criterion.value().apply(candidate) == null
                || Objects.equals(criterion.value().apply(candidate), criterion.requested()));
    }

    private int compare(T left, T right) {
        for (Criterion<T> criterion : criteria) {
            int compared = Boolean.compare(criterion.value().apply(right) != null, criterion.value().apply(left) != null);
            if (compared != 0) return compared;
        }
        int compared = Comparator.nullsLast(Comparator.<LocalDate>reverseOrder()).compare(start.apply(left), start.apply(right));
        return compared != 0 ? compared : tieBreaker.compare(left, right);
    }
}
