# ADRs da plataforma de regras — fundação até QL-08

- Data-base: 2026-07-14
- Classificação: `arquitetural`, `transversal` e futura `contrato-publico`
- Estado do pacote: `QL_08_VALIDATED_WITH_RESIDUAL_GATES`

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
| [P2F-ADR-10](p2f-adr-10-transactions-batches-and-effects.md) | transação, lote, contexto e effects | aceito; statement, outbox, HTTP inbox e reconciliação neutros provados |
| [P2F-ADR-11](p2f-adr-11-typed-transformations.md) | transformação tipada como proposta pura | aceito, publicado e consumido; materialização e auditoria redigida provadas no host |
| [P2F-ADR-12](p2f-adr-12-observation-redaction-and-retention.md) | observation, redaction e retenção | aceito; host sanitizado e ciclo de vida governado provados no Quickstart |

## Gate resultante

Os ADRs 01, 02, 03, 04, 06, 07, 08 e 09 liberaram QL-03: envelope imutável no
engine, persistência/head/rollback no Config Starter e adapter last-known-good
no host. O ADR-10 formaliza a fronteira já exercitada em QL-05/QL-08: o engine
planeja intents puros e o host possui contexto, idempotência, transação, lote,
outbox e effects. A decisão, a unidade statement-level, o dispatcher/outbox e o
contrato HTTP com inbox independente e reconciliação de resposta ambígua estão
provados no laboratório neutro. O recovery drill multi-processo e seu runbook foram concluídos em
2026-07-15 contra duas branches efêmeras do Neon, sem mover I/O para o engine. O host passou a
publicar métricas bounded, uma SPI interna de backlog/retenção segura e classificação segura entre
falhas permanentes e transitórias do transporte HTTP, além de replay governado e auditado de
dead-letter. Adapter corporativo real,
inbox/audit, dashboards/alertas no ambiente-alvo e autoridade continuam gates de implementação.

Para ADR-11, o Quickstart também provou auditoria append-only redigida na mesma
unidade transacional da materialização, preservando os digests canônicos do
engine sem persistir os valores transformados. Esse gate específico está
concluído no laboratório; não amplia a autoridade do engine nem libera regras
Ergon.

O ADR-12 preserva o resultado determinístico como única superfície do engine e
coloca observação, acesso, legal hold e retenção no host. O Quickstart prova a
allowlist QL-06 e uma fronteira SQL sem endpoint administrativo para os ledgers
QL-08. Operação no ambiente corporativo-alvo continua gate separado.

As fixtures e o checker focal vivem no `praxis-api-quickstart` em
`src/test/resources/rule-lab/` e `RuleLabGoldenContractTest`. Contratos,
planner e evaluator do core de QL-02 foram publicados e consumidos pelo
Quickstart. QL-03 adicionou o envelope de snapshot sem mover persistência, ETag
ou lifecycle para o engine. QL-05–QL-08 preservaram essa ownership boundary;
nenhum ADR autoriza regra Ergon, preflight, autoridade ou desligamento legado.
