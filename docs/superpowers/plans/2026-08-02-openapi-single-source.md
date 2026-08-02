# OpenAPI como fonte única de documentação — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Servir a spec OpenAPI gerada do código em `/openapi.json` e fazer a página `/docs` do site renderizar a partir dela, com "try it", eliminando o hardcode de `documentation.ts`.

**Architecture:** `quarkus-smallrye-openapi` gera a spec em build time a partir de annotations MicroProfile OpenAPI nos resources e entidades. O frontend (Vite/TS vanilla) busca `/openapi.json` em runtime e renderiza com a identidade visual atual. Spec: `docs/superpowers/specs/2026-08-02-openapi-single-source-design.md`.

**Tech Stack:** Quarkus 3.33.3 (BOM), MicroProfile OpenAPI annotations, REST Assured, TypeScript/Vite (sem dependência JS nova).

## Processo de execução (estabelecido pelo Elder)

A implementação é executada por **subagente(s) Opus** (Agent tool, `model: opus`), um por task, enquanto a sessão principal monitora, revisa diffs, roda a suíte e valida cada task antes da próxima.

## Global Constraints

- Trabalhar em branch: `feature/openapi-single-source` (nunca na `main`).
- Testes: `cd swapi-app && ./mvnw test` (porta de teste 8081). Nunca `mvn clean` com dev mode rodando.
- Rodar a suíte **completa** antes de todo commit.
- `quarkus-smallrye-openapi` SEM `<version>` (gerenciada pelo `quarkus-bom`).
- **Nenhum domínio hardcoded em lugar nenhum** — nem na spec (`servers`), nem em descrições (usar caminhos relativos como `/mcp`).
- Contrato HTTP é **200/404** — o 202 histórico está aposentado e não aparece na spec.
- Conteúdo da spec (summaries, descriptions) em **inglês** (idioma do produto).
- Nenhuma mudança de comportamento dos endpoints — a spec documenta o contrato existente.
- Frontend: sem infra de teste JS (não criar); validação por `npm run build` (type-check) + checklist manual em dev mode.
- Todos os caminhos abaixo são relativos a `swapi-app/`, exceto quando indicado.

---

### Task 1: Extensão + spec servida em `/openapi.json` (JSON garantido)

**Files:**
- Modify: `pom.xml` (dependencies)
- Modify: `src/main/resources/application.properties`
- Test: `src/test/java/com/eldermoraes/OpenApiSpecTest.java` (create)

**Interfaces:**
- Consumes: nada.
- Produces: endpoint `GET /openapi.json` retornando o documento OpenAPI 3.x em JSON, independente do header `Accept`. Tasks 2–6 estendem `OpenApiSpecTest` e criam `OpenApiContractTest` contra esse endpoint.

- [ ] **Step 1: Criar a branch**

```bash
git checkout -b feature/openapi-single-source
```

- [ ] **Step 2: Escrever o teste que falha**

Criar `src/test/java/com/eldermoraes/OpenApiSpecTest.java`:

```java
package com.eldermoraes;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;

@QuarkusTest
class OpenApiSpecTest {

    @Test
    void specIsServedAsJsonRegardlessOfAcceptHeader() {
        // Accept genérico (curl/navegador) — a URL diz .json, a resposta TEM que ser JSON
        given()
                .accept("*/*")
        .when()
                .get("/openapi.json")
        .then()
                .statusCode(200)
                .contentType(containsString("json"))
                .body("openapi", startsWith("3."));
    }
}
```

- [ ] **Step 3: Rodar e ver falhar**

Run: `./mvnw test -Dtest=OpenApiSpecTest`
Expected: FAIL (404 — endpoint não existe).

- [ ] **Step 4: Adicionar extensão e config**

Em `pom.xml`, dentro de `<dependencies>`, logo após `quarkus-rest-jsonb`:

```xml
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-smallrye-openapi</artifactId>
        </dependency>
```

Em `application.properties`, após o bloco do MCP server:

```properties
# OpenAPI: contrato canonico da API, servido em URL amigavel na raiz.
# A pagina /docs do site renderiza a partir dele (fonte unica de documentacao).
quarkus.smallrye-openapi.path=/openapi.json
```

- [ ] **Step 5: Rodar o teste de novo**

Run: `./mvnw test -Dtest=OpenApiSpecTest`

**Se PASSAR:** seguir ao Step 6.

**Contingência A — retornou YAML (content-type sem "json"):** o handler do SmallRye faz negotiation por `Accept` e o default é YAML. Aplicar reroute determinístico: mudar a config para `quarkus.smallrye-openapi.path=/openapi-doc` e criar `src/main/java/com/eldermoraes/OpenApiJsonRoute.java`:

```java
package com.eldermoraes;

import io.vertx.ext.web.Router;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/**
 * /openapi.json precisa devolver JSON para qualquer client (curl, navegador,
 * geradores de client), independente do Accept. O handler do SmallRye negocia
 * formato por header; este reroute fixa format=json via query param.
 */
@ApplicationScoped
public class OpenApiJsonRoute {

    void register(@Observes Router router) {
        router.get("/openapi.json").handler(ctx -> ctx.reroute("/openapi-doc?format=json"));
    }
}
```

**Contingência B — retornou o `index.html` do SPA (Quinoa engoliu a rota):** adicionar em `application.properties`:

```properties
quarkus.quinoa.ignored-path-prefixes=/api,/mcp,/openapi.json,/q
```

Re-rodar após cada contingência até PASS. Só aplicar a(s) contingência(s) necessária(s).

- [ ] **Step 6: Rodar a suíte completa**

Run: `./mvnw test`
Expected: tudo verde (nenhuma regressão nas rotas existentes).

- [ ] **Step 7: Commit**

```bash
git add pom.xml src/main/resources/application.properties src/test/java/com/eldermoraes/OpenApiSpecTest.java
# + OpenApiJsonRoute.java se a contingência A foi usada
git commit -m "feat: serve OpenAPI spec as JSON at /openapi.json"
```

---

### Task 2: Bloco `info` + guarda contra `servers` absoluto

**Files:**
- Modify: `src/main/resources/application.properties`
- Test: `src/test/java/com/eldermoraes/OpenApiSpecTest.java`

**Interfaces:**
- Consumes: `GET /openapi.json` (Task 1).
- Produces: `info.title` = `"swapi.build — Star Wars API"`, `info.version` não vazio, `info.license.name` = `"Apache 2.0"`, `info.description` mencionando `/mcp`; garantia de spec sem `servers` com URL absoluta (invariante do base-url por request).

- [ ] **Step 1: Escrever os testes que falham**

Adicionar a `OpenApiSpecTest.java` (novos imports: `org.hamcrest.Matchers.equalTo`, `org.hamcrest.Matchers.not`, `org.hamcrest.Matchers.emptyOrNullString`, `io.restassured.path.json.JsonPath`, `java.util.List`, `java.util.Map`, `org.junit.jupiter.api.Assertions`):

```java
    @Test
    void infoBlockIsComplete() {
        given().accept("*/*")
        .when().get("/openapi.json")
        .then()
                .statusCode(200)
                .body("info.title", equalTo("swapi.build — Star Wars API"))
                .body("info.version", not(emptyOrNullString()))
                .body("info.license.name", equalTo("Apache 2.0"))
                .body("info.description", containsString("/mcp"));
    }

    @Test
    void specHasNoAbsoluteServerUrls() {
        String body = given().accept("*/*")
                .when().get("/openapi.json")
                .then().statusCode(200)
                .extract().asString();

        List<Map<String, Object>> servers = new JsonPath(body).getList("servers");
        if (servers != null) {
            for (Map<String, Object> server : servers) {
                String url = String.valueOf(server.get("url"));
                Assertions.assertFalse(url.startsWith("http://") || url.startsWith("https://"),
                        "servers nao pode conter URL absoluta (base URL e por request): " + url);
            }
        }
    }
```

- [ ] **Step 2: Rodar e ver falhar**

Run: `./mvnw test -Dtest=OpenApiSpecTest`
Expected: `infoBlockIsComplete` FAIL (title default). `specHasNoAbsoluteServerUrls` pode passar — ele é uma guarda permanente.

- [ ] **Step 3: Configurar o bloco info**

Em `application.properties`, junto ao bloco OpenAPI da Task 1:

```properties
quarkus.smallrye-openapi.info-title=swapi.build — Star Wars API
quarkus.smallrye-openapi.info-description=RESTful public API with data about the Star Wars universe. All successful GETs return 200; nonexistent ids return 404. The same data is also exposed as an MCP server (Streamable HTTP) at /mcp.
quarkus.smallrye-openapi.info-license-name=Apache 2.0
quarkus.smallrye-openapi.info-license-url=https://www.apache.org/licenses/LICENSE-2.0
```

(`info-version` herda a versão da aplicação — não configurar.)

Se `specHasNoAbsoluteServerUrls` falhou no Step 2, adicionar também:

```properties
quarkus.smallrye-openapi.auto-add-server=false
```

- [ ] **Step 4: Rodar e ver passar**

Run: `./mvnw test -Dtest=OpenApiSpecTest`
Expected: PASS (3 testes).

- [ ] **Step 5: Suíte completa + commit**

```bash
./mvnw test
git add src/main/resources/application.properties src/test/java/com/eldermoraes/OpenApiSpecTest.java
git commit -m "feat: OpenAPI info block and absolute-server guard"
```

---

### Task 3: Annotations no PeopleResource (template do padrão)

**Files:**
- Modify: `src/main/java/com/eldermoraes/people/PeopleResource.java`
- Test: `src/test/java/com/eldermoraes/OpenApiContractTest.java` (create)

**Interfaces:**
- Consumes: `GET /openapi.json`.
- Produces: o padrão de annotation que as Tasks 4 replicam: `@Tag` na classe; `@Operation` + `@APIResponse`(s) por método; `@Parameter` em `id`/`search`. Paths na spec: `/api/people`, `/api/people/{id}`, `/api/people/random`.

- [ ] **Step 1: Escrever o teste que falha**

Criar `src/test/java/com/eldermoraes/OpenApiContractTest.java`:

```java
package com.eldermoraes;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class OpenApiContractTest {

    // Task 3 cobre "people"; Tasks 4 ampliam o @ValueSource para os demais
    @ParameterizedTest
    @ValueSource(strings = {"people"})
    void resourceOperationsAreFullyDocumented(String resource) {
        given().accept("*/*")
        .when().get("/openapi.json")
        .then()
                .statusCode(200)
                // list + search
                .body("paths.'/api/" + resource + "'.get.summary", not(emptyOrNullString()))
                .body("paths.'/api/" + resource + "'.get.parameters.find { it.name == 'search' }.description",
                        not(emptyOrNullString()))
                // by-id: contrato 200/404 explicito
                .body("paths.'/api/" + resource + "/{id}'.get.responses.'200'", notNullValue())
                .body("paths.'/api/" + resource + "/{id}'.get.responses.'404'.description", not(emptyOrNullString()))
                .body("paths.'/api/" + resource + "/{id}'.get.parameters.find { it.name == 'id' }.description",
                        not(emptyOrNullString()))
                // random
                .body("paths.'/api/" + resource + "/random'.get.summary", not(emptyOrNullString()));
    }
}
```

- [ ] **Step 2: Rodar e ver falhar**

Run: `./mvnw test -Dtest=OpenApiContractTest`
Expected: FAIL (sem annotations, `summary` e `404` não existem).

- [ ] **Step 3: Anotar o PeopleResource**

Em `PeopleResource.java`, adicionar os imports:

```java
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
```

Na classe:

```java
@Tag(name = "People", description = "People within the Star Wars universe")
public class PeopleResource {
```

Acima de `getAllPeople` (e `@Parameter` no argumento `search`):

```java
    @Operation(summary = "List all people",
            description = "Returns every person, or only those whose name matches the search query.")
    @APIResponse(responseCode = "200", description = "List of people",
            content = @Content(schema = @Schema(type = SchemaType.ARRAY, implementation = People.class)))
    public Response getAllPeople(
            @Parameter(description = "Filter by name (case-insensitive contains)", example = "luke")
            @QueryParam("search") String search) {
```

Acima de `getPeopleById` (e `@Parameter` no argumento `id`):

```java
    @Operation(summary = "Get a person by id")
    @APIResponse(responseCode = "200", description = "The person with the given id",
            content = @Content(schema = @Schema(implementation = People.class)))
    @APIResponse(responseCode = "404", description = "No person exists with the given id",
            content = @Content(mediaType = "text/plain"))
    public Response getPeopleById(
            @Parameter(description = "Numeric id of the person", example = "1")
            @PathParam("id") int id) {
```

Acima de `getRandomPeople`:

```java
    @Operation(summary = "Get a random person")
    @APIResponse(responseCode = "200", description = "A randomly selected person",
            content = @Content(schema = @Schema(implementation = People.class)))
    public Response getRandomPeople() {
```

- [ ] **Step 4: Rodar e ver passar**

Run: `./mvnw test -Dtest=OpenApiContractTest`
Expected: PASS.

- [ ] **Step 5: Suíte completa + commit**

```bash
./mvnw test
git add src/main/java/com/eldermoraes/people/PeopleResource.java src/test/java/com/eldermoraes/OpenApiContractTest.java
git commit -m "feat: OpenAPI annotations on PeopleResource (pattern template)"
```

---

### Task 4: Annotations nos demais 5 resources + root

**Files:**
- Modify: `src/main/java/com/eldermoraes/film/FilmResource.java`
- Modify: `src/main/java/com/eldermoraes/planet/PlanetResource.java`
- Modify: `src/main/java/com/eldermoraes/specie/SpecieResource.java`
- Modify: `src/main/java/com/eldermoraes/starship/StarshipResources.java`
- Modify: `src/main/java/com/eldermoraes/vehicle/VehicleResource.java`
- Modify: `src/main/java/com/eldermoraes/ApiResource.java`
- Test: `src/test/java/com/eldermoraes/OpenApiContractTest.java`

**Interfaces:**
- Consumes: padrão da Task 3.
- Produces: todas as operações documentadas. Tags: `Films`, `Planets`, `Species`, `Starships`, `Vehicles`, `Root`.

- [ ] **Step 1: Ampliar o teste (falha primeiro)**

Em `OpenApiContractTest.java`, trocar o `@ValueSource` do teste existente por:

```java
    @ValueSource(strings = {"people", "films", "planets", "species", "starships", "vehicles"})
```

E adicionar o teste do root:

```java
    @org.junit.jupiter.api.Test
    void rootOperationIsDocumented() {
        given().accept("*/*")
        .when().get("/openapi.json")
        .then()
                .statusCode(200)
                .body("paths.'/api/'.get.summary", not(emptyOrNullString()));
    }
```

Nota: o path do root na spec pode materializar como `/api/` ou `/api`; verificar no JSON gerado (`curl -s localhost:8081/openapi.json` com dev mode em porta de teste ou via output do teste) e usar o que o gerador emitir.

- [ ] **Step 2: Rodar e ver falhar**

Run: `./mvnw test -Dtest=OpenApiContractTest`
Expected: FAIL para os 5 novos resources e root.

- [ ] **Step 3: Anotar cada resource**

Mesmos imports da Task 3 em cada arquivo. O `FilmResource.java` abaixo está completo e é o template — os demais seguem a mesma estrutura, com os textos listados em seguida.

`FilmResource.java` — na classe:

```java
@Tag(name = "Films", description = "Star Wars films")
public class FilmResource {
```

Acima do método de list/search (`@Parameter` no argumento `search`):

```java
    @Operation(summary = "List all films",
            description = "Returns every film, or only those whose title matches the search query.")
    @APIResponse(responseCode = "200", description = "List of films",
            content = @Content(schema = @Schema(type = SchemaType.ARRAY, implementation = Film.class)))
    public Response getAllFilms(
            @Parameter(description = "Filter by title (case-insensitive contains)", example = "hope")
            @QueryParam("search") String search) {
```

Acima do método by-id (`@Parameter` no argumento `id`):

```java
    @Operation(summary = "Get a film by id")
    @APIResponse(responseCode = "200", description = "The film with the given id",
            content = @Content(schema = @Schema(implementation = Film.class)))
    @APIResponse(responseCode = "404", description = "No film exists with the given id",
            content = @Content(mediaType = "text/plain"))
    public Response getFilmById(
            @Parameter(description = "Numeric id of the film", example = "1")
            @PathParam("id") int id) {
```

Acima do método random:

```java
    @Operation(summary = "Get a random film")
    @APIResponse(responseCode = "200", description = "A randomly selected film",
            content = @Content(schema = @Schema(implementation = Film.class)))
    public Response getRandomFilm() {
```

(Manter os nomes de método que já existem em cada arquivo — as annotations vão acima da assinatura existente, sem renomear nada.)

`PlanetResource.java` — `@Tag(name = "Planets", description = "Planets in the Star Wars universe")`; entidade `Planet`:
- list: `"List all planets"` / `"Returns every planet, or only those whose name matches the search query."`; search `"Filter by name (case-insensitive contains)"`, example `"tatooine"`.
- by-id: `"Get a planet by id"`; 200 `"The planet with the given id"`; 404 `"No planet exists with the given id"`; id `"Numeric id of the planet"`, example `"1"`.
- random: `"Get a random planet"`; 200 `"A randomly selected planet"`.

`SpecieResource.java` — `@Tag(name = "Species", description = "Species in the Star Wars universe")`; entidade `Specie`:
- list: `"List all species"` / `"Returns every species, or only those whose name matches the search query."`; search example `"wookiee"`.
- by-id: `"Get a species by id"`; 200 `"The species with the given id"`; 404 `"No species exists with the given id"`; id `"Numeric id of the species"`, example `"1"`.
- random: `"Get a random species"`; 200 `"A randomly selected species"`.

`StarshipResources.java` — `@Tag(name = "Starships", description = "Starships in the Star Wars universe")`; entidade `Starship`:
- list: `"List all starships"` / `"Returns every starship, or only those whose name matches the search query."`; search example `"falcon"`.
- by-id: `"Get a starship by id"`; 200 `"The starship with the given id"`; 404 `"No starship exists with the given id"`; id `"Numeric id of the starship"`, example `"2"`.
- random: `"Get a random starship"`; 200 `"A randomly selected starship"`.

`VehicleResource.java` — `@Tag(name = "Vehicles", description = "Vehicles in the Star Wars universe")`; entidade `Vehicle`:
- list: `"List all vehicles"` / `"Returns every vehicle, or only those whose name matches the search query."`; search example `"speeder"`.
- by-id: `"Get a vehicle by id"`; 200 `"The vehicle with the given id"`; 404 `"No vehicle exists with the given id"`; id `"Numeric id of the vehicle"`, example `"4"`.
- random: `"Get a random vehicle"`; 200 `"A randomly selected vehicle"`.

`ApiResource.java` — na classe: `@Tag(name = "Root", description = "API entry point")`. No método `get()`:

```java
    @Operation(summary = "List all available resources",
            description = "Returns the URLs of every resource collection, built from the request's base URL.")
    @APIResponse(responseCode = "200", description = "Map of resource name to collection URL",
            content = @Content(schema = @Schema(type = SchemaType.OBJECT)))
    public Response get() {
```

- [ ] **Step 4: Rodar e ver passar**

Run: `./mvnw test -Dtest=OpenApiContractTest`
Expected: PASS (6 resources parametrizados + root).

- [ ] **Step 5: Suíte completa + commit**

```bash
./mvnw test
git add src/main/java/com/eldermoraes src/test/java/com/eldermoraes/OpenApiContractTest.java
git commit -m "feat: OpenAPI annotations on all resources and root"
```

---

### Task 5: `@Schema` nas 6 entidades

**Files:**
- Modify: `src/main/java/com/eldermoraes/people/People.java`
- Modify: `src/main/java/com/eldermoraes/film/Film.java`
- Modify: `src/main/java/com/eldermoraes/planet/Planet.java`
- Modify: `src/main/java/com/eldermoraes/specie/Specie.java`
- Modify: `src/main/java/com/eldermoraes/starship/Starship.java`
- Modify: `src/main/java/com/eldermoraes/vehicle/Vehicle.java`
- Modify: `src/main/java/com/eldermoraes/SWObject.java`
- Test: `src/test/java/com/eldermoraes/OpenApiContractTest.java`

**Interfaces:**
- Consumes: schemas gerados automaticamente (`People`, `Film`, `Planet`, `Specie`, `Starship`, `Vehicle` em `components.schemas`).
- Produces: cada schema com `description` de classe e de todos os campos; **sem** propriedade `baseUrl`. A Task 7 renderiza tabelas de campos a partir dessas descrições.

- [ ] **Step 1: Ampliar o teste (falha primeiro)**

Adicionar a `OpenApiContractTest.java`:

```java
    @ParameterizedTest
    @ValueSource(strings = {"People", "Film", "Planet", "Specie", "Starship", "Vehicle"})
    void schemaIsDescribedAndClean(String schemaName) {
        String base = "components.schemas." + schemaName;
        given().accept("*/*")
        .when().get("/openapi.json")
        .then()
                .statusCode(200)
                .body(base + ".description", not(emptyOrNullString()))
                // todo campo exposto tem description
                .body(base + ".properties.every { it.value.description != null && !it.value.description.isEmpty() }",
                        org.hamcrest.Matchers.is(true))
                // baseUrl e detalhe interno de serializacao, nunca parte do contrato
                .body(base + ".properties.baseUrl", org.hamcrest.Matchers.nullValue())
                // url sempre presente (identidade do recurso)
                .body(base + ".properties.url.description", not(emptyOrNullString()));
    }
```

- [ ] **Step 2: Rodar e ver falhar**

Run: `./mvnw test -Dtest=OpenApiContractTest`
Expected: FAIL (sem descriptions).

- [ ] **Step 3: Anotar as entidades**

Import em todas: `import org.eclipse.microprofile.openapi.annotations.media.Schema;`

Em `SWObject.java`, garantir que `baseUrl` nunca vaza para o schema (o `@JsonbTransient` cobre a serialização; `@Schema(hidden = true)` cobre o gerador de spec):

```java
    @JsonbTransient
    @Schema(hidden = true)
    public String getBaseUrl() {
        return RequestBaseUrl.get();
    }
```

Anotar **os campos privados** (o SmallRye lê a annotation do campo). Campos comuns a todas as entidades:

```java
    @Schema(description = "ISO 8601 timestamp of when this resource was created")
    private String created;
    @Schema(description = "ISO 8601 timestamp of when this resource was last edited")
    private String edited;
    @Schema(description = "Canonical URL of this resource, built from the request's base URL")
    private String url;
```

`People.java` — classe: `@Schema(description = "A person within the Star Wars universe")`. Campos:

```java
    @Schema(description = "Name of this person") private String name;
    @Schema(description = "Height in centimeters, as a string; \"unknown\" when not recorded") private String height;
    @Schema(description = "Mass in kilograms, as a string; \"unknown\" when not recorded") private String mass;
    @Schema(description = "Hair color; \"n/a\" when the person has no hair") private String hair_color;
    @Schema(description = "Skin color") private String skin_color;
    @Schema(description = "Eye color") private String eye_color;
    @Schema(description = "Birth year, relative to the Battle of Yavin (BBY/ABY), e.g. \"19BBY\"") private String birth_year;
    @Schema(description = "Gender; \"n/a\" for droids") private String gender;
    @Schema(description = "URL of the planet resource this person was born on") private String homeworld;
    @Schema(description = "URLs of the film resources this person appeared in") private List<String> films;
    @Schema(description = "URLs of the species resources this person belongs to") private List<String> species;
    @Schema(description = "URLs of the starship resources this person has piloted") private List<String> starships;
```

`Film.java` — classe: `@Schema(description = "A single Star Wars film")`. Campos:

```java
    @Schema(description = "Title of this film") private String title;
    @Schema(description = "Episode number of this film in the saga") private int episode_id;
    @Schema(description = "Opening crawl text at the beginning of this film") private String opening_crawl;
    @Schema(description = "Director of this film") private String director;
    @Schema(description = "Producer(s) of this film, comma-separated") private String producer;
    @Schema(description = "Release date (ISO 8601 date) at original creator country") private String release_date;
    @Schema(description = "URLs of the people resources that appear in this film") private List<String> characters;
    @Schema(description = "URLs of the planet resources that appear in this film") private List<String> planets;
    @Schema(description = "URLs of the starship resources that appear in this film") private List<String> starships;
    @Schema(description = "URLs of the vehicle resources that appear in this film") private List<String> vehicles;
    @Schema(description = "URLs of the species resources that appear in this film") private List<String> species;
```

`Planet.java` — classe: `@Schema(description = "A planet in the Star Wars universe")`. Campos:

```java
    @Schema(description = "Name of this planet") private String name;
    @Schema(description = "Rotation period in standard hours, as a string") private String rotation_period;
    @Schema(description = "Orbital period in standard days, as a string") private String orbital_period;
    @Schema(description = "Diameter in kilometers, as a string") private String diameter;
    @Schema(description = "Climate(s), comma-separated") private String climate;
    @Schema(description = "Gravity, where \"1 standard\" is Earth-like, e.g. \"1 standard\", \"2.5 standard\"") private String gravity;
    @Schema(description = "Terrain type(s), comma-separated") private String terrain;
    @Schema(description = "Percentage of the surface covered by water, as a string") private String surface_water;
    @Schema(description = "Average population; \"unknown\" when not recorded") private String population;
    @Schema(description = "URLs of the people resources that live on this planet") private List<String> residents;
    @Schema(description = "URLs of the film resources this planet appeared in") private List<String> films;
```

`Specie.java` — classe: `@Schema(description = "A species in the Star Wars universe")`. Campos:

```java
    @Schema(description = "Name of this species") private String name;
    @Schema(description = "Classification, e.g. \"mammal\", \"reptile\"") private String classification;
    @Schema(description = "Designation, e.g. \"sentient\"") private String designation;
    @Schema(description = "Average height in centimeters, as a string") private String average_height;
    @Schema(description = "Common skin colors, comma-separated; \"none\" when skinless") private String skin_colors;
    @Schema(description = "Common hair colors, comma-separated; \"none\" when hairless") private String hair_colors;
    @Schema(description = "Common eye colors, comma-separated") private String eye_colors;
    @Schema(description = "Average lifespan in standard years, as a string") private String average_lifespan;
    @Schema(description = "URL of the planet resource this species originates from") private String homeworld;
    @Schema(description = "Language commonly spoken by this species") private String language;
    @Schema(description = "URLs of the people resources that belong to this species") private List<String> people;
    @Schema(description = "URLs of the film resources this species appeared in") private List<String> films;
```

`Starship.java` — classe: `@Schema(description = "A starship (transport with hyperdrive) in the Star Wars universe")`. Campos:

```java
    @Schema(description = "Common name of this starship") private String name;
    @Schema(description = "Model or official name, e.g. \"T-65 X-wing\"") private String model;
    @Schema(description = "Manufacturer(s), comma-separated") private String manufacturer;
    @Schema(description = "Cost in galactic credits, as a string") private String cost_in_credits;
    @Schema(description = "Length in meters, as a string") private String length;
    @Schema(description = "Maximum speed in atmosphere; \"n/a\" when incapable of atmospheric flight") private String max_atmosphering_speed;
    @Schema(description = "Number of personnel needed to run or pilot this starship") private String crew;
    @Schema(description = "Number of non-essential people this starship can transport") private String passengers;
    @Schema(description = "Maximum cargo capacity in kilograms, as a string") private String cargo_capacity;
    @Schema(description = "Maximum time this starship can provide consumables for its crew") private String consumables;
    @Schema(description = "Hyperdrive rating class") private String hyperdrive_rating;
    @Schema(description = "Maximum speed in megalights per hour") private String MGLT;
    @Schema(description = "Class of this starship, e.g. \"Starfighter\"") private String starship_class;
    @Schema(description = "URLs of the people resources that have piloted this starship") private List<String> pilots;
    @Schema(description = "URLs of the film resources this starship appeared in") private List<String> films;
```

`Vehicle.java` — classe: `@Schema(description = "A vehicle (transport without hyperdrive) in the Star Wars universe")`. Campos:

```java
    @Schema(description = "Common name of this vehicle") private String name;
    @Schema(description = "Model or official name, e.g. \"All-Terrain Attack Transport\"") private String model;
    @Schema(description = "Manufacturer(s), comma-separated") private String manufacturer;
    @Schema(description = "Cost in galactic credits, as a string") private String cost_in_credits;
    @Schema(description = "Length in meters, as a string") private String length;
    @Schema(description = "Maximum speed in atmosphere") private String max_atmosphering_speed;
    @Schema(description = "Number of personnel needed to run or pilot this vehicle") private String crew;
    @Schema(description = "Number of non-essential people this vehicle can transport") private String passengers;
    @Schema(description = "Maximum cargo capacity in kilograms, as a string") private String cargo_capacity;
    @Schema(description = "Maximum time this vehicle can provide consumables for its crew") private String consumables;
    @Schema(description = "Class of this vehicle, e.g. \"Wheeled\"") private String vehicle_class;
    @Schema(description = "URLs of the people resources that have piloted this vehicle") private List<String> pilots;
    @Schema(description = "URLs of the film resources this vehicle appeared in") private List<String> films;
```

- [ ] **Step 4: Rodar e ver passar**

Run: `./mvnw test -Dtest=OpenApiContractTest`
Expected: PASS.

- [ ] **Step 5: Suíte completa + commit**

```bash
./mvnw test
git add src/main/java/com/eldermoraes
git commit -m "feat: OpenAPI schema descriptions on all entities"
```

---

### Task 6: Teste de cobertura total de paths

**Files:**
- Test: `src/test/java/com/eldermoraes/OpenApiContractTest.java`

**Interfaces:**
- Consumes: spec completa (Tasks 1–5).
- Produces: guarda de regressão — endpoint novo sem documentação quebra o build.

- [ ] **Step 1: Escrever o teste**

Adicionar a `OpenApiContractTest.java` (imports: `java.util.Set`, `java.util.HashSet`):

```java
    @org.junit.jupiter.api.Test
    void everyApiPathIsPresentInSpec() {
        String body = given().accept("*/*")
                .when().get("/openapi.json")
                .then().statusCode(200)
                .extract().asString();

        Set<String> paths = new io.restassured.path.json.JsonPath(body).getMap("paths").keySet()
                .stream().map(String::valueOf).collect(java.util.stream.Collectors.toSet());

        Set<String> expected = new HashSet<>();
        for (String r : new String[]{"people", "films", "planets", "species", "starships", "vehicles"}) {
            expected.add("/api/" + r);
            expected.add("/api/" + r + "/{id}");
            expected.add("/api/" + r + "/random");
        }

        org.junit.jupiter.api.Assertions.assertTrue(paths.containsAll(expected),
                "Paths ausentes na spec: " + expected.stream().filter(p -> !paths.contains(p)).toList());
        // root (a forma exata /api ou /api/ depende do gerador)
        org.junit.jupiter.api.Assertions.assertTrue(paths.contains("/api/") || paths.contains("/api"),
                "Path root ausente na spec");
    }
```

- [ ] **Step 2: Rodar (deve passar de primeira — é consolidação)**

Run: `./mvnw test -Dtest=OpenApiContractTest`
Expected: PASS. Se falhar, um path real ficou fora — corrigir a annotation ou o expected e entender o porquê antes de seguir.

- [ ] **Step 3: Suíte completa + commit**

```bash
./mvnw test
git add src/test/java/com/eldermoraes/OpenApiContractTest.java
git commit -m "test: full path coverage guard for the OpenAPI spec"
```

---

### Task 7: Frontend — tipos e fetch da spec

**Files:**
- Modify: `src/main/webui/src/types.ts`
- Modify: `src/main/webui/src/api.ts`

**Interfaces:**
- Consumes: `GET /openapi.json`.
- Produces: tipos `OpenApiSpec`, `OpenApiOperation`, `OpenApiParameter`, `OpenApiSchemaObj` e função `fetchOpenApiSpec(): Promise<OpenApiSpec>` — usados pela Task 8.

- [ ] **Step 1: Adicionar os tipos**

No fim de `types.ts`:

```ts
// Subconjunto do documento OpenAPI 3.x consumido pela página de docs
export interface OpenApiParameter {
  name: string;
  in: 'path' | 'query';
  description?: string;
  example?: string;
}

export interface OpenApiOperation {
  summary?: string;
  description?: string;
  tags?: string[];
  parameters?: OpenApiParameter[];
  responses: Record<string, { description?: string }>;
}

export interface OpenApiSchemaObj {
  description?: string;
  type?: string;
  properties?: Record<string, OpenApiSchemaObj>;
  items?: OpenApiSchemaObj;
}

export interface OpenApiSpec {
  openapi: string;
  info: { title: string; version: string; description?: string };
  tags?: { name: string; description?: string }[];
  paths: Record<string, { get?: OpenApiOperation }>;
  components?: { schemas?: Record<string, OpenApiSchemaObj> };
}
```

- [ ] **Step 2: Adicionar o fetch**

No fim de `api.ts` (import de tipo no topo: `import type { OpenApiSpec } from './types';`):

```ts
// Fetch direto (fora de request()): a spec não participa do cancelamento de
// navegação e precisa de Accept explícito para garantir JSON.
export async function fetchOpenApiSpec(): Promise<OpenApiSpec> {
  let res: Response;
  try {
    res = await fetch('/openapi.json', { headers: { Accept: 'application/json' } });
  } catch {
    throw new ApiError('Network error — check your connection', 0, 'network');
  }
  if (!res.ok) {
    throw new ApiError(`HTTP ${res.status}: ${res.statusText}`, res.status, 'http');
  }
  return (await res.json()) as OpenApiSpec;
}
```

- [ ] **Step 3: Type-check**

Run: `cd src/main/webui && npm run build`
Expected: build verde (tsc sem erros).

- [ ] **Step 4: Commit**

```bash
git add src/main/webui/src/types.ts src/main/webui/src/api.ts
git commit -m "feat(web): OpenAPI spec types and fetch"
```

---

### Task 8: Frontend — página de docs renderizada da spec

**Files:**
- Modify: `src/main/webui/src/pages/documentation.ts` (reescrita)
- Modify: `src/main/webui/src/main.ts` (await na chamada)
- Modify: `src/main/webui/src/style.css` (estilos novos)

**Interfaces:**
- Consumes: `fetchOpenApiSpec()` (Task 7); classes CSS existentes `docs`, `docs-intro`, `endpoint-block`, `endpoint-method`, `method-badge`, `endpoint-path`, `endpoint-desc`; `escapeHtml` de `utils.ts`.
- Produces: `renderDocumentation(container: HTMLElement): Promise<void>`; blocos de endpoint com `data-path` que a Task 9 usa para o try-it; tabelas `.schema-table`.

- [ ] **Step 1: Reescrever `documentation.ts`**

Substituir o conteúdo inteiro por:

```ts
import { fetchOpenApiSpec } from '../api';
import { escapeHtml } from '../utils';
import type { OpenApiOperation, OpenApiSchemaObj, OpenApiSpec } from '../types';

// tag OpenAPI -> nome do schema em components.schemas
const TAG_SCHEMA: Record<string, string> = {
  People: 'People',
  Films: 'Film',
  Planets: 'Planet',
  Species: 'Specie',
  Starships: 'Starship',
  Vehicles: 'Vehicle',
};

// Ordem de exibição: Root primeiro, demais na ordem declarada na spec
function orderedTags(spec: OpenApiSpec): string[] {
  const declared = (spec.tags ?? []).map((t) => t.name);
  const seen = new Set<string>();
  for (const item of Object.values(spec.paths)) {
    for (const tag of item.get?.tags ?? []) seen.add(tag);
  }
  const ordered = declared.filter((t) => seen.has(t));
  for (const t of seen) if (!ordered.includes(t)) ordered.push(t);
  return ordered.sort((a, b) => (a === 'Root' ? -1 : b === 'Root' ? 1 : 0));
}

function operationsByTag(spec: OpenApiSpec, tag: string): { path: string; op: OpenApiOperation }[] {
  return Object.entries(spec.paths)
    .filter(([, item]) => item.get?.tags?.includes(tag))
    .map(([path, item]) => ({ path, op: item.get! }))
    .sort((a, b) => a.path.localeCompare(b.path));
}

function endpointBlock(path: string, op: OpenApiOperation): string {
  const search = op.parameters?.find((p) => p.in === 'query' && p.name === 'search');
  const displayPath = search ? `${path}?search=${escapeHtml(search.example ?? 'value')}` : path;
  const desc = [op.summary, op.description].filter(Boolean).map((s) => escapeHtml(s!)).join(' — ');
  const has404 = Boolean(op.responses['404']);
  return `
    <div class="endpoint-block" data-path="${escapeHtml(path)}">
      <div class="endpoint-method">
        <span class="method-badge">GET</span>
        <span class="endpoint-path">${escapeHtml(displayPath)}</span>
      </div>
      <div class="endpoint-desc">${desc}${has404 ? ' <span class="status-note">200 / 404</span>' : ''}</div>
    </div>`;
}

function schemaTable(name: string, schema: OpenApiSchemaObj): string {
  const rows = Object.entries(schema.properties ?? {})
    .map(([field, prop]) => {
      const type = prop.type === 'array' ? `array of ${prop.items?.type ?? 'string'}` : (prop.type ?? '');
      return `<tr>
        <td class="schema-field">${escapeHtml(field)}</td>
        <td class="schema-type">${escapeHtml(type)}</td>
        <td>${escapeHtml(prop.description ?? '')}</td>
      </tr>`;
    })
    .join('');
  return `
    <details class="schema-details">
      <summary>${escapeHtml(name)} fields</summary>
      <table class="schema-table">
        <thead><tr><th>Field</th><th>Type</th><th>Description</th></tr></thead>
        <tbody>${rows}</tbody>
      </table>
    </details>`;
}

export async function renderDocumentation(container: HTMLElement): Promise<void> {
  container.innerHTML = `<div class="docs"><h1>Documentation</h1><p class="docs-intro">Loading API specification…</p></div>`;

  let spec: OpenApiSpec;
  try {
    spec = await fetchOpenApiSpec();
  } catch {
    container.innerHTML = `
      <div class="docs">
        <h1>Documentation</h1>
        <p class="docs-intro">Could not load the API specification right now.
        The raw spec is available at <a href="/openapi.json">/openapi.json</a>.</p>
      </div>`;
    return;
  }

  const tagDescriptions = new Map((spec.tags ?? []).map((t) => [t.name, t.description ?? '']));
  const sections = orderedTags(spec)
    .map((tag) => {
      const schemaName = TAG_SCHEMA[tag];
      const schema = schemaName ? spec.components?.schemas?.[schemaName] : undefined;
      return `
        <h2>${escapeHtml(tag)}</h2>
        ${tagDescriptions.get(tag) ? `<p class="tag-desc">${escapeHtml(tagDescriptions.get(tag)!)}</p>` : ''}
        ${operationsByTag(spec, tag).map(({ path, op }) => endpointBlock(path, op)).join('')}
        ${schema ? schemaTable(schemaName!, schema) : ''}`;
    })
    .join('');

  container.innerHTML = `
    <div class="docs">
      <h1>Documentation</h1>
      <p class="docs-intro">${escapeHtml(spec.info.description ?? '')}</p>
      <p class="spec-link">
        OpenAPI ${escapeHtml(spec.openapi)} · version ${escapeHtml(spec.info.version)} ·
        <a href="/openapi.json" download="openapi.json">Download the spec</a> and generate a client:
        <code>npx @openapitools/openapi-generator-cli generate -i /openapi.json -g typescript-fetch</code>
      </p>
      ${sections}
    </div>`;
}
```

- [ ] **Step 2: Ajustar `main.ts`**

Na função `navigate()`, trocar:

```ts
    case 'docs':
      renderDocumentation(container);
      break;
```

por:

```ts
    case 'docs':
      await renderDocumentation(container);
      break;
```

- [ ] **Step 3: Estilos**

No fim de `style.css` (ajustar às variáveis de cor existentes no arquivo — inspecionar as já usadas por `.endpoint-block` e reutilizá-las):

```css
/* Docs gerada da spec OpenAPI */
.spec-link { font-size: 0.9rem; }
.spec-link code { display: inline-block; margin-top: 0.25rem; word-break: break-all; }
.tag-desc { opacity: 0.8; margin: 0.25rem 0 0.75rem; }
.status-note { opacity: 0.6; font-size: 0.85em; }
.schema-details { margin: 0.5rem 0 1.5rem; }
.schema-details summary { cursor: pointer; }
.schema-table { width: 100%; border-collapse: collapse; margin-top: 0.5rem; font-size: 0.9rem; }
.schema-table th, .schema-table td { text-align: left; padding: 0.35rem 0.5rem; border-bottom: 1px solid rgba(255, 255, 255, 0.1); }
.schema-field { font-family: monospace; white-space: nowrap; }
.schema-type { opacity: 0.7; white-space: nowrap; }
```

- [ ] **Step 4: Type-check + verificação manual**

Run: `cd src/main/webui && npm run build` — Expected: verde.

Subir dev mode (`./mvnw quarkus:dev` a partir de `swapi-app/`, porta 5432) e verificar em `http://localhost:5432/docs`:
- Seções Root, People, Films, Planets, Species, Starships, Vehicles na ordem.
- Cada endpoint com badge GET, path e descrição vindos da spec.
- Tabelas de campos abrindo via `<details>`.
- Link de download da spec funciona.
- Simular falha (parar dev mode não dá — testar o estado de erro alterando temporariamente a URL do fetch para `/openapi-nope.json`, conferir a mensagem com link, e reverter).

- [ ] **Step 5: Commit**

```bash
git add src/main/webui/src/pages/documentation.ts src/main/webui/src/main.ts src/main/webui/src/style.css
git commit -m "feat(web): docs page rendered from the OpenAPI spec"
```

---

### Task 9: Frontend — "try it" nos endpoints

**Files:**
- Modify: `src/main/webui/src/pages/documentation.ts`
- Modify: `src/main/webui/src/style.css`

**Interfaces:**
- Consumes: blocos `data-path`/`data-tryable` (Task 8); `highlightJson` de `json-highlight.ts`; parâmetros da operação na spec.
- Produces: formulário try-it por endpoint; resposta (inclusive 404) renderizada inline com json-highlight.

- [ ] **Step 1: Estender `endpointBlock` e adicionar o handler**

Em `documentation.ts`, adicionar o import:

```ts
import { highlightJson } from '../json-highlight';
```

Substituir a função `endpointBlock` por:

```ts
function endpointBlock(path: string, op: OpenApiOperation): string {
  const search = op.parameters?.find((p) => p.in === 'query' && p.name === 'search');
  const idParam = op.parameters?.find((p) => p.in === 'path' && p.name === 'id');
  const displayPath = search ? `${path}?search=${escapeHtml(search.example ?? 'value')}` : path;
  const desc = [op.summary, op.description].filter(Boolean).map((s) => escapeHtml(s!)).join(' — ');
  const has404 = Boolean(op.responses['404']);

  const inputs = [
    idParam
      ? `<input class="try-input" name="id" type="number" min="1"
           placeholder="id" value="${escapeHtml(idParam.example ?? '1')}"
           aria-label="${escapeHtml(idParam.description ?? 'id')}">`
      : '',
    search
      ? `<input class="try-input" name="search" type="text"
           placeholder="search (optional)" value=""
           aria-label="${escapeHtml(search.description ?? 'search')}">`
      : '',
  ].join('');

  return `
    <div class="endpoint-block" data-path="${escapeHtml(path)}">
      <div class="endpoint-method">
        <span class="method-badge">GET</span>
        <span class="endpoint-path">${escapeHtml(displayPath)}</span>
      </div>
      <div class="endpoint-desc">${desc}${has404 ? ' <span class="status-note">200 / 404</span>' : ''}</div>
      <form class="try-form">
        ${inputs}
        <button type="submit" class="try-button">Try it</button>
      </form>
      <div class="try-result" hidden></div>
    </div>`;
}
```

No fim de `renderDocumentation`, após o `container.innerHTML = ...`, adicionar o handler (delegação única):

```ts
  container.querySelectorAll<HTMLFormElement>('.try-form').forEach((form) => {
    form.addEventListener('submit', async (e) => {
      e.preventDefault();
      const block = form.closest<HTMLElement>('.endpoint-block')!;
      const result = block.querySelector<HTMLElement>('.try-result')!;
      const idInput = form.querySelector<HTMLInputElement>('input[name="id"]');
      const searchInput = form.querySelector<HTMLInputElement>('input[name="search"]');

      let url = block.dataset.path!;
      if (idInput) url = url.replace('{id}', encodeURIComponent(idInput.value || '1'));
      if (searchInput?.value) url += `?search=${encodeURIComponent(searchInput.value)}`;

      result.hidden = false;
      result.innerHTML = '<p class="try-status">Loading…</p>';
      try {
        const res = await fetch(url);
        const statusLine = `<p class="try-status">GET ${escapeHtml(url)} → HTTP ${res.status}</p>`;
        const text = await res.text();
        let bodyHtml: string;
        try {
          bodyHtml = `<pre class="try-json">${highlightJson(JSON.parse(text))}</pre>`;
        } catch {
          bodyHtml = `<pre class="try-json">${escapeHtml(text)}</pre>`; // 404 devolve text/plain
        }
        result.innerHTML = statusLine + bodyHtml;
      } catch {
        result.innerHTML = '<p class="try-status">Network error — check your connection</p>';
      }
    });
  });
```

- [ ] **Step 2: Estilos**

No fim de `style.css`:

```css
/* Try it */
.try-form { display: flex; gap: 0.5rem; margin-top: 0.5rem; flex-wrap: wrap; }
.try-input { max-width: 12rem; padding: 0.3rem 0.5rem; }
.try-button { cursor: pointer; padding: 0.3rem 0.9rem; }
.try-result { margin-top: 0.5rem; }
.try-status { font-family: monospace; font-size: 0.85rem; opacity: 0.8; }
.try-json { overflow-x: auto; max-height: 24rem; }
```

(Como na Task 8: conferir e reutilizar as variáveis/estilos de input e botão já existentes no `style.css` para manter a identidade.)

- [ ] **Step 3: Type-check + verificação manual**

Run: `cd src/main/webui && npm run build` — Expected: verde.

Em dev mode (`http://localhost:5432/docs`):
- `GET /api/people/{id}` com id 1 → 200 com JSON highlighted.
- Mesmo endpoint com id 9999 → `HTTP 404` e corpo em texto exibidos (não é erro).
- List com search preenchido → URL com `?search=`.
- Random → 200.
- Root → 200 com o mapa de links.

- [ ] **Step 4: Commit**

```bash
git add src/main/webui/src/pages/documentation.ts src/main/webui/src/style.css
git commit -m "feat(web): try-it on every endpoint of the docs page"
```

---

### Task 10: README + verificação final

**Files:**
- Modify: `README.md` (raiz do repo)

**Interfaces:**
- Consumes: tudo acima.
- Produces: branch pronta para o gate de merge (decisão do Elder: merge local / PR / manter branch).

- [ ] **Step 1: README**

Na seção do README que descreve a API (após a listagem de endpoints ou equivalente — localizar a seção existente), adicionar:

```markdown
## OpenAPI

The full API contract is served at [`/openapi.json`](https://swapi.build/openapi.json)
(OpenAPI 3.x, generated from the code — always in sync). The
[documentation page](https://swapi.build/docs) renders from it, including a
"try it" for every endpoint. Generate a client with, e.g.:

    npx @openapitools/openapi-generator-cli generate -i https://swapi.build/openapi.json -g typescript-fetch
```

(URLs absolutas são permitidas no README — é documentação humana, não código; o invariante de base-url vale para a aplicação e a spec.)

- [ ] **Step 2: Suíte completa final**

Run: `cd swapi-app && ./mvnw test`
Expected: tudo verde.

- [ ] **Step 3: Commit**

```bash
git add README.md
git commit -m "docs: README documents the OpenAPI contract at /openapi.json"
```

- [ ] **Step 4: Parar — gate humano**

NÃO fazer merge nem deploy. Reportar ao Elder para decidir merge (local / PR / manter branch). Deploy segue `docs/DEPLOY.md` (preview → verificação → produção), incluindo:
- `curl -s https://<preview>/openapi.json | head` → JSON, `"openapi":"3.`
- Medir cold start antes/depois (a spec exige registrar o impacto no binário nativo).
- Página `/docs` no preview renderizando da spec com try-it funcional.
