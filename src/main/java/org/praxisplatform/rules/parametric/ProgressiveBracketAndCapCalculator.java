package org.praxisplatform.rules.parametric;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Calculadora determinística, imutável e auditável de faixas progressivas, tetos paramétricos,
 * glosa de abate-teto, margem consignável, reconciliação líquida e proporcionalidade
 * {@code pro-rata} ({@code PLAT-EVO-12}).
 *
 * <p>Complementa {@link EffectiveDatedIntervalCalculator} ({@code PLAT-EVO-08}) no pacote
 * {@code org.praxisplatform.rules.parametric}, fornecendo álgebra financeira em
 * {@link BigDecimal} (escala monetária {@code 2}, {@link RoundingMode#HALF_UP}) com emissão de
 * passos auditáveis ({@link FinancialTrailStep}) reutilizáveis em múltiplos domínios corporativos:
 * <ul>
 *   <li><b>Folha de Pagamento e Recursos Humanos:</b> contribuição previdenciária progressiva por
 *       fatias (RPPS EC 103/2019 e RGPS), trava de base no teto do RGPS para servidores no Regime
 *       de Previdência Complementar (RPC — Funpresp/Prevcom), IRRF mensal por alíquota efetiva
 *       menos parcela a deduzir com dedução por dependente, Abate-Teto Constitucional (CF/88, art.
 *       37, XI e § 11) segregando verbas remuneratórias de parcelas indenizatórias, controle de
 *       margem consignável (30%/35%/40%) e reconciliação {@code Bruto - Descontos = Líquido};</li>
 *   <li><b>Tributário e Fiscal:</b> cálculo progressivo de tributos estaduais/municipais, retenções
 *       na fonte com dedução legal e limites máximos de base tributável;</li>
 *   <li><b>Comercial, Comissionamento e B2B Pricing:</b> comissões escalonadas por faixas de
 *       receita, descontos progressivos por volume com teto contratual e rateio {@code pro-rata}
 *       de mensalidades;</li>
 *   <li><b>Crédito, Benefícios e Consignações:</b> apuração de renda líquida comprometível,
 *       limite máximo de comprometimento de margem e bloqueio fail-closed de saldo líquido
 *       negativo.</li>
 * </ul>
 */
public final class ProgressiveBracketAndCapCalculator {

    private static final BigDecimal ZERO = new BigDecimal("0.00");
    private static final BigDecimal ONE_CENT = new BigDecimal("0.01");
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100.00");
    private static final int MONEY_SCALE = 2;
    private static final int RATE_MAX_SCALE = 4;
    private static final int FACTOR_SCALE = 6;
    private static final RoundingMode DEFAULT_ROUNDING = RoundingMode.HALF_UP;

    private ProgressiveBracketAndCapCalculator() {}

    /**
     * Modo de avaliação da tabela progressiva.
     */
    public enum BracketMode {
        /**
         * Progressividade por fatias marginais: cada faixa incide exclusivamente sobre a parcela
         * da base compreendida entre {@code lowerBoundExclusive} e {@code upperBoundInclusive}
         * (ex.: RPPS EC 103/2019, RGPS/INSS, comissionamento escalonado por faixas).
         */
        MARGINAL_SLICES,

        /**
         * Alíquota da faixa enquadrada menos parcela fixa a deduzir sobre a base líquida
         * tributável: {@code max(0, (baseTributavel * aliquota / 100) - parcelaADeduzir)}
         * (ex.: Tabela Progressiva Mensal do IRRF).
         */
        TABLE_RATE_MINUS_DEDUCTION
    }

    /**
     * Papel semântico de cada passo emitido na memória de cálculo financeira.
     */
    public enum FinancialStepRole {
        BASE_COMPOSITION,
        BASE_DEDUCTION,
        BASE_CEILING_CAP,
        PROGRESSIVE_SLICE,
        TABLE_BRACKET_MATCH,
        EXEMPT_PARCEL_EXCLUSION,
        CAP_ABATEMENT_GLOSA,
        CONSIGNABLE_MARGIN_CHECK,
        PRO_RATA_SCALING,
        NET_RECONCILIATION,
        FINAL_RESULT
    }

    /**
     * Definição imutável de uma faixa progressiva paramétrica.
     *
     * @param id identificador estável da faixa (ex.: {@code "FAIXA-1"}, {@code "IRRF-275"})
     * @param lowerBoundExclusive limite inferior da faixa (exclusivo em aritmética contínua;
     *     {@code >= 0.00})
     * @param upperBoundInclusive limite superior inclusivo da faixa; {@code null} indica faixa
     *     final sem teto superior ("acima de...")
     * @param ratePercent alíquota percentual da faixa (ex.: {@code 14.00} para 14%)
     * @param fixedDeduction parcela fixa a deduzir na faixa (usada em
     *     {@link BracketMode#TABLE_RATE_MINUS_DEDUCTION}; {@code 0.00} quando não aplicável)
     * @param ruleCode código da regra ou rubrica associada (ex.: {@code "EC103-RPPS-F2"})
     * @param legalBasis fundamentação legal/normativa da faixa
     */
    public record ProgressiveBracket(
            String id,
            BigDecimal lowerBoundExclusive,
            BigDecimal upperBoundInclusive,
            BigDecimal ratePercent,
            BigDecimal fixedDeduction,
            String ruleCode,
            String legalBasis) {

        public ProgressiveBracket {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(lowerBoundExclusive, "lowerBoundExclusive must not be null");
            Objects.requireNonNull(ratePercent, "ratePercent must not be null");
            if (lowerBoundExclusive.compareTo(BigDecimal.ZERO) < 0) {
                throw new IllegalArgumentException(
                        "lowerBoundExclusive must be >= 0 for bracket " + id);
            }
            if (upperBoundInclusive != null
                    && upperBoundInclusive.compareTo(lowerBoundExclusive) < 0) {
                throw new IllegalArgumentException(
                        "upperBoundInclusive must be >= lowerBoundExclusive for bracket " + id);
            }
            if (ratePercent.compareTo(BigDecimal.ZERO) < 0) {
                throw new IllegalArgumentException("ratePercent must be >= 0 for bracket " + id);
            }
            lowerBoundExclusive = money(lowerBoundExclusive);
            upperBoundInclusive = upperBoundInclusive != null ? money(upperBoundInclusive) : null;
            ratePercent = ratePercent.setScale(4, DEFAULT_ROUNDING);
            fixedDeduction = fixedDeduction != null ? money(fixedDeduction) : ZERO;
            if (fixedDeduction.compareTo(BigDecimal.ZERO) < 0) {
                throw new IllegalArgumentException(
                        "fixedDeduction must be >= 0 for bracket " + id);
            }
        }
    }

    /**
     * Passo auditável e imutável da memória de cálculo financeira.
     *
     * @param stepId identificador do passo
     * @param role papel semântico na memória de cálculo
     * @param baseAmount valor-base considerado no passo
     * @param ratePercent alíquota ou percentual aplicado no passo (quando houver)
     * @param deductionOrCapAmount dedução fixa ou teto aplicado no passo (quando houver)
     * @param resultAmount valor resultante ou delta apurado no passo
     * @param formulaExpression expressão aritmética legível por humanos e auditores
     * @param ruleCode código da regra de domínio associada
     * @param legalBasis fundamentação legal/normativa associada
     */
    public record FinancialTrailStep(
            String stepId,
            FinancialStepRole role,
            BigDecimal baseAmount,
            BigDecimal ratePercent,
            BigDecimal deductionOrCapAmount,
            BigDecimal resultAmount,
            String formulaExpression,
            String ruleCode,
            String legalBasis) {

        public FinancialTrailStep {
            Objects.requireNonNull(stepId, "stepId must not be null");
            Objects.requireNonNull(role, "role must not be null");
            Objects.requireNonNull(resultAmount, "resultAmount must not be null");
            baseAmount = baseAmount != null ? money(baseAmount) : ZERO;
            ratePercent = ratePercent != null ? normalizeRate(ratePercent) : ZERO;
            deductionOrCapAmount =
                    deductionOrCapAmount != null ? money(deductionOrCapAmount) : ZERO;
            resultAmount = money(resultAmount);
        }
    }

    /**
     * Resultado imutável da apuração de tabela progressiva ({@link #calculateProgressive}).
     *
     * @param grossBaseAmount base bruta informada antes de deduções ou teto
     * @param totalBaseDeductions soma das deduções de base aplicadas (ex.: previdência +
     *     dependentes no IRRF)
     * @param effectiveBaseAmount base efetiva submetida às faixas (após deduções e eventual trava
     *     no {@code ceilingBaseCap})
     * @param ceilingCapApplied indica se a base foi limitada por {@code ceilingBaseCap} (ex.:
     *     servidor no RPC limitado ao teto do RGPS)
     * @param calculatedAmount valor final do tributo, contribuição ou comissão apurada
     * @param nominalRatePercent maior alíquota nominal alcançada na tabela
     * @param effectiveRatePercent alíquota efetiva real sobre a base bruta ({@code calculatedAmount
     *     / grossBaseAmount * 100})
     * @param trailSteps trilha ordenada e determinística de passos da memória de cálculo
     */
    public record ProgressiveCalculationResult(
            BigDecimal grossBaseAmount,
            BigDecimal totalBaseDeductions,
            BigDecimal effectiveBaseAmount,
            boolean ceilingCapApplied,
            BigDecimal calculatedAmount,
            BigDecimal nominalRatePercent,
            BigDecimal effectiveRatePercent,
            List<FinancialTrailStep> trailSteps) {

        public ProgressiveCalculationResult {
            trailSteps = List.copyOf(Objects.requireNonNull(trailSteps, "trailSteps"));
        }
    }

    /**
     * Resultado imutável da apuração de Abate-Teto Constitucional/Contratual
     * ({@link #calculateCapAbatement}).
     *
     * @param grossRemuneration total bruto do vínculo ({@code capSubjectAmount +
     *     capExemptIndemnizatoryAmount})
     * @param capSubjectAmount parcela remuneratória submetida ao teto (CF/88, art. 37, XI)
     * @param capExemptIndemnizatoryAmount parcela indenizatória/isenta excluída do teto (CF/88,
     *     art. 37, § 11)
     * @param capLimit valor do teto/subteto de referência
     * @param abatementAmount valor da glosa de abate-teto ({@code max(0, capSubjectAmount -
     *     capLimit)})
     * @param postCapRemuneration remuneração bruta efetiva pós-abate-teto
     * @param capUtilizationPercent percentual de ocupação do teto ({@code capSubjectAmount /
     *     capLimit * 100})
     * @param capTriggered {@code true} quando {@code abatementAmount > 0}
     * @param trailSteps passos auditáveis da memória de cálculo do teto
     */
    public record CapAbatementResult(
            BigDecimal grossRemuneration,
            BigDecimal capSubjectAmount,
            BigDecimal capExemptIndemnizatoryAmount,
            BigDecimal capLimit,
            BigDecimal abatementAmount,
            BigDecimal postCapRemuneration,
            BigDecimal capUtilizationPercent,
            boolean capTriggered,
            List<FinancialTrailStep> trailSteps) {

        public CapAbatementResult {
            trailSteps = List.copyOf(Objects.requireNonNull(trailSteps, "trailSteps"));
        }
    }

    /**
     * Resultado imutável da apuração de margem consignável e reconciliação da equação líquida
     * ({@link #reconcileNetAndMargin}).
     *
     * @param grossAdvantages total bruto de vantagens
     * @param compulsoryDeductions soma dos descontos compulsórios (abate-teto, previdência, IRRF,
     *     pensão judicial, reposições ao erário)
     * @param optionalConsignments soma das consignações facultativas
     * @param totalDeductions soma total de descontos ({@code compulsoryDeductions +
     *     optionalConsignments})
     * @param netAmount valor líquido apurado ({@code grossAdvantages - totalDeductions})
     * @param consignableBase base líquida consignável ({@code max(0, grossAdvantages -
     *     compulsoryDeductions - excludedFromMarginAmount)})
     * @param marginRatePercent percentual regulamentar da margem consignável (ex.: {@code 30.00},
     *     {@code 35.00}, {@code 40.00})
     * @param totalMarginLimit valor máximo total de margem consignável
     * @param availableMargin saldo disponível de margem ({@code totalMarginLimit -
     *     optionalConsignments})
     * @param marginUsagePercent percentual de utilização da margem consignável
     * @param marginExceeded {@code true} quando {@code optionalConsignments > totalMarginLimit}
     * @param negativeNetDetected {@code true} quando {@code netAmount < 0.00}
     * @param trailSteps passos auditáveis da memória de cálculo
     */
    public record NetAndMarginReconciliationResult(
            BigDecimal grossAdvantages,
            BigDecimal compulsoryDeductions,
            BigDecimal optionalConsignments,
            BigDecimal totalDeductions,
            BigDecimal netAmount,
            BigDecimal consignableBase,
            BigDecimal marginRatePercent,
            BigDecimal totalMarginLimit,
            BigDecimal availableMargin,
            BigDecimal marginUsagePercent,
            boolean marginExceeded,
            boolean negativeNetDetected,
            List<FinancialTrailStep> trailSteps) {

        public NetAndMarginReconciliationResult {
            trailSteps = List.copyOf(Objects.requireNonNull(trailSteps, "trailSteps"));
        }
    }

    /**
     * Resultado imutável do cálculo de proporcionalidade {@code pro-rata}
     * ({@link #calculateProRata}).
     *
     * @param nominalAmount valor integral de referência
     * @param workedUnits quantidade de unidades (dias/horas) efetivamente trabalhadas ou vigentes
     * @param standardCycleUnits divisor padrão do ciclo (ex.: {@code 30} dias comerciais ou
     *     {@code 40} horas semanais)
     * @param proportionalFactor fator decimal aplicado ({@code workedUnits / standardCycleUnits})
     * @param proportionalAmount valor proporcional resultante arredondado em 2 casas decimais
     * @param trailStep passo auditável da memória de cálculo
     */
    public record ProRataResult(
            BigDecimal nominalAmount,
            BigDecimal workedUnits,
            BigDecimal standardCycleUnits,
            BigDecimal proportionalFactor,
            BigDecimal proportionalAmount,
            FinancialTrailStep trailStep) {

        public ProRataResult {
            Objects.requireNonNull(trailStep, "trailStep");
        }
    }

    /**
     * Executa o cálculo progressivo determinístico sobre {@code grossBaseAmount}.
     *
     * @param grossBaseAmount valor bruto inicial da base de cálculo ({@code >= 0})
     * @param baseDeductions soma de deduções prévias da base (ex.: contribuição previdenciária no
     *     cálculo de IRRF; {@code null} tratado como {@code 0.00})
     * @param dependentCount número de dependentes elegíveis para dedução per capita ({@code >= 0})
     * @param perDependentDeduction valor unitário de dedução por dependente ({@code null} tratado
     *     como {@code 0.00})
     * @param ceilingBaseCap teto opcional de truncamento da base antes da aplicação das faixas
     *     (ex.: teto do RGPS para servidor vinculado ao RPC); {@code null} quando não houver teto
     *     de base
     * @param mode modo de cálculo ({@link BracketMode#MARGINAL_SLICES} ou
     *     {@link BracketMode#TABLE_RATE_MINUS_DEDUCTION})
     * @param brackets lista de faixas progressivas (ordenada automaticamente por
     *     {@code lowerBoundExclusive})
     * @param ruleCode código da regra principal
     * @param legalBasis fundamentação legal principal
     * @return {@link ProgressiveCalculationResult} com valores exatos e trilha de auditoria
     */
    public static ProgressiveCalculationResult calculateProgressive(
            BigDecimal grossBaseAmount,
            BigDecimal baseDeductions,
            int dependentCount,
            BigDecimal perDependentDeduction,
            BigDecimal ceilingBaseCap,
            BracketMode mode,
            List<ProgressiveBracket> brackets,
            String ruleCode,
            String legalBasis) {
        Objects.requireNonNull(grossBaseAmount, "grossBaseAmount must not be null");
        Objects.requireNonNull(mode, "mode must not be null");
        Objects.requireNonNull(brackets, "brackets must not be null");
        if (grossBaseAmount.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("grossBaseAmount must be >= 0");
        }
        if (dependentCount < 0) {
            throw new IllegalArgumentException("dependentCount must be >= 0");
        }
        if (brackets.isEmpty()) {
            throw new IllegalArgumentException("brackets must not be empty");
        }

        BigDecimal gross = money(grossBaseAmount);
        BigDecimal priorDeductions = baseDeductions != null ? money(baseDeductions) : ZERO;
        if (priorDeductions.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("baseDeductions must be >= 0");
        }
        BigDecimal depUnit = perDependentDeduction != null ? money(perDependentDeduction) : ZERO;
        if (depUnit.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("perDependentDeduction must be >= 0");
        }
        BigDecimal depTotal = money(depUnit.multiply(BigDecimal.valueOf(dependentCount)));
        BigDecimal totalDeductions = money(priorDeductions.add(depTotal));

        List<FinancialTrailStep> steps = new ArrayList<>();
        BigDecimal netBase = gross.subtract(totalDeductions);
        if (netBase.compareTo(BigDecimal.ZERO) < 0) {
            netBase = ZERO;
        }
        netBase = money(netBase);

        if (totalDeductions.compareTo(BigDecimal.ZERO) > 0) {
            steps.add(
                    new FinancialTrailStep(
                            "BASE-DEDUCTIONS",
                            FinancialStepRole.BASE_DEDUCTION,
                            gross,
                            ZERO,
                            totalDeductions,
                            netBase,
                            String.format(
                                    "Base líquida = max(0, %s - (%s + %d x %s)) = %s",
                                    gross.toPlainString(),
                                    priorDeductions.toPlainString(),
                                    dependentCount,
                                    depUnit.toPlainString(),
                                    netBase.toPlainString()),
                            ruleCode,
                            legalBasis));
        }

        boolean capApplied = false;
        BigDecimal effectiveBase = netBase;
        if (ceilingBaseCap != null) {
            if (ceilingBaseCap.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("ceilingBaseCap must be > 0 when provided");
            }
            BigDecimal cap = money(ceilingBaseCap);
            if (effectiveBase.compareTo(cap) > 0) {
                effectiveBase = cap;
                capApplied = true;
                steps.add(
                        new FinancialTrailStep(
                                "BASE-CEILING-CAP",
                                FinancialStepRole.BASE_CEILING_CAP,
                                netBase,
                                ZERO,
                                cap,
                                effectiveBase,
                                String.format(
                                        "Base limitada ao teto paramétrico: min(%s, %s) = %s",
                                        netBase.toPlainString(),
                                        cap.toPlainString(),
                                        effectiveBase.toPlainString()),
                                ruleCode,
                                legalBasis));
            }
        }

        List<ProgressiveBracket> sorted = normalizeAndValidateBrackets(brackets);

        BigDecimal totalCalculated = ZERO;
        BigDecimal highestNominalRate = ZERO;

        if (mode == BracketMode.MARGINAL_SLICES) {
            for (ProgressiveBracket b : sorted) {
                if (effectiveBase.compareTo(b.lowerBoundExclusive()) <= 0) {
                    continue;
                }
                BigDecimal upper =
                        b.upperBoundInclusive() != null
                                ? effectiveBase.min(b.upperBoundInclusive())
                                : effectiveBase;
                BigDecimal sliceAmount = money(upper.subtract(b.lowerBoundExclusive()));
                if (sliceAmount.compareTo(BigDecimal.ZERO) <= 0) {
                    continue;
                }
                BigDecimal sliceContribution =
                        money(
                                sliceAmount
                                        .multiply(b.ratePercent())
                                        .divide(ONE_HUNDRED, MONEY_SCALE, DEFAULT_ROUNDING));
                totalCalculated = money(totalCalculated.add(sliceContribution));
                if (b.ratePercent().compareTo(highestNominalRate) > 0) {
                    highestNominalRate = b.ratePercent();
                }
                steps.add(
                        new FinancialTrailStep(
                                b.id(),
                                FinancialStepRole.PROGRESSIVE_SLICE,
                                sliceAmount,
                                b.ratePercent(),
                                ZERO,
                                sliceContribution,
                                String.format(
                                        "Fatia [%s .. %s]: %s x %s%% = %s",
                                        b.lowerBoundExclusive().toPlainString(),
                                        b.upperBoundInclusive() != null
                                                ? b.upperBoundInclusive().toPlainString()
                                                : "∞",
                                        sliceAmount.toPlainString(),
                                        normalizeRate(b.ratePercent()).toPlainString(),
                                        sliceContribution.toPlainString()),
                                b.ruleCode() != null ? b.ruleCode() : ruleCode,
                                b.legalBasis() != null ? b.legalBasis() : legalBasis));
            }
        } else {
            ProgressiveBracket matched = null;
            for (ProgressiveBracket b : sorted) {
                boolean aboveLower =
                        b.lowerBoundExclusive().compareTo(BigDecimal.ZERO) == 0
                                ? effectiveBase.compareTo(BigDecimal.ZERO) >= 0
                                : effectiveBase.compareTo(b.lowerBoundExclusive()) > 0;
                boolean belowUpper =
                        b.upperBoundInclusive() == null
                                || effectiveBase.compareTo(b.upperBoundInclusive()) <= 0;
                if (aboveLower && belowUpper) {
                    matched = b;
                }
            }
            if (matched != null && effectiveBase.compareTo(BigDecimal.ZERO) > 0) {
                highestNominalRate = matched.ratePercent();
                BigDecimal grossTax =
                        effectiveBase
                                .multiply(matched.ratePercent())
                                .divide(ONE_HUNDRED, 4, DEFAULT_ROUNDING);
                BigDecimal netTax = money(grossTax.subtract(matched.fixedDeduction()));
                if (netTax.compareTo(BigDecimal.ZERO) < 0) {
                    netTax = ZERO;
                }
                totalCalculated = netTax;
                steps.add(
                        new FinancialTrailStep(
                                matched.id(),
                                FinancialStepRole.TABLE_BRACKET_MATCH,
                                effectiveBase,
                                matched.ratePercent(),
                                matched.fixedDeduction(),
                                totalCalculated,
                                String.format(
                                        "Faixa %s: max(0, (%s x %s%%) - %s) = %s",
                                        matched.id(),
                                        effectiveBase.toPlainString(),
                                        normalizeRate(matched.ratePercent()).toPlainString(),
                                        matched.fixedDeduction().toPlainString(),
                                        totalCalculated.toPlainString()),
                                matched.ruleCode() != null ? matched.ruleCode() : ruleCode,
                                matched.legalBasis() != null ? matched.legalBasis() : legalBasis));
            }
        }

        BigDecimal effectiveRate =
                gross.compareTo(BigDecimal.ZERO) > 0
                        ? totalCalculated
                                .multiply(ONE_HUNDRED)
                                .divide(gross, MONEY_SCALE, DEFAULT_ROUNDING)
                        : ZERO;

        steps.add(
                new FinancialTrailStep(
                        "PROGRESSIVE-TOTAL",
                        FinancialStepRole.FINAL_RESULT,
                        effectiveBase,
                        effectiveRate,
                        totalDeductions,
                        totalCalculated,
                        String.format(
                                "Total apurado = %s (alíquota efetiva %s%% sobre bruto %s)",
                                totalCalculated.toPlainString(),
                                effectiveRate.toPlainString(),
                                gross.toPlainString()),
                        ruleCode,
                        legalBasis));

        return new ProgressiveCalculationResult(
                gross,
                totalDeductions,
                effectiveBase,
                capApplied,
                totalCalculated,
                normalizeRate(highestNominalRate),
                effectiveRate,
                steps);
    }

    /**
     * Calcula a glosa de Abate-Teto Constitucional/Contratual preservando a intangibilidade das
     * parcelas de caráter indenizatório (CF/88, art. 37, XI e § 11).
     *
     * @param capSubjectAmount soma das rubricas remuneratórias submetidas ao teto ({@code >= 0})
     * @param capExemptIndemnizatoryAmount soma das rubricas indenizatórias/isentas excluídas da
     *     base do teto ({@code null} tratado como {@code 0.00})
     * @param capLimit limite monetário do teto de referência ({@code > 0})
     * @param ruleCode código da regra de abate-teto
     * @param legalBasis fundamentação legal/constitucional
     * @return {@link CapAbatementResult} com o valor exato da glosa e trilha de auditoria
     */
    public static CapAbatementResult calculateCapAbatement(
            BigDecimal capSubjectAmount,
            BigDecimal capExemptIndemnizatoryAmount,
            BigDecimal capLimit,
            String ruleCode,
            String legalBasis) {
        Objects.requireNonNull(capSubjectAmount, "capSubjectAmount must not be null");
        Objects.requireNonNull(capLimit, "capLimit must not be null");
        if (capSubjectAmount.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("capSubjectAmount must be >= 0");
        }
        if (capLimit.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("capLimit must be > 0");
        }

        BigDecimal subject = money(capSubjectAmount);
        BigDecimal exempt =
                capExemptIndemnizatoryAmount != null ? money(capExemptIndemnizatoryAmount) : ZERO;
        if (exempt.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("capExemptIndemnizatoryAmount must be >= 0");
        }
        BigDecimal limit = money(capLimit);
        BigDecimal gross = money(subject.add(exempt));

        BigDecimal abatement = subject.compareTo(limit) > 0 ? money(subject.subtract(limit)) : ZERO;
        BigDecimal postCap = money(gross.subtract(abatement));
        BigDecimal utilization =
                subject.multiply(ONE_HUNDRED).divide(limit, MONEY_SCALE, DEFAULT_ROUNDING);
        boolean triggered = abatement.compareTo(BigDecimal.ZERO) > 0;

        List<FinancialTrailStep> steps = new ArrayList<>();
        if (exempt.compareTo(BigDecimal.ZERO) > 0) {
            steps.add(
                    new FinancialTrailStep(
                            "CAP-EXEMPT-PARCELS",
                            FinancialStepRole.EXEMPT_PARCEL_EXCLUSION,
                            gross,
                            ZERO,
                            exempt,
                            subject,
                            String.format(
                                    "Base submetida ao teto = Bruto (%s) - Indenizatórias § 11 (%s) = %s",
                                    gross.toPlainString(),
                                    exempt.toPlainString(),
                                    subject.toPlainString()),
                            ruleCode,
                            "CF/88, art. 37, § 11; EC 47/2005"));
        }
        steps.add(
                new FinancialTrailStep(
                        "CAP-ABATEMENT-EVAL",
                        FinancialStepRole.CAP_ABATEMENT_GLOSA,
                        subject,
                        utilization,
                        limit,
                        abatement,
                        String.format(
                                "Glosa Abate-Teto = max(0, %s - %s) = %s (ocupação %s%%)",
                                subject.toPlainString(),
                                limit.toPlainString(),
                                abatement.toPlainString(),
                                utilization.toPlainString()),
                        ruleCode,
                        legalBasis));

        return new CapAbatementResult(
                gross,
                subject,
                exempt,
                limit,
                abatement,
                postCap,
                utilization,
                triggered,
                steps);
    }

    /**
     * Reconcilia a equação líquida ({@code Bruto - Descontos = Líquido}) e apura os limites e a
     * disponibilidade da margem consignável sobre a base líquida legal.
     *
     * @param grossAdvantages soma das vantagens brutas ({@code >= 0})
     * @param compulsoryDeductions soma dos descontos compulsórios ({@code >= 0})
     * @param optionalConsignments soma dos descontos consignados facultativos ({@code >= 0})
     * @param excludedFromMarginAmount parcelas excluídas da base de margem consignável (ex.:
     *     diárias, ajuda de custo, salário-família; {@code null} tratado como {@code 0.00})
     * @param marginRatePercent percentual regulamentar da margem consignável ({@code >= 0}, ex.:
     *     {@code 35.00})
     * @param ruleCode código da regra de margem/líquido
     * @param legalBasis fundamentação legal/normativa
     * @return {@link NetAndMarginReconciliationResult} auditável
     */
    public static NetAndMarginReconciliationResult reconcileNetAndMargin(
            BigDecimal grossAdvantages,
            BigDecimal compulsoryDeductions,
            BigDecimal optionalConsignments,
            BigDecimal excludedFromMarginAmount,
            BigDecimal marginRatePercent,
            String ruleCode,
            String legalBasis) {
        Objects.requireNonNull(grossAdvantages, "grossAdvantages must not be null");
        Objects.requireNonNull(compulsoryDeductions, "compulsoryDeductions must not be null");
        Objects.requireNonNull(optionalConsignments, "optionalConsignments must not be null");
        Objects.requireNonNull(marginRatePercent, "marginRatePercent must not be null");
        if (grossAdvantages.compareTo(BigDecimal.ZERO) < 0
                || compulsoryDeductions.compareTo(BigDecimal.ZERO) < 0
                || optionalConsignments.compareTo(BigDecimal.ZERO) < 0
                || marginRatePercent.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException(
                    "Financial amounts and marginRatePercent must be >= 0");
        }

        BigDecimal gross = money(grossAdvantages);
        BigDecimal compulsory = money(compulsoryDeductions);
        BigDecimal optional = money(optionalConsignments);
        BigDecimal excluded =
                excludedFromMarginAmount != null ? money(excludedFromMarginAmount) : ZERO;
        if (excluded.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("excludedFromMarginAmount must be >= 0");
        }
        BigDecimal rate = marginRatePercent.setScale(MONEY_SCALE, DEFAULT_ROUNDING);

        BigDecimal totalDeductions = money(compulsory.add(optional));
        BigDecimal netAmount = money(gross.subtract(totalDeductions));
        boolean negativeNet = netAmount.compareTo(BigDecimal.ZERO) < 0;

        BigDecimal consignableBase = gross.subtract(compulsory).subtract(excluded);
        if (consignableBase.compareTo(BigDecimal.ZERO) < 0) {
            consignableBase = ZERO;
        }
        consignableBase = money(consignableBase);

        BigDecimal marginLimit =
                money(
                        consignableBase
                                .multiply(rate)
                                .divide(ONE_HUNDRED, MONEY_SCALE, DEFAULT_ROUNDING));
        BigDecimal availableMargin = money(marginLimit.subtract(optional));
        boolean marginExceeded = optional.compareTo(marginLimit) > 0;
        BigDecimal marginUsage =
                marginLimit.compareTo(BigDecimal.ZERO) > 0
                        ? optional.multiply(ONE_HUNDRED)
                                .divide(marginLimit, MONEY_SCALE, DEFAULT_ROUNDING)
                        : (optional.compareTo(BigDecimal.ZERO) > 0 ? ONE_HUNDRED : ZERO);

        List<FinancialTrailStep> steps = new ArrayList<>();
        steps.add(
                new FinancialTrailStep(
                        "CONSIGNABLE-MARGIN-EVAL",
                        FinancialStepRole.CONSIGNABLE_MARGIN_CHECK,
                        consignableBase,
                        rate,
                        optional,
                        availableMargin,
                        String.format(
                                "Margem (%s%% de %s) = %s | Utilizada = %s | Disponível = %s",
                                rate.toPlainString(),
                                consignableBase.toPlainString(),
                                marginLimit.toPlainString(),
                                optional.toPlainString(),
                                availableMargin.toPlainString()),
                        ruleCode,
                        legalBasis));
        steps.add(
                new FinancialTrailStep(
                        "NET-EQUATION-RECONCILIATION",
                        FinancialStepRole.NET_RECONCILIATION,
                        gross,
                        ZERO,
                        totalDeductions,
                        netAmount,
                        String.format(
                                "Líquido = Bruto (%s) - Descontos (%s compulsórios + %s facultativos) = %s",
                                gross.toPlainString(),
                                compulsory.toPlainString(),
                                optional.toPlainString(),
                                netAmount.toPlainString()),
                        ruleCode,
                        legalBasis));

        return new NetAndMarginReconciliationResult(
                gross,
                compulsory,
                optional,
                totalDeductions,
                netAmount,
                consignableBase,
                rate,
                marginLimit,
                availableMargin,
                marginUsage,
                marginExceeded,
                negativeNet,
                steps);
    }

    /**
     * Calcula o valor proporcional {@code pro-rata} (por dias trabalhados no mês comercial ou por
     * fração de jornada horária) com arredondamento padrão {@link RoundingMode#HALF_UP}.
     *
     * @param nominalAmount valor nominal integral ({@code >= 0})
     * @param workedUnits unidades efetivamente trabalhadas/vigentes ({@code >= 0})
     * @param standardCycleUnits divisor padrão do ciclo ({@code > 0}, ex.: {@code 30} dias ou
     *     {@code 40} horas)
     * @param ruleCode código da regra associada
     * @param legalBasis fundamentação legal/normativa
     * @return {@link ProRataResult} com fator decimal de 6 casas e valor monetário de 2 casas
     */
    public static ProRataResult calculateProRata(
            BigDecimal nominalAmount,
            BigDecimal workedUnits,
            BigDecimal standardCycleUnits,
            String ruleCode,
            String legalBasis) {
        Objects.requireNonNull(nominalAmount, "nominalAmount must not be null");
        Objects.requireNonNull(workedUnits, "workedUnits must not be null");
        Objects.requireNonNull(standardCycleUnits, "standardCycleUnits must not be null");
        if (nominalAmount.compareTo(BigDecimal.ZERO) < 0
                || workedUnits.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("nominalAmount and workedUnits must be >= 0");
        }
        if (standardCycleUnits.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("standardCycleUnits must be > 0");
        }

        BigDecimal nominal = money(nominalAmount);
        BigDecimal worked = workedUnits.setScale(MONEY_SCALE, DEFAULT_ROUNDING);
        BigDecimal cycle = standardCycleUnits.setScale(MONEY_SCALE, DEFAULT_ROUNDING);
        BigDecimal factor = worked.divide(cycle, FACTOR_SCALE, DEFAULT_ROUNDING);
        BigDecimal proportional =
                money(nominal.multiply(worked).divide(cycle, MONEY_SCALE, DEFAULT_ROUNDING));

        FinancialTrailStep step =
                new FinancialTrailStep(
                        "PRO-RATA-CALC",
                        FinancialStepRole.PRO_RATA_SCALING,
                        nominal,
                        factor.multiply(ONE_HUNDRED).setScale(MONEY_SCALE, DEFAULT_ROUNDING),
                        cycle,
                        proportional,
                        String.format(
                                "Pro-rata: %s x (%s / %s) = %s",
                                nominal.toPlainString(),
                                worked.toPlainString(),
                                cycle.toPlainString(),
                                proportional.toPlainString()),
                        ruleCode,
                        legalBasis);

        return new ProRataResult(nominal, worked, cycle, factor, proportional, step);
    }

    private static List<ProgressiveBracket> normalizeAndValidateBrackets(
            List<ProgressiveBracket> brackets) {
        for (ProgressiveBracket b : brackets) {
            Objects.requireNonNull(b, "bracket entry must not be null");
        }
        List<ProgressiveBracket> sorted =
                brackets.stream()
                        .sorted(Comparator.comparing(ProgressiveBracket::lowerBoundExclusive))
                        .toList();
        List<ProgressiveBracket> normalized = new ArrayList<>(sorted.size());
        for (int i = 0; i < sorted.size(); i++) {
            ProgressiveBracket current = sorted.get(i);
            if (i > 0) {
                ProgressiveBracket prev = normalized.get(i - 1);
                if (prev.upperBoundInclusive() == null) {
                    throw new IllegalArgumentException(
                            "Open-ended bracket "
                                    + prev.id()
                                    + " must be the final bracket; followed by "
                                    + current.id());
                }
                int cmp = current.lowerBoundExclusive().compareTo(prev.upperBoundInclusive());
                if (cmp < 0) {
                    throw new IllegalArgumentException(
                            "Overlapping progressive brackets between "
                                    + prev.id()
                                    + " (upper="
                                    + prev.upperBoundInclusive().toPlainString()
                                    + ") and "
                                    + current.id()
                                    + " (lower="
                                    + current.lowerBoundExclusive().toPlainString()
                                    + ")");
                }
                if (cmp > 0) {
                    BigDecimal gap =
                            money(
                                    current.lowerBoundExclusive()
                                            .subtract(prev.upperBoundInclusive()));
                    if (gap.compareTo(ONE_CENT) > 0) {
                        throw new IllegalArgumentException(
                                "Gap > 0.01 between progressive brackets "
                                        + prev.id()
                                        + " and "
                                        + current.id()
                                        + ": gap="
                                        + gap.toPlainString());
                    }
                    // Bridges statutory inclusive step (e.g., prev ends at 2259.20, next starts at 2259.21)
                    current =
                            new ProgressiveBracket(
                                    current.id(),
                                    prev.upperBoundInclusive(),
                                    current.upperBoundInclusive(),
                                    current.ratePercent(),
                                    current.fixedDeduction(),
                                    current.ruleCode(),
                                    current.legalBasis());
                }
            }
            normalized.add(current);
        }
        return List.copyOf(normalized);
    }

    private static BigDecimal normalizeRate(BigDecimal rate) {
        BigDecimal scaled = rate.setScale(RATE_MAX_SCALE, DEFAULT_ROUNDING).stripTrailingZeros();
        if (scaled.scale() < MONEY_SCALE) {
            scaled = scaled.setScale(MONEY_SCALE, DEFAULT_ROUNDING);
        }
        return scaled;
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(MONEY_SCALE, DEFAULT_ROUNDING);
    }
}
