# Extensoes e transformacoes

## Operadores JSON Logic de host

O engine permite extensoes aditivas por `PraxisJsonLogicEngine.withHostOperators(...)`. Cada operador deve possuir descriptor completo e nome namespaced, por exemplo `finance:isBusinessDay`. Nomes nativos e Praxis publicados sao reservados; colisao deve falhar em vez de alterar silenciosamente a semantica persistida.

Use operador de host apenas para uma operacao atomica que exista com semantica equivalente em todos os runtimes que avaliarao a regra. Uma decisao composta de negocio deve ser um RuleSet governado, nao uma funcao opaca. O catalogo retornado por `listOperatorDescriptors()` deve alimentar editor, autocomplete, validacao e IA.

## Transformacoes tipadas

`TRANSFORMATION_INTENT` permite que um executor Java puro proponha uma alteracao, nunca a aplique. A proposta valida:

- chave estavel, path de destino fechado e root declarada;
- `schemaRef` URI absoluto com fragmento;
- operacao fechada (`SET` ou `REMOVE`);
- `before` e `after` tipados, distinguindo ausencia de `null`;
- reason code estavel, proveniencia de binding/slot e digests redigidos.

`SET` exige `after` presente. `REMOVE` exige `before` presente e `after` ausente. Uma proposta deve alterar o valor e nao pode duplicar destino. Transformacoes exigem executor Java confiavel e dependencia explicita de decisao de dominio ou pos-decisao.

O host continua dono de autorizacao, allowlist de campos, validacao do schema governado, ETag, transacao, persistencia e auditoria. Se qualquer uma dessas verificacoes falhar, trate como falha tecnica/projecao; nao invente um novo RuleSet nem converta a falha em `DENY`.

## Confianca de extensao Java

Na candidata do engine contract `1.3`, uma coordenada Java de cliente e admitida pelo control
plane somente quando o catalogo externo do host fornece `RuleExtensionTrust`.
O atestado contem o SHA-256 do artefato, a identidade de assinatura, a chave da
politica de confianca e o SHA-256 da evidencia de verificacao. O snapshot nao
carrega nem decide essa evidencia: o host a verifica fora do processo de
publicacao e o `DomainRuleImplementationCatalog` a fornece por escopo.

Isso nao transforma o engine em sandbox ou carregador de plugins. O Config
Starter apenas valida a coordenada atestada no modo de planejamento; o host
continua responsavel por decidir se instancia codigo confiavel e por montar o
registry executavel na ativacao.
