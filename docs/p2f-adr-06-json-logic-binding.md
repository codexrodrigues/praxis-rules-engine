# P2F-ADR-06 — binding ao dialect JSON Logic Praxis

- Estado: `ACCEPTED`; baseline semântico já executável
- Data: 2026-07-13
- Classificação: `arquitetural` e `contrato-publico`

## Owner normativo

O RFC, registry e corpus de `@praxisui/core` continuam sendo a fonte normativa
do dialect JSON Logic. `praxis-rules-engine` é a implementação Java canônica e
deve provar paridade pelo mesmo corpus. Este ADR não cria outro dialect.

## Binding persistido

Todo binding JSON Logic publicável declara:

- identificador/version do dialect;
- SHA-256 do corpus normativo;
- expressão validada;
- roots e facts permitidos;
- `nowUtc` e `userTimeZone` quando houver operador contextual;
- limites iguais ou menores que o baseline;
- descriptors requeridos de operadores de host.

Ausência e `null` permanecem distintos. Paths, coerção, truthiness, regex,
datas, aridade, short-circuit e códigos de erro seguem o RFC; o RuleSet não os
redefine.

## Operadores de host

Operadores de host são aditivos, namespaced, puros e introspectáveis. Uma regra
persistida só é publicada se todo runtime alvo comprovar descriptor e
implementação compatíveis. Names nativos são reservados; override implícito é
proibido. Regras de negócio compostas devem preferir slots/bindings, não um
operador opaco.

## Gate

QL-02 usa o corpus empacotado da versão pública do engine. Qualquer mudança de
semântica exige, no mesmo ciclo, RFC, registry TypeScript, runtime Java, corpus,
matriz de operadores e testes dos consumidores. DSL textual, JSONPath amplo,
timezone do processo e fallback permissivo permanecem proibidos.
