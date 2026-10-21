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

   For a release candidate, require both Linux and Windows CI jobs to pass.
   The JSON Logic corpus is a byte-addressed contract, so a green build on only
   one checkout policy is not sufficient release evidence.

3. Use **Release Praxis Rules Engine** with `workflow_dispatch`, executing it
   on `main`, with `create_tag=true` and either an explicit `version` or a
   `bump`/`preid` pair. The workflow creates the annotated `v<semver>` tag.
4. The tag push triggers signing, Central upload, and a Maven Central
   availability probe. Do not run `mvn deploy` locally and do not create tags
   manually as a bypass.
5. Only after the availability probe succeeds may `praxis-metadata-starter` or
   `praxis-api-quickstart` consume the public coordinate.

## Current public line

The current public coordinate and its publication evidence are recorded in
[release readiness](docs/release-readiness.md). The active release line remains
`0.1.0-beta.*`; the source implements engine contract `1.6`. The runtime contract
is an exact compatibility boundary: 1.4 compositions require governed migration
and new approval before execution with 1.6. Publication does not establish host
adoption. See the [contract reference](docs/contracts-reference.md#compatibilidade-publicada).

Beta.14 advertised a corpus hash different from its packaged bytes. Beta.15
corrected that drift but failed a Windows checkout audit because LF/CRLF was not
governed. A successor must pass Linux and Windows CI and a Central-only smoke
that checks both the advertised corpus hash and packaged bytes.

Compatible work remains on the active `0.1.0-beta.*` line. A new major requires
an explicitly governed breaking-change plan, consumer impact map, migration
guidance and downstream validation.
