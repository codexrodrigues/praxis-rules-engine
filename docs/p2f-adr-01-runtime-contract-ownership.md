# P2F-ADR-01 — ownership do contrato runtime

- Estado: `ACCEPTED_FOR_QL_02`
- Data: 2026-07-13
- Classificação: `arquitetural` e `transversal`
- Breaking change atual: nenhum; decisão documental

## Contexto

RuleSet, plano, bindings e resultado precisam ser consumidos pelo control
plane e pelos hosts sem DTOs duplicados. Colocá-los no Quickstart criaria um
contrato de exemplo; colocá-los no Config Starter faria o data plane depender
de Spring/JPA; criar imediatamente outro artefato aumentaria governança sem uma
segunda implementação comprovada.

## Decisão

`praxis-rules-engine` é owner do contrato Java runtime-neutro e da sua
validação, compilação e avaliação. O mesmo JAR puro poderá publicar packages
intencionais para:

- identidade e composição de RuleSet;
- slots, bindings, executor refs e policies;
- `RuleDecisionPlan` compilado;
- `RuleEvaluationResult`, diagnostics e compatibility requirements.

Isso não transfere o control plane para o engine:

| Responsabilidade | Owner |
| --- | --- |
| definição, aprovação, publicação, timeline e materialização | `praxis-config-starter` |
| envelope de snapshot, tenant/environment, ETag, head ativo e rollback | `praxis-config-starter` |
| modelo runtime-neutro, planner e evaluator | `praxis-rules-engine` |
| facts, autorização, registry Java, cache e compatibilidade do host | host de domínio |
| transação, effects e persistência de negócio | host/application service |
| schemas, actions, surfaces e capabilities de recurso | `praxis-metadata-starter` |
| prova operacional | `praxis-api-quickstart` |

O Config Starter pode depender do engine para validar o conteúdo runtime do
snapshot. O engine nunca depende do Config Starter, Spring, banco ou host.
Hosts declaram dependência direta no engine; não dependem apenas de trânsito
pelo Config Starter.

## Packages candidatos

```text
org.praxisplatform.rules.contract
org.praxisplatform.rules.plan
org.praxisplatform.rules.runtime
org.praxisplatform.rules.jsonlogic
```

Os nomes são boundaries planejadas, não autorização para expor tipos antes dos
goldens. Nenhum contrato compartilhável nasce em `com.example.*`.

## Extração futura

Não será criado `praxis-rules-contracts` em QL-02. A extração será reavaliada
somente se houver pelo menos um destes sinais:

- consumidores precisam do contrato sem o evaluator e o custo transitivo é
  material;
- versionamento do contrato precisa evoluir independentemente do runtime;
- outro runtime backend exige o mesmo modelo sem depender do JAR Java;
- dependências do engine deixam de ser mínimas.

Uma extração preservará os packages/JSON aprovados e terá plano de migração;
não criará duas fontes públicas paralelas.

## Consequências

- Config e hosts reutilizam os mesmos tipos e validações;
- o engine continua stateless, thread-safe e sem I/O;
- snapshot governado não vira um detalhe opaco do evaluator;
- Quickstart permanece consumidor e laboratório;
- a futura introdução dos tipos será `contrato-publico` e exigirá SemVer,
  build focal do engine e builds diretos de Config Starter e Quickstart.
