# P2F-ADR-09 — cache, hot reload e last-known-good

- Estado: aceito para QL-03
- Data: 2026-07-13
- Classificação: `arquitetural` e `transversal`

## Decisão

O host mantém uma única referência atômica para um `CompiledRuleSnapshot`.
Carregamento e reload ocorrem fora do caminho de avaliação:

1. ler o head com ETag e escopo explícitos;
2. desserializar o envelope público do engine;
3. validar snapshot contract, host contract, executores Java e RuleSet;
4. compilar o plano e confirmar o `snapshotContentHash`;
5. trocar a referência ativa em uma única operação atômica.

Uma avaliação captura a referência uma vez e termina integralmente nessa
versão. Mistura de versões ou ativação parcial é proibida.

## Falhas

- startup sem snapshot válido falha fechado;
- `304 Not Modified` preserva a referência atual;
- rede, parse, incompatibilidade, implementação ausente, hash divergente ou
  plano inválido preservam o last-known-good e produzem diagnóstico;
- um snapshot inválido nunca substitui o ativo;
- rollback usa o mesmo caminho de preparação e troca atômica de uma publicação
  nova.

## Fronteiras

O engine não faz HTTP, cache, retry, polling ou observação. O Config Starter não
mantém cache de execução no host. O adapter do host não redefine snapshot,
ETag, compatibilidade ou rollback.
