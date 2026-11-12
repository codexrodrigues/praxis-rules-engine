package org.praxisplatform.rules.parametric;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.praxisplatform.rules.parametric.ProgressiveBracketAndCapCalculator.BracketMode;
import org.praxisplatform.rules.parametric.ProgressiveBracketAndCapCalculator.CapAbatementResult;
import org.praxisplatform.rules.parametric.ProgressiveBracketAndCapCalculator.ProgressiveBracket;
import org.praxisplatform.rules.parametric.ProgressiveBracketAndCapCalculator.ProgressiveCalculationResult;

/**
 * Tabela paramétrica versionada por vigência temporal {@code [validFrom, validTo]} para faixas
 * progressivas, tetos constitucionais/previdenciários e deduções legais ({@code PLAT-EVO-18}).
 *
 * <p>Conecta a álgebra temporal de {@link EffectiveDatedIntervalCalculator} ({@code PLAT-EVO-08})
 * à álgebra financeira de {@link ProgressiveBracketAndCapCalculator} ({@code PLAT-EVO-12}),
 * permitindo resolver de forma determinística e <i>fail-closed</i> a tabela normativa vigente na
 * data de referência ({@code competenciaDireito} vs. {@code competenciaFolha}), inclusive em folhas
 * suplementares, recálculos retroativos (RRA) e simulações comparativas no Rules Studio sem
 * condicionais imperativos no host.</p>
 */
public final class EffectiveDatedBracketTable {

    private static final BigDecimal ZERO = new BigDecimal("0.00");
    private static final int MONEY_SCALE = 2;
    private static final RoundingMode DEFAULT_ROUNDING = RoundingMode.HALF_UP;

    private final String tableKey;
    private final String description;
    private final List<BracketScheduleEpoch> epochs;

    /**
     * Vigência normativa imutável de uma tabela progressiva ou teto paramétrico.
     *
     * @param epochId identificador único da vigência (ex.: {@code "IRRF-2025-LEI14848"})
     * @param validFrom início inclusivo da vigência normativa
     * @param validTo fim inclusivo da vigência normativa; {@code null} indica vigência aberta atual
     * @param mode modo de cálculo progressivo; pode ser {@code null} em tabelas puras de teto
     * @param ceilingAmount valor do teto/limite máximo da vigência (ex.: Teto RGPS ou Teto STF)
     * @param perDependentDeduction dedução unitária por dependente vigente na época
     * @param brackets faixas progressivas da vigência; vazio para tabelas exclusivas de teto
     * @param ruleCode código canônico da regra associada
     * @param legalBasis fundamentação legal/constitucional da vigência
     * @param publicationRef referência do ato/publicação oficial (ex.: {@code "DOU 02/05/2024"})
     */
    public record BracketScheduleEpoch(
            String epochId,
            LocalDate validFrom,
            LocalDate validTo,
            BracketMode mode,
            BigDecimal ceilingAmount,
            BigDecimal perDependentDeduction,
            List<ProgressiveBracket> brackets,
            String ruleCode,
            String legalBasis,
            String publicationRef) {

        /** Valida invariantes estruturais e normaliza escalas monetárias da vigência. */
        public BracketScheduleEpoch {
            if (epochId == null || epochId.isBlank()) {
                throw new IllegalArgumentException("epochId must not be blank");
            }
            Objects.requireNonNull(validFrom, "validFrom must not be null for epoch " + epochId);
            if (validTo != null && validTo.isBefore(validFrom)) {
                throw new IllegalArgumentException(
                        "validTo (" + validTo + ") must not be before validFrom (" + validFrom + ") in epoch " + epochId);
            }
            brackets = brackets == null ? List.of() : List.copyOf(brackets);
            if (!brackets.isEmpty() && mode == null) {
                throw new IllegalArgumentException(
                        "mode is required when brackets are declared in epoch " + epochId);
            }
            if (brackets.isEmpty() && (ceilingAmount == null || ceilingAmount.compareTo(BigDecimal.ZERO) <= 0)) {
                throw new IllegalArgumentException(
                        "Epoch " + epochId + " must declare either non-empty brackets or a positive ceilingAmount");
            }
            if (ceilingAmount != null) {
                if (ceilingAmount.compareTo(BigDecimal.ZERO) <= 0) {
                    throw new IllegalArgumentException(
                            "ceilingAmount must be > 0 when present in epoch " + epochId);
                }
                ceilingAmount = ceilingAmount.setScale(MONEY_SCALE, DEFAULT_ROUNDING);
            }
            perDependentDeduction =
                    perDependentDeduction == null
                            ? ZERO
                            : perDependentDeduction.setScale(MONEY_SCALE, DEFAULT_ROUNDING);
            if (perDependentDeduction.compareTo(BigDecimal.ZERO) < 0) {
                throw new IllegalArgumentException(
                        "perDependentDeduction must be >= 0 in epoch " + epochId);
            }
            if (ruleCode == null || ruleCode.isBlank()) {
                throw new IllegalArgumentException("ruleCode must not be blank in epoch " + epochId);
            }
            if (legalBasis == null || legalBasis.isBlank()) {
                throw new IllegalArgumentException("legalBasis must not be blank in epoch " + epochId);
            }
            if (!brackets.isEmpty()) {
                // Fail-closed validation of contiguous statutory brackets at registration time.
                ProgressiveBracketAndCapCalculator.calculateProgressive(
                        ZERO,
                        ZERO,
                        0,
                        ZERO,
                        ceilingAmount,
                        mode,
                        brackets,
                        ruleCode,
                        legalBasis);
            }
        }

        /**
         * Verifica se a data de referência recai dentro do intervalo inclusivo desta vigência.
         *
         * @param asOfDate data de competência ou fato gerador
         * @return {@code true} se vigente na data informada
         */
        public boolean isEffectiveOn(LocalDate asOfDate) {
            Objects.requireNonNull(asOfDate, "asOfDate must not be null");
            return !asOfDate.isBefore(validFrom) && (validTo == null || !asOfDate.isAfter(validTo));
        }
    }

    /**
     * Resultado auditável de cálculo progressivo resolvido por vigência temporal.
     *
     * @param tableKey chave canônica da tabela paramétrica
     * @param asOfDate data de referência utilizada para enquadramento temporal
     * @param activeEpoch vigência normativa selecionada
     * @param calculation resultado completo e trilha de passos financeiros
     */
    public record TemporalProgressiveResult(
            String tableKey,
            LocalDate asOfDate,
            BracketScheduleEpoch activeEpoch,
            ProgressiveCalculationResult calculation) {

        /** Garante presença dos componentes do resultado temporal. */
        public TemporalProgressiveResult {
            Objects.requireNonNull(tableKey, "tableKey");
            Objects.requireNonNull(asOfDate, "asOfDate");
            Objects.requireNonNull(activeEpoch, "activeEpoch");
            Objects.requireNonNull(calculation, "calculation");
        }
    }

    /**
     * Resultado auditável de abate-teto resolvido por vigência temporal.
     *
     * @param tableKey chave canônica da tabela de teto
     * @param asOfDate data de referência utilizada para enquadramento temporal
     * @param activeEpoch vigência normativa selecionada
     * @param abatement resultado completo da apuração de abate-teto
     */
    public record TemporalCapAbatementResult(
            String tableKey,
            LocalDate asOfDate,
            BracketScheduleEpoch activeEpoch,
            CapAbatementResult abatement) {

        /** Garante presença dos componentes do resultado temporal de abate-teto. */
        public TemporalCapAbatementResult {
            Objects.requireNonNull(tableKey, "tableKey");
            Objects.requireNonNull(asOfDate, "asOfDate");
            Objects.requireNonNull(activeEpoch, "activeEpoch");
            Objects.requireNonNull(abatement, "abatement");
        }
    }

    private EffectiveDatedBracketTable(
            String tableKey, String description, Collection<BracketScheduleEpoch> rawEpochs) {
        if (tableKey == null || tableKey.isBlank()) {
            throw new IllegalArgumentException("tableKey must not be blank");
        }
        if (rawEpochs == null || rawEpochs.isEmpty()) {
            throw new IllegalArgumentException(
                    "EffectiveDatedBracketTable '" + tableKey + "' requires at least one epoch");
        }
        List<BracketScheduleEpoch> sorted = new ArrayList<>(rawEpochs);
        sorted.forEach(e -> Objects.requireNonNull(e, "epoch must not be null"));
        sorted.sort(Comparator.comparing(BracketScheduleEpoch::validFrom));

        Set<String> seenIds = new HashSet<>();
        for (int i = 0; i < sorted.size(); i++) {
            BracketScheduleEpoch current = sorted.get(i);
            if (!seenIds.add(current.epochId())) {
                throw new IllegalArgumentException(
                        "Duplicate epochId '" + current.epochId() + "' in table '" + tableKey + "'");
            }
            if (i < sorted.size() - 1) {
                BracketScheduleEpoch next = sorted.get(i + 1);
                if (current.validTo() == null) {
                    throw new IllegalArgumentException(
                            "Open-ended epoch '"
                                    + current.epochId()
                                    + "' must be the final epoch in table '"
                                    + tableKey
                                    + "'");
                }
                if (!next.validFrom().isAfter(current.validTo())) {
                    throw new IllegalArgumentException(
                            "Overlapping epochs '"
                                    + current.epochId()
                                    + "' ["
                                    + current.validFrom()
                                    + ".."
                                    + current.validTo()
                                    + "] and '"
                                    + next.epochId()
                                    + "' ["
                                    + next.validFrom()
                                    + ".."
                                    + next.validTo()
                                    + "] in table '"
                                    + tableKey
                                    + "'");
                }
            }
        }
        this.tableKey = tableKey.trim();
        this.description = description == null ? "" : description.trim();
        this.epochs = List.copyOf(sorted);
    }

    /**
     * Cria uma tabela paramétrica imutável e validada contra sobreposições de vigência.
     *
     * @param tableKey identificador canônico da tabela (ex.: {@code "BR.IRRF.MENSAL"})
     * @param description descrição funcional/normativa da tabela
     * @param epochs coleção de vigências normativas
     * @return instância validada de {@link EffectiveDatedBracketTable}
     */
    public static EffectiveDatedBracketTable of(
            String tableKey, String description, Collection<BracketScheduleEpoch> epochs) {
        return new EffectiveDatedBracketTable(tableKey, description, epochs);
    }

    /** @return identificador canônico da tabela paramétrica */
    public String tableKey() {
        return tableKey;
    }

    /** @return descrição funcional da tabela */
    public String description() {
        return description;
    }

    /** @return lista imutável de vigências ordenadas cronologicamente */
    public List<BracketScheduleEpoch> epochs() {
        return epochs;
    }

    /**
     * Busca a vigência normativa ativa na data informada.
     *
     * @param asOfDate data de competência ou fato gerador
     * @return vigência encontrada ou {@link Optional#empty()}
     */
    public Optional<BracketScheduleEpoch> findEpochAsOf(LocalDate asOfDate) {
        Objects.requireNonNull(asOfDate, "asOfDate must not be null");
        for (BracketScheduleEpoch epoch : epochs) {
            if (epoch.isEffectiveOn(asOfDate)) {
                return Optional.of(epoch);
            }
        }
        return Optional.empty();
    }

    /**
     * Resolve de forma <i>fail-closed</i> a vigência normativa ativa na data informada.
     *
     * @param asOfDate data de competência ou fato gerador
     * @return vigência ativa
     * @throws IllegalArgumentException quando não houver vigência publicada para {@code asOfDate}
     */
    public BracketScheduleEpoch requireEpochAsOf(LocalDate asOfDate) {
        return findEpochAsOf(asOfDate)
                .orElseThrow(
                        () ->
                                new IllegalArgumentException(
                                        "No active parametric epoch in table '"
                                                + tableKey
                                                + "' for date "
                                                + asOfDate));
    }

    /**
     * Resolve o valor do teto paramétrico vigente na data informada.
     *
     * @param asOfDate data de competência ou fato gerador
     * @return teto monetário vigente
     */
    public BigDecimal resolveCeilingAsOf(LocalDate asOfDate) {
        BracketScheduleEpoch epoch = requireEpochAsOf(asOfDate);
        if (epoch.ceilingAmount() == null) {
            throw new IllegalStateException(
                    "Epoch '"
                            + epoch.epochId()
                            + "' in table '"
                            + tableKey
                            + "' does not define a ceilingAmount");
        }
        return epoch.ceilingAmount();
    }

    /**
     * Avalia o cálculo progressivo utilizando automaticamente as faixas, a dedução por dependente e
     * (quando {@code applyEpochCeiling = true}) o teto de base da vigência ativa em {@code asOfDate}.
     *
     * @param asOfDate data de competência ou direito
     * @param grossBase base bruta antes de deduções
     * @param priorDeductions deduções legais prévias (ex.: previdência oficial + abate-teto)
     * @param dependentCount quantidade de dependentes elegíveis
     * @param applyEpochCeiling {@code true} para limitar a base ao {@code ceilingAmount} da vigência
     *     (ex.: servidor optante pelo RPC limitado ao Teto do RGPS)
     * @return resultado progressivo enriquecido com a vigência normativa aplicada
     */
    public TemporalProgressiveResult calculateProgressiveAsOf(
            LocalDate asOfDate,
            BigDecimal grossBase,
            BigDecimal priorDeductions,
            int dependentCount,
            boolean applyEpochCeiling) {
        BracketScheduleEpoch epoch = requireEpochAsOf(asOfDate);
        if (epoch.brackets().isEmpty()) {
            throw new IllegalStateException(
                    "Epoch '"
                            + epoch.epochId()
                            + "' in table '"
                            + tableKey
                            + "' is ceiling-only and has no progressive brackets");
        }
        BigDecimal effectiveCap = applyEpochCeiling ? epoch.ceilingAmount() : null;
        ProgressiveCalculationResult calc =
                ProgressiveBracketAndCapCalculator.calculateProgressive(
                        grossBase,
                        priorDeductions,
                        dependentCount,
                        epoch.perDependentDeduction(),
                        effectiveCap,
                        epoch.mode(),
                        epoch.brackets(),
                        epoch.ruleCode(),
                        epoch.legalBasis());
        return new TemporalProgressiveResult(tableKey, asOfDate, epoch, calc);
    }

    /**
     * Avalia a glosa de Abate-Teto Constitucional/Contratual usando o teto vigente em
     * {@code asOfDate}.
     *
     * @param asOfDate data de competência ou direito
     * @param capSubjectAmount soma das verbas remuneratórias submetidas ao teto
     * @param capExemptIndemnizatoryAmount soma das parcelas indenizatórias extrateto
     * @return resultado da apuração de abate-teto vinculado à vigência normativa
     */
    public TemporalCapAbatementResult calculateCapAbatementAsOf(
            LocalDate asOfDate,
            BigDecimal capSubjectAmount,
            BigDecimal capExemptIndemnizatoryAmount) {
        BracketScheduleEpoch epoch = requireEpochAsOf(asOfDate);
        BigDecimal capLimit = resolveCeilingAsOf(asOfDate);
        CapAbatementResult abatement =
                ProgressiveBracketAndCapCalculator.calculateCapAbatement(
                        capSubjectAmount,
                        capExemptIndemnizatoryAmount,
                        capLimit,
                        epoch.ruleCode(),
                        epoch.legalBasis());
        return new TemporalCapAbatementResult(tableKey, asOfDate, epoch, abatement);
    }
}
