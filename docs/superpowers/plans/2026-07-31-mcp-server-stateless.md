# MCP Server Stateless (spec 2026-07-28) + Upgrade de Plataforma — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Expor o swapi.build como MCP Server remoto em `https://swapi.build/mcp`, aderente ao modelo stateless da spec MCP 2026-07-28, aproveitando a mesma tocada para subir Quarkus para a LTS 3.33 e Java para a LTS 25.

**Architecture:** A extensão Quarkiverse `quarkus-mcp-server-http` (linha 2.0.0, única que implementa a spec 2026-07-28 stateless — auto-detectada, sem flag) entra no mesmo app Quarkus, registrando o endpoint Streamable HTTP `/mcp` no mesmo servidor HTTP dos endpoints Jakarta REST `/api/*`. Quatro tools genéricas read-only (list/get/random/search com `resource` como enum) delegam aos seis services que já têm os dados em memória e serializam a resposta como JSON via JSON-B. Mesmo container nativo (Mandrel), mesmo deploy Vercel, mesmo domínio.

**Tech Stack:** Quarkus 3.33.3 (LTS), Java 25 (LTS), `io.quarkiverse.mcp:quarkus-mcp-server-http` 2.0.0.Beta3 (ou 2.0.0.x mais novo — ver Task 3 Step 1), McpAssured (`quarkus-mcp-server-test`) + rest-assured para testes, Mandrel `jdk-25`, Quinoa 2.8.3.

## Global Constraints

- Quarkus platform: `io.quarkus.platform:quarkus-bom:3.33.3` (LTS até 2027-03-25). A extensão MCP 2.0.0.Beta3 é buildada contra Quarkus 3.33.2.1 — o platform 3.23.0 atual NÃO serve; o upgrade é pré-requisito, não conveniência.
- Java: `maven.compiler.release=25` (LTS). Java 26 fora — Mandrel não tem linha jdk-26.
- Extensão MCP: linha **2.0.0** obrigatória — a 1.13.x só implementa até a spec 2025-11-25 e não tem modo stateless. Antes de fixar a versão, checar se saiu Beta4/CR/GA (a Beta3, de 10/07, implementa o RC da spec; a spec final saiu 28/07).
- Transporte: Streamable HTTP stateless. Nunca depender do endpoint legacy SSE (`/mcp/sse`); a 2.x não tem config para desligá-lo — aceito e documentado.
- Todas as tools: `readOnlyHint = true`, `idempotentHint` conforme o caso, `openWorldHint = false`.
- Imagens: builder `quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25`; runtime `quay.io/quarkus/ubi9-quarkus-micro-image:2.0` (inalterada — continua a atual); base JVM (jib) `eclipse-temurin:25.0.3_9-jre-ubi10-minimal` (NÃO existe `-jre-ubi9-minimal` para Java 25; UBI10 exige CPU x86-64-v3 — ok em Vercel/cloud moderna).
- Config obrigatória atrás da Vercel: `quarkus.mcp.server.http.dns-rebinding-check.enabled=false` (o default rejeita Origin não-localhost com 403).
- Deploy: pipeline existente da migração (credenciais em `.env` — `VERCEL_API_TOKEN`/`VERCEL_TEAM_ID`, gitignored, jamais imprimir). Cloudflare está DNS-only (nuvem cinza): NÃO há edge do Cloudflare para rate limiting.
- Quirk existente preservado: os endpoints REST respondem `202 Accepted` (não 200) — não "corrigir" de carona.

## Fora de escopo (explícito)

- **Publicação em diretórios/registries** (MCP Registry, PulseMCP, Glama, Smithery, Claude Connectors Directory) e privacy policy — só depois do server validado em produção, decisão à parte.
- **MCP Apps** (UI no chat) — v2.
- **LangChain4j — não entra**: o swapi.build não depende de LangChain4j em nenhum lugar (verificado no pom/código), e a extensão MCP server é independente dele. Para referência nos seus projetos de demo que consomem a API: upstream `dev.langchain4j:langchain4j` **1.18.1** (29/07/2026); `io.quarkiverse.langchain4j` **1.12.2** (29/07/2026, pareia com LangChain4j 1.17.2 — ainda não com 1.18.x).
- Rate limiting: a API REST já é pública sem limite; o `/mcp` não cria classe nova de exposição. Opção futura: regra no Vercel Firewall (dashboard, manual). Não bloqueia este plano.

---

### Task 1: Upgrade de plataforma (Quarkus 3.33.3 + Java 25 + Quinoa 2.8.3 + imagens)

**Files:**
- Modify: `swapi-app/pom.xml:10-19` (properties), `swapi-app/pom.xml:36-38` (quinoa)
- Modify: `swapi-app/src/main/resources/application.properties` (imagens)
- Modify: `swapi-app/Dockerfile.vercel:2` (builder)

**Interfaces:**
- Consumes: estado atual (Quarkus 3.23.0, Java 24, Quinoa 2.7.2).
- Produces: build JVM verde na plataforma nova; Tasks 2+ assumem `quarkus.platform.version=3.33.3` e `release=25`.

- [ ] **Step 1: Atualizar properties do `pom.xml`**

Em `swapi-app/pom.xml`, trocar:

```xml
<maven.compiler.release>25</maven.compiler.release>
<quarkus.platform.version>3.33.3</quarkus.platform.version>
<maven.compiler.source>25</maven.compiler.source>
<maven.compiler.target>25</maven.compiler.target>
```

E a dependência Quinoa:

```xml
<dependency>
    <groupId>io.quarkiverse.quinoa</groupId>
    <artifactId>quarkus-quinoa</artifactId>
    <version>2.8.3</version>
</dependency>
```

- [ ] **Step 2: Atualizar imagens em `application.properties`**

```properties
quarkus.jib.base-jvm-image=eclipse-temurin:25.0.3_9-jre-ubi10-minimal
quarkus.native.builder-image=quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25
```

(As demais linhas ficam como estão; `ubi9-quarkus-micro-image:2.0` no Dockerfile continua atual.)

- [ ] **Step 3: Atualizar builder no `Dockerfile.vercel`**

```dockerfile
FROM quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25 AS build
```

(O stage 2 `ubi9-quarkus-micro-image:2.0` permanece — builder e runtime seguem ambos UBI9/glibc 2.34, preservando a correção do commit 82a078a.)

- [ ] **Step 4: Build JVM completo**

Run: `cd swapi-app && ./mvnw clean package`
Expected: `BUILD SUCCESS`. Se falhar com erro de config/extensão, consultar os migration guides 3.24→3.33 em https://github.com/quarkusio/quarkus/wiki/Migration-Guides (a alternativa automatizada é `./mvnw io.quarkus.platform:quarkus-maven-plugin:3.33.3:update`).

- [ ] **Step 5: Smoke em dev mode**

Run (background): `cd swapi-app && ./mvnw quarkus:dev`
Depois:

```bash
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:5432/api/people/1
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:5432/
```

Expected: `202` e `200`. Encerrar o dev mode após verificar.

- [ ] **Step 6: Commit**

```bash
git add swapi-app/pom.xml swapi-app/src/main/resources/application.properties swapi-app/Dockerfile.vercel
git commit -m "Upgrade to Quarkus 3.33.3 LTS, Java 25 LTS, Quinoa 2.8.3, Mandrel jdk-25"
```

---

### Task 2: Verificação do build nativo pós-upgrade

**Files:**
- Nenhum arquivo novo; possível ajuste em `swapi-app/Dockerfile.vercel` se o build falhar.

**Interfaces:**
- Consumes: Task 1 completa.
- Produces: confirmação de que Mandrel 25 + Quarkus 3.33 + Quinoa buildam o binário nativo — pré-condição para Task 5 não descobrir problema tarde.

- [ ] **Step 1: Build da imagem (10–25 min)**

Run: `cd swapi-app && docker build -f Dockerfile.vercel -t swapi-native-upgrade . 2>&1 | tail -20`
Expected: termina sem erro. Warnings de `sun.misc.Unsafe` são esperados no Mandrel jdk-25 (quarkus#51697) — cosmético, ignorar. Se faltar memória, reduzir `-Dquarkus.native.native-image-xmx` para `4g` no Dockerfile.

- [ ] **Step 2: Smoke do container**

```bash
docker run -d --rm --name swapi-native-test -p 5432:5432 swapi-native-upgrade
sleep 2
curl -s http://localhost:5432/api/people/1 | grep -o '"name":"[^"]*"'
docker stop swapi-native-test
```

Expected: `"name":"Luke Skywalker"`.

- [ ] **Step 3: Commit (somente se houve ajuste no Dockerfile)**

```bash
git add swapi-app/Dockerfile.vercel
git commit -m "Adjust native build settings for Mandrel 25"
```

---

### Task 3: Extensão MCP + tools genéricas (TDD)

**Files:**
- Modify: `swapi-app/pom.xml` (dependências)
- Modify: `swapi-app/src/main/resources/application.properties` (config MCP)
- Create: `swapi-app/src/main/java/com/eldermoraes/mcp/SwapiTools.java`
- Test: `swapi-app/src/test/java/com/eldermoraes/mcp/SwapiToolsTest.java`

**Interfaces:**
- Consumes: services existentes — `PeopleService.getAllPeople()/getPeopleByName(String)/getPeopleById(int)/getRandomPeople()`; `FilmService.getAllFilms()/getFilmByTitle(String)/getFilmByEpisodeId(int)/getRandomFilm()`; `PlanetService`, `SpecieService`, `StarshipService`, `VehicleService` (padrão `getAllX/getXByName/getXById/getRandomX`); `SWService.setBaseUrl(String)`.
- Produces: bean `SwapiTools` com tools MCP `sw_list`, `sw_get`, `sw_random`, `sw_search` e enum `SwResource {PEOPLE, FILMS, PLANETS, SPECIES, STARSHIPS, VEHICLES}`; endpoint `/mcp`. Task 4 testa o modo stateless sobre estas tools; Task 6 as expõe em produção.

- [ ] **Step 1: Checar se existe release 2.0.0.x mais novo que Beta3**

Run: `curl -s https://api.github.com/repos/quarkiverse/quarkus-mcp-server/releases?per_page=5 | grep '"tag_name"'`
Se existir `2.0.0.Beta4`/`CR1`/`2.0.0` final, usar essa versão nos passos seguintes (a Beta3 implementa o RC da spec; versões posteriores alinham com o texto final de 28/07). Caso contrário, seguir com `2.0.0.Beta3`.

- [ ] **Step 2: Adicionar dependências no `pom.xml`**

```xml
<dependency>
    <groupId>io.quarkiverse.mcp</groupId>
    <artifactId>quarkus-mcp-server-http</artifactId>
    <version>2.0.0.Beta3</version>
</dependency>
<dependency>
    <groupId>io.quarkiverse.mcp</groupId>
    <artifactId>quarkus-mcp-server-test</artifactId>
    <version>2.0.0.Beta3</version>
    <scope>test</scope>
</dependency>
```

- [ ] **Step 3: Escrever o teste que falha**

Criar `swapi-app/src/test/java/com/eldermoraes/mcp/SwapiToolsTest.java`:

```java
package com.eldermoraes.mcp;

import io.quarkiverse.mcp.server.test.McpAssured;
import io.quarkiverse.mcp.server.test.McpAssured.McpStreamableTestClient;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
public class SwapiToolsTest {

    static McpStreamableTestClient client;

    static McpStreamableTestClient client() {
        if (client == null) {
            client = McpAssured.newConnectedStreamableClient();
        }
        return client;
    }

    @AfterAll
    static void closeClient() {
        if (client != null) {
            client.disconnect();
        }
    }

    @Test
    public void toolsAreListedAsReadOnly() {
        client().when()
                .toolsList(page -> {
                    assertEquals(4, page.size());
                    page.findByName("sw_list").annotations().ifPresentOrElse(
                            a -> assertTrue(a.readOnlyHint()),
                            () -> fail("sw_list sem annotations"));
                    assertNotNull(page.findByName("sw_get"));
                    assertNotNull(page.findByName("sw_random"));
                    assertNotNull(page.findByName("sw_search"));
                })
                .thenAssertResults();
    }

    @Test
    public void getReturnsLukeById() {
        client().when()
                .toolsCall("sw_get")
                .withArguments(java.util.Map.of("resource", "PEOPLE", "id", 1))
                .withAssert(r -> {
                    assertFalse(r.isError());
                    assertTrue(r.content().get(0).asText().text().contains("Luke Skywalker"));
                })
                .send()
                .thenAssertResults();
    }

    @Test
    public void searchFindsSkywalkers() {
        client().when()
                .toolsCall("sw_search")
                .withArguments(java.util.Map.of("resource", "PEOPLE", "query", "skywalker"))
                .withAssert(r -> {
                    assertFalse(r.isError());
                    assertTrue(r.content().get(0).asText().text().toLowerCase().contains("skywalker"));
                })
                .send()
                .thenAssertResults();
    }

    @Test
    public void unknownIdIsToolError() {
        client().when()
                .toolsCall("sw_get")
                .withArguments(java.util.Map.of("resource", "PEOPLE", "id", 99999))
                .withAssert(r -> assertTrue(r.isError()))
                .send()
                .thenAssertResults();
    }
}
```

⚠️ A API fluente do McpAssured 2.x pode divergir em detalhes (nomes como `findByName`/`annotations`). Se não compilar, consultar https://docs.quarkiverse.io/quarkus-mcp-server/dev/guides-testing.html e ajustar as asserções mantendo a intenção (4 tools, readOnlyHint, conteúdo com "Luke Skywalker", erro para id inexistente).

- [ ] **Step 4: Rodar e ver falhar**

Run: `cd swapi-app && ./mvnw test -Dtest=SwapiToolsTest`
Expected: FAIL (nenhuma tool registrada / classe `SwapiTools` inexistente).

- [ ] **Step 5: Implementar `SwapiTools`**

Criar `swapi-app/src/main/java/com/eldermoraes/mcp/SwapiTools.java`:

```java
package com.eldermoraes.mcp;

import com.eldermoraes.film.FilmService;
import com.eldermoraes.people.PeopleService;
import com.eldermoraes.planet.PlanetService;
import com.eldermoraes.specie.SpecieService;
import com.eldermoraes.starship.StarshipService;
import com.eldermoraes.vehicle.VehicleService;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.ToolCallException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.bind.Jsonb;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class SwapiTools {

    public enum SwResource { PEOPLE, FILMS, PLANETS, SPECIES, STARSHIPS, VEHICLES }

    @Inject PeopleService peopleService;
    @Inject FilmService filmService;
    @Inject PlanetService planetService;
    @Inject SpecieService specieService;
    @Inject StarshipService starshipService;
    @Inject VehicleService vehicleService;
    @Inject Jsonb jsonb;

    @ConfigProperty(name = "swapi.public-base-url", defaultValue = "https://swapi.build/api")
    String publicBaseUrl;

    // Os services montam URLs como baseUrl + path; o REST seta via UriInfo por request,
    // aqui o contexto e fixo e vem de config.
    private void applyBaseUrl() {
        peopleService.setBaseUrl(publicBaseUrl);
        filmService.setBaseUrl(publicBaseUrl);
        planetService.setBaseUrl(publicBaseUrl);
        specieService.setBaseUrl(publicBaseUrl);
        starshipService.setBaseUrl(publicBaseUrl);
        vehicleService.setBaseUrl(publicBaseUrl);
    }

    @Tool(description = "Lists all entities of a Star Wars resource type from swapi.build. "
            + "Returns a JSON array.",
          annotations = @Tool.Annotations(title = "List Star Wars resources",
                  readOnlyHint = true, idempotentHint = true, openWorldHint = false))
    public String sw_list(
            @ToolArg(description = "Resource type") SwResource resource) {
        applyBaseUrl();
        return jsonb.toJson(switch (resource) {
            case PEOPLE -> peopleService.getAllPeople();
            case FILMS -> filmService.getAllFilms();
            case PLANETS -> planetService.getAllPlanets();
            case SPECIES -> specieService.getAllSpecies();
            case STARSHIPS -> starshipService.getAllStarships();
            case VEHICLES -> vehicleService.getAllVehicles();
        });
    }

    @Tool(description = "Gets one Star Wars entity by numeric id. For FILMS the id is the "
            + "episode id (e.g. 4 = A New Hope). Returns a JSON object.",
          annotations = @Tool.Annotations(title = "Get Star Wars entity by id",
                  readOnlyHint = true, idempotentHint = true, openWorldHint = false))
    public String sw_get(
            @ToolArg(description = "Resource type") SwResource resource,
            @ToolArg(description = "Numeric id (FILMS: episode id)") int id) {
        applyBaseUrl();
        Object result = switch (resource) {
            case PEOPLE -> peopleService.getPeopleById(id);
            case FILMS -> filmService.getFilmByEpisodeId(id);
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

    @Tool(description = "Returns one random Star Wars entity of the given resource type. "
            + "Great for live demos. Returns a JSON object.",
          annotations = @Tool.Annotations(title = "Random Star Wars entity",
                  readOnlyHint = true, openWorldHint = false))
    public String sw_random(
            @ToolArg(description = "Resource type") SwResource resource) {
        applyBaseUrl();
        return jsonb.toJson(switch (resource) {
            case PEOPLE -> peopleService.getRandomPeople();
            case FILMS -> filmService.getRandomFilm();
            case PLANETS -> planetService.getRandomPlanet();
            case SPECIES -> specieService.getRandomSpecie();
            case STARSHIPS -> starshipService.getRandomStarship();
            case VEHICLES -> vehicleService.getRandomVehicle();
        });
    }

    @Tool(description = "Searches a Star Wars resource by name (title for FILMS), "
            + "case-insensitive substring match. Returns a JSON array.",
          annotations = @Tool.Annotations(title = "Search Star Wars resources",
                  readOnlyHint = true, idempotentHint = true, openWorldHint = false))
    public String sw_search(
            @ToolArg(description = "Resource type") SwResource resource,
            @ToolArg(description = "Name/title fragment") String query) {
        applyBaseUrl();
        return jsonb.toJson(switch (resource) {
            case PEOPLE -> peopleService.getPeopleByName(query);
            case FILMS -> filmService.getFilmByTitle(query);
            case PLANETS -> planetService.getPlanetByName(query);
            case SPECIES -> specieService.getSpecieByName(query);
            case STARSHIPS -> starshipService.getStarshipByName(query);
            case VEHICLES -> vehicleService.getVehicleByName(query);
        });
    }
}
```

Notas de design: retorno como `String` JSON (não `structuredContent`) porque as tools são genéricas sobre 6 tipos heterogêneos — um output schema por tipo de retorno seria impreciso; JSON textual é o que os agentes consomem. `Jsonb` é bean provido pelo `quarkus-rest-jsonb` já presente.

- [ ] **Step 6: Config MCP em `application.properties`**

```properties
# MCP server (Streamable HTTP em /mcp; stateless auto-detectado pela extensao 2.x)
# Atras da Vercel o Origin nunca e localhost -> o check de DNS rebinding retornaria 403
quarkus.mcp.server.http.dns-rebinding-check.enabled=false
quarkus.mcp.server.server-info.name=swapi.build
%dev.swapi.public-base-url=http://localhost:5432/api
```

(Se `server-info.name` não existir na 2.x, remover a linha — verificar no log de startup.)

- [ ] **Step 7: Rodar e ver passar**

Run: `cd swapi-app && ./mvnw test -Dtest=SwapiToolsTest`
Expected: PASS (4 testes).

- [ ] **Step 8: Commit**

```bash
git add swapi-app/pom.xml swapi-app/src/main/resources/application.properties \
        swapi-app/src/main/java/com/eldermoraes/mcp/ swapi-app/src/test/java/com/eldermoraes/mcp/
git commit -m "Add MCP server with generic read-only tools over in-memory services"
```

---

### Task 4: Conformidade stateless + regressão REST

**Files:**
- Test: `swapi-app/src/test/java/com/eldermoraes/mcp/SwapiStatelessTest.java`
- Test: `swapi-app/src/test/java/com/eldermoraes/ApiRegressionTest.java`

**Interfaces:**
- Consumes: tools da Task 3 (`sw_get` com `resource`/`id`); endpoints REST existentes.
- Produces: prova automatizada de que (a) um client stateless 2026-07-28 funciona sem `initialize` e (b) o `/api/*` não regrediu com a extensão MCP no mesmo servidor.

- [ ] **Step 1: Escrever teste stateless (falha se o modo não estiver ativo)**

Criar `swapi-app/src/test/java/com/eldermoraes/mcp/SwapiStatelessTest.java`:

```java
package com.eldermoraes.mcp;

import io.quarkiverse.mcp.server.test.McpAssured;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
public class SwapiStatelessTest {

    @Test
    public void statelessClientCallsToolWithoutInitialize() {
        var client = McpAssured.newStreamableClient()
                .setStateless()
                .build()
                .connect();
        try {
            client.when()
                    .toolsCall("sw_get")
                    .withArguments(java.util.Map.of("resource", "PEOPLE", "id", 1))
                    .withAssert(r -> {
                        assertFalse(r.isError());
                        assertTrue(r.content().get(0).asText().text().contains("Luke Skywalker"));
                    })
                    .send()
                    .thenAssertResults();
        } finally {
            client.disconnect();
        }
    }
}
```

(Mesma ressalva da Task 3 Step 3 sobre detalhes da API fluente — o essencial é `setStateless()` e a chamada funcionar sem handshake.)

- [ ] **Step 2: Escrever teste de regressão REST**

Criar `swapi-app/src/test/java/com/eldermoraes/ApiRegressionTest.java`:

```java
package com.eldermoraes;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;

@QuarkusTest
public class ApiRegressionTest {

    @Test
    public void peopleByIdStillAnswers202WithLuke() {
        // 202 e o comportamento historico da API (Response.accepted()) - nao mudar
        given().when().get("/api/people/1")
                .then().statusCode(202)
                .body(containsString("Luke Skywalker"));
    }

    @Test
    public void searchStillWorks() {
        given().when().get("/api/planets?search=tatooine")
                .then().statusCode(202)
                .body(containsString("Tatooine"));
    }

    @Test
    public void randomStillWorks() {
        given().when().get("/api/starships/random")
                .then().statusCode(202)
                .body(containsString("model"));
    }
}
```

- [ ] **Step 3: Rodar a suíte inteira**

Run: `cd swapi-app && ./mvnw test`
Expected: PASS (SwapiToolsTest + SwapiStatelessTest + ApiRegressionTest).

- [ ] **Step 4: Commit**

```bash
git add swapi-app/src/test/java/
git commit -m "Add stateless MCP conformance and REST regression tests"
```

---

### Task 5: Build nativo com MCP + smoke no container

**Files:**
- Nenhum novo; possíveis ajustes de reflection/config se o nativo falhar (ex.: `@RegisterForReflection` adicional).

**Interfaces:**
- Consumes: Tasks 3–4 completas.
- Produces: imagem nativa com `/mcp` funcional — a mesma que a Vercel vai buildar na Task 6.

- [ ] **Step 1: Build da imagem**

Run: `cd swapi-app && docker build -f Dockerfile.vercel -t swapi-native-mcp . 2>&1 | tail -20`
Expected: sem erro (extensão MCP tem job nativo no CI próprio; risco é o normal de Beta — se falhar, capturar o erro e ajustar reflection/config conforme a mensagem).

- [ ] **Step 2: Smoke stateless via HTTP puro**

```bash
docker run -d --rm --name swapi-mcp-test -p 5432:5432 swapi-native-mcp
sleep 2
curl -s -o /dev/null -w 'api: %{http_code}\n' http://localhost:5432/api/people/1
curl -s -X POST http://localhost:5432/mcp \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -H 'MCP-Protocol-Version: 2026-07-28' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28"}}}'
docker stop swapi-mcp-test
```

Expected: `api: 202`; a resposta do `/mcp` contém `"sw_list"`, `"sw_get"`, `"sw_random"`, `"sw_search"`. (Se o wire format stateless divergir — ex.: exigir `server/discover` — ajustar o curl conforme o que os testes McpAssured da Task 4 registram no snapshot; o formato exato do request manual é o único ponto não confirmado.)

- [ ] **Step 3: Commit (somente se houve ajuste)**

```bash
git add -A swapi-app/src/main
git commit -m "Adjust native image config for MCP extension"
```

---

### Task 6: Versão, deploy preview → produção e verificação remota

**Files:**
- Modify: `swapi-app/pom.xml:6` (version), `swapi-app/src/main/resources/application.properties` (container-image.tag)

**Interfaces:**
- Consumes: imagem validada na Task 5; pipeline de deploy da migração de 23/07 (docs/superpowers/plans/2026-07-23-migracao-vercel-cloudflare.md).
- Produces: `https://swapi.build/mcp` em produção.

- [ ] **Step 1: Bump de versão**

`pom.xml`: `<version>1.9.0</version>`. `application.properties`: `quarkus.container-image.tag=1.9.0`.

- [ ] **Step 2: Commit do bump**

```bash
git add swapi-app/pom.xml swapi-app/src/main/resources/application.properties
git commit -m "Bump version to 1.9.0 (MCP server)"
```

- [ ] **Step 3: Deploy preview**

```bash
cd swapi-app
set -a; source ../.env 2>/dev/null || source .env; set +a
npx vercel deploy --token "$VERCEL_API_TOKEN" 2>&1 | tee /tmp/vercel-mcp-deploy.log
PREVIEW_URL=$(grep -Eo 'https://[a-z0-9.-]+\.vercel\.app' /tmp/vercel-mcp-deploy.log | tail -1)
echo "$PREVIEW_URL"
```

Expected: build ~10–25 min, `Ready`. Nunca imprimir o token. Se o preview responder 401 (Deployment Protection), usar o bypass documentado no plano da migração.

- [ ] **Step 4: Verificar `/mcp` no preview**

```bash
curl -s -X POST "$PREVIEW_URL/mcp" \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -H 'MCP-Protocol-Version: 2026-07-28' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28"}}}'
```

Expected: JSON com as 4 tools (mesmo formato validado na Task 5 Step 2). Sem 403 — se vier 403, o dns-rebinding-check não foi desabilitado (rever Task 3 Step 6).

- [ ] **Step 5: Deploy produção**

```bash
npx vercel deploy --prod --token "$VERCEL_API_TOKEN"
```

- [ ] **Step 6: Verificação end-to-end em produção**

```bash
curl -s -o /dev/null -w 'api: %{http_code}\n' https://swapi.build/api/people/1
curl -s -X POST https://swapi.build/mcp \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -H 'MCP-Protocol-Version: 2026-07-28' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28"}}}'
claude mcp add --transport http swapi-build https://swapi.build/mcp
```

Expected: `api: 202`; tools listadas; `claude mcp add` conecta (teste real com um client MCP — depois remover com `claude mcp remove swapi-build` se não quiser manter).

- [ ] **Step 7: Push**

```bash
git push origin main
```

---

### Task 7: Documentação mínima (sem publicação em diretório)

**Files:**
- Modify: `README.md` (nova seção após "API Endpoints")

**Interfaces:**
- Consumes: server em produção (Task 6).
- Produces: documentação pública do endpoint — pré-requisito de qualquer submissão futura, mas sem submeter nada.

- [ ] **Step 1: Adicionar seção MCP ao `README.md`**

Inserir após a seção "API Endpoints":

```markdown
## MCP Server

swapi.build is also available as a remote [MCP](https://modelcontextprotocol.io) server —
Streamable HTTP, stateless (spec 2026-07-28), no authentication required:

```
https://swapi.build/mcp
```

Tools (all read-only): `sw_list`, `sw_get`, `sw_random`, `sw_search` — each takes a
`resource` argument (`PEOPLE`, `FILMS`, `PLANETS`, `SPECIES`, `STARSHIPS`, `VEHICLES`).
For `FILMS`, ids are episode ids (e.g. `4` = A New Hope).

Example (Claude Code):

```bash
claude mcp add --transport http swapi-build https://swapi.build/mcp
```
```

- [ ] **Step 2: Commit e push**

```bash
git add README.md
git commit -m "Document the MCP server endpoint"
git push origin main
```

---

## Riscos e observações

1. **Beta**: a 2.0.0.Beta3 implementa o RC da spec final — funcional, mas esperar Beta4/CR em breve; o Step 1 da Task 3 força a checagem na hora de executar. Plano de contingência: se algo da Beta quebrar, a 1.13.1 (estável, spec 2025-11-25) funciona com todos os clients atuais — perde só a aderência stateless, recuperável num bump futuro.
2. **Endpoint legacy `/mcp/sse` fica exposto** (sem config para desligar na 2.x) — inofensivo; não documentar, não usar.
3. **API fluente do McpAssured**: os snippets de teste podem precisar de ajuste sintático contra a versão real — a intenção de cada asserção está descrita em cada teste.
4. **Wire format do curl stateless** (Tasks 5–6): único smoke não confirmado por fonte primária; os testes McpAssured são a verificação canônica, o curl é conferência extra.
5. **Rate limiting**: sem edge Cloudflare (DNS-only). Se o tráfego do `/mcp` incomodar, ativar regra no Vercel Firewall pelo dashboard — decisão operacional futura, fora deste plano.
