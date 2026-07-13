# Praxis Rules Engine

Core Java 21 puro, embarcavel e deterministico para o dialeto JSON Logic da plataforma Praxis.

## Responsabilidade

O modulo valida e avalia expressoes, compila `RuleSetDefinition` em DAG imutavel, resolve bindings JSON Logic ou Java puro, consolida decisoes em cinco estados e publica digests e coordenadas de compatibilidade. Ele nao possui Spring, persistencia, endpoints, tenant registry, snapshots, workflow, efeitos, HADES, Oracle ou regras Ergon.

O owner normativo do dialeto e hoje `@praxisui/core`; o JAR e o owner do runtime Java. Mudancas de semantica exigem RFC, runtime TypeScript, runtime Java e corpus no mesmo ciclo.

## Uso

```java
PraxisJsonLogicEngine engine = new PraxisJsonLogicEngine();
JsonLogicValidationResult validation = engine.validateResult(expression, validationOptions);
JsonLogicEvaluationResult result = engine.evaluateResult(expression, facts, evaluationOptions);
List<JsonLogicOperatorDescriptor> catalog = engine.listOperatorDescriptors();
```

Para composicao de negocio, o host registra implementacoes Java puras por chave
e versao exatas, compila uma definicao imutavel e avalia facts ja resolvidos:

```java
RuleBindingExecutorRegistry registry = new RuleBindingExecutorRegistry(executors);
RuleDecisionPlan plan = new PraxisRulePlanCompiler(registry).compile(definition);
RuleEvaluationResult decision = new PraxisRuleSetEngine(registry)
        .evaluate(plan, facts, nowUtc, userTimeZone);
```

O resultado preserva `DENY`, `NOT_APPLICABLE`, `INCONCLUSIVE` e
`TECHNICAL_ERROR` sem colapsa-los em boolean, inclui `planDigest`, `factsDigest`,
baseline de engine/dialect e versoes das implementacoes Java utilizadas.

Operadores temporais exigem `nowUtc` e `userTimeZone`. Contextos multi-root exigem paths qualificados. Operadores de host precisam de namespace e nao podem colidir com operadores nativos ou Praxis.

## Build

```powershell
$env:JAVA_HOME='<JDK-21>'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn clean verify
```

Maven 3.9+ e Java exatamente 21 sao impostos pelo Enforcer. O build gera JAR binario, sources e Javadocs. O corpus FE/BE e empacotado no JAR para que o build isolado nao dependa de um checkout irmao.

## Estado e ownership

O runtime JSON Logic, contratos RuleSet, planner deterministico, registry versionado e evaluator service-level existem localmente. O repositorio canonico e `codexrodrigues/praxis-rules-engine` e todo push ou pull request para `main` passa por `mvn clean verify`. O POM de desenvolvimento permanece `0.0.1-SNAPSHOT`; a coordenada publica atual e `io.github.codexrodrigues:praxis-rules-engine:0.1.0-beta.6`. Os contratos RuleSet ainda exigem a proxima publicacao oficial e smoke downstream antes de consumo pelo Quickstart.

Consulte [architecture.md](docs/architecture.md), [operator-conformance-matrix.md](docs/operator-conformance-matrix.md), [release-readiness.md](docs/release-readiness.md) e o [pacote de ADRs da plataforma de regras](docs/p2f-rule-platform-adrs.md).
