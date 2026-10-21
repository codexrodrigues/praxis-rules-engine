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
- regra Java de cliente exige o processo assinado/allowlisted e a attestation
  externa definidos pelo [P2F-ADR-05](p2f-adr-05-protected-extensions.md);
  class name arbitrário não é payload válido.

## Validação negativa obrigatória

Publicação rejeita policy desconhecida, fonte incompatível, override proibido,
parameter fora da faixa, replacement parcial/global, binding órfão, duplicidade
de chave e tentativa de alterar protected slot.

## Restrição executável — fonte engine contract 1.6

Estado: implementado nesta fonte candidata, sem publicação Maven comprovada.
O inventário de #23 classificou enums/políticas como `suportado-parcialmente` e
schema/vínculo executável como `lacuna-real-de-contrato`. A fonte canônica desta
restrição é o Engine; Config governa a origem aprovada e projeta seu catálogo.

`DecisionSlot.parameterTemplate` é obrigatório em todo slot `PARAMETERIZABLE`
e proibido nas outras políticas. Ele publica `RuleParameterTemplate` com
`templateKey` namespaced, `templateVersion` exata, executor JSON Logic,
false outcome/reason, facts obrigatórios e parâmetros. Cada
`RuleParameterDefinition` declara nome exato, tipo fechado (`STRING`, `NUMBER`,
`INTEGER`, `BOOLEAN`), `required`, `nullable`, `readOnly`, unidade opcional,
default explícito, limites numéricos inclusivos, enum e `expressionPointers`.
A unidade é metadado semântico imutável, sem conversão implícita de medidas.
`INTEGER` compara o valor matemático: `10` e `10.0` são equivalentes.

O único escopo executável parametrizável desta revisão é JSON Logic com
substituição de literais escalares. Cada parâmetro precisa apontar para ao menos
um literal existente; não pode substituir raiz, containers, nomes de operadores,
endereçamento `var`/`missing`, nem compartilhar ou sobrepor sites com outro
parâmetro. Os sites usam JSON Pointer RFC 6901, distinto dos paths de facts.
O resolver valida forma, operadores, aridade e paths usando o registry canônico
built-in; operadores de extensão do host ainda não são admitidos em templates
parametrizáveis. O compilador final valida também as roots reais do RuleSet.
Não há novo operador ou novo dialeto JSON Logic. Template/executor Java para
`PARAMETERIZE` falha explicitamente; attestation de extensões Java não autoriza
parametrização por este contrato.

O source binding `CUSTOMER` + `PARAMETERIZE` declara somente
`parameterization: {templateKey, templateVersion, values}` e os metadados de
composição existentes. `executor`, `falseDecision`, `falseReasonCode` devem
estar ausentes/nulos e `requiredFactPaths` vazio. O compilador deriva todos
esses campos do template exato, impedindo que a escolha de valores vire troca
de expressão, executor, facts obrigatórios ou resultado falso.

O resolver público puro
`RuleParameterResolver.resolve(RuleParameterTemplate, ObjectNode)` devolve
`RuleParameterResolution(executor, effectiveValues, origins)`, sem criar
RuleSet fictício, acessar facts, executar host ou fazer I/O. `origins` distingue
`DECLARED` de `DEFAULT`; presença explícita de `null`, zero e false não é
omissão. Defaults precisam satisfazer o schema e coincidir com os literais
originais. Parâmetros opcionais e read-only exigem default explícito;
`required` sem default exige valor declarado. Read-only admite somente valor
igual ao default. JSON null exige `nullable`, inclusive em defaults/enums.
Java null em `defaultValue` representa ausência e é omitido na serialização.

Exemplo neutro de template do slot e valores do binding:

```json
{
  "parameterTemplate": {
    "templateKey": "neutral:threshold",
    "templateVersion": "1",
    "executor": {
      "type": "JSON_LOGIC",
      "implementationKey": null,
      "implementationVersion": "1.0",
      "expression": {">": [{"var": "request.amount"}, 10]}
    },
    "falseDecision": "DENY",
    "falseReasonCode": "BELOW_LIMIT",
    "requiredFactPaths": ["request.amount"],
    "parameters": [{
      "name": "limit", "type": "INTEGER", "required": false,
      "nullable": false, "readOnly": false, "unit": "units",
      "defaultValue": 10, "minimum": 10, "maximum": 30,
      "allowedValues": [], "expressionPointers": ["/>/1"]
    }]
  },
  "parameterization": {
    "templateKey": "neutral:threshold", "templateVersion": "1",
    "values": {"limit": 20}
  }
}
```

Com `request.amount = 15`, `limit = 10` produz `ALLOW`; `limit = 20` produz
`DENY`. Falta de `request.amount` produz `INCONCLUSIVE`, preservando os cinco
estados e as regras do DAG. A condição materializada é derivada; a aprovação
Config precisa vincular template/valores à definição governada de produto.
O Engine não autentica a alegação de ownership do conteúdo recebido.

Limites: 128 parâmetros, 256 sites totais, pointer até 4096 caracteres/64
segmentos, além dos limites JSON nativo/finito, profundidade, nós, strings e
256 KB canônicos compartilhados. O limite também é aplicado à expansão
combinada durante cada substituição. Nenhum serializer POJO participa da
admissão dos valores.

Diagnósticos estáveis: `PLAN_PARAMETER_TEMPLATE_INVALID` para restrição
inválida/ausente/não suportada; `PLAN_PARAMETER_VALUES_INVALID` para valores
inválidos; `PLAN_PARAMETERIZATION_INVALID` para identidade incorreta ou
metadados executáveis authorados pelo cliente. O compilador preserva
`bindingKey` quando a falha é associada ao binding. Validações do dialeto,
roots, DAG, cardinalidade e trust mantêm os códigos existentes.

O plano guarda a definição source e bindings executáveis derivados. O digest
usa JSON canônico com slots/templates completos, bindings source, bindings
compilados e coordenadas/trust exatos. Schema, unidades, identidade/versão,
valores explícitos e expressão derivada integram a evidência. Snapshots
normalizam a ordem preservando bindings source; recompilação restaura a mesma
restrição e o mesmo digest, sem serializar o executor derivado como source.

## Migração beta e impacto

Contract `1.6` rejeita `1.5`; dialeto `1.0` e corpus permanecem intactos.
Snapshots e bindings antigos sob o rótulo `PARAMETERIZE` não são aceitos por
compatibilidade: reauthorar template e valores, simular, aprovar e republicar.
Slots Java apenas rotulados `PARAMETERIZABLE` precisam corrigir sua política;
não devem anunciar suporte inexistente. Os construtores públicos de slot e
binding ganham seus campos finais, sem overloads que escondam a migração.

Consumidores afetados: Config authoring/publicação/catálogo, factories e
admissão do host Quickstart, Policy Studio/Angular via projeção Config, docs
públicas e corpus HTTP quando adotarem a nova coordenada. Não há nova aresta de
dependência entre libs Angular, endpoint ou alteração do corpus JSON Logic.
Adoção e provas downstream requerem release oficial; o POM candidato
`0.1.0-beta.16-SNAPSHOT` identificou a prova histórica de desenvolvimento isolado;
a coordenada preparada para publicação é `0.1.0-beta.17`.
