package org.praxisplatform.rules.parametric;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.praxisplatform.rules.parametric.ProgressiveBracketAndCapCalculator.BracketMode;
import org.praxisplatform.rules.parametric.ProgressiveBracketAndCapCalculator.FinancialStepRole;
import org.praxisplatform.rules.parametric.ProgressiveBracketAndCapCalculator.ProgressiveBracket;

class ProgressiveBracketAndCapCalculatorTest {

    private static final List<ProgressiveBracket> RPPS_EC103_BRACKETS =
            List.of(
                    new ProgressiveBracket(
                            "RPPS-F1",
                            new BigDecimal("0.00"),
                            new BigDecimal("1412.00"),
                            new BigDecimal("7.50"),
                            BigDecimal.ZERO,
                            "EC103-RPPS-F1",
                            "EC 103/2019, art. 11"),
                    new ProgressiveBracket(
                            "RPPS-F2",
                            new BigDecimal("1412.00"),
                            new BigDecimal("2666.68"),
                            new BigDecimal("9.00"),
                            BigDecimal.ZERO,
                            "EC103-RPPS-F2",
                            "EC 103/2019, art. 11"),
                    new ProgressiveBracket(
                            "RPPS-F3",
                            new BigDecimal("2666.68"),
                            new BigDecimal("4000.03"),
                            new BigDecimal("12.00"),
                            BigDecimal.ZERO,
                            "EC103-RPPS-F3",
                            "EC 103/2019, art. 11"),
                    new ProgressiveBracket(
                            "RPPS-F4",
                            new BigDecimal("4000.03"),
                            new BigDecimal("7786.02"),
                            new BigDecimal("14.00"),
                            BigDecimal.ZERO,
                            "EC103-RPPS-F4",
                            "EC 103/2019, art. 11"),
                    new ProgressiveBracket(
                            "RPPS-F5",
                            new BigDecimal("7786.02"),
                            null,
                            new BigDecimal("16.50"),
                            BigDecimal.ZERO,
                            "EC103-RPPS-F5",
                            "EC 103/2019, art. 11"));

    private static final List<ProgressiveBracket> IRRF_TABLE_BRACKETS =
            List.of(
                    new ProgressiveBracket(
                            "IRRF-ISENTO",
                            new BigDecimal("0.00"),
                            new BigDecimal("2259.20"),
                            new BigDecimal("0.00"),
                            new BigDecimal("0.00"),
                            "IRRF-FAIXA-1",
                            "Lei 11.482/2007"),
                    new ProgressiveBracket(
                            "IRRF-75",
                            new BigDecimal("2259.20"),
                            new BigDecimal("2826.65"),
                            new BigDecimal("7.50"),
                            new BigDecimal("169.44"),
                            "IRRF-FAIXA-2",
                            "Lei 11.482/2007"),
                    new ProgressiveBracket(
                            "IRRF-150",
                            new BigDecimal("2826.65"),
                            new BigDecimal("3751.05"),
                            new BigDecimal("15.00"),
                            new BigDecimal("381.44"),
                            "IRRF-FAIXA-3",
                            "Lei 11.482/2007"),
                    new ProgressiveBracket(
                            "IRRF-225",
                            new BigDecimal("3751.05"),
                            new BigDecimal("4664.68"),
                            new BigDecimal("22.50"),
                            new BigDecimal("662.77"),
                            "IRRF-FAIXA-4",
                            "Lei 11.482/2007"),
                    new ProgressiveBracket(
                            "IRRF-275",
                            new BigDecimal("4664.68"),
                            null,
                            new BigDecimal("27.50"),
                            new BigDecimal("896.00"),
                            "IRRF-FAIXA-5",
                            "Lei 11.482/2007"));

    @Test
    void calculatesMarginalProgressiveSlicesWithAndWithoutRpcCeilingCap() {
        // 1. Servidor no RPC (Funpresp/Prevcom): base de 20.000,00 travada no Teto RGPS (7.786,02)
        var rpcResult =
                ProgressiveBracketAndCapCalculator.calculateProgressive(
                        new BigDecimal("20000.00"),
                        BigDecimal.ZERO,
                        0,
                        BigDecimal.ZERO,
                        new BigDecimal("7786.02"),
                        BracketMode.MARGINAL_SLICES,
                        RPPS_EC103_BRACKETS,
                        "ERG-RPC-TETO-RGPS",
                        "CF/88, art. 40, §§ 14 a 16");

        assertTrue(rpcResult.ceilingCapApplied());
        assertEquals(new BigDecimal("20000.00"), rpcResult.grossBaseAmount());
        assertEquals(new BigDecimal("7786.02"), rpcResult.effectiveBaseAmount());
        // Fatias até 7786.02:
        // F1: 1412.00 * 7.5% = 105.90
        // F2: 1254.68 * 9.0% = 112.92
        // F3: 1333.35 * 12.0% = 160.00
        // F4: 3785.99 * 14.0% = 530.04
        // Soma = 908.86
        assertEquals(new BigDecimal("908.86"), rpcResult.calculatedAmount());
        assertEquals(new BigDecimal("14.00"), rpcResult.nominalRatePercent());
        assertTrue(
                rpcResult.trailSteps().stream()
                        .anyMatch(s -> s.role() == FinancialStepRole.BASE_CEILING_CAP));

        // 2. Servidor em RPPS Integral (sem teto RGPS): base de 10.000,00 alcança F5 (acima de 7.786,02)
        var integralResult =
                ProgressiveBracketAndCapCalculator.calculateProgressive(
                        new BigDecimal("10000.00"),
                        BigDecimal.ZERO,
                        0,
                        BigDecimal.ZERO,
                        null,
                        BracketMode.MARGINAL_SLICES,
                        RPPS_EC103_BRACKETS,
                        "ERG-RPPS-PROGRESSIVO",
                        "EC 103/2019, art. 11");

        assertFalse(integralResult.ceilingCapApplied());
        assertEquals(new BigDecimal("10000.00"), integralResult.effectiveBaseAmount());
        // 908.86 + (2213.98 * 16.5% = 365.31) = 1274.17
        assertEquals(new BigDecimal("1274.17"), integralResult.calculatedAmount());
        assertEquals(new BigDecimal("16.50"), integralResult.nominalRatePercent());
        assertEquals(new BigDecimal("12.74"), integralResult.effectiveRatePercent());
    }

    @Test
    void calculatesTableRateMinusDeductionWithSocialSecurityAndDependentDeductions() {
        // Base bruta: 12.000,00 | Previdência: 1.274,17 | 2 dependentes x 189,59 = 379,18
        // Base líquida IRRF = 12000.00 - 1653.35 = 10346.65
        // Imposto = (10346.65 * 27.5%) - 896.00 = 2845.32875 - 896.00 = 1949.33
        var irrf =
                ProgressiveBracketAndCapCalculator.calculateProgressive(
                        new BigDecimal("12000.00"),
                        new BigDecimal("1274.17"),
                        2,
                        new BigDecimal("189.59"),
                        null,
                        BracketMode.TABLE_RATE_MINUS_DEDUCTION,
                        IRRF_TABLE_BRACKETS,
                        "ERG-IRRF-MENSAL",
                        "RIR/2018; Lei 9.250/1995");

        assertEquals(new BigDecimal("1653.35"), irrf.totalBaseDeductions());
        assertEquals(new BigDecimal("10346.65"), irrf.effectiveBaseAmount());
        assertEquals(new BigDecimal("1949.33"), irrf.calculatedAmount());
        assertEquals(new BigDecimal("27.50"), irrf.nominalRatePercent());

        // Faixa isenta
        var exempt =
                ProgressiveBracketAndCapCalculator.calculateProgressive(
                        new BigDecimal("2100.00"),
                        BigDecimal.ZERO,
                        1,
                        new BigDecimal("189.59"),
                        null,
                        BracketMode.TABLE_RATE_MINUS_DEDUCTION,
                        IRRF_TABLE_BRACKETS,
                        "ERG-IRRF-MENSAL",
                        "Lei 11.482/2007");
        assertEquals(new BigDecimal("0.00"), exempt.calculatedAmount());
    }

    @Test
    void calculatesConstitutionalCapAbatementPreservingExemptIndemnizatoryParcels() {
        // Remuneração submetida ao teto: 46.500,00 | Verbas indenizatórias (§ 11): 3.200,00
        // Teto STF: 44.008,52 => Glosa Abate-Teto = 46.500,00 - 44.008,52 = 2.491,48
        // Remuneração pós-teto = 44.008,52 + 3.200,00 = 47.208,52
        var cap =
                ProgressiveBracketAndCapCalculator.calculateCapAbatement(
                        new BigDecimal("46500.00"),
                        new BigDecimal("3200.00"),
                        new BigDecimal("44008.52"),
                        "ERG-ABATE-TETO-CF88-ART37-XI",
                        "CF/88, art. 37, XI e § 11; Temas 377 e 384 STF");

        assertTrue(cap.capTriggered());
        assertEquals(new BigDecimal("49700.00"), cap.grossRemuneration());
        assertEquals(new BigDecimal("46500.00"), cap.capSubjectAmount());
        assertEquals(new BigDecimal("3200.00"), cap.capExemptIndemnizatoryAmount());
        assertEquals(new BigDecimal("2491.48"), cap.abatementAmount());
        assertEquals(new BigDecimal("47208.52"), cap.postCapRemuneration());
        assertEquals(2, cap.trailSteps().size());
    }

    @Test
    void reconcilesNetEquationConsignableMarginAndProRataAndFailsClosedOnInvalidInputs() {
        // Bruto: 15.000,00 | Compulsórios: 3.500,00 | Excluídos da margem (indenizatórias): 1.500,00
        // Base consignável = 15000 - 3500 - 1500 = 10.000,00
        // Margem 35% = 3.500,00 | Consignados facultativos: 3.800,00 => Excedida em 300,00!
        var margin =
                ProgressiveBracketAndCapCalculator.reconcileNetAndMargin(
                        new BigDecimal("15000.00"),
                        new BigDecimal("3500.00"),
                        new BigDecimal("3800.00"),
                        new BigDecimal("1500.00"),
                        new BigDecimal("35.00"),
                        "ERG-MARGEM-CONSIGNAVEL-EXCEDIDA",
                        "Lei 14.509/2022");

        assertEquals(new BigDecimal("7300.00"), margin.totalDeductions());
        assertEquals(new BigDecimal("7700.00"), margin.netAmount());
        assertEquals(new BigDecimal("10000.00"), margin.consignableBase());
        assertEquals(new BigDecimal("3500.00"), margin.totalMarginLimit());
        assertEquals(new BigDecimal("-300.00"), margin.availableMargin());
        assertTrue(margin.marginExceeded());
        assertFalse(margin.negativeNetDetected());

        // Pro-rata: 9.000,00 por 18 de 30 dias = 5.400,00
        var proRata =
                ProgressiveBracketAndCapCalculator.calculateProRata(
                        new BigDecimal("9000.00"),
                        new BigDecimal("18"),
                        new BigDecimal("30"),
                        "ERG-PRO-RATA-30D",
                        "Lei 8.112/1990, art. 40");
        assertEquals(new BigDecimal("5400.00"), proRata.proportionalAmount());
        assertEquals(new BigDecimal("0.600000"), proRata.proportionalFactor());

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ProgressiveBracketAndCapCalculator.calculateCapAbatement(
                                new BigDecimal("-1.00"),
                                BigDecimal.ZERO,
                                new BigDecimal("44008.52"),
                                "ERR",
                                null));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ProgressiveBracketAndCapCalculator.calculateProRata(
                                new BigDecimal("1000.00"),
                                new BigDecimal("10"),
                                BigDecimal.ZERO,
                                "ERR",
                                null));
    }

    @Test
    void bridgesStatutoryOneCentInclusiveStepsPreservesFourDecimalRatesAndRejectsOverlapsOrWideGaps() {
        // Tabela estatutária com degrau de R$ 0,01 (2259.20 -> 2259.21) e alíquota de 4 casas decimais
        var statutoryBrackets =
                List.of(
                        new ProgressiveBracket(
                                "F1",
                                new BigDecimal("0.00"),
                                new BigDecimal("2259.20"),
                                new BigDecimal("0.00"),
                                BigDecimal.ZERO,
                                "F1",
                                "Lei"),
                        new ProgressiveBracket(
                                "F2",
                                new BigDecimal("2259.21"),
                                null,
                                new BigDecimal("11.6875"),
                                new BigDecimal("169.44"),
                                "F2",
                                "Lei"));

        // Base exatamente em R$ 2.259,21 deve enquadrar em F2 (sem cair em zona morta de 1 centavo)
        var exactBoundary =
                ProgressiveBracketAndCapCalculator.calculateProgressive(
                        new BigDecimal("2259.21"),
                        BigDecimal.ZERO,
                        0,
                        BigDecimal.ZERO,
                        null,
                        BracketMode.TABLE_RATE_MINUS_DEDUCTION,
                        statutoryBrackets,
                        "TEST-STATUTORY-BOUNDARY",
                        "Lei");
        assertEquals(new BigDecimal("11.6875"), exactBoundary.nominalRatePercent());
        assertEquals(new BigDecimal("94.61"), exactBoundary.calculatedAmount());
        assertEquals(
                new BigDecimal("11.6875"),
                exactBoundary.trailSteps().getFirst().ratePercent());

        // Faixas sobrepostas devem falhar fechado
        var overlapping =
                List.of(
                        new ProgressiveBracket(
                                "F1",
                                new BigDecimal("0.00"),
                                new BigDecimal("2000.00"),
                                new BigDecimal("7.50"),
                                BigDecimal.ZERO,
                                null,
                                null),
                        new ProgressiveBracket(
                                "F2",
                                new BigDecimal("1999.99"),
                                null,
                                new BigDecimal("9.00"),
                                BigDecimal.ZERO,
                                null,
                                null));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ProgressiveBracketAndCapCalculator.calculateProgressive(
                                new BigDecimal("3000.00"),
                                BigDecimal.ZERO,
                                0,
                                BigDecimal.ZERO,
                                null,
                                BracketMode.MARGINAL_SLICES,
                                overlapping,
                                "OVERLAP",
                                null));

        // Lacunas > R$ 0,01 devem falhar fechado
        var wideGap =
                List.of(
                        new ProgressiveBracket(
                                "F1",
                                new BigDecimal("0.00"),
                                new BigDecimal("2000.00"),
                                new BigDecimal("7.50"),
                                BigDecimal.ZERO,
                                null,
                                null),
                        new ProgressiveBracket(
                                "F2",
                                new BigDecimal("2000.05"),
                                null,
                                new BigDecimal("9.00"),
                                BigDecimal.ZERO,
                                null,
                                null));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ProgressiveBracketAndCapCalculator.calculateProgressive(
                                new BigDecimal("3000.00"),
                                BigDecimal.ZERO,
                                0,
                                BigDecimal.ZERO,
                                null,
                                BracketMode.MARGINAL_SLICES,
                                wideGap,
                                "GAP",
                                null));
    }
}
