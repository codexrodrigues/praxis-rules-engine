# Arquitetura do runtime

## Invariantes

- runtime stateless, thread-safe, sem I/O e sem Spring;
- uma avaliacao usa facts e tempo congelados;
- ausente e `null` sao distintos internamente;
- validacao usa os descriptors publicados para aridade e disponibilidade; o `operatorCatalog` do corpus compartilhado bloqueia drift de nome, origem e aridade entre Java e TypeScript;
- paths, regex e limites falham com codigos estaveis;
- os contratos runtime-neutros, planner e evaluator de QL-02 pertencem a este
  modulo conforme [P2F-ADR-01](p2f-adr-01-runtime-contract-ownership.md) e ja
  integram a linha beta publica;
- envelope governado de snapshot, approval, ETag, head ativo e rollback
  permanecem no `praxis-config-starter`; contratos Ergon nao pertencem a este
  modulo.

## Pipeline

`JsonNode -> limites estruturais -> validacao/preparacao -> avaliacao com budget -> valor publico + truthiness`.

O sentinel de ausencia nunca atravessa a API publica. Resultados ausentes sao materializados como `null`, mas `var` com default decide antes dessa materializacao.

## Extensao

Operadores de host sao aditivos, exigem namespace (`corp:operator`) e descriptor completo. Uma regra persistida com operador de host so pode ser publicada quando todos os runtimes que a avaliarao tiverem o mesmo descriptor e semantica.

## Limites default

Profundidade 64; 10.000 nos; 256 KB de expressao/resultado; 10.000 itens por array; 64 KB por string; 50.000 operacoes; regex de 512 caracteres e complexidade 64. Limites menores podem ser definidos por avaliacao. Java e TypeScript verificam estrutura antes da execucao, aplicam limite incremental ao agregar arrays e verificam o resultado antes de atravessar a API publica. O TypeScript tambem limita concatenacao intermediaria de strings.

Regex aceita somente o subconjunto comum limitado: literais, escapes, classes, ancoras, `?` e quantificadores `{n}`/`{n,m}` com maximo 256. Grupos, alternancia, backreferences, `*` e `+` sao rejeitados antes da compilacao.

## Composicao RuleSet

`RuleSetDefinition -> validacao de compatibilidade e referencias -> DAG
topologicamente ordenado -> RuleDecisionPlan -> avaliacao com facts congelados
-> RuleEvaluationResult`.

Slots singulares usam `SINGLE_RESULT`; slots multiplos declaram explicitamente
`DENY_OVERRIDES`. Chaves e versoes de executores Java sao exatas. O plano fixa
engine contract, dialect e SHA-256 do corpus, rejeita roots desconhecidos,
ciclos, dependencias futuras, fan-out excessivo e overrides incompativeis.
Facts e outputs Java atravessam os mesmos limites estruturais do runtime JSON
Logic. `EFFECT_INTENT` e `TRANSFORMATION_INTENT` nao executam enquanto uma
decisao anterior estiver inconclusiva e exigem dependencia explicita. O segundo
aceita somente executor Java confiavel, valida propostas tipadas contra o
snapshot e as raizes declaradas, mas nunca altera facts nem materializa writes.
Effects, autorizacao, schema governado, concorrencia e transacao permanecem no
host. A decisao final usa os bindings terminais do DAG; um
`ALLOW` intermediario nao converte um ramo terminal `NOT_APPLICABLE` em sucesso.

Observacao, duracao, metricas, logs, auditoria, legal hold e retencao tambem
permanecem fora do core, conforme
[P2F-ADR-12](p2f-adr-12-observation-redaction-and-retention.md). O host projeta
somente uma allowlist sanitizada e nunca devolve telemetria ao resultado
deterministico.

## Evolucao aceita

O pacote [ADRs da plataforma de regras](p2f-rule-platform-adrs.md) separa
contrato de execucao, control plane e responsabilidades do host. A
implementacao de planner/composicao preserva todos os invariantes acima e
continua sem Spring, I/O, persistencia, tenant store, cache ou effects.
