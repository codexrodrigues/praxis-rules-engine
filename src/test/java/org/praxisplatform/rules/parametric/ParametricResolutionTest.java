package org.praxisplatform.rules.parametric;

import static org.junit.jupiter.api.Assertions.*;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;

class ParametricResolutionTest {
    record Row(int id, Integer company, String category, LocalDate start, LocalDate end) { }
    private final LocalDate reference = LocalDate.of(2026, 11, 1);
    private EffectiveDatedMatrixMatcher<Row> matcher(Integer company) {
        return new EffectiveDatedMatrixMatcher<>(Row::start, Row::end, List.of(
                new EffectiveDatedMatrixMatcher.Criterion<>(Row::company, company),
                new EffectiveDatedMatrixMatcher.Criterion<>(Row::category, "A")), Comparator.comparingInt(Row::id));
    }

    @Test void specificityIsLexicographicThenLatestStartThenStableIdentity() {
        var global = new Row(1, null, "A", reference, null);
        var specific = new Row(2, 7, null, reference.minusYears(2), null);
        var wrongScope = new Row(3, 8, "A", reference, null);
        assertEquals(specific, matcher(7).match(List.of(global, specific, wrongScope), reference).orElseThrow());
        var older = new Row(4, 7, "A", reference.minusDays(1), null);
        var newer = new Row(6, 7, "A", reference, reference);
        var tied = new Row(5, 7, "A", reference, reference);
        var rows = new ArrayList<>(List.of(global, older, specific, newer, tied));
        assertEquals(tied, matcher(7).match(rows, reference).orElseThrow());
        Collections.reverse(rows);
        assertEquals(tied, matcher(7).match(rows, reference).orElseThrow());
        assertEquals(global, matcher(null).match(rows, reference).orElseThrow());
    }

    @Test void filtersValidityAndRejectsAmbiguityInvalidRangesAndMissingReference() {
        var future = new Row(1, null, null, reference.plusDays(1), null);
        var past = new Row(2, null, null, null, reference.minusDays(1));
        assertTrue(matcher(7).match(List.of(future, past), reference).isEmpty());
        var row = new Row(3, null, null, null, null);
        assertEquals(row, matcher(7).match(List.of(row), reference).orElseThrow());
        assertThrows(IllegalArgumentException.class, () -> matcher(7).match(List.of(row, row), reference));
        assertThrows(IllegalArgumentException.class, () -> matcher(7).match(List.of(new Row(4, null, null, reference, reference.minusDays(1))), reference));
        assertThrows(NullPointerException.class, () -> matcher(7).match(List.of(row), null));
    }

    @Test void triStateAndNumericOverridesPreserveExplicitFalseZeroAndAbsence() {
        assertEquals(Optional.of(false), TriStateOverrideResolver.resolve("N", "S"));
        assertEquals(Optional.of(true), TriStateOverrideResolver.resolve("HERDAR", "PERMITIR"));
        assertEquals(Optional.of(false), TriStateOverrideResolver.resolve(null, "VEDAR"));
        assertEquals(Optional.empty(), TriStateOverrideResolver.resolve("I", null));
        assertEquals(Optional.of(0), TriStateOverrideResolver.resolveNumber(0, 10));
        assertEquals(Optional.of(10), TriStateOverrideResolver.resolveNumber(null, 10));
        assertEquals(Optional.empty(), TriStateOverrideResolver.resolveNumber(null, null));
        assertThrows(IllegalArgumentException.class, () -> TriStateOverrideResolver.resolve("unknown", "S"));
        assertThrows(IllegalArgumentException.class, () -> TriStateOverrideResolver.resolve("S", "unknown"));
    }
}
