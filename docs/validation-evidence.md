# Evidencia de validacao

Esta pagina mapeia testes existentes para os invariantes que sustentam o
contrato. Ela descreve cobertura de codigo; nao substitui a execucao dos gates
de release nem prova consumo de uma coordenada ainda nao publicada.

```mermaid
flowchart TB
  Core["Invariantes do runtime"]
  Json["JSON Logic\nparidade, limites e diagnosticos"]
  Plan["RuleSet\nDAG, composicao e decisoes"]
  Snapshot["Snapshots\nhash, compatibilidade e planning"]
  Trust["Extensoes protegidas\ntrust e anti-substituicao"]
  Transform["Transformacoes tipadas\nproposta sem mutar facts"]

  Core --> Json
  Core --> Plan
  Core --> Snapshot
  Core --> Trust
  Core --> Transform

  Json --> JsonTest["PraxisJsonLogicConformanceTest\nPraxisJsonLogicEngineTest"]
  Plan --> PlanTest["PraxisRuleSetEngineTest"]
  Snapshot --> SnapshotTest["PraxisRuleSnapshotCompilerTest"]
  Trust --> TrustTest["ProtectedExtensionTrustContractTest\nRuleImplementationRefJsonTest"]
  Transform --> TransformTest["TypedTransformationContractTest"]
```

## O que cada grupo demonstra

| Grupo | Evidencia coberta | Testes principais |
| --- | --- | --- |
| Dialeto JSON Logic | Corpus Angular empacotado, catalogo de operadores, erros estruturados, missing vs. `null`, regex, limites, tempo e determinismo. | `PraxisJsonLogicConformanceTest`, `PraxisJsonLogicEngineTest` |
| Plano e decisao | Ordenacao DAG, ciclos, roots, versoes de executor, precedencia de `DENY`, `NOT_APPLICABLE`, `INCONCLUSIVE` e bloqueio de intents. | `PraxisRuleSetEngineTest` |
| Snapshot | Canonicalizacao, hash de conteudo, contrato do host, proveniencia e registry somente de planejamento. | `PraxisRuleSnapshotCompilerTest` |
| Extensao de cliente | Default-deny, atestacao obrigatoria, guard protegido, digest com trust e rejeicao de substituicao de artefato. | `ProtectedExtensionTrustContractTest`, `RuleImplementationRefJsonTest` |
| Transformacao | Proveniencia, ausencia vs. `null`, schema/path, conflito, limites e ausencia de mutacao dos facts. | `TypedTransformationContractTest` |

## Gates e fronteiras de prova

```mermaid
flowchart LR
  Unit["Testes unitarios\ncontrato e runtime"] --> Verify["mvn clean verify\nJava 21"]
  Verify --> Package["JAR + sources + Javadocs"]
  Package --> Published["Coordenada Maven publicada"]
  Published --> Downstream["Smoke downstream\nhost real"]
  Downstream --> Release["Evidencia de release"]
```

`mvn clean verify` prova o engine no seu repositorio. A prova de integracao
somente se completa quando um consumidor resolve a coordenada publicada sem
override local. Para a candidata `1.3`, o requisito adicional e provar o
catalogo externo de `RuleExtensionTrust` no control plane e no host consumidor.

## Execucao focal desta revisao

Em 2026-07-17, a bateria focal abaixo foi executada com Java 21 e concluiu com
**52 testes aprovados, sem falhas, erros ou skips**:

```powershell
mvn "-Dtest=PraxisJsonLogicConformanceTest,PraxisJsonLogicEngineTest,PraxisRuleSetEngineTest,PraxisRuleSnapshotCompilerTest,ProtectedExtensionTrustContractTest,RuleImplementationRefJsonTest,TypedTransformationContractTest" test
```

Essa execucao valida os contratos ilustrados nesta pagina. Ela nao substitui
`mvn clean verify`, nem a prova downstream contra uma coordenada Maven publica.
