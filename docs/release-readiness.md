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

Classificacao da proxima linha: os contratos RuleSet/planner/evaluator sao
aditivos sobre o canal beta publico ativo. Portanto, a candidata correta e
`0.1.0-beta.7`, nao uma major nova nem uma versao estavel. A implementacao e os
testes focais do engine estao locais; a publicacao permanece bloqueada ate
`mvn clean verify`, revisao do diff/commit remoto e workflow oficial por tag.
Depois da disponibilidade no Maven Central, Config Starter e Quickstart devem
executar smoke direto contra a coordenada publica, sem override de repositorio
local.
