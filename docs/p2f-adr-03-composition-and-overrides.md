# P2F-ADR-03 — composição, fontes e overrides

- Estado: `ACCEPTED_FOR_QL_02`
- Data: 2026-07-13
- Classificação: `arquitetural` e futura `contrato-publico`

## Decisão

`DecisionSlot` é a unidade estável de composição. Um `DecisionBinding` declara
fonte, executor, condição, dependências, estado e política. Produto, cliente,
segurança e infraestrutura são fontes explícitas; posição não define owner.

Existem dois vocabulários diferentes:

| Vocabulário | Valores | Pergunta respondida |
| --- | --- | --- |
| composição | `AUGMENT`, `RESTRICT`, `PARAMETERIZE`, `REPLACE_EXACT` | como o binding participa da decisão? |
| override do produto | `FORBIDDEN`, `RESTRICT_ONLY`, `PARAMETERIZABLE`, `REPLACEABLE` | o que o tenant pode configurar neste slot? |

Eles não são aliases. A publicação valida a combinação:

| Override do produto | Composição admitida do cliente |
| --- | --- |
| `FORBIDDEN` | nenhuma |
| `RESTRICT_ONLY` | `RESTRICT`; `AUGMENT` somente em slot múltiplo com `DENY_OVERRIDES` |
| `PARAMETERIZABLE` | `PARAMETERIZE` dentro do schema/faixa |
| `REPLACEABLE` | `REPLACE_EXACT` para o slot exato |

`AUGMENT` só é aceito em slot que declare cardinalidade múltipla e o agregador
fechado `DENY_OVERRIDES`. Ele adiciona uma decisão independente, mas não pode
levantar um `DENY` nem contornar protected guards.

## Guardrails

- `BYPASS_ALL` e supressão regional genérica não existem;
- segurança, autorização, integridade e auditoria obrigatória usam
  `FORBIDDEN` por default;
- customização nunca converte `DENY` protegido em `ALLOW`;
- `DISABLED` é estado de binding, não política;
- parâmetro possui schema, faixa, unidade e default publicados;
- replacement aponta para um único `slotKey` marcado `REPLACEABLE`;
- regra Java de cliente exige processo de plugin assinado/allowlisted definido
  pelo futuro P2F-ADR-05; class name arbitrário não é payload válido.

## Validação negativa obrigatória

Publicação rejeita policy desconhecida, fonte incompatível, override proibido,
parameter fora da faixa, replacement parcial/global, binding órfão, duplicidade
de chave e tentativa de alterar protected slot.
