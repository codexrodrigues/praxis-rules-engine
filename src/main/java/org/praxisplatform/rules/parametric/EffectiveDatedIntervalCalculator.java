package org.praxisplatform.rules.parametric;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Deterministic, domain-neutral temporal interval algebra for statutory interstices, contractual
 * SLAs, and legal prescription windows ({@code PLAT-EVO-08}).
 *
 * <p>Computes gross calendar days, cycle resets caused by {@link InterruptionEffect#INTERRUPT_AND_RESTART}
 * events, exact coalesced deductions caused by {@link InterruptionEffect#SUSPEND} events (eliminating
 * double-counting when two suspension windows overlap), capped or uncapped {@link BonusAddition}s,
 * exact historical or projected target completion dates, and an auditable {@link TrailStep} memory.</p>
 */
public final class EffectiveDatedIntervalCalculator {

    private EffectiveDatedIntervalCalculator() {
    }

    /**
     * Effect of a temporal occurrence over an active counting window.
     */
    public enum InterruptionEffect {
        /** Pauses counting during the inclusive window without discarding prior accrued days. */
        SUSPEND,
        /** Discards accrued days up to the end of the event and restarts counting on the next day. */
        INTERRUPT_AND_RESTART,
        /** Informational occurrence that neither pauses nor resets counting. */
        IGNORE
    }

    /**
     * Role of an explainable step in the calculation audit trail.
     */
    public enum TrailStepRole {
        /** Initial inclusive window from original start to reference date. */
        BASE_WINDOW,
        /** Cycle reset triggered by an interrupting occurrence. */
        INTERRUPTION_RESET,
        /** Coalesced non-overlapping suspension interval deducted from active cycle days. */
        SUSPENSION_MERGED,
        /** Credited bonus/averbacao days applied to net time. */
        BONUS_ADDITION,
        /** Final net synthesis and target attainment status. */
        NET_RESULT
    }

    /**
     * Inclusive calendar date range {@code [startDate, endDate]}.
     *
     * @param startDate inclusive start date
     * @param endDate inclusive end date, never before {@code startDate}
     */
    public record DateInterval(LocalDate startDate, LocalDate endDate) {
        /** Validates inclusive interval bounds. */
        public DateInterval {
            Objects.requireNonNull(startDate, "startDate is required");
            Objects.requireNonNull(endDate, "endDate is required");
            if (endDate.isBefore(startDate)) {
                throw new IllegalArgumentException("endDate must not be before startDate");
            }
        }

        /**
         * Returns the inclusive day count {@code endDate - startDate + 1}.
         *
         * @return positive inclusive day count
         */
        public long inclusiveDays() {
            return ChronoUnit.DAYS.between(startDate, endDate) + 1L;
        }
    }

    /**
     * Occurrence affecting a temporal counting window. An open-ended occurrence ({@code endDate == null})
     * is treated as active through {@code referenceDate}.
     *
     * @param eventId stable identifier of the source occurrence
     * @param startDate inclusive start date of the occurrence
     * @param endDate optional inclusive end date ({@code null} means ongoing through reference date)
     * @param effect normative effect on counting
     * @param reasonCode machine-readable business/rule code
     * @param legalBasis optional statutory or contractual citation
     */
    public record IntervalEvent(
            String eventId,
            LocalDate startDate,
            LocalDate endDate,
            InterruptionEffect effect,
            String reasonCode,
            String legalBasis
    ) {
        /** Validates event bounds and mandatory metadata. */
        public IntervalEvent {
            Objects.requireNonNull(eventId, "eventId is required");
            if (eventId.isBlank()) {
                throw new IllegalArgumentException("eventId must not be blank");
            }
            Objects.requireNonNull(startDate, "startDate is required");
            if (endDate != null && endDate.isBefore(startDate)) {
                throw new IllegalArgumentException("IntervalEvent endDate must not be before startDate: " + eventId);
            }
            Objects.requireNonNull(effect, "effect is required");
        }
    }

    /**
     * Positive day credit added to net accrued days (for example external time averbacao).
     *
     * @param bonusId stable identifier of the credit
     * @param days non-negative number of credited days
     * @param reasonCode machine-readable business/rule code
     * @param legalBasis optional statutory or contractual citation
     */
    public record BonusAddition(
            String bonusId,
            long days,
            String reasonCode,
            String legalBasis
    ) {
        /** Validates non-negative bonus credit. */
        public BonusAddition {
            Objects.requireNonNull(bonusId, "bonusId is required");
            if (bonusId.isBlank()) {
                throw new IllegalArgumentException("bonusId must not be blank");
            }
            if (days < 0L) {
                throw new IllegalArgumentException("BonusAddition days must not be negative: " + bonusId);
            }
        }
    }

    /**
     * Single auditable step of the interval calculation memory.
     *
     * @param role semantic role of this step
     * @param code stable rule or reason code
     * @param windowStart optional start date associated with the step
     * @param windowEnd optional end date associated with the step
     * @param daysDelta signed day contribution ({@code >= 0} for base/bonus/result, {@code <= 0} for deductions)
     * @param summary concise human-readable audit summary
     * @param sourceIds identifiers of contributing events or bonuses
     * @param legalBases deduplicated statutory/contractual bases
     */
    public record TrailStep(
            TrailStepRole role,
            String code,
            LocalDate windowStart,
            LocalDate windowEnd,
            long daysDelta,
            String summary,
            List<String> sourceIds,
            List<String> legalBases
    ) {
        /** Wraps collections immutably. */
        public TrailStep {
            Objects.requireNonNull(role, "role is required");
            Objects.requireNonNull(code, "code is required");
            Objects.requireNonNull(summary, "summary is required");
            if (windowStart != null && windowEnd != null && windowEnd.isBefore(windowStart)) {
                throw new IllegalArgumentException("TrailStep windowEnd must not be before windowStart");
            }
            sourceIds = sourceIds == null ? List.of() : List.copyOf(sourceIds);
            legalBases = legalBases == null ? List.of() : List.copyOf(legalBases);
        }
    }

    /**
     * Immutable outcome of an interval calculation.
     *
     * @param originalStartDate initial anchor date before any cycle reset
     * @param effectiveStartDate active cycle start date after the latest {@link InterruptionEffect#INTERRUPT_AND_RESTART}
     * @param referenceDate inclusive cutoff date of the evaluation
     * @param targetNetDays required net days to attain the milestone ({@code 0} when informational)
     * @param interruptedDaysLost calendar days discarded prior to {@code effectiveStartDate} due to cycle reset
     * @param grossDaysInActiveCycle inclusive calendar days in {@code [effectiveStartDate, referenceDate]}
     * @param suspendedDays coalesced suspended days deducted from {@code grossDaysInActiveCycle}
     * @param bonusDaysApplied credited bonus days after applying {@code maxBonusDays} cap
     * @param netDays final accrued net days ({@code max(0, grossDaysInActiveCycle - suspendedDays) + bonusDaysApplied})
     * @param remainingDaysToTarget remaining net days needed to reach {@code targetNetDays}
     * @param targetReached {@code true} when {@code netDays >= targetNetDays}
     * @param projectedTargetDate exact historical date on which {@code targetNetDays} was reached, or earliest future completion date assuming no new suspensions
     * @param mergedSuspensions disjoint, chronologically ordered suspension intervals applied in the active cycle
     * @param trailSteps ordered explainable audit trail steps
     */
    public record IntervalCalculationResult(
            LocalDate originalStartDate,
            LocalDate effectiveStartDate,
            LocalDate referenceDate,
            long targetNetDays,
            long interruptedDaysLost,
            long grossDaysInActiveCycle,
            long suspendedDays,
            long bonusDaysApplied,
            long netDays,
            long remainingDaysToTarget,
            boolean targetReached,
            LocalDate projectedTargetDate,
            List<DateInterval> mergedSuspensions,
            List<TrailStep> trailSteps
    ) {
        /** Wraps collections immutably. */
        public IntervalCalculationResult {
            Objects.requireNonNull(originalStartDate, "originalStartDate is required");
            Objects.requireNonNull(effectiveStartDate, "effectiveStartDate is required");
            Objects.requireNonNull(referenceDate, "referenceDate is required");
            Objects.requireNonNull(projectedTargetDate, "projectedTargetDate is required");
            mergedSuspensions = List.copyOf(Objects.requireNonNull(mergedSuspensions, "mergedSuspensions is required"));
            trailSteps = List.copyOf(Objects.requireNonNull(trailSteps, "trailSteps is required"));
        }
    }

    private record ClippedSuspension(
            LocalDate start,
            LocalDate end,
            List<String> eventIds,
            List<String> reasonCodes,
            List<String> legalBases
    ) {
    }

    /**
     * Evaluates net days, suspensions, resets, and target attainment without bonus additions.
     *
     * @param originalStartDate inclusive initial counting start date
     * @param referenceDate inclusive cutoff date (must not be before {@code originalStartDate})
     * @param targetNetDays required net days ({@code >= 0})
     * @param events occurrences affecting the window
     * @return immutable calculation result
     */
    public static IntervalCalculationResult calculate(
            LocalDate originalStartDate,
            LocalDate referenceDate,
            long targetNetDays,
            Collection<IntervalEvent> events
    ) {
        return calculate(originalStartDate, referenceDate, targetNetDays, events, List.of(), null);
    }

    /**
     * Evaluates net days, coalesced suspensions, cycle resets, capped bonuses, exact completion
     * date, and explainable calculation trail.
     *
     * @param originalStartDate inclusive initial counting start date
     * @param referenceDate inclusive cutoff date (must not be before {@code originalStartDate})
     * @param targetNetDays required net days ({@code >= 0})
     * @param events occurrences affecting the window
     * @param bonuses optional positive day credits
     * @param maxBonusDays optional maximum total bonus days allowed ({@code null} means uncapped)
     * @return immutable calculation result
     */
    public static IntervalCalculationResult calculate(
            LocalDate originalStartDate,
            LocalDate referenceDate,
            long targetNetDays,
            Collection<IntervalEvent> events,
            Collection<BonusAddition> bonuses,
            Long maxBonusDays
    ) {
        Objects.requireNonNull(originalStartDate, "originalStartDate is required");
        Objects.requireNonNull(referenceDate, "referenceDate is required");
        if (referenceDate.isBefore(originalStartDate)) {
            throw new IllegalArgumentException("referenceDate must not be before originalStartDate");
        }
        if (targetNetDays < 0L) {
            throw new IllegalArgumentException("targetNetDays must not be negative");
        }
        if (maxBonusDays != null && maxBonusDays < 0L) {
            throw new IllegalArgumentException("maxBonusDays must not be negative");
        }

        List<IntervalEvent> safeEvents = events == null ? List.of() : List.copyOf(events);
        List<BonusAddition> safeBonuses = bonuses == null ? List.of() : List.copyOf(bonuses);
        List<TrailStep> trail = new ArrayList<>();

        long initialGrossDays = ChronoUnit.DAYS.between(originalStartDate, referenceDate) + 1L;
        trail.add(new TrailStep(
                TrailStepRole.BASE_WINDOW,
                "BASE_WINDOW",
                originalStartDate,
                referenceDate,
                initialGrossDays,
                "Janela bruta inicial de apuracao",
                List.of(),
                List.of()
        ));

        // 1. Resolve latest INTERRUPT_AND_RESTART overlapping [originalStartDate, referenceDate]
        LocalDate effectiveStartDate = originalStartDate;
        IntervalEvent winningReset = null;
        LocalDate winningResetClippedEnd = null;
        for (IntervalEvent event : safeEvents) {
            Objects.requireNonNull(event, "event must not be null");
            if (event.effect() != InterruptionEffect.INTERRUPT_AND_RESTART) {
                continue;
            }
            if (event.startDate().isAfter(referenceDate)) {
                continue;
            }
            LocalDate effectiveEnd = event.endDate() == null || event.endDate().isAfter(referenceDate)
                    ? referenceDate
                    : event.endDate();
            if (effectiveEnd.isBefore(originalStartDate)) {
                continue;
            }
            LocalDate candidateRestart = effectiveEnd.plusDays(1L);
            if (candidateRestart.isAfter(effectiveStartDate)
                    || (winningReset != null
                            && candidateRestart.equals(effectiveStartDate)
                            && event.eventId().compareTo(winningReset.eventId()) < 0)) {
                effectiveStartDate = candidateRestart;
                winningReset = event;
                winningResetClippedEnd = effectiveEnd;
            }
        }

        long interruptedDaysLost = 0L;
        if (winningReset != null) {
            LocalDate lostEnd = effectiveStartDate.minusDays(1L);
            interruptedDaysLost = ChronoUnit.DAYS.between(originalStartDate, lostEnd) + 1L;
            List<String> resetLegal = winningReset.legalBasis() != null && !winningReset.legalBasis().isBlank()
                    ? List.of(winningReset.legalBasis().trim())
                    : List.of();
            String resetCode = winningReset.reasonCode() != null && !winningReset.reasonCode().isBlank()
                    ? winningReset.reasonCode().trim()
                    : "INTERRUPT_AND_RESTART";
            trail.add(new TrailStep(
                    TrailStepRole.INTERRUPTION_RESET,
                    resetCode,
                    originalStartDate,
                    winningResetClippedEnd,
                    -interruptedDaysLost,
                    "Interrupcao com reinicio de ciclo em " + effectiveStartDate,
                    List.of(winningReset.eventId()),
                    resetLegal
            ));
        }

        long grossDaysInActiveCycle = effectiveStartDate.isAfter(referenceDate)
                ? 0L
                : ChronoUnit.DAYS.between(effectiveStartDate, referenceDate) + 1L;

        // 2. Collect and coalesce SUSPEND intervals clipped to [effectiveStartDate, referenceDate]
        List<ClippedSuspension> rawSuspensions = new ArrayList<>();
        if (grossDaysInActiveCycle > 0L) {
            for (IntervalEvent event : safeEvents) {
                if (event.effect() != InterruptionEffect.SUSPEND) {
                    continue;
                }
                if (event.startDate().isAfter(referenceDate)) {
                    continue;
                }
                LocalDate effectiveEnd = event.endDate() == null || event.endDate().isAfter(referenceDate)
                        ? referenceDate
                        : event.endDate();
                if (effectiveEnd.isBefore(effectiveStartDate)) {
                    continue;
                }
                LocalDate clippedStart = event.startDate().isBefore(effectiveStartDate)
                        ? effectiveStartDate
                        : event.startDate();
                List<String> reasons = event.reasonCode() != null && !event.reasonCode().isBlank()
                        ? List.of(event.reasonCode().trim())
                        : List.of();
                List<String> bases = event.legalBasis() != null && !event.legalBasis().isBlank()
                        ? List.of(event.legalBasis().trim())
                        : List.of();
                rawSuspensions.add(new ClippedSuspension(
                        clippedStart,
                        effectiveEnd,
                        List.of(event.eventId()),
                        reasons,
                        bases
                ));
            }
        }

        rawSuspensions.sort(Comparator.comparing(ClippedSuspension::start)
                .thenComparing(ClippedSuspension::end)
                .thenComparing(s -> s.eventIds().getFirst()));

        List<ClippedSuspension> coalesced = new ArrayList<>();
        for (ClippedSuspension current : rawSuspensions) {
            if (coalesced.isEmpty()) {
                coalesced.add(current);
                continue;
            }
            ClippedSuspension last = coalesced.getLast();
            if (!current.start().isAfter(last.end().plusDays(1L))) {
                LocalDate mergedEnd = current.end().isAfter(last.end()) ? current.end() : last.end();
                Set<String> ids = new LinkedHashSet<>(last.eventIds());
                ids.addAll(current.eventIds());
                Set<String> reasons = new LinkedHashSet<>(last.reasonCodes());
                reasons.addAll(current.reasonCodes());
                Set<String> bases = new LinkedHashSet<>(last.legalBases());
                bases.addAll(current.legalBases());
                coalesced.set(coalesced.size() - 1, new ClippedSuspension(
                        last.start(),
                        mergedEnd,
                        List.copyOf(ids),
                        List.copyOf(reasons),
                        List.copyOf(bases)
                ));
            } else {
                coalesced.add(current);
            }
        }

        List<DateInterval> mergedIntervals = new ArrayList<>(coalesced.size());
        long suspendedDays = 0L;
        for (ClippedSuspension merged : coalesced) {
            DateInterval interval = new DateInterval(merged.start(), merged.end());
            long days = interval.inclusiveDays();
            suspendedDays += days;
            mergedIntervals.add(interval);
            String code = merged.reasonCodes().isEmpty() ? "SUSPEND" : String.join("+", merged.reasonCodes());
            trail.add(new TrailStep(
                    TrailStepRole.SUSPENSION_MERGED,
                    code,
                    merged.start(),
                    merged.end(),
                    -days,
                    "Suspensao consolidada sem dupla contagem (" + days + " dias)",
                    merged.eventIds(),
                    merged.legalBases()
            ));
        }

        // 3. Apply bonus credits ( only when active cycle has started or informational )
        long rawBonusDays = 0L;
        Set<String> bonusIds = new LinkedHashSet<>();
        Set<String> bonusLegalBases = new LinkedHashSet<>();
        for (BonusAddition bonus : safeBonuses) {
            Objects.requireNonNull(bonus, "bonus must not be null");
            if (bonus.days() == 0L) {
                continue;
            }
            rawBonusDays = Math.addExact(rawBonusDays, bonus.days());
            bonusIds.add(bonus.bonusId());
            if (bonus.legalBasis() != null && !bonus.legalBasis().isBlank()) {
                bonusLegalBases.add(bonus.legalBasis().trim());
            }
        }
        long bonusDaysApplied = maxBonusDays != null ? Math.min(rawBonusDays, maxBonusDays) : rawBonusDays;
        if (bonusDaysApplied > 0L) {
            trail.add(new TrailStep(
                    TrailStepRole.BONUS_ADDITION,
                    maxBonusDays != null && rawBonusDays > maxBonusDays ? "BONUS_CAPPED" : "BONUS_ADDITION",
                    null,
                    null,
                    bonusDaysApplied,
                    maxBonusDays != null && rawBonusDays > maxBonusDays
                            ? "Acrescimo bonificado/averbado limitado ao teto de " + maxBonusDays + " dias"
                            : "Acrescimo bonificado/averbado (" + bonusDaysApplied + " dias)",
                    List.copyOf(bonusIds),
                    List.copyOf(bonusLegalBases)
            ));
        }

        long activeCalendarDays = Math.max(0L, grossDaysInActiveCycle - suspendedDays);
        long netDays = Math.addExact(activeCalendarDays, bonusDaysApplied);
        long remainingDaysToTarget = Math.max(0L, targetNetDays - netDays);
        boolean targetReached = netDays >= targetNetDays;

        // 4. Exact O(K) segment-walk for historical/projected target completion date
        List<DateInterval> projectionSuspensions = buildProjectionSuspensions(
                safeEvents, effectiveStartDate, referenceDate);
        LocalDate projectedTargetDate = computeProjectedTargetDate(
                effectiveStartDate,
                targetNetDays,
                bonusDaysApplied,
                projectionSuspensions
        );

        LocalDate netWindowStart = effectiveStartDate.isAfter(referenceDate)
                ? referenceDate
                : effectiveStartDate;
        trail.add(new TrailStep(
                TrailStepRole.NET_RESULT,
                targetReached ? "TARGET_REACHED" : "TARGET_PENDING",
                netWindowStart,
                referenceDate,
                netDays,
                targetReached
                        ? "Tempo liquido apurado: " + netDays + " dias (meta " + targetNetDays + " atingida em " + projectedTargetDate + ")"
                        : "Tempo liquido apurado: " + netDays + " dias (faltam " + remainingDaysToTarget + " dias; projecao " + projectedTargetDate + ")",
                List.of(),
                List.of()
        ));

        return new IntervalCalculationResult(
                originalStartDate,
                effectiveStartDate,
                referenceDate,
                targetNetDays,
                interruptedDaysLost,
                grossDaysInActiveCycle,
                suspendedDays,
                bonusDaysApplied,
                netDays,
                remainingDaysToTarget,
                targetReached,
                projectedTargetDate,
                mergedIntervals,
                trail
        );
    }

    private static List<DateInterval> buildProjectionSuspensions(
            List<IntervalEvent> safeEvents,
            LocalDate effectiveStartDate,
            LocalDate referenceDate
    ) {
        List<DateInterval> raw = new ArrayList<>();
        for (IntervalEvent event : safeEvents) {
            if (event.effect() != InterruptionEffect.SUSPEND
                    || event.startDate().isAfter(referenceDate)) {
                continue;
            }
            LocalDate projEnd = event.endDate() != null ? event.endDate() : referenceDate;
            if (projEnd.isBefore(effectiveStartDate)) {
                continue;
            }
            LocalDate projStart = event.startDate().isBefore(effectiveStartDate)
                    ? effectiveStartDate
                    : event.startDate();
            if (!projEnd.isBefore(projStart)) {
                raw.add(new DateInterval(projStart, projEnd));
            }
        }
        raw.sort(Comparator.comparing(DateInterval::startDate).thenComparing(DateInterval::endDate));
        List<DateInterval> merged = new ArrayList<>();
        for (DateInterval current : raw) {
            if (merged.isEmpty()) {
                merged.add(current);
                continue;
            }
            DateInterval last = merged.getLast();
            if (!current.startDate().isAfter(last.endDate().plusDays(1L))) {
                LocalDate mergedEnd = current.endDate().isAfter(last.endDate())
                        ? current.endDate()
                        : last.endDate();
                merged.set(merged.size() - 1, new DateInterval(last.startDate(), mergedEnd));
            } else {
                merged.add(current);
            }
        }
        return merged;
    }

    private static LocalDate computeProjectedTargetDate(
            LocalDate effectiveStartDate,
            long targetNetDays,
            long bonusDaysApplied,
            List<DateInterval> mergedSuspensions
    ) {
        long neededActiveCalendarDays = targetNetDays - bonusDaysApplied;
        if (neededActiveCalendarDays <= 0L) {
            return effectiveStartDate;
        }
        LocalDate cursor = effectiveStartDate;
        long remaining = neededActiveCalendarDays;
        for (DateInterval suspension : mergedSuspensions) {
            if (cursor.isBefore(suspension.startDate())) {
                long activeSegmentDays = ChronoUnit.DAYS.between(cursor, suspension.startDate());
                if (activeSegmentDays >= remaining) {
                    return cursor.plusDays(remaining - 1L);
                }
                remaining -= activeSegmentDays;
            }
            if (!cursor.isAfter(suspension.endDate())) {
                cursor = suspension.endDate().plusDays(1L);
            }
        }
        return cursor.plusDays(remaining - 1L);
    }
}
