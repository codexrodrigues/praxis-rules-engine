# P2F-ADR-11 — Transformações tipadas como propostas puras

- Data: 2026-07-15
- Estado: aceito, publicado em `0.1.0-beta.12` e consumido pelo Quickstart
- Classificação: arquitetural, transversal e contrato público
- Owners: `praxis-rules-engine` (contrato e validação) e host consumidor (autorização e materialização)

## Contexto

Decisões corporativas frequentemente precisam recomendar uma alteração no
write model: normalizar um valor monetário, remover um atributo incompatível ou
converter uma representação temporal. Retornar apenas um `JsonNode output` não
expressa tipo, destino, valor anterior, proveniência nem concorrência. Permitir
que o engine aplique a mudança, por outro lado, introduziria mutação, I/O e
autoridade de negócio no core determinístico.

## Decisão

O novo stage `TRANSFORMATION_INTENT` aceita apenas executores Java confiáveis e
exige dependência explícita de uma decisão anterior. O executor retorna
`TransformationDraft`; o engine valida e enriquece o draft como
`TypedTransformationProposal`. O engine nunca altera os facts e nunca persiste
ou executa a proposta.

Cada proposta contém:

- chave estável, `bindingKey` e `slotKey` para proveniência;
- `targetPath` fechado em propriedades pontuadas e pertencente a uma raiz
  declarada em `availableRoots`;
- `schemaRef` absoluto com fragmento;
- operação `SET` ou `REMOVE`;
- valores `before` e `after` com presença explícita e tipo declarado;
- digests estáveis dos valores e reason code estável.

Os tipos públicos são `STRING`, `INTEGER`, `NUMBER`, `BOOLEAN`, `DATE`,
`DATE_TIME`, `UUID`, `OBJECT`, `ARRAY` e `NULL`. Ausência é distinta de JSON
`null`. O engine rejeita tipo incompatível, valor acima dos limites do runtime,
`before` divergente do snapshot, no-op, chave ou destino duplicado, destino fora
das raízes do RuleSet, output genérico no stage de transformação e proposta
associada a decisão diferente de `ALLOW`.

Propostas só aparecem no agregado de `RuleEvaluationResult` quando a decisão
consolidada é `ALLOW`. Em qualquer conflito ou violação, o resultado é
`TECHNICAL_ERROR` com reason code estável e sem proposta materializável.

## Fronteira do host

O host é exclusivamente responsável por:

1. autorizar o ator e o campo de destino;
2. resolver `schemaRef` contra o schema governado e validar o valor final;
3. comparar versão/ETag e o valor `before` antes do write;
4. aplicar todas as propostas aceitas na mesma unidade transacional do comando;
5. manter idempotência, auditoria, segregação por tenant e política de retenção;
6. impedir que valores sensíveis sejam enviados a logs, métricas ou traces.

Os digests permitem correlação redigida, mas não substituem criptografia nem
controle de acesso. O envelope contém os valores necessários à materialização e
deve ser tratado como dado de negócio potencialmente sensível.

## Compatibilidade

O contrato do engine passa de `1.1` para `1.2`. A mudança é aditiva na linha
pública beta: construtores anteriores permanecem disponíveis, não há alteração
no dialeto JSON Logic e não há motivo para nova major. A próxima publicação deve
continuar em `0.1.0-beta.*`, exclusivamente pelo workflow oficial baseado em
tag. Nenhuma publicação é autorizada por este ADR.

## Evidência e gate downstream

`TypedTransformationContractTest` prova imutabilidade dos facts, proveniência,
tipos, ausência versus null, limites, conflitos, snapshot stale, raízes,
dependência e executor confiável. O Quickstart consumiu `0.1.0-beta.12` sem
override local, validou allowlist/schema no adapter e persistiu o valor na
transação existente. A auditoria append-only dos digests permanece gate
corporativo do host, não responsabilidade do engine.
