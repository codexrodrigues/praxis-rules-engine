# P2F-ADR-14 — outputs de dependências diretas

- Estado: `PUBLISHED_ENGINE_1_5_BETA_16`; smoke neutro Central-only aprovado; adoção Config/hosts pendente
- Publicação: 2026-10-21; evidências em [release readiness](release-readiness.md)
- Data: 2026-10-20
- Classificação: `arquitetural`, `contrato-publico` e `transversal`

## Inventário e decisão

O [ADR-04](p2f-adr-04-decision-plan-and-dag.md) já prevê que output intermediário
alimenta dependente declarado. `dependsOn`, os gates dos cinco estados,
`RuleBindingResult.output`, o registry exato e o digest já existem. A lacuna real
é o transporte no contexto Java: o host não deve executar uma segunda avaliação
nem pré-preencher facts derivados para contornar a ausência desse canal.

`RuleExecutorContext.directDependencyOutputs` projeta outputs ALLOW da avaliação
corrente, por bindingKey, usando exclusivamente dependências diretas. Java
`null` significa ausência e omite a chave; `NullNode` preserva JSON null. O mapa
é ordenado por bindingKey, não modificável e copia defensivamente JSON na
entrada e a cada acesso. Facts não recebem outputs, e JSON Logic continua
vendo apenas seus facts originais. Não há lookup, root, operador, envelope
paralelo ou exposição de motivos internos, transformação, aprovação ou token.

## Política estrutural

Os dois endpoints da aresta devem ser bindings habilitados `PRODUCT + JAVA`,
em `DOMAIN_DECISION` ou `POST_DECISION`. Demais arestas válidas são apenas de
controle. `SECURITY`, `CUSTOMER` mesmo attestado, `GENERATED_INFRASTRUCTURE`,
`PROTECTED_GUARD`, intents e JSON Logic não fornecem nem recebem o canal.

`RuleDecisionPlan.directDependencyOutputKeys(bindingKey)` deriva essa
classificação do plano compilado; evaluator e admissão no host consultam a
mesma política, sem armazenar outro grafo. A consulta não prova presença de
output nem compatibilidade do shape. O planner continua validando referências,
ciclos, stages, limites, composição e trust. O evaluator verifica a coordenada
e a attestation atuais antes de montar o contexto; o payload não concede trust.

`DENY` e `TECHNICAL_ERROR` continuam encerrando a avaliação. `INCONCLUSIVE` e
`NOT_APPLICABLE` bloqueiam dependentes; ramos independentes preservam sua
semântica. Nenhum output de estado diferente de ALLOW entra no transporte.

## Orçamentos e forma JSON

Cada contexto entregue e a soma dos contextos da avaliação têm limite de
`RuleEngineLimits.MAX_PUBLIC_RESULT_BYTES` (256.000). A unidade é o tamanho
UTF-8 do JSON canônico do mapa: chaves, aspas, separadores, números normalizados
e valores. Mapa vazio custa zero. Cada consumidor paga novamente por um output
compartilhado. Gates de decisão, facts e trust precedem essa reserva. A admissão da forma JSON
ocorre em todo o mapa antes da contagem: conteúdo inválido tem precedência fixa
sobre excesso de orçamento, independente da ordem de inserção dos campos.

O contador limitado percorre referências privadas já validadas sem serializar,
montar strings de números com escalas gigantes ou copiar o JSON de transporte.
Soma por subtração protege overflow. Só depois de reservar o orçamento o engine
constrói o contexto e copia seus valores. Os limites existentes individuais,
do resultado retido/público, profundidade, nós, arrays, strings e DAG continuam
valendo independentemente; podem falhar antes do transporte.

Excesso produz `TECHNICAL_ERROR/DEPENDENCY_OUTPUT_LIMIT_EXCEEDED`, sem
bindingResults, propostas ou outputs parciais, e não invoca o consumidor.

O canal transporta árvores JSON nativas e números finitos. `BinaryNode`,
`POJONode`, `MissingNode` e números não finitos, inclusive aninhados, produzem
`TECHNICAL_ERROR/DEPENDENCY_OUTPUT_INVALID` antes de copiar o contexto, com
bindingResults vazio. Não são convertidos silenciosamente em outros valores.
O construtor público do contexto rejeita essas formas com
`IllegalArgumentException`, pois `deepCopy` de um objeto Java/binário não
provaria isolamento. Outputs que não entram no canal conservam o comportamento
anterior. Essa admissão explicita a forma JSON do contrato e evita exceções
numéricas escapando do contador.

## Compatibilidade e adoção

A beta.16 publica `engineContractVersion = 1.5`. O POM
continua com sua versão de desenvolvimento. Dialeto 1.0 e corpus não mudam.
RuleSets 1.4 são incompatíveis e não são reescritos. O evaluator também confere
a tupla engine/dialeto/corpus antes de qualquer executor: plano incompatível
restaurado de cache retorna `TECHNICAL_ERROR/PLAN_COMPATIBILITY_INVALID`, sem
bindingResults nem factsDigest. O envelope identifica a baseline do runtime
atual; a definição original continua íntegra. Isso não autentica um plano
construído manualmente nem substitui compilação, aprovação e registry do host.
Facts são capturados uma única vez antes do digest e compartilhados somente por
cópias isoladas nos contextos. Mudar o draft do chamador depois dessa captura não
muda bindings Java, JSON Logic ou o digest da avaliação. O chamador não deve
mutar a árvore durante a captura inicial. Alterar executor, aresta ou
compatibilidade altera o conteúdo governado; Config e hosts precisam de nova
composição/aprovação e adoção de coordenada oficialmente publicada.

O host é dono de shapes tipados, escala/unidade, algoritmos e validação dos
outputs requeridos. Chave ausente ou shape incompatível não autoriza default,
consulta alternativa nos facts ou nova avaliação para completar a cadeia.
`requiredFactPaths` continua descrevendo facts; `factsDigest` continua dos
facts iniciais. O core não oferece sandbox JVM contra código hostil, loops ou
alocações arbitrárias de executores confiáveis.

## Evidência e limites

`DirectDependencyOutputsTest` cobre cadeia derivada, ordem, ausência/null,
arestas diretas, exclusão transitiva, matriz de fontes/stages, extensão
attestada, JSON Logic, cinco estados, mutação aninhada, concorrência, gates,
JSON inválido, orçamento por contexto e cumulativo, fan-out e limites retidos.
A fronteira canônica exata também é alcançada com número decimal de expoente
grande: seu JSON público usa notação científica compacta, mas seu JSON canônico
normalizado ocupa 256.000 bytes. Isso prova o orçamento sem enfraquecer o
limite independente do resultado público. O contador compara Unicode, escapes,
surrogates e normalização numérica com `PraxisCanonicalJson`.

O gate completo é Java 21/Maven 3.9+ `mvn clean verify`, incluindo Javadocs.
Não há banco, host, Config, HTTP, UI, release ou publicação provados por esse
gate. Skills runtime/reactive-determinations exigem atualização canônica no
mesmo ciclo antes da adoção; essa entrega de fonte não declara tal etapa feita.

## Revisão corporativa da fronteira de entrada

A captura de facts é restrita, no RuleSet engine e no contexto Java, a JSON
nativo finito; POJO, binário, missing sentinel e não finitos não provam
imutabilidade e são recusados, sem coerção. O engine retorna `FACTS_INVALID`
antes de gerar digest ou invocar bindings; o construtor público rejeita a forma
com `IllegalArgumentException`. Isso não altera o dialeto JSON Logic.

Facts também respeitam o tamanho canônico UTF-8 máximo antes de `sha256`:
notação científica compacta não pode expandir sem limite durante `toPlainString`.
Excesso retorna `FACTS_LIMIT_EXCEEDED` sem factsDigest ou resultados parciais.
Este orçamento é independente do transporte dos outputs. Os limites estruturais
já existentes continuam protegendo a captura, e a árvore capturada é validada
antes de ser usada pelo digest. A cópia não promete atomicidade contra mutação
do chamador durante a própria captura; o host fornece entrada estável.

As regressões incluem facts exatos/+1 byte, expansão numérica extrema sem
alocá-la, nós Jackson não nativos, alteração do draft entre bindings, cache com
coordenadas incompatíveis e requisições concorrentes distintas. A última prova
isolamento de memória de avaliações, não autorização de tenants: esta pertence
ao host e exige prova HTTP com identidades reais nos gates posteriores.
