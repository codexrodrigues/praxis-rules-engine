# ADRs da plataforma de regras — gate QL-02

- Data-base: 2026-07-13
- Classificação: `arquitetural`, `transversal` e futura `contrato-publico`
- Estado do pacote: `ACCEPTED_FOR_QL_02_CONTRACT_IMPLEMENTATION`

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
| P2F-ADR-07 | snapshot, ETag e rollback | requerido antes de QL-03 |
| [P2F-ADR-08](p2f-adr-08-results-errors-and-fail-policy.md) | resultados, erros e fail policy | aceito |
| P2F-ADR-09 | cache e hot reload | requerido antes de QL-03 |
| P2F-ADR-10 | transação, lote e effects | requerido antes de QL-05 |
| P2F-ADR-11 | transformação tipada | requerido antes de transformação |
| P2F-ADR-12 | observation e redaction | requerido antes de QL-06 |

## Gate resultante

Os ADRs 01, 02, 03, 04, 06 e 08 liberam somente a implementação focal de
contratos, planner e avaliação service-level de QL-02. Endpoint, persistência,
snapshot ativo, cache, workflow, effects e autoridade permanecem fora do
escopo.

As fixtures e o checker focal vivem no `praxis-api-quickstart` em
`src/test/resources/rule-lab/` e `RuleLabGoldenContractTest`. Contratos,
planner e evaluator do core de QL-02 foram implementados e validados
localmente. O estado transversal é `ENGINE_VALIDATED_AWAITING_PUBLIC_RELEASE`:
consumo service-level no Quickstart aguarda a próxima coordenada beta oficial
no Maven Central.
