# Praxis Rules Engine

Core Java 21 puro, embarcável e determinístico para o dialeto JSON Logic e avaliação de `RuleSet` da [**Praxis Platform**](https://praxisui.dev/) — garantindo que regras de negócio descritas uma única vez sejam avaliadas de forma idêntica (5 estados determinísticos) em **interfaces tradicionais, interfaces generativas, agentes de IA e integrações backend**.

## Responsabilidade e Separação Ortogonal (`BUSINESS` vs. `AUTHORIZATION`)

Este módulo é o **PDP (Policy Decision Point) exclusivo para `decisionKind = BUSINESS`** na **Praxis Decision Platform**: valida e avalia expressões, compila `RuleSetDefinition` e snapshots imutáveis em DAGs determinísticas, resolve `DecisionBinding` (JSON Logic ou Java puro), consolida decisões em cinco estados (`ALLOW`, `DENY`, `NOT_APPLICABLE`, `INCONCLUSIVE`, `TECHNICAL_ERROR`) e publica digests e coordenadas de compatibilidade. Ele **não** possui Spring, persistência, endpoints, estado mutável, workflow ou regras acopladas a sistemas legados, bancos de dados ou produtos específicos.

Em grandes sistemas corporativos, misturar regras de negócio com segurança gera vazamentos por mensagem de erro (*side-channel*) e código impossível de auditar. Por isso, a arquitetura Praxis mantém dois planos rigorosamente ortogonais e integrados:

| Dimensão | `decisionKind = BUSINESS` (**Este Projeto**) | `decisionKind = AUTHORIZATION` (**Família Authz `0.1.0-beta.1`**) |
| :--- | :--- | :--- |
| **Módulos Canônicos** | **`praxis-rules-engine`** | [`praxis-authz-contracts`](https://github.com/codexrodrigues/praxis-authz-contracts) → [`praxis-authz-enforcement-core`](https://github.com/codexrodrigues/praxis-authz-enforcement-core) → [`praxis-authz-opa`](https://github.com/codexrodrigues/praxis-authz-opa) → [`praxis-authz-starter`](https://github.com/codexrodrigues/praxis-authz-starter) |
| **Quando Executa no PEP** | Exatamente no **Passo 12 (`STEP_12`)**, já após o bloqueio pré-hidratação (`STEP_11`) e **sempre sobre dados reais não mascarados (`P40`)** | **Passos 1–11** (autenticação, anti-spoofing, `FIELD_USE`, `DATA_SCOPE` Kleene 3VL em SQL, `ACCESS` e `FIELD_WRITE`) e **Passos 13–15** (outbox e mascaramento desconectado `MASK`) |
| **Tecnologia / Vínculo** | JSON Logic determinístico / DAGs Java (`PublishedRuleSnapshot` + `DecisionBinding`) | OPA `import rego.v1` + `DataScopePlan` Kleene 3VL (`RuntimeEnforcementBinding`) |
| **Governança e Autoria (PAP / Studio)** | [`praxis-config-starter`](https://github.com/codexrodrigues/praxis-config-starter) + **Lente de Regras de Negócio** no [`praxis-policy-studio`](https://github.com/codexrodrigues/praxis-policy-studio) | [`praxis-config-starter`](https://github.com/codexrodrigues/praxis-config-starter) + **Lente de Acesso e Proteção de Dados** no [`praxis-policy-studio`](https://github.com/codexrodrigues/praxis-policy-studio) |

**Vantagem frente ao mercado e ganho em tokens de IA:** Motoristas de regras convencionais (*BRMS* pesados) prendem regras em bancos proprietários ou DSLs opacas que exigem dezenas de integrações para funcionar no browser e no servidor. Aqui, o mesmo dialeto canônico (normatizado em `@praxisui/core` no [`praxis-ui-angular`](https://github.com/codexrodrigues/praxis-ui-angular) e executado aqui em Java 21 puro) opera no frontend e no backend, integrado ainda aos recursos semânticos de [`praxis-metadata-starter`](https://github.com/codexrodrigues/praxis-metadata-starter) e provado no [`praxis-api-quickstart`](https://github.com/codexrodrigues/praxis-api-quickstart). Para agentes de IA e LLMs, raciocinar sobre `RuleSet` declarativos em JSON Logic com 5 estados fechados reduz drasticamente os tokens de contexto necessários para auditar ou propor novas regras em comparação com percorrer árvores de `if/else` espalhadas em múltiplos `@Service`s.

O owner normativo do dialeto é hoje `@praxisui/core`; o JAR é o owner do runtime Java. Mudanças de semântica exigem RFC, runtime TypeScript, runtime Java e corpus no mesmo ciclo.

## Uso

Hosts podem usar o [template governado, captura de fatos e resolução paramétrica](docs/governed-rule-template.md)
para compor os contratos puros do engine e publicar violações estruturadas pelo metadata starter.

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

O [candidato de admissão tipada por operação](docs/p2f-adr-15-typed-fact-admission.md)
acrescenta `RuleFactSchema`, `RuleFactContract`, `TypedRuleDecisionPlan` e
`PraxisRuleSnapshotCompiler.compileForOperation`. O host fornece os schemas e
as coordenadas exatas do provider; o Engine verifica caminhos, tipos e fatos
antes dos bindings. A API está em desenvolvimento local, sem publicação ou
ativação implícita em consumidores.

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


## Dependências obrigatórias de controle — fonte contract 1.7

`DecisionSlot.requiredControlDependencies` declara as identidades de bindings
protegidos do produto que todo binding ativo daquele slot deve ter em `dependsOn`.
Uma substituição ou parametrização que omite a aresta é rejeitada pelo compiler.
Os controles devem estar habilitados, ser `PRODUCT` e pertencer a slots
`PROTECTED_GUARD`/`FORBIDDEN`. O host confere a identidade dos slots e dos controles
contra seu produto antes de admitir uma composição. O Engine não consulta políticas.

Uma guarda de aplicabilidade retorna `NOT_APPLICABLE` fora de seu âmbito. O DAG
suprime o predicado dependente, inclusive a exigência de seus fatos. Falta de fonte
permanece inconclusiva; mensagem de negócio do ponto de extensão é outra decisão.
`PraxisRulePlanCompiler.compilePreview` admite primeiro a definição completa,
seleciona o fechamento transitivo e recompila a projeção sem apagar dependências.
Bindings parametrizados conservam sua representação fonte no snapshot da prévia.

O contrato avança para `1.7`. Snapshots históricos continuam imutáveis; novos
candidatos exigem recompilação e aprovação no contrato instalado. A coordenada de
desenvolvimento é `0.1.0-beta.18-SNAPSHOT`, dentro da linha beta, sem publicação.
Consumo de um JAR candidato isolado prova integração local, e não disponibilidade
no Maven Central.

## Parametrização governada — introduzida no contract 1.6

`PARAMETERIZE` agora resolve somente valores escalares admitidos por um
`DecisionSlot.parameterTemplate` de produto com identidade/versão exatas.
`RuleParameterResolver` publica a resolução pura para Config; bindings source
não podem fornecer expressão/executor ou alterar outcomes/facts obrigatórios.
O [ADR-03](docs/p2f-adr-03-composition-and-overrides.md) documenta schema,
defaults, sites literais, limites, diagnósticos e a migração beta limpa.

Esta fonte exige engine contract `1.7`, preservando dialeto/corpus JSON Logic.
A coordenada deste incremento é `0.1.0-beta.17`; a disponibilidade no Maven Central
deve ser confirmada antes da adoção downstream. Snapshots 1.5 e PARAMETERIZE antigo precisam de
nova publicação governada compatível; Java PARAMETERIZE não é suportado.
