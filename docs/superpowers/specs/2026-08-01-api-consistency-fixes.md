# Spec: Consistência de API — 200 nos sucessos, homeworld absoluto e 404 para ids não numéricos

Origem: dois achados da rodada de fact-check de 01/08/2026 (um observado em produção
na verificação pós-deploy, outro deferido pela revisão final da branch
`fix/factcheck-corrections`) + decisão do Elder em 01/08/2026 de aposentar o quirk
do 202: "Não tem clientes pra ser quebrados, então se o correto é 200, mude as
nossas definições."

## Item C — GETs de sucesso passam a retornar 200 (fim do quirk 202)

**Contexto:** o `Response.accepted()` (202) veio da implementação original
(commit `3f8a183`, 05/2025) sem justificativa técnica registrada; pela RFC 9110,
202 significa processamento assíncrono pendente — errado para GETs síncronos. A
regra "do not fix" do CLAUDE.md existia para proteger compatibilidade; o dono do
projeto revogou-a por não haver clientes a preservar.

**Correção:** todos os `Response.accepted()` → `Response.ok()` (24 ocorrências nos
seis resources). Atualizar em conjunto, no mesmo commit: asserts 202→200 nos testes
(ApiRegressionTest ×3 + rename do método `peopleByIdStillAnswers202WithLuke` e
comentário, FilmIdSemanticsTest ×2, ForwardedHeadersTest ×2, comentário do
NotFoundRegressionTest), a regra do CLAUDE.md (linha 30) e o runbook
`docs/DEPLOY.md` (linhas 33 e 72: expect 200; linha 85: a linha de troubleshooting
vira "202 = deployment antigo no ar"). O frontend já exibe o status real — passará
a mostrar 200 sem mudança de código.

## Item A — `People.homeworld` sai como caminho relativo

**Fato verificado (prod e código):** `GET /api/people/1` retorna
`"homeworld": "/planets/1"` enquanto todos os outros links (`films`, `starships`,
`vehicles`, `species`, `url`) saem absolutos com a base URL descoberta por request.

- Causa: `People.getHomeworld()` (`People.java:94-96`) retorna o campo cru.
- O padrão correto já existe no próprio codebase: `Specie.getHomeworld()`
  (`Specie.java:94-99`) aplica `getBaseUrl() + homeworld` com guarda para
  null/"null"/vazio.
- Varredura dos seis modelos: `People.homeworld` é o único campo de referência
  (singular ou lista) sem o prefixo. Nenhum outro caso.

**Correção:** espelhar em `People.getHomeworld()` o padrão de `Specie` (guarda de
null/vazio → `""`; senão `getBaseUrl() + homeworld`). Alcança REST e MCP
(`sw_get`/`sw_list`/`sw_search`/`sw_random` serializam o mesmo objeto).

## Item B — ids não numéricos retornam 500 em cinco resources

**Fato verificado:** `FilmResource.getFilmById` recebe `int` — a conversão JAX-RS
falha para `/api/films/abc` e responde **404** automaticamente. Os outros cinco
resources (People, Planet, Specie, Starship, Vehicle) recebem `String id` e chamam
`Integer.parseInt(id)` sem tratamento: `/api/people/abc` →
`NumberFormatException` → **500**.

**Correção (decisão pendente, opções):**
- **Opção A (recomendada):** trocar o parâmetro para `int id` nos cinco resources,
  igual a filmes. A conversão JAX-RS passa a responder 404 para não numéricos, e o
  branch `else` de BAD_REQUEST (id vazio) morre junto — ele já era código morto,
  pois `{id}` vazio nunca casa com a rota. Uniformiza os seis resources.
- **Opção B:** manter `String id` e envolver `Integer.parseInt` em try/catch
  retornando 404. Mais código, preserva um branch morto, mantém assinaturas atuais.

**Contrato resultante (Opção A):** id não numérico → 404 (body padrão do
container); id numérico inexistente → 404 `text/plain` `"No <resource> found with
id <id>"` (comportamento atual); sucesso → 200 (Item C).

## Restrições

- TDD; suíte completa `cd swapi-app && ./mvnw test` antes de cada commit; branch
  própria; deploy só via `docs/DEPLOY.md` após merge aprovado.
- Diretriz da sessão: implementação por subagentes Opus; plano e validação da
  entrega pelo controller (Fable).

## Testes de aceitação

- Item C: `GET /api/people/1` (e demais GETs de sucesso) → **200**; docs e
  CLAUDE.md sem menção a 202 como comportamento vigente.
- Item A: `GET /api/people/1` → `"homeworld"` começando com a base URL da request
  (nos testes, `http://localhost:8081/api/planets/1`); MCP `sw_get PEOPLE 1` idem.
- Item B: `GET /api/{people,planets,species,starships,vehicles}/abc` → 404;
  `/api/films/abc` → 404 (regressão do comportamento já existente).
