# P2F-ADR-13 — hardening determinístico das fronteiras

- Estado: `ACCEPTED_FOR_ENGINE_1_4`
- Data: 2026-07-17
- Classificação: `arquitetural`, `contrato-publico` e `transversal`

## Decisão

O engine contract `1.4` fecha quatro ambiguidades que não podem permanecer como
detalhes de implementação:

1. o default composto de `var` é uma expressão JSON Logic avaliada de forma
   lazy somente quando o path está ausente;
2. todo `EFFECT_INTENT` ou `TRANSFORMATION_INTENT` possui ao menos uma
   dependência direta em `DOMAIN_DECISION` ou `POST_DECISION`, sem proibir
   dependências adicionais válidas de ordenação;
3. a coordenada declarativa usa exatamente a versão do dialeto aceita pelo
   runtime;
4. outputs individuais e o envelope público consolidado obedecem limites
   determinísticos, falhando sem payload parcial.

O resultado consolidado aceita no máximo 1.024 reason codes, 256 propostas de
transformação e 256.000 bytes. Excesso produzido por um executor retorna
`IMPLEMENTATION_RESULT_LIMIT_EXCEEDED`; expansão combinada retorna
`EVALUATION_RESULT_LIMIT_EXCEEDED`. O compilador rejeita antecipadamente um
plano cujo envelope técnico mínimo não caiba nesses limites.

Razões de bindings terminais `NOT_APPLICABLE` permanecem no resultado final
quando nenhuma decisão terminal permite o caso. Isso preserva explicabilidade
sem elevar um `ALLOW` intermediário.

## Compatibilidade

As novas validações podem rejeitar RuleSets ou resultados anteriormente aceitos.
Por isso a mudança eleva explicitamente o engine contract de `1.3` para `1.4` e
será publicada na próxima beta da linha ativa, sem criar contrato paralelo.
`RuleExtensionTrust`, protected guards, attestation no digest e
`IMPLEMENTATION_TRUST_MISMATCH` permanecem integralmente preservados.

## Evidência obrigatória

- RFC, TypeScript, Java e corpus concordam sobre default lazy de `var`;
- índices e bounds numéricos gigantes produzem códigos estruturados;
- intents sem decisão de negócio direta são rejeitados;
- dependências adicionais válidas não são rejeitadas;
- versão divergente do dialeto é rejeitada no planejamento;
- expansão agregada falha sem resultado parcial;
- o envelope técnico mínimo é provado na compilação;
- toda a suíte de trust do contract `1.3` continua verde.
