# Json Logic Runtime Implementation Plan

> Atualizacao 2026-07-13: o runtime Java deixou de ser plano. Engine, registry introspectavel, operadores publicados, missing sentinel, limites, path fechado, regex segura, tempo congelado, corpus empacotado e testes focais estao implementados. O estado executavel esta em `architecture.md` e `operator-conformance-matrix.md`; ownership Git/release e integracao produtiva continuam externos.

## Objetivo

Implementar no `praxis-rules-engine` o runtime Java canonico de Json Logic da plataforma Praxis, com paridade comportamental com o runtime TypeScript ja existente em:

- [praxis-json-logic.service.ts](/D:/Developer/praxis-plataform/praxis-ui-angular/projects/praxis-core/src/lib/services/praxis-json-logic.service.ts)

Este documento existe para que a implementacao Java siga o contrato de plataforma correto, em vez de introduzir mais um dialeto local.

## Estado Atual da Plataforma

### Ja existe

- dialeto normativo publicado em:
  - [rfc-json-logic-semantics.md](/D:/Developer/praxis-plataform/praxis-ui-angular/projects/praxis-core/docs/rfc-json-logic-semantics.md)
- runtime TypeScript canonico em:
  - [praxis-json-logic.service.ts](/D:/Developer/praxis-plataform/praxis-ui-angular/projects/praxis-core/src/lib/services/praxis-json-logic.service.ts)
- validacao semantica canonica em:
  - [praxis-json-logic.service.ts](/D:/Developer/praxis-plataform/praxis-ui-angular/projects/praxis-core/src/lib/services/praxis-json-logic.service.ts)
- corpus de conformidade versionado em:
  - [conformance-fixtures.json](/D:/Developer/praxis-plataform/praxis-ui-angular/docs/json-logic-conformance/conformance-fixtures.json)
- consumo do corpus em teste do lado TypeScript:
  - [praxis-json-logic-conformance.spec.ts](/D:/Developer/praxis-plataform/praxis-ui-angular/projects/praxis-core/src/lib/services/praxis-json-logic-conformance.spec.ts)

### Implementado nesta linha

- runtime Java canonico de Json Logic
- suite de conformidade Java consumindo o mesmo corpus
- registry backend publicado com os mesmos operadores do runtime TS

### Fechado na rodada de endurecimento

- `operatorCatalog` machine-readable no corpus, validado integralmente pelos dois runtimes
- limites estruturais e de bytes aplicados a expressoes e resultados publicos
- subset regex comum sem grupos, alternancia, backreferences ou quantificadores ilimitados

### Ainda pendente para publicacao

- ownership Git, workflow oficial de release e validacao downstream a partir de artefato publico

## Fronteira Canonica

O runtime Java deve nascer neste modulo:

- [praxis-rules-engine](/D:/Developer/praxis-plataform/praxis-rules-engine/README.md)

Nao implementar o runtime definitivo em:

- `praxis-metadata-starter`
- `praxis-config-starter`
- `praxis-api-quickstart`

Esses modulos podem consumir o runtime ou hospedá-lo mais tarde, mas nao devem virar a fonte canonica do dialeto.

## Recursos Obrigatorios da V1

### 1. Avaliacao

O runtime Java precisa expor avaliacao equivalente a:

- `evaluate(expression, data, options)`
- `evaluateResult(expression, data, options)`
- `matches(condition, data, options)`

### 2. Validacao semantica

O runtime Java precisa expor validacao equivalente a:

- `validate(expression, options)`
- `validateResult(expression, options)`

Minimos obrigatorios:

- shape invalido
- operador desconhecido
- aridade invalida
- root invalida para o contexto
- root implicita ambigua
- tipo invalido de argumento

### 3. Registry de operadores

O modulo Java precisa ter um registry canonico equivalente ao registry embutido hoje no runtime TS.

Operadores baseline obrigatorios:

- nativos:
  - `var`
  - `==`
  - `===`
  - `!=`
  - `!==`
  - `>`
  - `>=`
  - `<`
  - `<=`
  - `!`
  - `!!`
  - `and`
  - `or`
  - `if`
  - `in`
- custom Praxis:
  - `contains`
  - `startsWith`
  - `endsWith`
  - `matches`
  - `isBlank`
  - `len`
  - `jsonGet`
  - `hasKey`
  - `isToday`
  - `inLast`
  - `weekdayIn`

### 4. Conformidade FE/BE

O runtime Java deve consumir o mesmo corpus versionado em:

- [conformance-fixtures.json](/D:/Developer/praxis-plataform/praxis-ui-angular/docs/json-logic-conformance/conformance-fixtures.json)

Sem isso, a plataforma nao pode declarar paridade executavel FE/BE.

## Proposta de Estrutura de Codigo

### Pacotes sugeridos

```text
org.praxisplatform.rules.jsonlogic
org.praxisplatform.rules.jsonlogic.model
org.praxisplatform.rules.jsonlogic.operators
org.praxisplatform.rules.jsonlogic.validation
org.praxisplatform.rules.jsonlogic.conformance
```

### Classes principais sugeridas

#### Runtime

- `PraxisJsonLogicEngine`
  - equivalente funcional ao `PraxisJsonLogicService` do Angular
  - avalia expressoes
- `PraxisJsonLogicMatchService`
  - facade pequena para `matches`
  - opcional se o engine ja cobrir isso com clareza

#### Modelos

- `JsonLogicExpression`
- `JsonLogicEvaluationOptions`
- `JsonLogicEvaluationContext`
- `JsonLogicEvaluationResult`
- `JsonLogicValidationOptions`
- `JsonLogicValidationResult`
- `JsonLogicValidationIssue`
- `JsonLogicIssueCode`

#### Operadores

- `PraxisJsonLogicOperator`
- `PraxisJsonLogicOperatorRegistry`
- `DefaultPraxisJsonLogicOperators`

#### Validacao

- `PraxisJsonLogicValidator`
  - pode ser classe propria ou responsabilidade do engine
  - nao deve divergir do runtime TS

#### Conformidade

- `JsonLogicConformanceFixtureLoader`
- `JsonLogicConformanceEvaluationCase`
- `JsonLogicConformanceValidationCase`
- `PraxisJsonLogicConformanceTest`

### Observacao de desenho

Se validacao e avaliacao ficarem em classes separadas, o registry e a resolucao de path devem continuar compartilhados. Nao duplique semantica.

## Semantica que Deve Ser Copiada do Runtime TS

### Truthiness

Seguir exatamente o runtime TypeScript:

- `undefined` ou ausente -> `false`
- `null` -> `false`
- `false` -> `false`
- `0` -> `false`
- `""` -> `false`
- `[]` -> `false`
- `{}` -> `true`

### Roots e ambiguidade

O runtime Java precisa respeitar:

- `availableRoots`
- `defaultRoot`
- `allowImplicitRoot`

Se o contexto tiver multiplas roots validas e `allowImplicitRoot = false`, path simples deve falhar com o equivalente de:

- `RULE_CONTEXT_AMBIGUOUS`

### Temporal

O runtime Java precisa suportar:

- `nowUtc`
- `userTimeZone`

E reproduzir a mesma semantica para:

- `isToday`
- `inLast`
- `weekdayIn`

## Dialeto de Path

O runtime Java nao deve inventar JSONPath completo.

Ele deve reproduzir o mesmo subconjunto do runtime TS:

- `a.b`
- `a.b[0]`
- `a["key"]`
- `$.a.b`

## Diagnosticos e Codigos

Os codigos de validacao precisam permanecer alinhados com o contrato atual do core:

- `RULE_SHAPE_INVALID`
- `RULE_OPERATOR_UNKNOWN`
- `RULE_ARITY_INVALID`
- `RULE_PATH_INVALID`
- `RULE_CONTEXT_AMBIGUOUS`
- `RULE_ROOT_UNKNOWN`
- `RULE_ARGUMENT_TYPE_INVALID`

Se o backend introduzir um codigo novo, o contrato do core e o corpus de conformidade devem ser atualizados no mesmo ciclo.

## Sequencia Recomendada de Implementacao

1. modelos Java de avaliacao e validacao
2. registry de operadores
3. suporte a `var`, comparacoes, `and`, `or`, `if`, `in`
4. suporte a `contains`, `startsWith`, `endsWith`, `matches`, `isBlank`, `len`
5. suporte a `jsonGet`, `hasKey`
6. suporte temporal: `isToday`, `inLast`, `weekdayIn`
7. validador semantico
8. teste de conformidade consumindo o corpus compartilhado

## Criterios de Conclusao da V1

O runtime Java so pode ser considerado pronto quando:

- consome o corpus de conformidade compartilhado
- reproduz os mesmos resultados de avaliacao
- reproduz os mesmos codigos de validacao
- nao depende de locale implicita
- nao depende de timezone do processo
- nao aceita DSL textual como atalho

## Fora de Escopo Desta V1

- DSL textual legado
- migradores de string DSL para Json Logic
- motor de workflow
- decisao tipada de negocio mais ampla que Json Logic
- publicacao de endpoints de regra antes de haver runtime estavel
