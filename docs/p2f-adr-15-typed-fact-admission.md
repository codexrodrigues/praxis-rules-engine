# ADR-15 — Admissão estrutural de fatos por operação

Estado de implementação em 05/11/2026. Esta etapa do G3 desenvolve o candidato executável do contrato de fatos definido em G1.

O Engine é dono da análise pura de schemas e expressões. O host registra a identidade da operação, o provider, a revisão executável e o contrato de host, além do schema fechado. O Config consome a mesma análise estrutural ao preparar candidatos. Os descritores editoriais e a autorização dos dados pertencem aos respectivos produtores.

O schema aceita o perfil G1 de objetos fechados ou mapas tipados, arrays homogêneos, primitivas, nulabilidade explícita, limites, enum textual e formatos canônicos. Referências remotas, composição arbitrária de JSON Schema e palavras-chave desconhecidas são rejeitadas. O compilador verifica folhas globais e locais de iteradores, incluindo o acumulador de reduce. Os contratos de executores Java são declarados por identidade e versão exatas pelo host.

A admissão tipada produz um plano vinculado ao schema e à operação. A avaliação valida uma cópia limitada dos fatos antes de chamar qualquer binding. A ausência estrutural gera diagnóstico técnico. Um conceito explicitamente null é distinguido de uma propriedade ausente. A expressão continua responsável pelo predicado de negócio.

O desenvolvimento por família usa candidatos isolados e testes com a coordenada candidata. A troca do contrato ativo do Ergon exige a migração do conjunto completo prevista no blueprint. A integração pública e o corte operacional seguem seus gates próprios.

Impacto nos consumidores diretos envolve o schema publicado pelo Config, os providers e o registry do Ergon, a admissão de snapshots e os testes de Quickstart. A análise tipada acrescenta uma fronteira de admissão ao plano. A prova temporal também identificou a correção do corpus descrita a seguir.

Atualização da prova temporal: o replay da AST de Funções encontrou normalização
indevida de datas inexistentes no TypeScript. O parser canônico foi corrigido e
quatro contracasos foram adicionados ao corpus compartilhado. O dialeto continua
`1.0`, mas o hash do corpus passa a
`56821F63B4E0CCFD7851D19B10AE9835A6BE6024F8752794C9279D5A650CE6B9`.
Definições e snapshots com o hash anterior exigem nova compilação e admissão;
aprovações anteriores não migram por troca de token.

A revisão do escopo de `reduce` também corrigiu o acumulador inicial omitido
no Java para seguir o owner TypeScript: o valor inicial é null. O enum interno
de ausência não pode aparecer como texto no frame local. Dois casos de
conformidade provam o null inicial e que o valor padrão de `var` não o substitui.

## Uso do candidato

```java
RuleFactSchema schema = RuleFactSchema.compile(operationSchema);
RuleFactContract contract = new RuleFactContract(
        "my-host/2.0", "orders.create", "orders:2", schema, javaInputs);
TypedRuleDecisionPlan admitted = TypedRuleDecisionPlan.compile(definition, registry, contract);
TypedRuleEvaluationResult evidence = new PraxisRuleSetEngine(registry).evaluate(
        admitted, "my-host/2.0", "orders.create", "orders:2", facts, nowUtc, zone);
```

O control plane compila o plano canônico e chama `contract.validate(plan)`;
somente o host usa `TypedRuleDecisionPlan.compile` com registry executável.
Snapshots usam `compileForOperation(snapshot, contract)`. Nenhuma dessas APIs
resolve operação HTTP, consulta tenant ou escolhe um schema enviado pelo cliente.
A seleção de operação, provider e contrato é responsabilidade do registry
autoritativo do host. O token de host, sozinho, não prova compatibilidade.

`JavaInputs` declara chave, revisão e tipos aceitos para cada entrada. Se uma
folha ou ancestral permite null, a declaração deve incluir `null`. Presença
obrigatória e nulabilidade são verificações distintas. Templates do produto
também são analisados quando uma substituição efetiva está em uso.

O `admissionDigest` vincula `planDigest` e `factContractDigest`. O resultado
tipado mantém esses três identificadores e as coordenadas da invocação para
replay, sem incluir fatos ou schema no diagnóstico. Chaves dinâmicas de mapas
e nomes inesperados dos dados são representados por `*` nos caminhos de erro.

## Envelope e limites

Compilação de schema: profundidade 32, 4.096 shapes e 1.024.000 bytes. Análise
de expressão: até 50.000 visitas e 256 variantes estruturais. Reduções devem
preservar a estrutura do acumulador. Extensões de operador sem contrato tipado
e caminhos dinâmicos de `jsonGet`/`hasKey` falham na admissão.

Fatos continuam sujeitos ao orçamento nativo conjunto: 10.000 nós,
256.000 bytes e os demais limites de `JsonLogicLimits.DEFAULT`. `maxItems`
por coleção não garante que a soma de todas as coleções cabe no snapshot.
O provider deve provar exaustão e ordenação e falhar quando não puder produzir
uma população completa no envelope. Não truncar nem converter falha em DENY.

`RuleFactContractException` identifica o erro estrutural por código e caminho.
Deriva de provider durante avaliação tipada produz `TECHNICAL_ERROR` com
`FACTS_SCHEMA_INVALID` e nenhum binding executado. Invocação divergente falha
antes de avaliar. As entradas sem schema preservam o contrato defensivo
existente; não constituem prova de admissão tipada nem autorizam ativação 2.0.
