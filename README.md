# Praxis Rules Engine

Core Java 21 puro, embarcavel e deterministico para o dialeto JSON Logic da plataforma Praxis.

## Responsabilidade

O modulo valida e avalia expressoes, compila `RuleSetDefinition` e snapshots
imutaveis em DAGs deterministicas, resolve bindings JSON Logic ou Java puro,
consolida decisoes em cinco estados e publica digests e coordenadas de
compatibilidade. Ele nao possui Spring, persistencia, endpoints, tenant store,
head mutavel, workflow, execucao de efeitos, HADES, Oracle ou regras Ergon.

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

Para consumo governado, o control plane publica um `PublishedRuleSnapshot`. O
host valida a versão exata do seu contrato e prepara o snapshot fora do caminho
de avaliação antes de trocar sua referência ativa:

```java
CompiledRuleSnapshot prepared = new PraxisRuleSnapshotCompiler(registry)
        .compile(snapshot, "my-domain-host/1.0");
```

O `snapshotContentHash` identifica o conteúdo imutável. Concorrência, ETag do
head, persistência, rollback, cache e troca atômica continuam fora deste JAR.

O resultado preserva `DENY`, `NOT_APPLICABLE`, `INCONCLUSIVE` e
`TECHNICAL_ERROR` sem colapsa-los em boolean, inclui `planDigest`, `factsDigest`,
baseline de engine/dialect e versoes das implementacoes Java utilizadas.
Coordenadas Java são revalidadas na avaliação, e o resultado agregado aplica
limites determinísticos de bytes, itens e strings. Razões terminais de
`NOT_APPLICABLE` permanecem no resultado consolidado para explicação e auditoria.

Extensoes Java de cliente falham por default. Para um slot explicitamente
customizavel, o host deve registrar a coordenada exata com `RuleExtensionTrust`,
produzida depois da verificacao externa do artefato assinado e da allowlist. A
attestation participa do `planDigest` e do resultado; ela nunca permite alterar
um `PROTECTED_GUARD`. O engine nao carrega JARs, nao verifica certificados e nao
oferece sandbox para codigo arbitrario.

Transformacoes de write model usam `TRANSFORMATION_INTENT`: o executor Java
propoe valores `before`/`after` tipados e o engine valida destino, schema ref,
snapshot, limites, conflitos e proveniencia. O resultado e apenas uma proposta;
autorizacao, validacao contra o schema governado, ETag, transacao, persistencia e
auditoria pertencem ao host. Veja a
[P2F-ADR-11](docs/p2f-adr-11-typed-transformations.md).

Operadores temporais exigem `nowUtc` e `userTimeZone`. Contextos multi-root exigem paths qualificados. Operadores de host precisam de namespace e nao podem colidir com operadores nativos ou Praxis.

## Build

```powershell
$env:JAVA_HOME='<JDK-21>'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn clean verify
```

Maven 3.9+ e Java exatamente 21 sao impostos pelo Enforcer. O build gera JAR binario, sources e Javadocs. O corpus FE/BE e empacotado no JAR para que o build isolado nao dependa de um checkout irmao.

## Estado e ownership

O runtime JSON Logic, contratos RuleSet/snapshot, planner deterministico,
registry versionado e evaluator service-level pertencem ao repositorio canonico
`codexrodrigues/praxis-rules-engine`. Todo push ou pull request para `main` passa
por `mvn clean verify`. O POM registra a coordenada da versão preparada para publicação.

A coordenada pública e seus gates estão registrados em
[release readiness](docs/release-readiness.md). Historicamente, a beta.14 anunciou
hash diferente do corpus empacotado; a beta.15 corrigiu esse drift, mas falhou
no checkout Windows por LF/CRLF. Nenhuma das duas é baseline verde entre
plataformas. Uma sucessora requer gates Linux/Windows e smoke Central-only.

O smoke histórico da beta.14 comprovou consumo da coordenada pública, trust de
extensões, proposta tipada, allowlist/schema no host e persistência transacional;
não cobriu a invariável de bytes/hash. A beta.13 permanece marco histórico de
extensões protegidas. A beta.7 não deve ser adotada por hosts porque consolidava
incorretamente ALLOW intermediário com NOT_APPLICABLE terminal.

O contrato `1.5`, descrito abaixo e no ADR-14, exige composições compatíveis
e migração governada nos consumidores. Publicação do engine e adoção no host
são gates separados; consultar a evidência da coordenada em release readiness.

Para uma explicação didática de como o motor se integra ao control plane e aos
hosts corporativos, consulte o
[guia de integração e evolução](docs/platform-integration-and-roadmap.md).
Consulte também [architecture.md](docs/architecture.md),
[operator-conformance-matrix.md](docs/operator-conformance-matrix.md),
[release-readiness.md](docs/release-readiness.md) e o
[pacote de ADRs da plataforma de regras](docs/p2f-rule-platform-adrs.md).

## Engine contract 1.5 — outputs diretos

O checkout implementa `RuleExecutorContext.directDependencyOutputs` para
composição de cálculos Java no DAG existente, conforme
[ADR-14](docs/p2f-adr-14-direct-dependency-outputs.md). A revisão 1.5 exige
PRODUCT/JAVA em DOMAIN/POST nos dois endpoints, preserva facts e JSON Logic e
limita a transferência por contexto e por avaliação antes de copiar valores.
RuleSets 1.4 são rejeitados; o corpus/dialeto não muda. A adoção nos hosts exige a
coordenada pública verificada e provas próprias de integração; publicação do
engine não comprova o exemplo financeiro nem a UI.


## Parametrização governada — fonte contract 1.6

`PARAMETERIZE` agora resolve somente valores escalares admitidos por um
`DecisionSlot.parameterTemplate` de produto com identidade/versão exatas.
`RuleParameterResolver` publica a resolução pura para Config; bindings source
não podem fornecer expressão/executor ou alterar outcomes/facts obrigatórios.
O [ADR-03](docs/p2f-adr-03-composition-and-overrides.md) documenta schema,
defaults, sites literais, limites, diagnósticos e a migração beta limpa.

Esta fonte exige engine contract `1.6`, preservando dialeto/corpus JSON Logic.
A coordenada deste incremento é `0.1.0-beta.17`; a disponibilidade no Maven Central
deve ser confirmada antes da adoção downstream. Snapshots 1.5 e PARAMETERIZE antigo precisam de
nova publicação governada compatível; Java PARAMETERIZE não é suportado.
