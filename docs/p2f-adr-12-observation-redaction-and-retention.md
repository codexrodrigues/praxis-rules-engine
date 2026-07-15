# P2F-ADR-12 — Observação, redaction e retenção governada

- Data: 2026-07-15
- Estado: aceito; contrato exercitado no Quickstart QL-06/QL-08
- Classificação: arquitetural e transversal
- Owners: `praxis-rules-engine` (resultado determinístico) e host/infraestrutura (observação, acesso e ciclo de vida)

## Contexto

Uma avaliação corporativa precisa ser explicável e operável sem transformar
facts, valores, identidades ou exceções em telemetria de alta cardinalidade. O
resultado puro deve continuar reproduzível; duração, amostragem, dual-run,
exportação, auditoria e retenção dependem do ambiente e não podem alterar a
decisão.

Também existe uma tensão real entre imutabilidade e retenção: registros
auditáveis não podem ser alterados ou apagados pela aplicação, mas políticas
legais exigem expurgo autorizado e legal hold. Tratar `append-only` como
retenção infinita seria incorreto.

## Decisão

`RuleEvaluationResult` permanece o único resultado determinístico do engine. O
engine não publica `RuleObservation`, duração, trace, métrica, tenant, ator,
correlation ID ou política de retenção. O host pode projetar uma observação a
partir do resultado, mas essa projeção não volta para o core e não adquire
autoridade de negócio.

Existem três superfícies distintas:

1. **resultado determinístico** — decisão, reason codes, coordenadas exatas,
   digests e propostas puras pertencentes ao engine;
2. **observação operacional** — comparação, duração e estados sanitizados,
   produzidos pelo host e exportados para infraestrutura de observabilidade;
3. **auditoria durável** — ato administrativo ou materialização autorizada,
   persistidos pelo host em ledger protegido.

Nenhuma delas pode ser usada como cópia oportunista das demais.

## Allowlist e classificação

Observações e métricas podem conter somente:

- identificador aleatório de observação;
- instante UTC, estados enumerados e booleans de equivalência;
- identidade técnica versionada de RuleSet/snapshot/implementação;
- digests canônicos necessários à correlação;
- contadores e durações com tags de cardinalidade fechada.

São proibidos por default em observações, logs, traces e tags:

- facts, payloads, valores `before`/`after` e outputs livres;
- ator, authorities, tenant, empresa, usuário ou referência de negócio;
- tokens, cookies, SQL, headers, stack trace e mensagem de exceção;
- reason text localizado ou qualquer texto livre não classificado.

Reason codes estáveis podem existir em auditoria protegida quando necessários,
mas não viram tags de métrica sem catálogo fechado e avaliação explícita de
cardinalidade.

Um digest não é criptografia nem anonimização. Valores de baixa entropia não
devem ser publicados apenas porque foram submetidos a SHA-256. Referências
externas de autorização devem usar HMAC com segredo mantido fora do banco.

## Retenção e legal hold

O engine não conhece prazo de retenção. O host deve separar identidades:

- runtime insere evidência, mas não lê, atualiza ou apaga o ledger;
- auditoria possui leitura estritamente necessária;
- compliance gerencia legal hold por função dedicada;
- retenção executa apenas expurgo limitado e autorizado;
- owner de migração é identidade administrativa de emergência, nunca runtime.

Ledgers são imutáveis para operações normais. Exclusão só ocorre por uma
fronteira de retenção `SECURITY DEFINER`, sem endpoint HTTP, que exige política,
cutoff, UUID da execução, batch limitado e HMAC da autorização. Cada execução
gera registro append-only sem ator ou ticket em claro. Legal hold ativo exclui
o registro dos candidatos; colocação e liberação do hold também geram eventos
append-only.

O backend de métricas possui retenção própria e não é uma segunda base de
autoridade. Desligar shadow/exportação é rollback operacional; nunca reverte
dado de negócio porque shadow não materializa efeito.

## Concorrência e falha

O expurgo seleciona candidatos em ordem estável, usa `FOR UPDATE SKIP LOCKED` e
limite máximo. Guard transacional permite `DELETE` somente dentro da função
governada. Falha reverte exclusão, guard e registro da execução na mesma
transação. Reutilizar o UUID de uma execução falha de forma idempotente, sem
apagar um segundo lote.

Ao colocar um legal hold, o host bloqueia primeiro a linha do ledger. Se
compliance vencer a corrida, o expurgo pula a linha bloqueada; se o expurgo
vencer, o hold falha sem criar estado órfão. A prova concorrente em duas sessões
continua obrigatória no ambiente-alvo.

## Evidência downstream

O Quickstart QL-06 publica uma observação HTTP sanitizada e métricas bounded,
sem persistir facts ou alterar os quatro ledgers operacionais. QL-08 mantém as
auditorias de replay e transformação insert-only e adiciona retenção/legal hold
governados no PostgreSQL. Essa prova é neutra e não autoriza shadow, preflight,
promoção ou desligamento de regra Ergon.

## Compatibilidade e release

O ADR formaliza a fronteira já praticada e não altera a API Java, o engine
contract `1.2`, o dialeto JSON Logic nem o corpus. Não exige nova coordenada,
tag ou publicação de `praxis-rules-engine`.
