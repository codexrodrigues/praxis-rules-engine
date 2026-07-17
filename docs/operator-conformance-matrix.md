# Matriz de conformidade de operadores

Todos os operadores abaixo estao no tipo/registry TypeScript e no registry Java. `native` usa namespace reservado; `praxis` usa namespace `praxis`.

| Categoria | Operadores | Aridade | Retorno | Null/ausente e decisao |
| --- | --- | --- | --- | --- |
| native | `var` | 1..2 | unknown | default lazy somente para ausente; aridade, path e expressão default são validados integralmente |
| native | `== != === !==` | 2 | boolean | coercao fechada; estruturas por valor |
| native | `> >= < <=` | 2 | boolean | numero-numero ou string-string |
| native | `! !!` | 1 | boolean | truthiness Praxis |
| native | `and or if` | 1+/2+ | unknown | short-circuit |
| native | `in` | 2 | boolean | string/array; shape diferente e erro |
| native | `cat substr` | 1+/2..3 | string | conversao documentada |
| native | `merge map filter reduce all some none` | 1+/2..3 | variado | fonte deve ser array |
| native | `+ - * / % min max` | 1+/2 | number | somente numero finito |
| praxis | `contains startsWith endsWith` | 2 | boolean | alvo ausente/null retorna false |
| praxis | `matches` | 2 | boolean | subset limitado sem grupos, alternancia, backreferences, `*` ou `+`; bounds ate 256 |
| praxis | `isBlank len` | 1 | boolean/number | vazio, null e ausente explicitos |
| praxis | `round ceil floor abs` | 1 | number | numero finito |
| praxis | `coalesce` | 1+ | unknown | ignora ausente, null e string vazia |
| praxis contextual | `now` | 0 | number | exige `nowUtc` |
| praxis | `date toNumber stringify jsonGet hasKey` | 1..2 | variado | parsing/path fechado |
| praxis contextual | `yearsSince monthsSince daysSince isToday inLast weekdayIn` | 1..3 | variado | exige tempo e timezone congelados; `inLast` aceita somente day(s), week(s), month(s) |

Operadores host nao fazem parte do dialeto persistivel por default. Sua convergencia depende de descriptor e implementacao equivalentes em todos os runtimes.

O corpus tambem governa erros de avaliacao estruturados. `operatorCatalog` e a fonte machine-readable normativa de nome, origem e aridade; testes em Java e TypeScript bloqueiam qualquer drift dos descriptors empacotados.
