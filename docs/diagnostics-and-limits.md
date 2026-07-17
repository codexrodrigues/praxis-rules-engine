# Diagnosticos e limites

## Diagnosticos por fase

| Fase | Tipo | Exemplos | Resposta esperada |
| --- | --- | --- | --- |
| JSON Logic | `JsonLogicIssueCode` | `RULE_OPERATOR_UNKNOWN`, `RULE_PATH_INVALID`, `RULE_CONTEXT_AMBIGUOUS`, `RULE_REGEX_INVALID`, `RULE_LIMIT_EXCEEDED` | Rejeitar authoring/publicacao ou apresentar correcao ao usuario. |
| Planejamento | `RulePlanIssueCode` | `PLAN_CYCLE`, `PLAN_OVERRIDE_INVALID`, `PLAN_IMPLEMENTATION_UNAVAILABLE`, `PLAN_COMPATIBILITY_INVALID` | Nao ativar a candidata; corrigir RuleSet, catalogo ou coordenadas. |
| Snapshot | `RuleSnapshotIssueCode` | `SNAPSHOT_CONTRACT_INCOMPATIBLE`, `SNAPSHOT_HOST_INCOMPATIBLE`, `SNAPSHOT_PROVENANCE_INVALID` | Manter last-known-good e corrigir publicacao/host. |
| Avaliacao | `RuleDecision.TECHNICAL_ERROR` ou `INCONCLUSIVE` | executor ausente, facts invalidos, limite excedido, contexto temporal invalido | Nao converter em `DENY`; aplicar politica de falha no host. |

As APIs de validacao retornam codigo, path e operador quando aplicavel. Consuma os codigos, nao mensagens livres, como contrato de automacao.

## Limites padrao

`JsonLogicLimits.DEFAULT` fixa: profundidade 64, 10.000 nos, 256.000 bytes, 10.000 itens por array, 64.000 caracteres por string, 50.000 operacoes, regex de 512 caracteres e complexidade de regex 64.

Os limites podem ser reduzidos por chamada por meio de `JsonLogicEvaluationOptions` ou `JsonLogicValidationOptions`; nao devem ser desabilitados. Eles se aplicam a expressoes, resultados publicos e agregacoes. O envelope consolidado tambem limita reason codes e propostas de transformacao; quando o conjunto ultrapassa o limite, nenhum payload parcial deve ser interpretado como resultado valido.

## Regras de tratamento

- `RULE_PATH_INVALID` e `RULE_REGEX_INVALID` sao erros estruturados, inclusive para indices ou bounds excessivos; nao exponha excecoes do JDK.
- `INCONCLUSIVE` pode coexistir com ramos independentes; uma negativa real nao deve ser escondida por um ramo inconclusivo.
- Limites e erros de adaptacao/projecao sao evidencia tecnica, nunca uma decisao de negocio.
