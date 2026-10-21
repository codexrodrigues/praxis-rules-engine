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

## Beta.15 e próxima beta corretiva — convergência de corpus / contract 1.4

P2F-ADR-13 foi publicado na beta.14, mas essa release anunciou o hash antigo
enquanto empacotou o corpus novo. A beta.15 manteve engine contract `1.4`,
convergiu o default composto de `var` entre TypeScript e Java e adicionou
regressão executável para bytes/hash. O trust de `1.3` permaneceu integralmente
preservado.

A publicação da beta.15 existe no Maven Central, porém sua prova não é
reprodutível em checkout Windows com `core.autocrlf=true`: o repositório não
fixava LF e o teste do hash observava bytes CRLF. Por isso, beta.15 não deve ser
recomendada como baseline verde. A correção mantém a comparação byte a byte,
versiona a política de finais de linha e adiciona `clean verify` no Windows CI.
Ela deve seguir na mesma linha compatível como próxima `0.1.0-beta.*`, somente
após gates Linux/Windows e smoke downstream Central-only.

## Contrato 1.5 — desenho e condição de adoção

O [ADR-14](p2f-adr-14-direct-dependency-outputs.md) evolui a fonte para contrato
1.5. O plano corretivo compatível 1.4 acima permanece registro histórico; não
é a descrição da fonte atual. A implementação e o gate local isolados não comprovam publicação. A evidência
da beta.16 está abaixo, incluindo gates Linux/Windows, publicação oficial,
hashes/corpus e consumo Central-only. Config e hosts devem
ser alinhados pela migração governada, com novas composições/aprovações e sem
tráfego misto de contratos incompatíveis. Não reescrever snapshots 1.4 nem
concluir que leitura histórica implica capacidade de ativação/execução.


## Beta.16 — publicação verificada em 2026-10-21

Coordenada: `io.github.codexrodrigues:praxis-rules-engine:0.1.0-beta.16`.
Contrato engine `1.5`, dialeto JSON Logic `1.0`. O POM de desenvolvimento permanece
`0.0.1-SNAPSHOT`; o workflow define a versão publicada. A linha beta ativa é mantida,
mas a compatibilidade de RuleSets é exata: definições 1.4 requerem novas composições,
aprovação e compilação governadas antes da execução em 1.5.

- [PR de implementação #21](https://github.com/codexrodrigues/praxis-rules-engine/pull/21),
  merge `ff852975004a8f04285f978b0bd910bec15e3496`.
- [CI do PR](https://github.com/codexrodrigues/praxis-rules-engine/actions/runs/39589792442)
  e [CI da main](https://github.com/codexrodrigues/praxis-rules-engine/actions/runs/39590141304)
  aprovados em Linux e Windows, com 83 testes por execução.
- [Dispatch oficial](https://github.com/codexrodrigues/praxis-rules-engine/actions/runs/39590341230)
  criou a tag anotada `v0.1.0-beta.16` no merge acima;
  [publicação por tag](https://github.com/codexrodrigues/praxis-rules-engine/actions/runs/39590361201)
  concluiu build, assinatura, upload e prova de disponibilidade no Central.
- [POM público](https://repo.maven.apache.org/maven2/io/github/codexrodrigues/praxis-rules-engine/0.1.0-beta.16/praxis-rules-engine-0.1.0-beta.16.pom),
  JAR, sources, Javadocs e respectivas assinaturas disponíveis. Checksums SHA-1
  publicados conferidos; SHA-256 do JAR:
  `c214084a9aeff5f8af1346eb67e9a303e61f3a310c7e543883afce080aaf2db7`.
- Smoke de consumidor Java neutro, com repositório Maven inicialmente vazio e
  mirror exclusivo do Central: cadeia de dois executores via API pública,
  facts originais/digest e recusa de plano 1.4 antes dos executores aprovados.
  O hash anunciado e os bytes do corpus conferem com
  `E890925C7306A2646CF86443C29ABDDEF0C0645C6228F1DE4A8740E45253D181`.
  Os 60 arquivos Java do sources JAR são idênticos aos da tag revisada.
- [Guidance canônico integrado](https://github.com/codexrodrigues/praxis-codex-skills/pull/807)
  para engine e Reactive Determinations, com preflight e revisão independente.

O smoke neutro não é prova financeira, HTTP, UI, transação ou autorização multitenant.
Adoção Config/Metadata/Quickstart, migração de composições e provas do exemplo
continuam gates próprios. A documentação no tag registra o estado pré-publicação;
este registro posterior não reescreve a tag nem altera o artefato publicado.

## Beta.17 — publicação verificada em 2026-10-21

Coordenada: `io.github.codexrodrigues:praxis-rules-engine:0.1.0-beta.17`.
Contrato engine `1.6`; dialeto `1.0` e corpus permanecem inalterados. Este incremento
implementa PARAMETERIZE escalar por template do produto; snapshots de contrato 1.5
precisam de nova composição, aprovação e publicação governadas.

- [PR #24](https://github.com/codexrodrigues/praxis-rules-engine/pull/24), merge
  `630fe1ccbaeb449f0673fd9ef22d2db41512356f`, também alvo da tag `v0.1.0-beta.17`.
- [CI do PR](https://github.com/codexrodrigues/praxis-rules-engine/actions/runs/39606756485):
  Linux e Windows aprovados, 100 testes por execução.
- [Preparação oficial](https://github.com/codexrodrigues/praxis-rules-engine/actions/runs/39606980327)
  e [publicação por tag](https://github.com/codexrodrigues/praxis-rules-engine/actions/runs/39607002564)
  concluídas com sucesso.
- [POM público](https://repo.maven.apache.org/maven2/io/github/codexrodrigues/praxis-rules-engine/0.1.0-beta.17/praxis-rules-engine-0.1.0-beta.17.pom),
  JAR, sources e Javadoc disponíveis; SHA-1, SHA-256 e SHA-512 dos quatro artefatos
  conferidos. As quatro assinaturas foram verificadas com fingerprint público
  `15AC2D93323900106EA6091A675D757DAC8D8031`, identificado no workflow oficial.
- SHA-256 do JAR: `2ca78cc7fe1abcf63b36df0259ccf41491acf6056eb4fd837ea334229c61ea52`.
  Os 67 arquivos Java do sources JAR são idênticos à tag. O JAR confirma contrato 1.6
  e o hash dos bytes do corpus `E890925C7306A2646CF86443C29ABDDEF0C0645C6228F1DE4A8740E45253D181`.
- [Guidance canônico #809](https://github.com/codexrodrigues/praxis-codex-skills/pull/809)
  integrado e sincronizado seletivamente para Engine, Config e Core.

A auditoria pública foi independente da implementação. O workflow não preserva um
bundle ou manifesto de hashes do runner: esta prova não afirma igualdade binária
com uma build local ou com a primeira build do job. Fontes, assinaturas e checksums
públicos foram verificados; os gates de adoção dos consumidores continuam próprios.
Este registro posterior não modifica a tag nem o artefato publicado.
