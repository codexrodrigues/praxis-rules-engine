# Ponte governada para hosts Java

Este contrato é um candidato local da P2. A instalação Maven local não comprova
publicação nem adoção em hosts. Não há mudança no dialeto JSON Logic, corpus ou
contrato serializado de RuleSet 1.6.

## Fronteiras

`GovernedRuleEngineTemplate` reúne o compilador e o evaluator existentes. O cache
concorrente pertence à instância criada pelo host, indexado por
`(ruleSetKey, scopeKey)`. Não consulta publicação, tenant, banco ou relógio.
O host fornece a definição admitida, o registry executável e um scope explícito
que separe seus domínios/operações/tenants. Conteúdo igual reaproveita o plano;
conteúdo diferente é compilado antes da substituição atômica. Compilação inválida
lança o erro e não retorna o plano anterior para a nova solicitação.

O cache não é uma autoridade de ativação: o host deve serializar suas ativações,
evitar a readmissão acidental de snapshots antigos e chamar `invalidate`/`clear`
ao retirar scopes. Ele não retém fatos ou resultados de avaliação.

```java
var template = new GovernedRuleEngineTemplate(registry, RuleErrorCatalog.of(errorsByCode));
var plan = template.compile(admittedDefinition, authenticatedScopeKey);
var facts = PraxisFactTreeBuilder.from(preloadedContext)
        .put("referenceDate", explicitReferenceDate)
        .derive("itemCount", tree -> tree.path("items").size())
        .build();
var result = template.enforce(plan, facts, frozenNowUtc, explicitUserTimeZone);
```

`evaluate` conserva os cinco estados. `enforce` permite somente `ALLOW`, incluindo
a rejeição explícita de `NOT_APPLICABLE`, e lança `PraxisRuleViolationException`
nos demais estados sem converter erro técnico em `DENY`. O tempo e timezone são
obrigatoriamente explícitos por avaliação; contexto temporal inválido produz
o resultado técnico canônico do evaluator.

## Erros e diagnóstico

A projeção de causas foi corrigida após a revisão focal da P2. A exceção conserva
`RuleEvaluationResult.reasonCodes` e publica como violações apenas os bindings
causais da decisão consolidada. NOT_APPLICABLE/INCONCLUSIVE de um ramo anterior
não viram erro de campo quando outro ramo determina DENY. Quando o resultado
consolidado é NOT_APPLICABLE, os motivos terminais permanecem fail-closed.
Diagnósticos de execução completos continuam no resultado original do evaluator.

`RuleErrorDescriptor` define código atômico, campo público, mensagem pt-BR,
lineage pública e categoria do host. `RuleErrorCatalog` é uma porta funcional;
`MapRuleErrorCatalog` valida identidades e copia o índice. Os textos e lineage
devem ser seguros para publicação e nunca conter SQL, credenciais ou exceções.

A exceção aceita `(plan, result, catalog)`, um `SlotViolationDetail` direto ou
uma lista não vazia de detalhes diretos com a mesma decisão consolidada.
Adaptadores de host podem estendê-la, mantendo a classificação de status e os
diagnósticos canônicos. A lista é copiada, as razões são distintas e rejeições
de decisões diferentes não podem ser agregadas no construtor direto.
Preserva código, slot, binding, source, stage, decisão e campo de correção.
Erros do plano, sem binding avaliado, não recebem slot/source fictícios.
Código ausente do catálogo mantém a rejeição com mensagem genérica e sem campo
inventado. `toDiagnosticProperties` exclui facts,
outputs dos executores, stack traces e causas. O mapa e suas listas são imutáveis.
O adapter HTTP canônico fica no metadata starter.

`SlotViolationDetail.category` preserva a classificação do catálogo e assume
`BUSINESS_LOGIC` quando ausente. O construtor original de nove argumentos mantém
esse default. A exceção usa a primeira mensagem resolvida em `getMessage()` e
inclui categoria nas violações de `toDiagnosticProperties`. `getHttpStatus()`
sugere `409` quando todas as categorias são `CONFLICT` (case-insensitive) e `422`
nos demais casos; o engine permanece independente de frameworks HTTP.

## Captura de fatos

`PraxisFactTreeBuilder` captura records, propriedades de objetos Java, mapas
com chaves string, arrays, listas, sets, enum por `name`, números finitos e datas
ISO-8601. Preserva null explícito; `putIfNotNull` preserva ausência. Ordena chaves
e sets, preservando a ordem de listas. Retorna árvores destacadas. Não considera
escala decimal uma diferença semântica. Rejeita ciclos, valores JSON não nativos
e estruturas acima dos limites do runtime.

O host deve fornecer projeções acíclicas e já carregadas: o builder não faz
fetch nem resolve proxies JPA. Getters são acessados como parte da captura;
nunca forneça um grafo de entidades com associações lazy ou getters com efeitos.
`derive` recebe uma cópia e é apenas conveniência para fatos primitivos;
comparações governadas continuam na árvore do RuleSet.

`put` valida o novo nó sem copiar o acumulado; `build` aplica os limites à árvore
inteira. `putScalarBean` e `toScalarObjectNode` permitem capturar propriedades
escalares, coleções escalares e `@Embedded` simples sem invocar getters anotados
com associações JPA (`ManyToOne`, `OneToMany`, `OneToOne`, `ManyToMany`). Objetos
arbitrários são omitidos. As anotações são reconhecidas por nome, sem dependência
de persistência no artefato; getters escalares continuam devendo ser puros e o
host deve fornecer os valores já carregados.

`isWithinDateRange` e `hasDateRangeOverlap` usam limites inclusivos. Null em
um extremo significa intervalo aberto; intervalos invertidos são rejeitados.

## Diagnóstico de operandos nulos

`PraxisJsonLogicException.getNullOperandPaths()` fornece caminhos explícitos de
variáveis somente quando o runtime comprova que o erro de tipo veio desses
operandos nulos e os demais operandos são compatíveis. Não inclui valores nem
atribui variáveis locais de iteradores a roots do chamador. Uma lista vazia
significa origem não comprovada; o código `RULE_ARGUMENT_TYPE_INVALID` sozinho
não autoriza ignorar a falha. Divisão por zero e outros erros de tipo continuam
falhando. Essa evidência é diagnóstica: valores, truthiness, short-circuit,
códigos de erro e o corpus do dialeto permanecem os mesmos.

## Matriz e overrides

`EffectiveDatedMatrixMatcher<T>` recebe acessores de início/fim, critérios
ordenados por prioridade e um comparator final estável. Um valor null no
candidato é global; pedido null admite apenas candidatos globais naquela
dimensão. A seleção usa especificidade lexicográfica, início mais recente e
desempate final. Empate persistente é ambiguidade e falha, independentemente
da ordem da coleção. Data de referência nunca é inferida do relógio.

`TriStateOverrideResolver` interpreta S/N/I e PERMITIR/VEDAR/HERDAR, aceitando
null como herança. Valores desconhecidos, inclusive string vazia, são inválidos.
O filho prevalece sobre a matriz. Dupla herança retorna `Optional.empty`, sem
autorizar implicitamente. Overrides numéricos preservam zero e ausência.
