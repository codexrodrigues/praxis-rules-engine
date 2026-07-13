# P2F-ADR-02 — identidade e lifecycles separados

- Estado: `ACCEPTED_FOR_QL_02`
- Data: 2026-07-13
- Classificação: `arquitetural`

## Identidade runtime

A identidade estável mínima é formada por:

```text
domainKey / boundedContextKey / ruleSetKey / operationKey / version
```

Dentro do RuleSet, `slotKey` e `bindingKey` são estáveis e únicos. Chaves usam
vocabulário de domínio; não incluem path HTTP, classe, tabela, ordem HADES,
`ROWID`, empresa, usuário ou sessão. `version` é inteiro positivo e imutável.

`tenant` e `environment` particionam publicação e seleção, mas não alteram a
identidade semântica do RuleSet. `snapshotId` e `publicationRevision` pertencem
ao envelope do Config Starter. Proveniência legada é evidência, nunca chave.

## Lifecycles

Três lifecycles não podem ser colapsados:

1. **Definition governada:** usa os estados canônicos do Config Starter. No
   comportamento atual, somente `approved` ou `active` satisfazem o gate de
   publicação.
2. **Snapshot publicado:** é append-only, governado pelo Config Starter e
   selecionado por head ativo. Seus estados serão fechados no P2F-ADR-07.
3. **Solicitação de negócio:** pertence ao host e não muda porque uma definition
   foi simulada, aprovada ou publicada.

O contrato do engine não possui status mutável de authoring. Ele recebe um
artefato imutável e compatível, compila um plano ou o rejeita integralmente.

## Regras de seleção

- tenant e environment são obrigatórios em boundaries protegidas;
- não existe fallback para `default/dev` em autoridade;
- uma avaliação usa exatamente uma versão do RuleSet;
- ausência, ambiguidade ou incompatibilidade não seleciona “a mais próxima”;
- rollback seleciona outra versão já publicada; não altera conteúdo existente;
- avaliações em curso terminam com a versão que capturaram no início.

## Compatibilidade

Referências públicas carregam identidade e versão, nunca o objeto inteiro por
conveniência. Equality e hash não dependem de label, descrição, timestamp ou
ordem de serialização. O envelope de publicação registra separadamente versão
do contrato, engine, dialect JSON Logic e implementações Java requeridas.
