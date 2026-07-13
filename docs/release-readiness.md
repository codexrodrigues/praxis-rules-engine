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
`INCONCLUSIVE`, inclusive o bloqueio de `EFFECT_INTENT`. A `beta.8` é a
coordenada pública corrente para novos consumidores; a `beta.7` permanece
explicitamente não recomendada.
