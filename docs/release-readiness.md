# Release readiness

`0.0.1-SNAPSHOT` permanece a linha do POM de desenvolvimento. O primeiro canal
publico foi aberto com
`io.github.codexrodrigues:praxis-rules-engine:0.1.0-beta.6`; evolucoes devem
classificar explicitamente a linha beta antes de qualquer tag.

Pronto localmente: Java 21/Maven 3.9 impostos pelo build; JAR, sources e Javadocs; runtime sem Spring; testes unitarios, conformidade, limites, determinismo, thread safety e consumo neutro.

O repositorio Git canonico e o owner sao
`codexrodrigues/praxis-rules-engine`, com CI de verificacao para `main` e pull
requests. O namespace `io.github.codexrodrigues` foi publicado pelo workflow
oficial assinado e a disponibilidade downstream da primeira coordenada foi
verificada.

Os contratos RuleSet/planner/evaluator foram publicados em `0.1.0-beta.7`, mas
a prova downstream encontrou uma consolidação incorreta de
`ALLOW` intermediário com ramo terminal `NOT_APPLICABLE`. Essa coordenada não
deve ser adotada por hosts. A correção altera a semântica determinística e eleva
o engine contract de `1.0` para `1.1`.

A correção foi publicada como `0.1.0-beta.8`, ainda dentro do canal beta
público ativo, com engine contract `1.1`. O smoke service-level do Quickstart
resolveu a coordenada diretamente do Maven Central, sem override de repositório
local, e passou os cenários focais de `ALLOW`, `DENY`, `NOT_APPLICABLE` e
`INCONCLUSIVE`, inclusive o bloqueio de `EFFECT_INTENT`.

A mesma linha compatível evoluiu para `0.1.0-beta.9` com snapshot imutável,
`0.1.0-beta.10` com registry Java planning-only e `0.1.0-beta.11` com
normalização de expressão nula em executor Java. A `beta.7` permanece
explicitamente não recomendada.

## Beta.12 — contrato de transformação tipada

P2F-ADR-11 adiciona `TRANSFORMATION_INTENT` e propostas tipadas puras, elevando
o engine contract para `1.2` sem alterar o dialeto JSON Logic. A classificação
de versão é evolução aditiva da linha beta pública ativa: deve ser uma nova
`0.1.0-beta.*`, não uma major. Construtores anteriores foram preservados.

`0.1.0-beta.12` foi publicada pelo workflow oficial assinado e exposta no Maven
Central em 2026-07-15. O Quickstart resolveu a coordenada em repositório Maven
isolado, executou o engine contract `1.2` e provou transformação allowlisted,
schema-bound e persistida na transação existente. A suíte QL-08 passou com 58
testes e o CI amplo do host também passou. Em seguida, o host adicionou uma
auditoria append-only redigida da identidade e dos digests canônicos da
proposta, com provas de replay sem duplicação, rollback sem registro órfão e
imutabilidade no PostgreSQL.

O gate downstream específico do ADR-11 está concluído no laboratório. A
mudança pertence ao host e não exige alteração, tag ou nova publicação do
contrato puro `1.2` do engine.

P2F-ADR-12 formaliza observation, redaction e retenção governada sem adicionar
campos ao resultado puro. A prova de allowlist, métricas bounded, auditoria
insert-only, legal hold e expurgo autorizado pertence ao Quickstart. Portanto,
essa decisão também não exige tag nem nova publicação do engine.

## Beta.13 — protected extensions

P2F-ADR-05 introduz `RuleExtensionTrust`, exige attestation externa para todo
binding `CUSTOMER + JAVA`, inclui a evidência no digest do plano e no resultado
e mantém protected guards `FORBIDDEN` mesmo para artefatos assinados. O engine
contract passa a `1.3`; construtores de implementações built-in permanecem
compatíveis.

Classificação de versão: evolução aditiva da linha pública beta ativa,
`0.1.0-beta.13`, não nova major. A publicação só pode ocorrer após `clean verify`
e merge em `main`, pelo `workflow_dispatch` oficial com `create_tag=true`. A
coordenada somente será recomendada depois de prova downstream pública no
Quickstart e de um catálogo externo de trust no control plane; o próprio
snapshot nunca pode gerar sua allowlist.

`0.1.0-beta.13` foi publicada pelo workflow oficial e consumida pelo Quickstart
em repositório Maven vazio e exclusivo do Central. QL-07 registrou o SHA-256 do
JAR resolvido e QL-09 provou attestation no plano/resultado, bloqueio de
protected guard e substituição entre compilação e avaliação. IAM, PKI e
revogação do ambiente corporativo-alvo permanecem gates separados.

## Próxima beta corretiva — convergência de corpus / contract 1.4

P2F-ADR-13 foi publicado na beta.14, mas essa release anunciou o hash antigo
enquanto empacotou o corpus novo. A fonte corretiva mantém engine contract
`1.4`, converge o default composto de `var` entre TypeScript e Java e adiciona
regressão executável para bytes/hash. O trust de `1.3` permanece integralmente
preservado. A linha continua `0.1.0-beta.*`; a próxima versão disponível é
`0.1.0-beta.15`, condicionada a `clean verify`, corpus byte a byte, hash
anunciado idêntico e smoke downstream Central-only.
