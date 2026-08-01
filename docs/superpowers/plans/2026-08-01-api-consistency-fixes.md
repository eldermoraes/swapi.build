# API Consistency Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** GETs de sucesso retornam 200 (fim do quirk 202), `People.homeworld` sai absoluto, e ids não numéricos retornam 404 nos seis resources.

**Architecture:** Três mudanças independentes e pequenas no backend Quarkus. Item C troca `Response.accepted()` por `Response.ok()` em todos os resources e atualiza testes/docs/CLAUDE.md juntos. Item A espelha em `People.getHomeworld()` o padrão já existente em `Specie.getHomeworld()`. Item B troca `@PathParam String id` por `int id` nos cinco resources que ainda parseiam manualmente, deixando a conversão JAX-RS responder 404 e removendo o branch morto de BAD_REQUEST.

**Tech Stack:** Quarkus 3.33 / Java 25, JUnit 5 + RestAssured. Frontend não muda (já exibe status dinâmico).

**Spec:** `docs/superpowers/specs/2026-08-01-api-consistency-fixes.md`

## Global Constraints

- Testes: `cd swapi-app && ./mvnw test` (porta 8081). Suíte completa antes de cada commit. Nunca `mvn clean` com dev mode ativo.
- Branch `fix/api-consistency`, nunca em `main`.
- Deploy fora do plano — após merge, `docs/DEPLOY.md` (sempre de `swapi-app/`).
- Diretriz da sessão: implementadores são subagentes Opus; validação da entrega pelo controller.
- Decisão do usuário (01/08/2026): o quirk do 202 está revogado — 200 é o correto para GETs síncronos de sucesso. 404s existentes (id inexistente, `text/plain`) não mudam.

---

### Task 0: Branch

- [ ] **Step 1: Criar a branch a partir de main**

```bash
cd /Users/eldermoraes/git/eldermoraes/swapi.build
git checkout main && git checkout -b fix/api-consistency
```

(Commitar spec+plano deste ciclo como primeiro commit da branch.)

---

### Task 1: 202 → 200 em todos os GETs de sucesso (código + testes + docs + CLAUDE.md)

**Files:**
- Modify: os 6 resources em `swapi-app/src/main/java/com/eldermoraes/` — `film/FilmResource.java`, `people/PeopleResource.java`, `planet/PlanetResource.java`, `specie/SpecieResource.java`, `starship/StarshipResources.java`, `vehicle/VehicleResource.java` (24 ocorrências de `Response.accepted()`)
- Modify: `swapi-app/src/test/java/com/eldermoraes/ApiRegressionTest.java`, `FilmIdSemanticsTest.java`, `ForwardedHeadersTest.java`, `NotFoundRegressionTest.java` (só comentário)
- Modify: `CLAUDE.md:30`, `docs/DEPLOY.md:33,72,85`

**Interfaces:**
- Produces: contrato público — GET de sucesso → `200 OK`. As Tasks 2 e 3 escrevem testes novos já esperando 200.

- [ ] **Step 1: TDD — virar os asserts dos testes para 200 (RED)**

Em `ApiRegressionTest.java`: trocar os três `.statusCode(202)` por `.statusCode(200)`; renomear `peopleByIdStillAnswers202WithLuke` → `peopleByIdAnswers200WithLuke`; trocar o comentário da linha 14 por:

```java
        // 200 e o contrato atual (Response.ok()); o 202 historico foi aposentado em 2026-08-01
```

Em `FilmIdSemanticsTest.java`: trocar os dois `.statusCode(202)` por `.statusCode(200)`.
Em `ForwardedHeadersTest.java`: trocar os dois `.statusCode(202)` por `.statusCode(200)`.
Em `NotFoundRegressionTest.java`: trocar as duas linhas de comentário (12-13) por:

```java
    // Sucessos retornam 200 (quirk 202 aposentado em 2026-08-01);
    // "nao existe" e um 404 de verdade, nao um 200 com body vazio.
```

- [ ] **Step 2: Rodar e confirmar RED**

Run: `cd swapi-app && ./mvnw test -Dtest='ApiRegressionTest,FilmIdSemanticsTest,ForwardedHeadersTest'`
Expected: FAIL — 7 asserts esperando 200 recebem 202.

- [ ] **Step 3: Trocar Response.accepted() por Response.ok()**

Nos 6 resources, substituir TODAS as ocorrências de `Response.accepted()` por `Response.ok()` (24 no total; conferir com `grep -rn "Response.accepted()" swapi-app/src/main/java` → deve retornar vazio ao final). Nada mais muda nesses arquivos (404/BAD_REQUEST intocados).

- [ ] **Step 4: Rodar a suíte completa (GREEN)**

Run: `cd swapi-app && ./mvnw test`
Expected: PASS (20/20).

- [ ] **Step 5: Atualizar CLAUDE.md e docs/DEPLOY.md**

`CLAUDE.md` linha 30, de:

```
- **The API returns HTTP 202 (not 200) by design** — historic behavior, do not "fix".
```

para:

```
- **Successful GETs return HTTP 200; nonexistent ids return 404** (the historic
  202 quirk was retired on 2026-08-01 — no external clients depended on it).
```

`docs/DEPLOY.md` linha 33, de `**REST** — expect HTTP 202 and embedded URLs` para `**REST** — expect HTTP 200 and embedded URLs`.

`docs/DEPLOY.md` linha 72, de:

```
Expect `status: 202` and `1` (embedded URLs on `https://swapi.build`, scheme `https`).
```

para:

```
Expect `status: 200` and `1` (embedded URLs on `https://swapi.build`, scheme `https`).
```

`docs/DEPLOY.md` linha 85 (tabela de troubleshooting), de:

```
| 202 responses from the API | By design (historic behavior). Not an error. |
```

para:

```
| 202 responses from the API | Legacy quirk retired 2026-08-01 — current builds return 200; a 202 means an old deployment is live. |
```

- [ ] **Step 6: Commit**

```bash
git add swapi-app/src CLAUDE.md docs/DEPLOY.md
git commit -m "fix: successful GETs return 200 — retire the historic 202 quirk"
```

---

### Task 2: `People.homeworld` absoluto

**Files:**
- Modify: `swapi-app/src/main/java/com/eldermoraes/people/People.java:94-96`
- Test (create): `swapi-app/src/test/java/com/eldermoraes/HomeworldUrlTest.java`
- Test (modify): `swapi-app/src/test/java/com/eldermoraes/mcp/SwapiToolsTest.java` (1 assert a mais no teste existente `getReturnsLukeById`)

**Interfaces:**
- Consumes: `SWObject.getBaseUrl()` (mesmo mecanismo dos demais getters); padrão de referência: `Specie.getHomeworld()` (`Specie.java:94-99`).
- Produces: campo `homeworld` absoluto em todas as respostas de People (REST e MCP).

- [ ] **Step 1: TDD — teste falhando**

Criar `swapi-app/src/test/java/com/eldermoraes/HomeworldUrlTest.java`:

```java
package com.eldermoraes;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.equalTo;

@QuarkusTest
public class HomeworldUrlTest {

    // homeworld deve sair absoluto como todos os outros links (films, starships, url...)
    @Test
    public void peopleHomeworldIsAbsolute() {
        given().when().get("/api/people/1")
                .then().statusCode(200)
                .body("homeworld", equalTo("http://localhost:8081/api/planets/1"));
    }
}
```

Em `SwapiToolsTest.getRetornaLukeById` (nome real: `getReturnsLukeById`), adicionar um assert dentro do `withAssert` existente, após o assert de Luke:

```java
                    assertTrue(r.content().get(0).asText().text()
                            .contains("\"homeworld\":\"http://localhost:8081/api/planets/1\""));
```

- [ ] **Step 2: Rodar e confirmar RED**

Run: `cd swapi-app && ./mvnw test -Dtest='HomeworldUrlTest,SwapiToolsTest'`
Expected: FAIL — homeworld hoje é `"/planets/1"` (relativo).

- [ ] **Step 3: Implementar — espelhar o padrão de Specie**

Em `People.java`, substituir:

```java
    public String getHomeworld() {
        return homeworld;
    }
```

por:

```java
    public String getHomeworld() {
        if (homeworld == null || homeworld.equals("null") || homeworld.isEmpty()) {
            return "";
        } else{
            return getBaseUrl() + homeworld;
        }
    }
```

(Formatação idêntica à de `Specie.getHomeworld()` — é deliberado.)

- [ ] **Step 4: Suíte completa (GREEN)**

Run: `cd swapi-app && ./mvnw test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add swapi-app/src/main/java/com/eldermoraes/people/People.java swapi-app/src/test/java/com/eldermoraes/HomeworldUrlTest.java swapi-app/src/test/java/com/eldermoraes/mcp/SwapiToolsTest.java
git commit -m "fix: People.homeworld emits absolute url like every other link"
```

---

### Task 3: 404 para ids não numéricos (param `int` nos cinco resources)

**Files:**
- Modify: `people/PeopleResource.java`, `planet/PlanetResource.java`, `specie/SpecieResource.java`, `starship/StarshipResources.java`, `vehicle/VehicleResource.java`
- Test (create): `swapi-app/src/test/java/com/eldermoraes/NonNumericIdRegressionTest.java`

**Interfaces:**
- Consumes: contrato 200 da Task 1 (métodos de sucesso já usam `Response.ok()`).
- Produces: id não numérico → 404 nos seis resources (films já tem via param `int` — vira teste de regressão).

- [ ] **Step 1: TDD — testes falhando**

Criar `swapi-app/src/test/java/com/eldermoraes/NonNumericIdRegressionTest.java`:

```java
package com.eldermoraes;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;

@QuarkusTest
public class NonNumericIdRegressionTest {

    // Id nao numerico nunca e um erro de servidor: a conversao JAX-RS do
    // @PathParam int responde 404, igual ao comportamento de films.
    @Test
    public void nonNumericFilmIdIs404() {
        given().when().get("/api/films/abc").then().statusCode(404);
    }

    @Test
    public void nonNumericPersonIdIs404() {
        given().when().get("/api/people/abc").then().statusCode(404);
    }

    @Test
    public void nonNumericPlanetIdIs404() {
        given().when().get("/api/planets/abc").then().statusCode(404);
    }

    @Test
    public void nonNumericSpecieIdIs404() {
        given().when().get("/api/species/abc").then().statusCode(404);
    }

    @Test
    public void nonNumericStarshipIdIs404() {
        given().when().get("/api/starships/abc").then().statusCode(404);
    }

    @Test
    public void nonNumericVehicleIdIs404() {
        given().when().get("/api/vehicles/abc").then().statusCode(404);
    }
}
```

- [ ] **Step 2: Rodar e confirmar RED (5 falham, films passa)**

Run: `cd swapi-app && ./mvnw test -Dtest=NonNumericIdRegressionTest`
Expected: films → 404 (PASS); os outros cinco → 500 (FAIL).

- [ ] **Step 3: Trocar o parâmetro para `int` e remover o branch morto**

Padrão (exemplo `PeopleResource.java` — o método por id vira exatamente):

```java
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{id}")
    public Response getPeopleById(@PathParam("id") int id) {
        People people = peopleService.getPeopleById(id);
        if (people == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .type(MediaType.TEXT_PLAIN)
                    .entity("No people found with id " + id).build();
        }
        return Response.ok().entity(people).build();
    }
```

Aplicar o mesmo shape nos outros quatro (`Planet planet = planetService.getPlanetById(id);` com "No planet found...", `Specie specie = specieService.getSpecieById(id);` com "No specie found...", `Starship starship = starshipService.getStarshipById(id);` com "No starship found...", `Vehicle vehicle = vehicleService.getVehicleById(id);` com "No vehicle found..."). Em cada um: some o `if (id != null && !id.isEmpty())`, o `Integer.parseInt` e o `else` de BAD_REQUEST (código morto — `{id}` vazio nunca casa com a rota). Manter anotações e ordem dos métodos como estão em cada arquivo.

- [ ] **Step 4: Suíte completa (GREEN)**

Run: `cd swapi-app && ./mvnw test`
Expected: PASS — os 6 novos + toda a regressão.

- [ ] **Step 5: Commit**

```bash
git add swapi-app/src
git commit -m "fix: non-numeric ids return 404 via int path params (drop dead BAD_REQUEST branch)"
```

---

### Task 4: Verificação final e handoff (controller)

- [ ] **Step 1:** Suíte completa do zero + `grep -rn "Response.accepted()" swapi-app/src/main/java` vazio + `grep -rn "statusCode(202)" swapi-app/src/test` vazio.
- [ ] **Step 2:** Smoke em dev mode (porta 5432): `people/1` → 200 com homeworld absoluto; `people/abc` → 404; `films/1` → 200 A New Hope.
- [ ] **Step 3:** Decisão de merge com o usuário (finishing-a-development-branch); suíte no resultado do merge.
- [ ] **Step 4:** Deploy fora do plano via `docs/DEPLOY.md` (nota: os checks pós-deploy agora esperam 200).
