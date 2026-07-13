# Next Agent Handoff

> Atualizacao 2026-07-13: o backlog de runtime deste handoff foi implementado e o ownership Git foi definido em `codexrodrigues/praxis-rules-engine`. O proximo trabalho e concluir a governanca de release, manter corpus/catalogo sincronizados e integrar um host somente depois de existir um artefato publico; nao forcar `praxis-api-quickstart`.

## Contexto

O monorepo ja convergiu o lado Angular para Json Logic como contrato canônico de condição.

As etapas ja fechadas no `praxis-ui-angular` incluem:

- contrato de `CompositionLink.condition`
- runtime TS canônico
- validacao semantica canônica
- alinhamento de `dynamic-form`, `table`, `page-builder`, `visual-builder` e `ai`
- corpus de conformidade canônico

O runtime Java canonico existe e consome o corpus compartilhado. Catalogo machine-readable, limites de resultado e subset regex limitado estao cobertos pelos dois runtimes. O gap remanescente e concluir a governanca de release e validar um consumidor contra o artefato publicado, sem depender do repositorio Maven local.

## Fonte de Verdade que Deve Ser Lida Primeiro

1. [rfc-json-logic-semantics.md](/D:/Developer/praxis-plataform/praxis-ui-angular/projects/praxis-core/docs/rfc-json-logic-semantics.md)
2. [conformance-fixtures.json](/D:/Developer/praxis-plataform/praxis-ui-angular/docs/json-logic-conformance/conformance-fixtures.json)
3. [praxis-json-logic.service.ts](/D:/Developer/praxis-plataform/praxis-ui-angular/projects/praxis-core/src/lib/services/praxis-json-logic.service.ts)
4. [json-logic-runtime-implementation-plan.md](/D:/Developer/praxis-plataform/praxis-rules-engine/docs/json-logic-runtime-implementation-plan.md)
5. [angular-integration-map.md](/D:/Developer/praxis-plataform/praxis-rules-engine/docs/angular-integration-map.md)

## O Que Nao Deve Ser Feito

- nao implementar o runtime definitivo em `praxis-metadata-starter`
- nao reabrir DSL textual como contrato publico
- nao criar corpus Java separado
- nao declarar paridade FE/BE sem consumir o corpus compartilhado

## Primeiro Backlog Recomendado

1. criar os modelos Java de avaliacao/validacao
2. criar o registry Java de operadores
3. implementar `var`, comparacoes, `and`, `or`, `if`, `in`
4. implementar operadores custom baseline
5. implementar suite Java consumindo o corpus compartilhado

## Evidencias de Que a Implementacao Esta Certa

- a suite Java executa o corpus canônico
- os codigos de validacao batem com o runtime TS
- os operadores temporais usam `nowUtc` e `userTimeZone`
- os contextos multi-root respeitam `allowImplicitRoot`

## Escopo Deliberadamente Fora do Handoff

- workflow engine
- decisionRef tipado de negocio além do runtime Json Logic
- endpoints HTTP de rules engine
- migração de DSL textual legado
