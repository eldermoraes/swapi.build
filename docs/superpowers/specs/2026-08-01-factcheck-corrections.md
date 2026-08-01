# Spec: Correções do fact-check do site (2026-08-01)

Origem: relatório de fact-checking externo, validado afirmação por afirmação contra o
código deste repositório e fontes primárias. Status de cada item abaixo.

## Itens confirmados no código

### 1. (Critical) Contradição de IDs de filmes — CONFIRMADO

- `FilmResource.getFilmByEpisodeId()` (`swapi-app/src/main/java/com/eldermoraes/film/FilmResource.java:37-40`)
  resolve `/api/films/{id}` por **episode id**.
- O dataset (`swapi-app/src/main/resources/data/films.json`) mantém os record ids do
  swapi.dev: A New Hope tem `episode_id: 4` e `url: /films/1`. Todos os cross-references
  (`people.json` etc.) apontam para record ids (Luke → `/films/1,2,3,6`).
- O browser do site (`resource.ts:44-49`) extrai o id do campo `url` — clicar em
  "A New Hope" abre The Phantom Menace.
- `SwapiTools.java:92` (MCP `sw_get FILMS`) usa o mesmo caminho por episode id.
- A página MCP (`mcp.ts:149-150`) documenta "For FILMS, ids are episode ids".

**Decisão pendente (escolher uma):**
- **Opção A (recomendada):** endpoint volta a resolver por record id
  (`getFilmById`), alinhando com dataset, hypermedia e legado swapi.dev. Remover a
  frase sobre episode ids da página MCP. Menor risco; nenhum dado alterado.
- **Opção B:** manter episode ids e remapear todos os `url` e cross-references do
  dataset. Mais invasivo; quebra paridade com swapi.dev.

Impacto da Opção A: `FilmResource`, `SwapiTools` (FILMS), testes de filme, texto em
`mcp.ts`. Comportamento público muda (`/api/films/4` passa a ser The Phantom Menace).

### 2. (High) Status HTTP — PARCIALMENTE ACIONÁVEL

Confirmado: todos os GETs usam `Response.accepted()` (202); o frontend exibe um
"200" hardcoded (`home.ts:60`, `resource.ts:64` e `resource.ts:141`); id inexistente
retorna 202 com body vazio (`orElse(null)` nos services).

**Restrição do projeto:** o 202 é comportamento histórico intencional
(CLAUDE.md: "do not fix"). Portanto o escopo aqui é:
- **2a.** Frontend passa a exibir o status **real** da resposta (mostrará 202) em vez
  do 200 hardcoded. Sem mudança de backend.
- **2b. (decisão pendente)** Retornar 404 para ids inexistentes. Não conflita com o
  quirk do 202 (que cobre respostas de sucesso), mas muda comportamento público —
  requer aprovação explícita.

### 3. (Medium) Caminho global do IBM Bob — CONFIRMADO, com correção adicional

`mcp.ts:94` diz `~/.bob/mcp.json` (global). A documentação da IBM define o global em
`~/.bob/settings/mcp_settings.json` (macOS) — note o subdiretório `settings/`, que o
próprio relatório omitiu. Corrigir o texto para esse caminho (mantendo `.bob/mcp.json`
de projeto, que está correto).

### 4. (Medium) "cold-starts in milliseconds" — CONFIRMADO

`mcp.ts:181`. Trocar por redação qualificada: o binário nativo inicia em dezenas de
milissegundos; o cold start da plataforma pode levar mais.

### 5. (Medium) Drift README × pom — CONFIRMADO

`README.md:9` ("Java 21+") e `README.md:202` ("Quarkus 3.23 on Java 21+") vs
`pom.xml` (`maven.compiler.release=25`, Quarkus 3.33.3) e
`application.properties` (Mandrel jdk-25). Atualizar README para Java 25 e
Quarkus 3.33.x.

## Itens de baixa prioridade (opcionais)

### 6. Rótulo de navegação Claude — texto atual funciona

`mcp.ts:47` ("Settings → Connectors"). Rótulos variam entre versões do produto; não
confirmei divergência em fonte primária nesta sessão. Sugestão: manter, ou usar
redação neutra ("nas configurações, seção Connectors"). Sem urgência.

### 7. "never go offline" no About — retórica, manter

`about.ts:25`. Tensão retórica aceitável (contexto narrativo da origem do projeto).
Nenhuma ação, salvo preferência do autor.

## Plano de execução (após aprovação)

1. Branch `fix/factcheck-corrections`.
2. TDD para o item 1 (opção escolhida) e, se aprovado, 2b (404).
3. Ajustes de texto (itens 2a, 3, 4) no frontend; item 5 no README.
4. Suíte completa (`cd swapi-app && ./mvnw test`).
5. Merge conforme preferência do usuário; deploy via `docs/DEPLOY.md`
   (preview → verify → production) e verificação pós-deploy.
