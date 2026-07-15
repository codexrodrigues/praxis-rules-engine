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
