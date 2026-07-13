# P2F-ADR-07 — snapshot imutável, ETag e rollback

- Estado: aceito para QL-03
- Data: 2026-07-13
- Classificação: `arquitetural`, `transversal` e `contrato-publico`

## Decisão

`praxis-rules-engine` é o owner do envelope runtime-neutro
`PublishedRuleSnapshot` e da preparação determinística para
`CompiledRuleSnapshot`. `praxis-config-starter` é o owner exclusivo da
persistência append-only, publicação, head ativo, concorrência e rollback.
Hosts consomem snapshots publicados e não mantêm um store editável paralelo.

O snapshot contém contrato próprio versionado, identidade e revisão de
publicação, escopo tenant/environment, host requerido, validade UTC, RuleSet
completo, provenance de definições e evidências seguras de approval. O compiler
normaliza slots, bindings, sources e approvals antes de calcular o hash
canônico e compila o `RuleDecisionPlan` antes que o control plane possa ativá-lo.

## Dois validators distintos

O contrato separa deliberadamente dois validators HTTP:

1. `snapshotContentHash`: SHA-256 da representação canônica normalizada. É
   imutável, identifica o conteúdo e é o ETag de leituras do snapshot histórico;
2. `headEtag`: token opaco rotacionado em toda publicação ou rollback. Governa
   `If-Match` do head mutável e impede o problema ABA.

Reutilizar o content hash como ETag do head permitiria que um writer antigo
observasse v1, perdesse v2 e voltasse a casar depois de `v1 → v2 → v1`. Por
isso, rollback para conteúdo anterior sempre produz um novo `headEtag`.

## Publicação e concorrência

- snapshot é append-only e nunca recebe `PATCH`;
- primeira publicação exige `If-None-Match: *` e ausência de head;
- publicação subsequente exige `If-Match` forte do `headEtag` observado;
- definição, provenance, approvals, compatibilidade e plano completo são
  validados antes da persistência;
- snapshot, evento e novo head são gravados na mesma transação;
- conflito nunca usa last-write-wins;
- leitura histórica usa cache imutável; leitura do head exige revalidação.

## Rollback

Rollback move o head para um snapshot anterior já publicado no mesmo escopo e
RuleSet. Ele exige `If-Match` forte do head atual, cria evento append-only,
incrementa a revisão de ativação e rotaciona o `headEtag`. O snapshot alvo não é
copiado nem modificado.

## Consequências

O Config Starter passa a exigir Java 21 para consumir diretamente o contrato
canônico do engine. Essa é uma mudança incompatível de baseline, aceita na fase
pré-estável para remover a duplicação de DTOs e alinhar o backend Java da
plataforma. Consumidores precisam executar em Java 21 antes de adotar a release
do starter que publicar snapshots.
