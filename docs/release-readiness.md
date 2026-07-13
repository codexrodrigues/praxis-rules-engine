# Release readiness

`0.0.1-SNAPSHOT` e uma linha de desenvolvimento, ainda nao um canal publico. Nao promover silenciosamente.

Pronto localmente: Java 21/Maven 3.9 impostos pelo build; JAR, sources e Javadocs; runtime sem Spring; testes unitarios, conformidade, limites, determinismo, thread safety e consumo neutro.

O repositorio Git canonico e o owner foram definidos como `codexrodrigues/praxis-rules-engine`, com CI de verificacao para `main` e pull requests. O namespace `io.github.codexrodrigues` já possui publicações aceitas no Central Portal e o projeto agora declara Apache-2.0, maintainer, assinatura GPG e workflow oficial por tag. Bloqueios externos restantes: configurar secrets de Central/GPG no repositório, decidir a primeira versão pública e executar smoke downstream contra essa coordenada publicada. Nenhuma tag, deploy ou publicação deve ser criada antes de resolver esses itens.
