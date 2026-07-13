# P2F-ADR-08 — resultados, erros e fail policy

- Estado: `ACCEPTED_FOR_QL_02`
- Data: 2026-07-13
- Classificação: `arquitetural` e futura `contrato-publico`

## Decisão consolidada

`RuleDecision` possui exatamente cinco estados:

| Estado | Significado |
| --- | --- |
| `ALLOW` | todos os gates aplicáveis permitem continuar |
| `DENY` | uma regra de negócio ou guard negou explicitamente |
| `NOT_APPLICABLE` | a decisão não se aplica aos facts válidos |
| `INCONCLUSIVE` | faltam facts confiáveis ou a política não pode concluir |
| `TECHNICAL_ERROR` | contrato, implementação, limite ou runtime falhou |

`DENY` não representa erro técnico. `NOT_APPLICABLE` não concede permissão.
`INCONCLUSIVE` não é sucesso silencioso.

## Resultado mínimo

O envelope runtime-neutro implementado em QL-02 contém:

- `decision`, `ruleSetRef` e `planDigest`;
- resultados ordenados por binding com stage, slot, decisão, reason codes e
  output puro opcional;
- `factsDigest`, sem facts integrais;
- compatibilidade exata de engine/dialect/corpus e versões Java usadas;
- fail policy declarada para aplicação pelo host.

Duração, diagnostics, `observationRef` e redaction pertencem a P2F-ADR-12/QL-06
porque tempo de parede não pode alterar o resultado determinístico do core.
Snapshot pertence a QL-03. Calculation e `effectIntents` tipadas pertencem aos
contratos de transformação/efeitos de QL-05; em QL-02 o output permanece dado
puro limitado, sem execução de efeito.

Reason code é estável e machine-readable. Mensagem localizada pertence ao
host/metadata; exception text não é código público.

## Fail policy

Fail policy pertence à boundary e é validada na publicação:

| Policy | Uso permitido |
| --- | --- |
| `FAIL_CLOSED` | protected guard; inconclusive/error bloqueia a operação sem fingir `DENY` de domínio |
| `RETURN_INCONCLUSIVE` | decisão consultiva ou não crítica |
| `APPROVED_BASELINE_FALLBACK` | transição observável com baseline definido e sem dupla mutação |

Fallback nunca é default global, não existe em `AUTHORITY` sem aprovação e não
pode ocultar tenant, snapshot ou implementação incompatível.

## Matriz de classificação

| Condição | Resultado |
| --- | --- |
| condição válida não satisfeita | `DENY` ou `NOT_APPLICABLE`, conforme binding |
| fact obrigatório ausente ou stale | `INCONCLUSIVE` |
| `null` permitido | expressão avalia `null` explicitamente |
| operador/implementação incompatível | `TECHNICAL_ERROR` |
| limite determinístico excedido | `TECHNICAL_ERROR` |
| protected guard negou | `DENY` e short-circuit |
| baseline shadow indisponível | comparação inconclusiva; decisão observada não muda negócio |

## Boundary HTTP e lote

O engine não define status HTTP. Hosts mapeiam o resultado para o contrato
canônico de erro sem perder `decision`, reason code e correlation. Avaliação em
lote retorna resultado por item; falha de um item não pode ser apresentada
como sucesso ou rollback integral sem semântica explícita do command.

Diagnostics, observations e logs aplicam allowlist/redaction. Facts sensíveis,
tokens, usuário, empresa, SQL, HADES e stack trace nunca atravessam o resultado
público por default.
