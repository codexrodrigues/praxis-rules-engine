# P2F-ADR-05 — protected rules e extensões Java de cliente

- Estado: `ACCEPTED_FOR_ENGINE_1_3`
- Data: 2026-07-15
- Classificação: `arquitetural`, `contrato-publico` e `transversal`

## Decisão

Protected guards continuam estruturalmente `FORBIDDEN` e nunca aceitam binding
de cliente, mesmo quando a implementação possui assinatura válida. Uma extensão
Java de cliente só pode participar de um slot explicitamente customizável quando
o registry do control plane e o registry executável do host carregam uma
attestation externa para a coordenada exata `implementationKey@version`.

`RuleExtensionTrust` registra somente evidência segura e determinística:

- SHA-256 do artefato executável exato;
- identidade namespaced do assinante verificado;
- chave namespaced e versionada da política de trust;
- SHA-256 da evidência ou bundle de verificação imutável.

O engine não acessa arquivo, certificado, transparency log, secret manager ou
rede e não verifica criptografia. O pipeline de supply chain ou o host realiza
essa verificação antes de construir `RuleBindingExecutorRegistry`. O core
confere que uma extensão `CUSTOMER + JAVA` possui attestation, inclui a evidência
no `planDigest` e a preserva em `RuleEvaluationResult.implementationRefs`.

## Fronteiras de trust

1. O snapshot solicita somente uma coordenada Java namespaced e versionada; ele
   nunca declara a si próprio confiável.
2. O control plane valida publicação contra um catálogo externo de
   implementações admitidas. Derivar a allowlist do próprio payload publicado é
   self-attestation e permanece proibido.
3. O host verifica o artefato real e constrói o registry executável com a mesma
   coordenada e attestation.
4. O engine compila o plano somente quando a coordenada é exata e, para código
   de cliente, a attestation existe.
5. Trocar artefato ou evidência, mesmo mantendo key e versão, muda o
   `planDigest`; o resultado identifica o artefato efetivamente usado.
6. O plano carrega as coordenadas attestadas e o evaluator as compara com o
   registry executável antes da chamada. Divergência falha com
   `IMPLEMENTATION_TRUST_MISMATCH` sem executar o plugin.

## Matriz de política

| Origem e target | Condição | Resultado |
| --- | --- | --- |
| `CUSTOMER` em `PROTECTED_GUARD` | qualquer executor ou assinatura | rejeitado pela política `FORBIDDEN` |
| `CUSTOMER` em qualquer slot `FORBIDDEN` | qualquer composição | rejeitado |
| `CUSTOMER + JSON_LOGIC` | slot e composição permitem | aceito após validação normal do dialeto |
| `CUSTOMER + JAVA` | slot permite, coordenada exata e attestation externa presente | aceito |
| `CUSTOMER + JAVA` | registry ausente, versão divergente ou sem attestation | fail closed com `PLAN_IMPLEMENTATION_UNAVAILABLE`, `PLAN_COMPATIBILITY_INVALID` ou `PLAN_EXTENSION_TRUST_INVALID` |
| `PRODUCT/SECURITY + JAVA` | implementação incorporada ao host | segue a política normal de supply chain do produto; a attestation ADR-05 não é obrigatória |

## Threat model

O contrato bloqueia:

- class name, URL, JAR ou script arbitrário vindo do snapshot;
- extensão de cliente não cadastrada ou com versão divergente;
- substituição silenciosa de binário sem alteração do digest do plano;
- substituição do registry entre compilação e avaliação;
- tentativa de usar assinatura para contornar autorização, integridade ou outro
  protected guard;
- promoção baseada em self-attestation do payload.

O contrato não transforma o mesmo processo JVM em sandbox. Assinatura comprova
identidade/integridade conforme uma política; não comprova qualidade nem ausência
de comportamento malicioso. Por isso, somente código previamente revisado e
confiável pode ser carregado como `RuleBindingExecutor`. Código hostil,
multi-tenant arbitrário ou enviado dinamicamente por usuário não entra no
processo: deve permanecer declarativo ou ser isolado por uma boundary externa
governada, sem mover I/O para o engine.

## Operação e rollback

- revogação remove a coordenada do catálogo/registry e faz a próxima preparação
  falhar antes da ativação;
- rollback troca atomicamente para snapshot e registry previamente aprovados;
- active head, rollout, revogação, armazenamento de bundles, SBOM, assinatura e
  auditoria operacional permanecem no control plane, pipeline e host;
- nenhuma evidência ADR-05 autoriza regra Ergon, preflight ou promoção de
  autoridade.

## Evidência mínima

- cliente Java sem attestation é rejeitado;
- extensão attestada em slot `REPLACEABLE` compila e avalia;
- digest do artefato ou da verificação altera o `planDigest`;
- substituição entre compile/evaluate falha antes de executar a extensão;
- resultado carrega a attestation exata;
- extensão assinada continua proibida em protected guard;
- planning registry e registry executável aplicam a mesma regra fail-closed.
