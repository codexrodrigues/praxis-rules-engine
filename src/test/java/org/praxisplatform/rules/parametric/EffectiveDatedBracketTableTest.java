package org.praxisplatform.rules.parametric;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.praxisplatform.rules.parametric.EffectiveDatedBracketTable.BracketScheduleEpoch;
import org.praxisplatform.rules.parametric.ProgressiveBracketAndCapCalculator.BracketMode;
import org.praxisplatform.rules.parametric.ProgressiveBracketAndCapCalculator.ProgressiveBracket;

class EffectiveDatedBracketTableTest {

    private static EffectiveDatedBracketTable sampleRppsAndRpcTable() {
        return EffectiveDatedBracketTable.of(
                "BR.RPPS.EC103",
                "Tabela Progressiva RPPS EC 103/2019 com Teto RGPS/RPC",
                List.of(
                        new BracketScheduleEpoch(
                                "RPPS-2024",
                                LocalDate.of(2024, 1, 1),
                                LocalDate.of(2024, 12, 31),
                                BracketMode.MARGINAL_SLICES,
                                new BigDecimal("7786.02"),
                                BigDecimal.ZERO,
                                List.of(
                                        new ProgressiveBracket(
                                                "F1",
                                                new BigDecimal("0.00"),
                                                new BigDecimal("1412.00"),
                                                new BigDecimal("7.50"),
                                                BigDecimal.ZERO,
                                                "RPPS-2024-F1",
                                                "Portaria MPS/MF 2/2024"),
                                        new ProgressiveBracket(
                                                "F2",
                                                new BigDecimal("1412.01"),
                                                null,
                                                new BigDecimal("14.00"),
                                                BigDecimal.ZERO,
                                                "RPPS-2024-F2",
                                                "Portaria MPS/MF 2/2024")),
                                "EC103-RPPS-2024",
                                "CF/88, art. 40, § 14; Portaria MPS/MF 2/2024",
                                "DOU 12/01/2024"),
                        new BracketScheduleEpoch(
                                "RPPS-2025-ATUAL",
                                LocalDate.of(2025, 1, 1),
                                null,
                                BracketMode.MARGINAL_SLICES,
                                new BigDecimal("8157.41"),
                                BigDecimal.ZERO,
                                List.of(
                                        new ProgressiveBracket(
                                                "F1",
                                                new BigDecimal("0.00"),
                                                new BigDecimal("1518.00"),
                                                new BigDecimal("7.50"),
                                                BigDecimal.ZERO,
                                                "RPPS-2025-F1",
                                                "Portaria MPS/MF 6/2025"),
                                        new ProgressiveBracket(
                                                "F2",
                                                new BigDecimal("1518.01"),
                                                null,
                                                new BigDecimal("14.00"),
                                                BigDecimal.ZERO,
                                                "RPPS-2025-F2",
                                                "Portaria MPS/MF 6/2025")),
                                "EC103-RPPS-2025",
                                "CF/88, art. 40, § 14; Portaria MPS/MF 6/2025",
                                "DOU 13/01/2025")));
    }

    @Test
    void resolvesRetroactiveVsCurrentCompetenceEpochsWithRpcCeiling() {
        EffectiveDatedBracketTable table = sampleRppsAndRpcTable();

        // Retroactive payroll recalculation for competence 2024-10 (RGPS cap = 7,786.02)
        var retro2024 =
                table.calculateProgressiveAsOf(
                        LocalDate.of(2024, 10, 1),
                        new BigDecimal("25000.00"),
                        BigDecimal.ZERO,
                        0,
                        true);
        assertEquals("RPPS-2024", retro2024.activeEpoch().epochId());
        assertTrue(retro2024.calculation().ceilingCapApplied());
        assertEquals(new BigDecimal("7786.02"), retro2024.calculation().effectiveBaseAmount());
        // 1412 * 7.5% = 105.90; (7786.02 - 1412.00) = 6374.02 * 14% = 892.3628 -> 892.36; total = 998.26
        assertEquals(new BigDecimal("998.26"), retro2024.calculation().calculatedAmount());

        // Current payroll competence 2026-10 (RGPS cap = 8,157.41)
        var current2026 =
                table.calculateProgressiveAsOf(
                        LocalDate.of(2026, 10, 1),
                        new BigDecimal("25000.00"),
                        BigDecimal.ZERO,
                        0,
                        true);
        assertEquals("RPPS-2025-ATUAL", current2026.activeEpoch().epochId());
        assertEquals(new BigDecimal("8157.41"), current2026.calculation().effectiveBaseAmount());
        // 1518 * 7.5% = 113.85; (8157.41 - 1518.00) = 6639.41 * 14% = 929.5174 -> 929.52; total = 1043.37
        assertEquals(new BigDecimal("1043.37"), current2026.calculation().calculatedAmount());
    }

    @Test
    void evaluatesEffectiveDatedConstitutionalCeilingsAcrossStatutoryChanges() {
        EffectiveDatedBracketTable stfCapTable =
                EffectiveDatedBracketTable.of(
                        "BR.TETO.STF_100",
                        "Teto Constitucional Nacional — Subsídio Ministro do STF (CF/88, art. 37, XI)",
                        List.of(
                                new BracketScheduleEpoch(
                                        "STF-2024",
                                        LocalDate.of(2024, 2, 1),
                                        LocalDate.of(2025, 1, 31),
                                        null,
                                        new BigDecimal("44008.52"),
                                        BigDecimal.ZERO,
                                        List.of(),
                                        "CF88-TETO-STF-2024",
                                        "Lei 14.520/2023, art. 1º, II",
                                        "DOU 10/01/2023"),
                                new BracketScheduleEpoch(
                                        "STF-2025-ATUAL",
                                        LocalDate.of(2025, 2, 1),
                                        null,
                                        null,
                                        new BigDecimal("46366.19"),
                                        BigDecimal.ZERO,
                                        List.of(),
                                        "CF88-TETO-STF-2025",
                                        "Lei 14.520/2023, art. 1º, III",
                                        "DOU 10/01/2023")));

        var cap2024 =
                stfCapTable.calculateCapAbatementAsOf(
                        LocalDate.of(2024, 11, 1),
                        new BigDecimal("48000.00"),
                        new BigDecimal("2500.00"));
        assertEquals("STF-2024", cap2024.activeEpoch().epochId());
        assertEquals(new BigDecimal("3991.48"), cap2024.abatement().abatementAmount());

        var cap2026 =
                stfCapTable.calculateCapAbatementAsOf(
                        LocalDate.of(2026, 10, 1),
                        new BigDecimal("48000.00"),
                        new BigDecimal("2500.00"));
        assertEquals("STF-2025-ATUAL", cap2026.activeEpoch().epochId());
        assertEquals(new BigDecimal("1633.81"), cap2026.abatement().abatementAmount());
    }

    @Test
    void rejectsOverlappingEpochsNonFinalOpenEpochAndUnpublishedDatesFailClosed() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        EffectiveDatedBracketTable.of(
                                "INVALID.OVERLAP",
                                "Overlap test",
                                List.of(
                                        new BracketScheduleEpoch(
                                                "E1",
                                                LocalDate.of(2024, 1, 1),
                                                LocalDate.of(2024, 6, 30),
                                                null,
                                                new BigDecimal("10000.00"),
                                                BigDecimal.ZERO,
                                                List.of(),
                                                "R1",
                                                "Lei 1",
                                                null),
                                        new BracketScheduleEpoch(
                                                "E2",
                                                LocalDate.of(2024, 6, 30),
                                                null,
                                                null,
                                                new BigDecimal("12000.00"),
                                                BigDecimal.ZERO,
                                                List.of(),
                                                "R2",
                                                "Lei 2",
                                                null))));

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        EffectiveDatedBracketTable.of(
                                "INVALID.OPEN",
                                "Open non-final test",
                                List.of(
                                        new BracketScheduleEpoch(
                                                "E1",
                                                LocalDate.of(2024, 1, 1),
                                                null,
                                                null,
                                                new BigDecimal("10000.00"),
                                                BigDecimal.ZERO,
                                                List.of(),
                                                "R1",
                                                "Lei 1",
                                                null),
                                        new BracketScheduleEpoch(
                                                "E2",
                                                LocalDate.of(2025, 1, 1),
                                                null,
                                                null,
                                                new BigDecimal("12000.00"),
                                                BigDecimal.ZERO,
                                                List.of(),
                                                "R2",
                                                "Lei 2",
                                                null))));

        EffectiveDatedBracketTable valid = sampleRppsAndRpcTable();
        assertThrows(
                IllegalArgumentException.class,
                () -> valid.requireEpochAsOf(LocalDate.of(2023, 12, 31)));
    }
}
