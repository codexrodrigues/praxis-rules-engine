# P2F-ADR-10 — transação, lote, contexto de operação e effects

- Estado: `ACCEPTED_WITH_IMPLEMENTATION_GATES`
- Data: 2026-07-14
- Classificação: `arquitetural` e `transversal`
- Breaking change atual: nenhum; decisão de fronteira sem nova API Java

## Contexto

O engine produz uma decisão determinística e outputs puros. O host, porém,
precisa transformar essa decisão em comandos de negócio com concorrência,
idempotência, cardinalidade por item ou statement, persistência, effects locais
ou externos e auditoria. Misturar essas responsabilidades no core introduziria
Spring, banco, rede e semântica transacional no runtime puro.

QL-05 provou no Quickstart uma unidade item-level: agregado, transição e ledger
de effect no mesmo datasource e na mesma transação. QL-08 acrescentou
concorrência, rollback e read-after-failure. O slice seguinte provou
`STATEMENT_ATOMIC`, barreira agregada e cleanup explícito. Essas provas não
demonstram atomicidade distribuída.

## Decisão de ownership

| Responsabilidade | Owner canônico |
| --- | --- |
| decisão, plano determinístico e `EffectIntent` puro | `praxis-rules-engine` |
| snapshot publicado, head e rollback administrativo | `praxis-config-starter` |
| contexto de operação, transação, lote e idempotência | host/application service |
| persistência de negócio e effect local | host/application service |
| outbox e entrega externa | host e infraestrutura operacional do domínio |
| observação, redaction e retenção | P2F-ADR-12 + host observável |

O Config Starter não participa da transação de negócio. O engine não abre
transação, não lê ou escreve outbox, não executa effects e não conhece commit,
rollback, HTTP, broker ou datasource.

## Contexto explícito de operação

Cada comando autoritativo deve criar um contexto imutável e limitado, com no
mínimo:

- identidade única da operação e correlation id;
- tenant, environment e identidade autenticada já validados pelo host;
- instante UTC congelado e timezone explícito;
- snapshot key, content hash e activation revision capturados uma vez;
- cardinalidade e política transacional declaradas;
- idempotency key e fingerprint canônico quando houver replay;
- limites de itens, payload e duração impostos pelo host.

O contexto é passado explicitamente entre os estágios do application service.
É proibido usar `ThreadLocal`, estado estático, package/session state implícito
ou conexão reutilizada como contrato de continuidade. Facts sensíveis e
payloads integrais não fazem parte da identidade observável da operação.

Cleanup é estrutural: o contexto possui duração léxica igual à execução do
comando. Estado auxiliar persistido deve ser particionado por operation id e
ter encerramento determinístico em sucesso, falha e rollback. Um job de
reconciliação pode remover resíduos de crash, mas não substitui o cleanup do
fluxo normal.

## Cardinalidade e barreiras

Cardinalidade de operação do host não é `SlotCardinality` do RuleSet. O host
deve declarar uma das seguintes semânticas antes de executar:

| Modo | Unidade transacional | Resultado |
| --- | --- | --- |
| `SINGLE_ITEM` | um item por transação | um resultado terminal |
| `ITEM_INDEPENDENT` | uma transação por item | resultado ordenado por item; sucesso parcial explícito |
| `STATEMENT_ATOMIC` | todos os itens e o estágio agregado na mesma transação | sucesso único ou rollback integral |

`ITEM_INDEPENDENT` não pode anunciar semântica `AFTER STATEMENT`. Uma etapa
executada depois de vários commits é apenas `AFTER BATCH`, sem acesso a uma
visão transacional única. Para equivalência com triggers `AFTER STATEMENT`, o
host precisa usar `STATEMENT_ATOMIC`, executar o estágio agregado depois das
mutações locais e antes do commit e provar que rollback remove itens, estado
agregado, transições e effects locais.

As barreiras são semanticamente distintas:

1. `EVALUATED`: decisão pura concluída; nenhum write ocorreu;
2. `LOCAL_FLUSHED`: writes foram enviados ao datasource, ainda sujeitos a rollback;
3. `LOCAL_COMMITTED`: transação local confirmada e visível a novos leitores;
4. `EXTERNAL_DELIVERED`: consumidor externo confirmou processamento idempotente.

Flush não equivale a commit. Commit local não equivale a visibilidade ou
confirmação em outro sistema.

## Concorrência e idempotência

- o host aplica optimistic concurrency no agregado ou head de comando;
- a idempotency key é escopada por tenant, resource, target, action e ator;
- a mesma chave com fingerprint diferente falha em conflito;
- replay concluído retorna o resultado persistido sem reexecutar effect;
- concorrentes podem observar uma reserva `STARTED`, mas somente uma execução
  pode confirmar a unidade transacional;
- falha marca a execução de forma observável sem preservar mutação parcial.

Idempotência HTTP e idempotência de entrega externa são contratos diferentes e
possuem chaves próprias correlacionadas pela operation id.

## Effects locais e externos

Um effect local pode ser confirmado atomicamente apenas quando agregado,
transição e ledger compartilham o mesmo transaction manager e datasource.
Restrições únicas protegem a identidade do effect, mas não autorizam chamar
rede dentro da transação.

Todo effect externo usa transactional outbox:

1. a transação local grava agregado, transição e mensagem outbox imutável;
2. o commit torna a mensagem elegível para entrega;
3. um dispatcher entrega com semântica pelo menos uma vez;
4. o consumidor aplica idempotência pela identidade do effect;
5. retry limitado, dead-letter, reconciliação e operação manual são auditáveis;
6. confirmação externa avança o estado de entrega sem reescrever a decisão.

O contrato não promete exactly-once distribuído. A garantia é commit local
atômico mais entrega repetível e efeito externo idempotente.

Auditoria que precise sobreviver ao rollback não pode registrar como fato uma
mutação que não foi confirmada. Ela registra tentativa, identidade limitada e
resultado técnico em canal separado e redigido; auditoria de negócio confirmada
deriva do commit ou da outbox.

## Matriz de falhas

| Falha | Comportamento obrigatório |
| --- | --- |
| ETag/versão obsoleta | rejeitar antes da mutação |
| fingerprint idempotente divergente | conflito; não executar novamente |
| item falha em `ITEM_INDEPENDENT` | rollback do item; demais resultados preservados |
| item ou estágio agregado falha em `STATEMENT_ATOMIC` | rollback integral |
| ledger local conflita | rollback de agregado, transição e effect |
| gravação da outbox falha | rollback da transação local |
| entrega externa falha | commit local preservado; retry/dead-letter/reconciliação |
| cleanup falha após crash | operação permanece reconciliável por operation id |

`DENY`, `NOT_APPLICABLE` e `INCONCLUSIVE` não produzem effect autoritativo.
`TECHNICAL_ERROR` nunca é persistido como negativa de negócio.

## Evidência e gates residuais

Evidência validada no `praxis-api-quickstart`:

- QL-05: item-level, lote parcial ordenado, ETag, replay, transação local,
  ledger exatamente uma vez e rollback;
- QL-08: um vencedor sob `apply` concorrente e read-after-failure íntegro.
- QL-08/ADR-10: dois itens sob `STATEMENT_ATOMIC`, referência imutável do
  snapshot capturada antes do primeiro item, instante/timezone congelados,
  barreira agregada, rollback integral e cleanup sem `ThreadLocal`; hot reload
  concorrente não altera a sessão capturada.
- QL-08/ADR-10 outbox: reserva/fingerprint/replay vinculam `executionId` e
  `operationId`; itens, resultado idempotente e mensagem mínima são confirmados
  juntos; lease expirado, retry limitado, acknowledgement e dead-letter foram
  provados sem expor facts ou token de lease ao sink.
- QL-08/ADR-10 entrega externa neutra: adapter HTTP real com TLS obrigatório por
  padrão entrega a um consumidor fictício com inbox em datasource independente;
  após commit externo seguido de resposta perdida, o reconciler consulta o
  acknowledgement por `messageId`, confirma `DELIVERED` e impede redelivery.

Ainda exigem implementação e prova antes de Fase 9 ou autoridade:

- adapter do sistema corporativo-alvo e prova de seu efeito idempotente real;
- retenção e governança do inbox/audit na infraestrutura-alvo;
- scheduler/worker governado, dashboards, alertas e replay de dead-letter auditado no ambiente-alvo;
- harness DB-backed real e semântica transacional do host Ergon.

O recovery drill multi-processo foi concluído em 2026-07-15 no Quickstart: duas branches schema-only
efêmeras de projetos PostgreSQL Neon distintos, consumidor em JVM própria, HTTPS, timeout depois do
commit do inbox, restart dos dois processos, reconciliação e rotação de bearer token. A evidência
registrou `RETRY_SCHEDULED -> RECONCILED`, HTTP 401 para a credencial anterior e `DELIVERED` para a
nova; as branches foram excluídas após a coleta. O harness não altera o engine nem o contrato deste
ADR. O Quickstart também oferece métricas bounded, snapshot de backlog sem payload e retenção em
lotes somente para `DELIVERED`. O adapter neutro separa falhas permanentes de autenticação/contrato
das falhas transitórias de throttling, timeout, transporte e indisponibilidade, evitando retries
inúteis e persistindo somente códigos seguros. O host também possui replay governado de dead-letter
com quarentena, probe externo, lock pessimista e auditoria append-only sem payload. Adapter específico,
inbox/audit corporativo e aceite operacional no sistema alvo
continuam gates residuais.

A aceitação deste ADR fecha a decisão de arquitetura. Ela não transforma esses
gates residuais em capacidade implementada e não altera o readiness global
`BLOCKED`.

## Alternativas rejeitadas

- transação, outbox ou effect executor dentro do engine;
- chamada síncrona a ERP/folha/broker durante a transação HTTP;
- tratar lote parcial como statement atômico;
- usar `SlotCardinality` para representar cardinalidade transacional;
- `ThreadLocal`, package state ou sessão de banco como contexto implícito;
- afirmar exactly-once distribuído a partir de unique constraint local;
- usar o Config Starter como coordenador da transação de negócio.
