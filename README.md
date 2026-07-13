# Praxis Rules Engine

Core Java 21 puro, embarcavel e deterministico para o dialeto JSON Logic da plataforma Praxis.

## Responsabilidade

O modulo valida e avalia expressoes, resolve paths do subset Praxis, normaliza numeros, congela o contexto temporal, aplica limites deterministicos e publica diagnosticos e descriptors de operadores. Ele nao possui Spring, persistencia, endpoints, tenant registry, snapshots, workflow, efeitos, HADES, Oracle ou regras Ergon.

O owner normativo do dialeto e hoje `@praxisui/core`; o JAR e o owner do runtime Java. Mudancas de semantica exigem RFC, runtime TypeScript, runtime Java e corpus no mesmo ciclo.

## Uso

```java
PraxisJsonLogicEngine engine = new PraxisJsonLogicEngine();
JsonLogicValidationResult validation = engine.validateResult(expression, validationOptions);
JsonLogicEvaluationResult result = engine.evaluateResult(expression, facts, evaluationOptions);
List<JsonLogicOperatorDescriptor> catalog = engine.listOperatorDescriptors();
```

Operadores temporais exigem `nowUtc` e `userTimeZone`. Contextos multi-root exigem paths qualificados. Operadores de host precisam de namespace e nao podem colidir com operadores nativos ou Praxis.

## Build

```powershell
$env:JAVA_HOME='<JDK-21>'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn clean verify
```

Maven 3.9+ e Java exatamente 21 sao impostos pelo Enforcer. O build gera JAR binario, sources e Javadocs. O corpus FE/BE e empacotado no JAR para que o build isolado nao dependa de um checkout irmao.

## Estado e ownership

O runtime, registry introspectavel, limits, paths fechados, regex segura, contexto temporal e suite focal existem. O repositorio canonico e `codexrodrigues/praxis-rules-engine` e todo push ou pull request para `main` passa por `mvn clean verify`. A coordenada permanece `0.0.1-SNAPSHOT`; nao houve publicacao nem tag. O projeto adota Apache-2.0, metadados Maven Central, assinatura GPG e workflow oficial por tag; a primeira release continua bloqueada ate configurar os secrets do repositório e executar o smoke downstream contra um artefato público.

Consulte [architecture.md](docs/architecture.md), [operator-conformance-matrix.md](docs/operator-conformance-matrix.md) e [release-readiness.md](docs/release-readiness.md).
