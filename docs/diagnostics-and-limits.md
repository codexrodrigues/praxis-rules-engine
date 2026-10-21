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

## Transporte direto Java (fonte contract 1.5)

`DEPENDENCY_OUTPUT_LIMIT_EXCEEDED` significa que o mapa JSON canônico de um
contexto, ou a soma por avaliação, excedeu 256.000 bytes UTF-8. Mapa vazio custa
zero; fan-out cobra cada entrega. A medição limitada precede a cópia e a chamada
do consumidor, após os gates. `DEPENDENCY_OUTPUT_INVALID` identifica nó não JSON
nativo ou número não finito no canal; não converte valores. Ambos devolvem
`TECHNICAL_ERROR` com `bindingResults` e propostas vazios. Os limites existentes
de outputs e resultado público permanecem independentes. Ver [ADR-14](p2f-adr-14-direct-dependency-outputs.md).

A forma JSON do mapa é validada antes de medir bytes; `DEPENDENCY_OUTPUT_INVALID`
tem precedência sobre excesso de orçamento mesmo se ambos ocorrerem. Reordenar
campos de um objeto não pode mudar o diagnóstico. `PLAN_COMPATIBILITY_INVALID`
na avaliação indica plano de cache com engine/dialeto/corpus incompatíveis;
nenhum executor é chamado e o resultado identifica o runtime atual. Recompile
a composição pelo fluxo governado; não altere a versão do plano em memória.

No RuleSet engine, `FACTS_INVALID` também cobre nós Jackson não nativos ou não
finitos. `FACTS_LIMIT_EXCEEDED` cobre o orçamento canônico antes do hash, além
dos limites estruturais existentes. O tamanho compacto de um expoente decimal
não elimina esse limite. Ambos encerram sem digest/execução; não são negações
de negócio. O orçamento de facts não consome o orçamento de outputs.
