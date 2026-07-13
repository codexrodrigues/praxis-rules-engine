# Arquitetura do runtime

## Invariantes

- runtime stateless, thread-safe, sem I/O e sem Spring;
- uma avaliacao usa facts e tempo congelados;
- ausente e `null` sao distintos internamente;
- validacao usa os descriptors publicados para aridade e disponibilidade; o `operatorCatalog` do corpus compartilhado bloqueia drift de nome, origem e aridade entre Java e TypeScript;
- paths, regex e limites falham com codigos estaveis;
- nenhum contrato de composicao, RuleSet, snapshot ou Ergon pertence a este modulo.

## Pipeline

`JsonNode -> limites estruturais -> validacao/preparacao -> avaliacao com budget -> valor publico + truthiness`.

O sentinel de ausencia nunca atravessa a API publica. Resultados ausentes sao materializados como `null`, mas `var` com default decide antes dessa materializacao.

## Extensao

Operadores de host sao aditivos, exigem namespace (`corp:operator`) e descriptor completo. Uma regra persistida com operador de host so pode ser publicada quando todos os runtimes que a avaliarao tiverem o mesmo descriptor e semantica.

## Limites default

Profundidade 64; 10.000 nos; 256 KB de expressao/resultado; 10.000 itens por array; 64 KB por string; 50.000 operacoes; regex de 512 caracteres e complexidade 64. Limites menores podem ser definidos por avaliacao. Java e TypeScript verificam estrutura antes da execucao, aplicam limite incremental ao agregar arrays e verificam o resultado antes de atravessar a API publica. O TypeScript tambem limita concatenacao intermediaria de strings.

Regex aceita somente o subconjunto comum limitado: literais, escapes, classes, ancoras, `?` e quantificadores `{n}`/`{n,m}` com maximo 256. Grupos, alternancia, backreferences, `*` e `+` sao rejeitados antes da compilacao.
