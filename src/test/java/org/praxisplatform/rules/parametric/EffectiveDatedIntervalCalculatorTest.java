package org.praxisplatform.rules.parametric;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.praxisplatform.rules.parametric.EffectiveDatedIntervalCalculator.BonusAddition;
import org.praxisplatform.rules.parametric.EffectiveDatedIntervalCalculator.DateInterval;
import org.praxisplatform.rules.parametric.EffectiveDatedIntervalCalculator.InterruptionEffect;
import org.praxisplatform.rules.parametric.EffectiveDatedIntervalCalculator.IntervalEvent;
import org.praxisplatform.rules.parametric.EffectiveDatedIntervalCalculator.TrailStepRole;

class EffectiveDatedIntervalCalculatorTest {

    @Test
    void coalescesOverlappingAndAdjacentSuspensionsWithoutDoubleCountingAndProjectsTargetDate() {
        LocalDate start = LocalDate.of(2025, 1, 1);
        LocalDate ref = LocalDate.of(2025, 12, 31); // 365 gross days

        // Overlapping: May 1..10 (10d) + May 5..15 (11d) => May 1..15 (15d)
        // Adjacent: May 16..20 (5d) => coalesced into single May 1..20 (20d)!
        // Out-of-window future & past events are clipped/ignored safely.
        List<IntervalEvent> events = List.of(
                new IntervalEvent("AF-1", LocalDate.of(2025, 5, 1), LocalDate.of(2025, 5, 10),
                        InterruptionEffect.SUSPEND, "ERG-LIC-PARTICULAR", "Lei 8.112/90, art. 91"),
                new IntervalEvent("AF-2", LocalDate.of(2025, 5, 5), LocalDate.of(2025, 5, 15),
                        InterruptionEffect.SUSPEND, "ERG-FALTA-INJUST", "Lei 8.112/90, art. 44"),
                new IntervalEvent("AF-3", LocalDate.of(2025, 5, 16), LocalDate.of(2025, 5, 20),
                        InterruptionEffect.SUSPEND, "ERG-SUSP-DISC", "Lei 8.112/90, art. 130"),
                new IntervalEvent("AF-PAST", LocalDate.of(2024, 12, 20), LocalDate.of(2024, 12, 31),
                        InterruptionEffect.SUSPEND, "PAST", null),
                new IntervalEvent("AF-FUT", LocalDate.of(2026, 1, 10), LocalDate.of(2026, 1, 15),
                        InterruptionEffect.SUSPEND, "FUTURE", null),
                new IntervalEvent("AF-IGN", LocalDate.of(2025, 8, 1), LocalDate.of(2025, 8, 10),
                        InterruptionEffect.IGNORE, "FERIAS-EFETIVO-EXERCICIO", "Lei 8.112/90, art. 102")
        );

        var result = EffectiveDatedIntervalCalculator.calculate(start, ref, 730L, events);

        assertEquals(365L, result.grossDaysInActiveCycle());
        assertEquals(0L, result.interruptedDaysLost());
        assertEquals(20L, result.suspendedDays());
        assertEquals(345L, result.netDays());
        assertEquals(385L, result.remainingDaysToTarget());
        assertFalse(result.targetReached());
        // 2025-12-31 + 385 days = 365 days (2026) + 20 days (Jan 2027) => 2027-01-20
        assertEquals(LocalDate.of(2027, 1, 20), result.projectedTargetDate());
        assertEquals(List.of(new DateInterval(LocalDate.of(2025, 5, 1), LocalDate.of(2025, 5, 20))),
                result.mergedSuspensions());
        assertEquals(3, result.trailSteps().size());
        assertEquals(TrailStepRole.SUSPENSION_MERGED, result.trailSteps().get(1).role());
        assertEquals(-20L, result.trailSteps().get(1).daysDelta());
        assertEquals(List.of("AF-1", "AF-2", "AF-3"), result.trailSteps().get(1).sourceIds());
    }

    @Test
    void interruptionResetsCycleDiscardsPriorSuspensionsAndFindsExactHistoricalAchievementDate() {
        LocalDate originalStart = LocalDate.of(2023, 1, 1);
        LocalDate ref = LocalDate.of(2026, 6, 30);

        List<IntervalEvent> events = List.of(
                // Pre-reset suspension (must be discarded after reset)
                new IntervalEvent("SUSP-OLD", LocalDate.of(2023, 3, 1), LocalDate.of(2023, 3, 31),
                        InterruptionEffect.SUSPEND, "OLD-SUSP", null),
                // Interruption ending 2023-12-31 => restarts cycle on 2024-01-01 (2024 is leap year: 366 days!)
                new IntervalEvent("INT-1", LocalDate.of(2023, 12, 15), LocalDate.of(2023, 12, 31),
                        InterruptionEffect.INTERRUPT_AND_RESTART, "ERG-INTERRUPCAO-QUINQUENIO", "Estatuto Estadual, art. 118"),
                // Suspension inside new cycle before completion: 2024-05-01..2024-05-10 (10 days)
                new IntervalEvent("SUSP-MID", LocalDate.of(2024, 5, 1), LocalDate.of(2024, 5, 10),
                        InterruptionEffect.SUSPEND, "SUSP-MID", "Art. 91"),
                // Suspension AFTER the 730-day milestone was already achieved (2026-03-01..2026-03-05, 5 days)
                new IntervalEvent("SUSP-POST", LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 5),
                        InterruptionEffect.SUSPEND, "SUSP-POST", "Art. 91")
        );

        var result = EffectiveDatedIntervalCalculator.calculate(originalStart, ref, 730L, events);

        assertEquals(LocalDate.of(2024, 1, 1), result.effectiveStartDate());
        assertEquals(365L, result.interruptedDaysLost());
        assertEquals(15L, result.suspendedDays());
        assertTrue(result.targetReached());
        assertEquals(0L, result.remainingDaysToTarget());
        // Exact historical milestone date:
        // 2024 has 366 gross days minus 10 suspended = 356 net days in 2024.
        // Remaining needed in 2025/2026 = 730 - 356 = 374 days.
        // 2025 has 365 days => 374 - 365 = 9 days in Jan 2026 => 2026-01-09!
        // Notice SUSP-POST in March 2026 does NOT distort the historical achievement date!
        assertEquals(LocalDate.of(2026, 1, 9), result.projectedTargetDate());
    }

    @Test
    void appliesCappedBonusesOpenEndedInterruptionsAndValidatesInvalidInputsFailClosed() {
        LocalDate start = LocalDate.of(2025, 1, 1);
        LocalDate ref = LocalDate.of(2025, 12, 31); // 365 days

        var withBonus = EffectiveDatedIntervalCalculator.calculate(
                start,
                ref,
                730L,
                List.of(),
                List.of(
                        new BonusAddition("AVB-1", 300L, "AVERBACAO-FEDERAL", "Art. 100"),
                        new BonusAddition("AVB-2", 200L, "AVERBACAO-ESTADUAL", "Art. 103")
                ),
                400L // capped at 400 days
        );
        assertEquals(400L, withBonus.bonusDaysApplied());
        assertEquals(765L, withBonus.netDays());
        assertTrue(withBonus.targetReached());
        // Needed active calendar days = 730 - 400 = 330th day from 2025-01-01 => 2025-11-26
        assertEquals(LocalDate.of(2025, 11, 26), withBonus.projectedTargetDate());

        // Ongoing open-ended interruption (`endDate == null`) covering `ref`
        var ongoingReset = EffectiveDatedIntervalCalculator.calculate(
                start,
                ref,
                365L,
                List.of(new IntervalEvent("OPEN-INT", LocalDate.of(2025, 6, 1), null,
                        InterruptionEffect.INTERRUPT_AND_RESTART, "AFASTAMENTO-INTERRUPTIVO", null))
        );
        assertEquals(LocalDate.of(2026, 1, 1), ongoingReset.effectiveStartDate());
        assertEquals(0L, ongoingReset.grossDaysInActiveCycle());
        assertEquals(0L, ongoingReset.netDays());
        assertFalse(ongoingReset.targetReached());
        assertEquals(LocalDate.of(2026, 12, 31), ongoingReset.projectedTargetDate());
        // Cutoff-day reset must keep valid non-inverted TrailStep window [ref, ref]
        var netTrail = ongoingReset.trailSteps().getLast();
        assertEquals(TrailStepRole.NET_RESULT, netTrail.role());
        assertEquals(ref, netTrail.windowStart());
        assertEquals(ref, netTrail.windowEnd());

        assertThrows(IllegalArgumentException.class,
                () -> EffectiveDatedIntervalCalculator.calculate(ref, start.minusDays(1), 365L, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> EffectiveDatedIntervalCalculator.calculate(start, ref, -1L, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new IntervalEvent("BAD", ref, start, InterruptionEffect.SUSPEND, "ERR", null));
        assertThrows(IllegalArgumentException.class,
                () -> new BonusAddition("BAD", -5L, "ERR", null));
    }

    @Test
    void projectsFutureTargetDateAccountingForKnownFiniteSuspensionExtendingBeyondReferenceDate() {
        LocalDate start = LocalDate.of(2026, 1, 1);
        LocalDate ref = LocalDate.of(2026, 6, 30); // 181 gross days

        // Finite unpaid leave spanning 2026-06-21..2026-07-10 (20 days total: 10 days up to ref + 10 days after ref)
        var result = EffectiveDatedIntervalCalculator.calculate(
                start,
                ref,
                365L,
                List.of(new IntervalEvent(
                        "AF-SPAN",
                        LocalDate.of(2026, 6, 21),
                        LocalDate.of(2026, 7, 10),
                        InterruptionEffect.SUSPEND,
                        "LIC-PARTICULAR",
                        "Art. 91"))
        );

        // Accrued up to ref (2026-06-30): only 10 suspended days deducted => 171 net days
        assertEquals(10L, result.suspendedDays());
        assertEquals(171L, result.netDays());
        assertEquals(194L, result.remainingDaysToTarget());
        // Future target completion skips all 20 known suspended days => 2026-01-01 + 365 + 20 - 1 = 2027-01-20
        assertEquals(LocalDate.of(2027, 1, 20), result.projectedTargetDate());
    }
}
