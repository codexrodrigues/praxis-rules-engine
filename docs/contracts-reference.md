# Referencia de contratos

## Regra e compatibilidade

`RuleSetDefinition` contem a identidade (`RuleSetRef`), roots permitidas, slots, bindings, `RuleRuntimeCompatibility` e `RuleFailPolicy`. Toda definicao e imutavel e declara as coordenadas exatas de engine, dialeto JSON Logic e SHA-256 do corpus.

`DecisionBinding` representa uma unidade executavel. Ele declara slot, origem, dependencias, ordem, facts obrigatorios e um `RuleExecutorRef`:

- `RuleExecutorRef.jsonLogic(expression)` usa JSON Logic e exige `falseDecision` (`DENY`, `NOT_APPLICABLE` ou `INCONCLUSIVE`) e `falseReasonCode`.
- `RuleExecutorRef.java(key, version)` chama um executor confiavel; a decisao vem do executor e o binding nao pode declarar resultado falso.

## Slots e composicao

| Contrato | Regra |
| --- | --- |
| `SlotCardinality.SINGLE` | Exatamente uma decisao habilitada; exige `SINGLE_RESULT`. |
| `SlotCardinality.MULTIPLE` | Permite composicao explicita; exige `DENY_OVERRIDES`. |
| `CompositionPolicy.AUGMENT` | Adiciona decisao independente. |
| `RESTRICT` | So pode tornar o resultado mais restritivo. |
| `PARAMETERIZE` | Altera parametros declarados do produto. |
| `REPLACE_EXACT` | Substitui apenas slot explicitamente permitido pelo produto. |

`DecisionStage` define a ordem fechada do plano. Dependencias devem apontar para bindings existentes em estagio anterior ou no mesmo estagio permitido; ciclos, referencias futuras e cardinalidade invalida sao erros de planejamento.

## Snapshots e resultados

`PublishedRuleSnapshot` e um envelope imutavel com escopo, revisao de publicacao, janela de validade, proveniencia, aprovacoes e RuleSet. `PraxisRuleSnapshotCompiler` valida a versao do envelope, o contrato do host e a proveniencia antes de produzir `CompiledRuleSnapshot`.

`RuleEvaluationResult` devolve decisao consolidada, resultados ordenados por binding, reason codes, digests, coordenadas, implementacoes e propostas tipadas. O resultado e uma evidencia deterministica; autorizacao, transacao, persistencia e efeitos continuam no host.

## Politica para falhas nao-negociais

`RuleFailPolicy` e aplicada pelo host, sem alterar a decisao original:

- `FAIL_CLOSED`: bloqueia operacao protegida.
- `RETURN_INCONCLUSIVE`: devolve resultado para fluxo consultivo.
- `APPROVED_BASELINE_FALLBACK`: permite somente fallback previamente aprovado e observavel.

## Compatibilidade publicada

| Artefato/linha | Engine contract | Dialeto | Estado |
| --- | --- | --- | --- |
| `0.1.0-beta.12` | `1.2` | `1.0` | Linha anterior com propostas de transformação tipadas. |
| `0.1.0-beta.13` | `1.3` | `1.0` | Marco público anterior de `RuleExtensionTrust`, com prova downstream Central-only. |
| `0.1.0-beta.14` | `1.4` | `1.0` | Publicada, mas não recomendada: hash anunciado diverge do corpus empacotado. |
| `0.1.0-beta.15` | `1.4` | `1.0` | Publicada segundo o registro de release readiness; corrige corpus/hash e `var`, mas falha de LF/CRLF no Windows impede baseline verde. |
| `0.1.0-beta.16` | `1.5` | `1.0` | Publicada pelo workflow oficial; CI Linux/Windows e smoke neutro Central-only aprovados. Adoção Config/hosts pendente; ver release readiness. |

O SHA-256 do corpus e parte de `RuleRuntimeCompatibility`; consumidores nao devem aceitar RuleSets com coordenadas divergentes. O contrato efetivo de um consumidor deve ser provado contra a coordenada Maven publicada, nao inferido de uma fonte candidata em `main`.

## Contexto Java e outputs diretos (fonte 1.5)

`RuleExecutorContext` inclui `directDependencyOutputs`, mapa imutável por
bindingKey com cópias defensivas de JSON nativo finito. Só arestas diretas
PRODUCT/JAVA em DOMAIN/POST entre ambos os endpoints fornecem outputs ALLOW.
Ausência Java omite a chave; JSON null preserva a chave. Facts e JSON Logic
não recebem esses valores. `RuleDecisionPlan.directDependencyOutputKeys`
compartilha a classificação derivada com evaluator e admissão no host.

O [ADR-14](p2f-adr-14-direct-dependency-outputs.md) define trust, cinco estados,
compatibilidade, budgets e erros sem resultados parciais. Consumidores devem
adotar uma release oficial 1.5 e composições aprovadas compatíveis; esta fonte
não é prova de disponibilidade Maven. O construtor do contexto mudou nesta
linha beta limpa, sem overload de compatibilidade que esconda o novo canal.
