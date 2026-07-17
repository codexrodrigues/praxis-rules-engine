# Angular Integration Map for Json Logic

> Atualizacao 2026-07-13: a paridade base foi implementada no engine Java. O Angular continua owner normativo e consumidor frontend; nenhuma lib Angular importa o JAR. Mudancas futuras exigem teste focal, conformidade, build do core e consumidor direto.

## Objetivo

Mapear, do lado Angular, quais classes, serviços, componentes e contratos ja dependem do dialeto canonico de Json Logic. Este documento existe para que o runtime Java seja implementado olhando para as superfícies corretas.

## Runtime e Contrato Canonicos no Angular

### Runtime principal

- [praxis-json-logic.service.ts](../../praxis-ui-angular/projects/praxis-core/src/lib/services/praxis-json-logic.service.ts)

Responsabilidades atuais:

- avaliacao
- truthiness canônica
- operador `var`
- comparacoes
- `and`, `or`, `if`, `in`
- operadores custom Praxis
- validacao semantica canônica

### Modelos e tipos

- [json-logic.model.ts](../../praxis-ui-angular/projects/praxis-core/src/lib/models/rules/json-logic.model.ts)
- [json-logic-runtime.model.ts](../../praxis-ui-angular/projects/praxis-core/src/lib/models/rules/json-logic-runtime.model.ts)

### Corpus de conformidade

- [conformance-fixtures.json](../../praxis-ui-angular/docs/json-logic-conformance/conformance-fixtures.json)
- [praxis-json-logic-conformance.spec.ts](../../praxis-ui-angular/projects/praxis-core/src/lib/services/praxis-json-logic-conformance.spec.ts)

## Consumidores Angular que Ja Dependem do Contrato

### 1. Dynamic Form

#### Runtime de regras

- [form-rules.service.ts](../../praxis-ui-angular/projects/praxis-dynamic-form/src/lib/services/form-rules.service.ts)

#### Editor de regras

- [rules-editor.component.ts](../../praxis-ui-angular/projects/praxis-dynamic-form/src/lib/rules-editor/rules-editor.component.ts)
- [rules-editor.component.spec.ts](../../praxis-ui-angular/projects/praxis-dynamic-form/src/lib/rules-editor/rules-editor.component.spec.ts)

#### Contratos de layout

- [form-layout.model.ts](../../praxis-ui-angular/projects/praxis-core/src/lib/models/form/form-layout.model.ts)

Campos relevantes:

- `condition`
- `hiddenCondition`
- `visibilityCondition`

### 2. Table

#### Runtime/consumo de condicoes

- [praxis-table.ts](../../praxis-ui-angular/projects/praxis-table/src/lib/praxis-table.ts)
- [praxis-table-toolbar.ts](../../praxis-ui-angular/projects/praxis-table/src/lib/praxis-table-toolbar.ts)

#### Editor de regras

- [table-rules-editor.component.ts](../../praxis-ui-angular/projects/praxis-table/src/lib/rules-editor/table-rules-editor.component.ts)
- [table-rules-editor.component.spec.ts](../../praxis-ui-angular/projects/praxis-table/src/lib/rules-editor/table-rules-editor.component.spec.ts)

#### Modelos relevantes

- [table-config-v2.model.ts](../../praxis-ui-angular/projects/praxis-core/src/lib/models/table-config-v2.model.ts)

Campos relevantes:

- `disabledCondition`
- `condition`
- `visibleWhen`

### 3. Composition Links / Dynamic Page

#### Executor de link

- [link-executor.service.ts](../../praxis-ui-angular/projects/praxis-core/src/lib/composition/link-executor.service.ts)

#### Modelo de composicao

- [composition-link.model.ts](../../praxis-ui-angular/projects/praxis-core/src/lib/models/composition-link.model.ts)

Campos relevantes:

- `CompositionLink.condition`
- `availableRoots` de composicao:
  - `source`
  - `event`
  - `payload`
  - `state`
  - `context`
  - `meta`

### 4. AI

#### Validacao de respostas

- [ai-response-validator.service.ts](../../praxis-ui-angular/projects/praxis-ai/src/lib/core/services/ai-response-validator.service.ts)
- [ai-response-validator.service.spec.ts](../../praxis-ui-angular/projects/praxis-ai/src/lib/core/services/ai-response-validator.service.spec.ts)

Papel:

- validar condicao recebida da IA contra o runtime canônico
- impedir persistencia de shape invalido

### 5. Schema normalizer / metadata-driven core

- [schema-normalizer.service.ts](../../praxis-ui-angular/projects/praxis-core/src/lib/services/schema-normalizer.service.ts)
- [field-definition-mapper.ts](../../praxis-ui-angular/projects/praxis-core/src/lib/helpers/field-definition-mapper.ts)
- [component-metadata.interface.ts](../../praxis-ui-angular/projects/praxis-core/src/lib/models/component-metadata.interface.ts)

Papel:

- transportar `hiddenCondition` em Json Logic como shape canônico

## O Que o Runtime Java Precisa Reproduzir

### Semantica de validacao

O runtime Java nao deve fazer somente avaliacao. Ele precisa expor validacao semantica equivalente porque:

- AI validator depende disso
- editores manuais dependem disso
- surfaces metadata-driven nao podem validar apenas “rodando a regra”

### Contextos que ja existem no Angular

#### Form

- roots tipicas:
  - `form`
- `allowImplicitRoot = true`

#### Table

- roots tipicas:
  - `row`
  - `computed`
- `allowImplicitRoot` varia por superficie

#### Composition links

- roots obrigatorias:
  - `source`
  - `event`
  - `payload`
  - `state`
  - `context`
  - `meta`
- `allowImplicitRoot = false`

## Componentes Angular Envolvidos no Processo de Authoring

Estes componentes nao devem ser reimplementados no backend, mas precisam guiar o que o backend aceita:

- [rules-editor.component.ts](../../praxis-ui-angular/projects/praxis-dynamic-form/src/lib/rules-editor/rules-editor.component.ts)
- [table-rules-editor.component.ts](../../praxis-ui-angular/projects/praxis-table/src/lib/rules-editor/table-rules-editor.component.ts)
- [columns-config-editor.component.ts](../../praxis-ui-angular/projects/praxis-table/src/lib/columns-config-editor/columns-config-editor.component.ts)
- [rule-editor.component.ts](../../praxis-ui-angular/projects/praxis-visual-builder/src/lib/components/rule-editor.component.ts)
- [rule-definition.component.ts](../../praxis-ui-angular/projects/praxis-visual-builder/src/lib/components/rule-definition.component.ts)

O backend precisa aceitar o mesmo AST que esses componentes produzem.

## Anti-Patterns que o Runtime Java Nao Deve Reintroduzir

- aceitar string DSL textual como caminho nominal
- aceitar root implicita ambigua em composicao
- usar regex/locale/timezone do processo sem contrato explicito
- divergir dos codigos de validacao do runtime TS
- criar um corpus de teste diferente do corpus canônico

## Criterio de Sucesso da Paridade

Paridade FE/BE so pode ser declarada quando:

- o mesmo corpus gera o mesmo `value`
- o mesmo corpus gera o mesmo `truthy`
- o mesmo corpus gera os mesmos codigos de validacao
- novos operadores entram apenas com extensao do corpus e das duas suites
