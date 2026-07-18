# Releasing — Praxis Rules Engine

## Prerequisites

- GitHub repository secrets: `CENTRAL_TOKEN_USER`, `CENTRAL_TOKEN_PASS`,
  `GPG_PRIVATE_KEY`, `GPG_PASSPHRASE`, and optionally `GPG_KEY_ID`.
- `GPG_PRIVATE_KEY` may be an ASCII-armored private-key block or a base64
  encoding of the exported key. The workflow removes a UTF-8 BOM, CRLF line
  endings, and any accidental text before the armor header before importing it.
- An active `io.github.codexrodrigues` namespace in Sonatype Central.
- A release version that is not already published and follows SemVer.
- A successful consumer smoke test against the published version before any
  subsequent host release.

## Release flow

1. Merge the intended commit into `main`; the working tree must be clean.
2. Run the focused local gate:

   ```powershell
   $env:JAVA_HOME='<JDK-21>'
   mvn clean verify
   ```

3. Use **Release Praxis Rules Engine** with `workflow_dispatch`, executing it
   on `main`, with `create_tag=true` and either an explicit `version` or a
   `bump`/`preid` pair. The workflow creates the annotated `v<semver>` tag.
4. The tag push triggers signing, Central upload, and a Maven Central
   availability probe. Do not run `mvn deploy` locally and do not create tags
   manually as a bypass.
5. Only after the availability probe succeeds may `praxis-metadata-starter` or
   `praxis-api-quickstart` consume the public coordinate.

## Current public line

The current documented public coordinate is
`io.github.codexrodrigues:praxis-rules-engine:0.1.0-beta.14`, with engine
contract `1.4`. It was consumed by `praxis-api-quickstart` directly from Maven
Central, but a later audit proved that its advertised corpus hash differs from
the packaged corpus bytes. Do not recommend beta.14 to new consumers. The
current source prepares the corrective beta on the same `0.1.0-beta.*` line;
it must not be recommended until the official workflow publishes it and
downstream proof verifies the packaged bytes and advertised hash. See
[release readiness](docs/release-readiness.md) and the
[contract reference](docs/contracts-reference.md#compatibilidade-publicada).

Compatible work remains on the active `0.1.0-beta.*` line. A new major requires
an explicitly governed breaking-change plan, consumer impact map, migration
guidance and downstream validation.
