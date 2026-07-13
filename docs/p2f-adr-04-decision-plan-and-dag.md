# P2F-ADR-04 — stages, plano, DAG e short-circuit

- Estado: `ACCEPTED_FOR_QL_02`
- Data: 2026-07-13
- Classificação: `arquitetural` e futura `contrato-publico`

## Stages fechados

A ordem entre stages é fixa:

```text
PROTECTED_GUARD -> DOMAIN_DECISION -> POST_DECISION -> EFFECT_INTENT
```

Bindings declaram `dependsOn`. Um inteiro de ordem é apenas desempate estável
dentro do mesmo stage; não substitui dependência semântica.

## Compilação

A publicação preserva o grafo original. O planner do engine:

1. valida identidade, stage, slot, binding e executor;
2. rejeita dependência ausente, cross-boundary ou para stage posterior;
3. rejeita ciclos por ordenação topológica;
4. ordena nós prontos por stage, ordem declarada e `bindingKey`;
5. rejeita duplicidade ou ambiguidade em vez de usar ordem de coleção;
6. produz `RuleDecisionPlan` imutável com ordem resolvida e digest canônico.

O host avalia um plano capturado uma vez. Hot reload não mistura versões.

## Cardinalidade e agregação

- slot singular aceita um binding efetivo;
- slot múltiplo exige o agregador fechado e determinístico `DENY_OVERRIDES`;
- `RESTRICT` usa deny-overrides;
- `NOT_APPLICABLE` não equivale a `ALLOW`;
- output intermediário só alimenta dependente declarado;
- transformação entre shapes precisa de contrato tipado futuro, nunca mutação
  escondida em JSON Logic.

## Short-circuit

- `DENY` em `PROTECTED_GUARD` encerra a avaliação;
- `TECHNICAL_ERROR` encerra o plano e não vira negativa de negócio;
- `INCONCLUSIVE` impede dependentes, mas ramos independentes continuam para que
  um `DENY` real não seja ocultado; ao final segue a fail policy da boundary;
- cálculo não executa após negativa consolidada;
- `EFFECT_INTENT` somente planeja intenções após `ALLOW` consolidável e nunca
  admite `DENY` como resultado de planejamento;
- nenhum stage executa I/O, transação ou efeito dentro do engine.

## Limites

O plano limita slots, bindings, arestas e fan-out. Facts, expressões e outputs
usam limites de bytes, profundidade, nós, arrays e strings na avaliação. Timeout dependente de
wall clock não altera semântica; o runtime usa budgets determinísticos e o host
pode impor deadline externo.
