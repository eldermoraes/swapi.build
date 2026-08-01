# Fact-check Corrections Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Corrigir as inconsistências confirmadas pelo fact-check: semântica de IDs de filmes (record ids), 404 para recursos inexistentes, status HTTP real no frontend, textos da página MCP e versões no README.

**Architecture:** Backend Quarkus (Jakarta REST resources + services em memória carregados de JSON) muda a resolução de `/api/films/{id}` de episode id para record id (o número no campo `url`), alinhando com o dataset e com o MCP `sw_get`. Todos os seis resources passam a responder 404 quando o id não existe (o 202 dos sucessos é intocável — regra do projeto). O frontend (Vite/TS vanilla) passa a propagar o status HTTP real da resposta em vez de exibir "200" hardcoded.

**Tech Stack:** Quarkus 3.33 / Java 25, JUnit 5 + RestAssured + McpAssured, TypeScript + Vite (sem test runner de frontend — verificação via `tsc` no `npm run build` + eslint).

**Spec:** `docs/superpowers/specs/2026-08-01-factcheck-corrections.md`

## Global Constraints

- **HTTP 202 nos GETs de sucesso é comportamento histórico intencional — não trocar por 200** (CLAUDE.md).
- Testes: `cd swapi-app && ./mvnw test` (porta de teste 8081). Rodar a suíte completa antes de cada commit.
- Nunca rodar `mvn clean` com dev mode ativo.
- Trabalho em branch `fix/factcheck-corrections`, nunca em `main`.
- Deploy NÃO faz parte deste plano — após merge, seguir `docs/DEPLOY.md` (preview → verify → production), sempre a partir de `swapi-app/`.
- Decisões aprovadas pelo usuário: Opção A (record ids para filmes), 404 para ids inexistentes, itens 6/7 do fact-check ficam como estão ("Settings → Connectors" e "never go(es) offline" não mudam — nem no site nem no README).

---

### Task 0: Branch

- [ ] **Step 1: Criar a branch a partir de main**

```bash
cd /Users/eldermoraes/git/eldermoraes/swapi.build
git checkout main && git pull && git checkout -b fix/factcheck-corrections
```

---

### Task 1: REST de filmes resolve por record id

**Files:**
- Modify: `swapi-app/src/main/java/com/eldermoraes/film/FilmService.java` (adicionar `getFilmById`)
- Modify: `swapi-app/src/main/java/com/eldermoraes/film/FilmResource.java:35-40`
- Test (create): `swapi-app/src/test/java/com/eldermoraes/FilmIdSemanticsTest.java`

**Interfaces:**
- Consumes: `Film.getUrl()` (retorna `baseUrl + url`; o sufixo `/films/N` do dataset é estável mesmo com baseUrl nulo — o mesmo padrão já usado por `PeopleService.getPeopleById`).
- Produces: `FilmService.getFilmById(int id): Film` (retorna `null` se não existir) — a Task 2 troca o MCP para este método; a Task 3 usa o retorno `null` para o 404.

**Contexto para quem nunca viu o repo:** o dataset `films.json` guarda A New Hope com `episode_id: 4` e `url: "/films/1"`. Hoje o endpoint resolve por `episode_id`, mas todas as URLs emitidas pela API (campo `url` e cross-references de people/planets/etc.) usam o record id — seguir `/films/1` retornado pela própria API abre o filme errado. Esta task alinha o endpoint aos links emitidos.

- [ ] **Step 1: Escrever os testes que falham**

Criar `swapi-app/src/test/java/com/eldermoraes/FilmIdSemanticsTest.java`:

```java
package com.eldermoraes;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;

@QuarkusTest
public class FilmIdSemanticsTest {

    // O dataset emite "url": ".../films/1" para A New Hope — o endpoint tem
    // que honrar o link que a propria API publica (record id, nao episode id).
    @Test
    public void filmsIdMatchesEmittedUrl() {
        given().when().get("/api/films/1")
                .then().statusCode(202)
                .body(containsString("A New Hope"));
    }

    @Test
    public void recordIdFourIsThePhantomMenace() {
        given().when().get("/api/films/4")
                .then().statusCode(202)
                .body(containsString("The Phantom Menace"));
    }
}
```

- [ ] **Step 2: Rodar e confirmar que falham**

Run: `cd swapi-app && ./mvnw test -Dtest=FilmIdSemanticsTest`
Expected: FAIL — `/api/films/1` hoje retorna The Phantom Menace (episode 1) e `/api/films/4` retorna A New Hope (episode 4). Os dois asserts de body quebram.

- [ ] **Step 3: Adicionar `getFilmById` ao FilmService**

Em `FilmService.java`, adicionar após `getFilmByEpisodeId` (que será removido na Task 2 — não remover agora, o MCP ainda o usa):

```java
    public Film getFilmById(int id) {
        if (filmList == null) {
            return null;
        }
        String suffix = "/films/" + id;
        return filmList.stream()
                .filter(f -> f.getUrl() != null && f.getUrl().endsWith(suffix))
                .findFirst()
                .orElse(null);
    }
```

- [ ] **Step 4: Trocar o endpoint no FilmResource**

Substituir o método em `FilmResource.java:35-40`:

```java
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{id}")
    public Response getFilmById(@PathParam("id") int id){
        return Response.accepted().entity(filmService.getFilmById(id)).build();
    }
```

- [ ] **Step 5: Rodar a suíte completa**

Run: `cd swapi-app && ./mvnw test`
Expected: PASS (incluindo os dois testes novos e toda a regressão existente).

- [ ] **Step 6: Commit**

```bash
git add swapi-app/src/main/java/com/eldermoraes/film/ swapi-app/src/test/java/com/eldermoraes/FilmIdSemanticsTest.java
git commit -m "fix: /api/films/{id} resolves by record id, honoring emitted urls"
```

---

### Task 2: MCP `sw_get FILMS` usa record id; remover método órfão

**Files:**
- Modify: `swapi-app/src/main/java/com/eldermoraes/mcp/SwapiTools.java:82-92`
- Modify: `swapi-app/src/main/java/com/eldermoraes/film/FilmService.java` (remover `getFilmByEpisodeId`)
- Test (modify): `swapi-app/src/test/java/com/eldermoraes/mcp/SwapiToolsTest.java`

**Interfaces:**
- Consumes: `FilmService.getFilmById(int id): Film` (Task 1).
- Produces: descrição da tool `sw_get` sem menção a episode ids — a Task 5 alinha o texto da página MCP a isto.

- [ ] **Step 1: Escrever o teste que falha**

Adicionar a `SwapiToolsTest.java` (mesmo idioma dos testes existentes na classe):

```java
    @Test
    public void getFilmByRecordIdReturnsANewHope() {
        client().when()
                .toolsCall("sw_get")
                .withArguments(java.util.Map.of("resource", "FILMS", "id", 1))
                .withAssert(r -> {
                    assertFalse(r.isError());
                    assertTrue(r.content().get(0).asText().text().contains("A New Hope"));
                })
                .send()
                .thenAssertResults();
    }
```

- [ ] **Step 2: Rodar e confirmar que falha**

Run: `cd swapi-app && ./mvnw test -Dtest=SwapiToolsTest`
Expected: FAIL — `sw_get FILMS 1` hoje retorna The Phantom Menace via episode id.

- [ ] **Step 3: Trocar o dispatch e as descrições em SwapiTools**

Em `SwapiTools.java`, o método `sw_get` (linhas 82-103) fica:

```java
    @Tool(description = "Gets one Star Wars entity by numeric id. Ids are the record ids "
            + "from each entity's url field (e.g. FILMS id 1 = A New Hope). Returns a JSON object.",
          annotations = @Tool.Annotations(title = "Get Star Wars entity by id",
                  readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public String sw_get(
            @ToolArg(description = "Resource type") SwResource resource,
            @ToolArg(description = "Numeric record id (from the entity's url field)") int id) {
        applyBaseUrl();
        Object result = switch (resource) {
            case PEOPLE -> peopleService.getPeopleById(id);
            case FILMS -> filmService.getFilmById(id);
            case PLANETS -> planetService.getPlanetById(id);
            case SPECIES -> specieService.getSpecieById(id);
            case STARSHIPS -> starshipService.getStarshipById(id);
            case VEHICLES -> vehicleService.getVehicleById(id);
        };
        if (result == null) {
            throw new ToolCallException("No " + resource.name().toLowerCase()
                    + " found with id " + id);
        }
        return jsonb.toJson(result);
    }
```

(Só mudam as duas descriptions e a linha `case FILMS ->`; o resto permanece idêntico.)

- [ ] **Step 4: Remover `getFilmByEpisodeId` do FilmService**

Apagar de `FilmService.java` o método inteiro (era o último usuário):

```java
    public Film getFilmByEpisodeId(int episodeId) {
        return filmList.stream()
                .filter(film -> film.getEpisode_id() == episodeId)
                .findFirst()
                .orElse(null);
    }
```

Confirmar que não sobrou referência: `grep -rn getFilmByEpisodeId swapi-app/src` deve retornar vazio.

- [ ] **Step 5: Rodar a suíte completa**

Run: `cd swapi-app && ./mvnw test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add swapi-app/src/main/java/com/eldermoraes/mcp/SwapiTools.java swapi-app/src/main/java/com/eldermoraes/film/FilmService.java swapi-app/src/test/java/com/eldermoraes/mcp/SwapiToolsTest.java
git commit -m "fix: MCP sw_get FILMS uses record ids; drop episode-id lookup"
```

---

### Task 3: 404 para ids inexistentes nos seis resources

**Files:**
- Modify: `swapi-app/src/main/java/com/eldermoraes/film/FilmResource.java`
- Modify: `swapi-app/src/main/java/com/eldermoraes/people/PeopleResource.java:33-42`
- Modify: `swapi-app/src/main/java/com/eldermoraes/planet/PlanetResource.java`
- Modify: `swapi-app/src/main/java/com/eldermoraes/specie/SpecieResource.java`
- Modify: `swapi-app/src/main/java/com/eldermoraes/starship/StarshipResources.java`
- Modify: `swapi-app/src/main/java/com/eldermoraes/vehicle/VehicleResource.java`
- Test (create): `swapi-app/src/test/java/com/eldermoraes/NotFoundRegressionTest.java`

**Interfaces:**
- Consumes: os `get*ById` de cada service, que retornam `null` quando não há match.
- Produces: contrato público — id inexistente → `404` com body de texto `"No <resource> found with id <id>"`. Sucessos continuam `202` (regra do projeto).

- [ ] **Step 1: Escrever os testes que falham**

Criar `swapi-app/src/test/java/com/eldermoraes/NotFoundRegressionTest.java`:

```java
package com.eldermoraes;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;

@QuarkusTest
public class NotFoundRegressionTest {

    // Sucessos continuam 202 (comportamento historico, ver CLAUDE.md);
    // "nao existe" agora e um 404 de verdade, nao um 202 com body vazio.
    @Test
    public void unknownFilmIs404() {
        given().when().get("/api/films/9999").then().statusCode(404);
    }

    @Test
    public void unknownPersonIs404() {
        given().when().get("/api/people/9999").then().statusCode(404);
    }

    @Test
    public void unknownPlanetIs404() {
        given().when().get("/api/planets/9999").then().statusCode(404);
    }

    @Test
    public void unknownSpecieIs404() {
        given().when().get("/api/species/9999").then().statusCode(404);
    }

    @Test
    public void unknownStarshipIs404() {
        given().when().get("/api/starships/9999").then().statusCode(404);
    }

    @Test
    public void unknownVehicleIs404() {
        given().when().get("/api/vehicles/9999").then().statusCode(404);
    }
}
```

- [ ] **Step 2: Rodar e confirmar que falham**

Run: `cd swapi-app && ./mvnw test -Dtest=NotFoundRegressionTest`
Expected: FAIL — todos retornam 202 hoje.

- [ ] **Step 3: Implementar o null-check nos seis resources**

`FilmResource.java` — o método por id (da Task 1) fica:

```java
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{id}")
    public Response getFilmById(@PathParam("id") int id){
        Film film = filmService.getFilmById(id);
        if (film == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("No film found with id " + id).build();
        }
        return Response.accepted().entity(film).build();
    }
```

(`Film` já está no mesmo package; não precisa de import novo.)

`PeopleResource.java` — o branch de sucesso do `getPeopleById` fica:

```java
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{id}")
    public Response getPeopleById(@PathParam("id") String id) {
        if (id != null && !id.isEmpty()) {
            People people = peopleService.getPeopleById(Integer.parseInt(id));
            if (people == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity("No people found with id " + id).build();
            }
            return Response.accepted().entity(people).build();
        } else {
            return Response.status(Response.Status.BAD_REQUEST).entity("ID parameter is required").build();
        }
    }
```

`PlanetResource.java` — mesmo padrão (o método atual está nas linhas 36-44 e também recebe `String id` com `Integer.parseInt`):

```java
            Planet planet = planetService.getPlanetById(Integer.parseInt(id));
            if (planet == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity("No planet found with id " + id).build();
            }
            return Response.accepted().entity(planet).build();
```

`SpecieResource.java`:

```java
            Specie specie = specieService.getSpecieById(Integer.parseInt(id));
            if (specie == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity("No specie found with id " + id).build();
            }
            return Response.accepted().entity(specie).build();
```

`StarshipResources.java`:

```java
            Starship starship = starshipService.getStarshipById(Integer.parseInt(id));
            if (starship == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity("No starship found with id " + id).build();
            }
            return Response.accepted().entity(starship).build();
```

`VehicleResource.java`:

```java
            Vehicle vehicle = vehicleService.getVehicleById(Integer.parseInt(id));
            if (vehicle == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity("No vehicle found with id " + id).build();
            }
            return Response.accepted().entity(vehicle).build();
```

Em cada um, apenas a linha `return Response.accepted().entity(...getXById(...)).build();` é substituída pelo bloco acima; o `else` de BAD_REQUEST existente permanece. Os tipos (`Planet`, `Specie`, `Starship`, `Vehicle`) estão no mesmo package de cada resource — sem imports novos.

- [ ] **Step 4: Rodar a suíte completa**

Run: `cd swapi-app && ./mvnw test`
Expected: PASS — os 6 novos testes verdes e a regressão intacta (os testes de sucesso continuam esperando 202).

- [ ] **Step 5: Commit**

```bash
git add swapi-app/src/main/java/com/eldermoraes/ swapi-app/src/test/java/com/eldermoraes/NotFoundRegressionTest.java
git commit -m "fix: return 404 for nonexistent resource ids (202 kept for successes)"
```

---

### Task 4: Frontend exibe o status HTTP real (fim do "200" hardcoded)

**Files:**
- Modify: `swapi-app/src/main/webui/src/api.ts`
- Modify: `swapi-app/src/main/webui/src/pages/home.ts:56-65`
- Modify: `swapi-app/src/main/webui/src/pages/resource.ts` (call sites + `showJson` + detail)

**Interfaces:**
- Consumes: `fetch` Response.status.
- Produces: `ApiResponse<T> = { data: T; status: number }` retornado por todas as funções de `api.ts`. O compilador TypeScript força a atualização de todos os call sites — é a rede de segurança desta task (não há test runner de frontend).

- [ ] **Step 1: Refatorar `api.ts` para retornar dados + status**

Em `api.ts`, adicionar o tipo e mudar `request` e as cinco funções exportadas:

```ts
export interface ApiResponse<T> {
  data: T;
  status: number;
}

async function request<T>(url: string): Promise<ApiResponse<T>> {
  cancelPending();
  currentController = new AbortController();

  let res: Response;
  try {
    res = await fetch(url, { signal: currentController.signal });
  } catch (err) {
    if (err instanceof DOMException && err.name === 'AbortError') {
      throw err;
    }
    throw new ApiError('Network error — check your connection', 0, 'network');
  } finally {
    currentController = null;
  }

  if (!res.ok) {
    throw new ApiError(`HTTP ${res.status}: ${res.statusText}`, res.status, 'http');
  }
  return { data: (await res.json()) as T, status: res.status };
}

export async function fetchResources<T = unknown>(type: string): Promise<ApiResponse<T[]>> {
  return request<T[]>(`${BASE}/${type}`);
}

export async function fetchResourceById<T = unknown>(type: string, id: string): Promise<ApiResponse<T>> {
  return request<T>(`${BASE}/${type}/${id}`);
}

export async function searchResource<T = unknown>(type: string, query: string): Promise<ApiResponse<T[]>> {
  return request<T[]>(`${BASE}/${type}?search=${encodeURIComponent(query)}`);
}

export async function fetchRandom<T = unknown>(type: string): Promise<ApiResponse<T>> {
  return request<T>(`${BASE}/${type}/random`);
}

export async function fetchEndpoint<T = unknown>(path: string): Promise<ApiResponse<T>> {
  const url = path.startsWith('/') ? path : `${BASE}/${path}`;
  return request<T>(url);
}
```

- [ ] **Step 2: Atualizar `home.ts` (painel "Try it now")**

Em `doRequest()`:

```ts
      const { data, status } = await fetchEndpoint(path);
      resultDiv.innerHTML = `
        <div class="result-panel">
          <div class="result-header">
            <span class="result-status">GET /api/${escapeHtml(path)} <span class="status-code">${status}</span></span>
          </div>
          <pre class="result-body">${highlightJson(data)}</pre>
        </div>
      `;
```

- [ ] **Step 3: Atualizar `resource.ts` (lista, busca, random e detalhe)**

Carga inicial da lista:

```ts
    const { data: items } = await fetchResources<SWResource>(type);
    renderItems(items);
```

Handler de busca:

```ts
      const { data: results } = await searchResource<SWResource>(type, q);
      renderItems(results);
```

Handler de random:

```ts
      const { data, status } = await fetchRandom(type);
      showJson(data, status);
```

Assinatura e header de `showJson`:

```ts
  function showJson(data: unknown, status: number) {
    contentDiv.innerHTML = `
      <div class="result-panel">
        <div class="result-header">
          <span class="result-status"><span class="status-code">${status}</span></span>
          <a href="/resource/${type}" class="back-btn">Back to list</a>
        </div>
        <pre class="result-body">${highlightJson(data)}</pre>
      </div>
    `;
  }
```

Detalhe (`renderResourceDetail`):

```ts
    const { data, status } = await fetchResourceById<Record<string, unknown>>(type, id);
```

e no template do painel:

```ts
          <span class="result-status">GET /api/${escapeHtml(type)}/${escapeHtml(id)} <span class="status-code">${status}</span></span>
```

- [ ] **Step 4: Verificar com o compilador e o linter**

Run: `cd swapi-app/src/main/webui && npm run build && npm run lint`
Expected: build limpo (o `tsc` acusaria qualquer call site esquecido) e lint sem erros. O painel passará a exibir `202` — correto por definição.

- [ ] **Step 5: Rodar a suíte backend (garante que nada quebrou no empacotamento Quinoa)**

Run: `cd swapi-app && ./mvnw test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add swapi-app/src/main/webui/src/
git commit -m "fix: frontend shows real HTTP status instead of hardcoded 200"
```

---

### Task 5: Textos da página MCP (record ids, caminho do Bob, cold start)

**Files:**
- Modify: `swapi-app/src/main/webui/src/pages/mcp.ts:94`, `:149-150`, `:180-181`

**Interfaces:**
- Consumes: semântica de record ids estabelecida nas Tasks 1-2.
- Produces: página MCP consistente com o comportamento real da API.

- [ ] **Step 1: Corrigir a nota de ids (linhas 149-150)**

De:

```
      <code>SPECIES</code>, <code>STARSHIPS</code>, <code>VEHICLES</code>. For <code>FILMS</code>, ids are
      episode ids (<code>4</code> = A New Hope).</p>
```

Para:

```
      <code>SPECIES</code>, <code>STARSHIPS</code>, <code>VEHICLES</code>. Ids are the record ids from each
      entity's <code>url</code> field (for <code>FILMS</code>, <code>1</code> = A New Hope).</p>
```

- [ ] **Step 2: Corrigir o caminho global do IBM Bob (linha 94)**

De:

```
      <p>Add to <code>~/.bob/mcp.json</code> (global) or <code>.bob/mcp.json</code> in your project:</p>
```

Para:

```
      <p>Add to <code>~/.bob/settings/mcp_settings.json</code> (global) or <code>.bob/mcp.json</code> in your project:</p>
```

(Fonte: docs da IBM — o global fica em `~/.bob/settings/mcp_settings.json` no macOS; o caminho de projeto `.bob/mcp.json` está correto e não muda.)

- [ ] **Step 3: Qualificar o cold start (linhas 180-181)**

De:

```
      <p>The server scales to zero when idle. If the very first connection attempt fails or times out,
      retry once — the container cold-starts in milliseconds and stateless requests are immune after that.</p>
```

Para:

```
      <p>The server scales to zero when idle. If the very first connection attempt fails or times out,
      retry once — the native binary starts in tens of milliseconds (the platform may take a bit longer
      to provision the container) and stateless requests are immune after that.</p>
```

- [ ] **Step 4: Verificar build e lint**

Run: `cd swapi-app/src/main/webui && npm run build && npm run lint`
Expected: limpo.

- [ ] **Step 5: Commit**

```bash
git add swapi-app/src/main/webui/src/pages/mcp.ts
git commit -m "docs: fix MCP page — record ids note, Bob global path, cold-start wording"
```

---

### Task 6: README alinhado ao pom (Java 25, Quarkus 3.33)

**Files:**
- Modify: `README.md:9` e `README.md:202`

**Interfaces:**
- Consumes: `pom.xml` (`maven.compiler.release=25`, Quarkus 3.33.3) e `application.properties` (Mandrel jdk-25) — fonte de verdade.
- Produces: instruções de build executáveis (build com Java 21 falha hoje).

- [ ] **Step 1: Atualizar prerequisites (linha 9)**

De:

```
**Prerequisites:** Java 21+ and Maven (or use the included Maven Wrapper).
```

Para:

```
**Prerequisites:** Java 25 and Maven (or use the included Maven Wrapper).
```

- [ ] **Step 2: Atualizar tech stack (linha 202)**

De:

```
- **Runtime:** [Quarkus 3.23](https://quarkus.io/) on Java 21+ with Virtual Threads
```

Para:

```
- **Runtime:** [Quarkus 3.33](https://quarkus.io/) on Java 25 with Virtual Threads
```

(Decisão do usuário: a frase "never goes offline" do README/About permanece — não tocar.)

- [ ] **Step 3: Commit**

```bash
git add README.md
git commit -m "docs: README prerequisites match pom (Java 25, Quarkus 3.33)"
```

---

### Task 7: Verificação final e handoff

- [ ] **Step 1: Suíte completa + build do frontend, do zero**

```bash
cd swapi-app && ./mvnw test
cd src/main/webui && npm run build && npm run lint
```

Expected: tudo verde. Só declarar concluído com a saída em mãos (verification-before-completion).

- [ ] **Step 2: Smoke manual dos pontos corrigidos (dev mode)**

Com o dev mode rodando (porta 5432), conferir:
- `curl -s localhost:5432/api/films/1` → A New Hope (202)
- `curl -s -o /dev/null -w '%{http_code}' localhost:5432/api/films/9999` → 404
- No browser: lista de Films → clicar "A New Hope" abre A New Hope; painel mostra `202`.

- [ ] **Step 3: Decisão de merge com o usuário**

Usar superpowers:finishing-a-development-branch — perguntar: merge local em main, PR, ou manter a branch. Rodar a suíte de novo no resultado do merge.

- [ ] **Step 4: Deploy (fora deste plano)**

Após merge aprovado: seguir `docs/DEPLOY.md` exatamente (deploy de `swapi-app/`, preview → verify → production, checks de pós-deploy com curl). Lembrete: `git push` não deploya.
