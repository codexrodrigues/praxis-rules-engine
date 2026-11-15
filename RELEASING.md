# Guia de Release — Praxis Rules Engine

## Pré-requisitos

- Segredos configurados no repositório GitHub: `CENTRAL_TOKEN_USER`, `CENTRAL_TOKEN_PASS`,
  `GPG_PRIVATE_KEY`, `GPG_PASSPHRASE` e, opcionalmente, `GPG_KEY_ID`.
- `GPG_PRIVATE_KEY` pode ser um bloco de chave privada em ASCII-armor ou codificado
  em base64. O workflow remove BOM UTF-8, quebras de linha CRLF e qualquer texto
  acidental antes do cabeçalho ASCII-armor antes da importação.
- Namespace `io.github.codexrodrigues` ativo no Sonatype Central.
- Versão de release inédita e aderente ao padrão SemVer.
- Smoke test de consumidor bem-sucedido contra a versão publicada antes de
  qualquer release subsequente em hosts ou starters downstream.

## Fluxo de release

1. Faça o merge do commit desejado para `main`; a árvore de trabalho deve estar limpa.
2. Execute o gate local focado:

   ```powershell
   $env:JAVA_HOME='<JDK-21>'
   mvn clean verify
   ```

   Para um release candidate, exija aprovação tanto do job de CI em Linux quanto
   do job em Windows. O corpus JSON Logic é um contrato endereçado por bytes,
   portanto um build verde em apenas uma política de checkout não constitui
   evidência suficiente de release.

3. Execute **Release Praxis Rules Engine** via `workflow_dispatch` sobre `main`,
   com `create_tag=true` e `version` explícita ou um par `bump`/`preid`. O
   workflow criará a tag anotada `v<semver>`.
4. O push da tag dispara assinatura, upload para o Maven Central e sondagem de
   disponibilidade pública. Não execute `mvn deploy` localmente nem crie tags
   manualmente como atalho.
5. Somente após a sondagem de disponibilidade concluir com sucesso é que módulos
   como `praxis-metadata-starter` ou `praxis-api-quickstart` podem consumir a
   nova coordenada pública.

## Linha pública atual

A coordenada pública vigente e suas evidências de publicação estão registradas em
[prontidão para release](docs/release-readiness.md). A linha ativa de release
permanece em `0.1.0-beta.*`; o código-fonte implementa o contrato de engine
`1.6`. O contrato de runtime é uma fronteira exata de compatibilidade:
composições `1.4` exigem migração governada e nova aprovação antes da execução
com `1.6`. A publicação no Central não implica adoção automática nos hosts. Veja
a [referência de contratos](docs/contracts-reference.md#compatibilidade-publicada).

Historicamente, a versão `beta.14` anunciou um hash de corpus divergente de seus
bytes empacotados; a `beta.15` corrigiu esse desvio, mas falhou na auditoria de
checkout Windows por falta de governança de LF/CRLF. Todo release sucessor deve
passar nos checks de Linux e Windows no CI e em smoke test contra o Maven
Central verificando tanto o hash anunciado do corpus quanto os bytes
efetivamente empacotados.

Evoluções compatíveis continuam na linha ativa `0.1.0-beta.*`. Uma nova versão
major exige plano explícito de breaking change governado, mapa de impacto nos
consumidores, guia de migração e validação downstream.
