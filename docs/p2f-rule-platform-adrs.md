# ADRs da plataforma de regras — gate QL-03

- Data-base: 2026-07-13
- Classificação: `arquitetural`, `transversal` e futura `contrato-publico`
- Estado do pacote: `ACCEPTED_FOR_QL_03_SNAPSHOT_IMPLEMENTATION`

Este índice registra as decisões de plataforma que o laboratório do
`praxis-api-quickstart` deve consumir. O Quickstart não é owner destes
contratos, e nenhum ADR autoriza execução de regras Ergon ou promoção de
autoridade.

| ADR | Decisão | Estado |
| --- | --- | --- |
| [P2F-ADR-01](p2f-adr-01-runtime-contract-ownership.md) | ownership e dependências | aceito |
| [P2F-ADR-02](p2f-adr-02-identity-and-lifecycles.md) | identidade e lifecycles separados | aceito |
| [P2F-ADR-03](p2f-adr-03-composition-and-overrides.md) | composição e override | aceito |
| [P2F-ADR-04](p2f-adr-04-decision-plan-and-dag.md) | stages, DAG e short-circuit | aceito |
| P2F-ADR-05 | protected rules e extensões assinadas | requerido antes de plugins |
| [P2F-ADR-06](p2f-adr-06-json-logic-binding.md) | binding ao dialect JSON Logic | aceito |
| [P2F-ADR-07](p2f-adr-07-snapshot-etag-and-rollback.md) | snapshot, ETag e rollback | aceito |
| [P2F-ADR-08](p2f-adr-08-results-errors-and-fail-policy.md) | resultados, erros e fail policy | aceito |
| [P2F-ADR-09](p2f-adr-09-cache-and-hot-reload.md) | cache e hot reload | aceito |
| P2F-ADR-10 | transação, lote e effects | requerido antes de QL-05 |
| P2F-ADR-11 | transformação tipada | requerido antes de transformação |
| P2F-ADR-12 | observation e redaction | requerido antes de QL-06 |

## Gate resultante

Os ADRs 01, 02, 03, 04, 06, 07, 08 e 09 liberam QL-03: envelope imutável no
engine, persistência/head/rollback no Config Starter e adapter last-known-good
no host. Endpoint de negócio, persistência da concessão, execução de effects,
shadow e autoridade permanecem fora do escopo.

As fixtures e o checker focal vivem no `praxis-api-quickstart` em
`src/test/resources/rule-lab/` e `RuleLabGoldenContractTest`. Contratos,
planner e evaluator do core de QL-02 foram publicados em `0.1.0-beta.8` e
consumidos pelo Quickstart. QL-03 adiciona o envelope de snapshot sem mover
persistência, ETag ou lifecycle para o engine.
