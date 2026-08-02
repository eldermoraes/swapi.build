# BaseUrl Request Context Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminar o race do `baseUrl` por construção: entidades param de carregar estado de request; a base URL vive num holder por request (`ThreadLocal`) setado por um filtro JAX-RS (REST) e pelo entry de cada tool (MCP).

**Architecture:** `SWObject.getBaseUrl()` passa a ler `RequestBaseUrl.get()` — os getters dos 6 modelos (que já chamam `getBaseUrl()`) não mudam uma linha. Um `ContainerRequestFilter` seta o holder em toda request REST; `SwapiTools.applyBaseUrl()` seta no início de cada tool call. Todo o mecanismo antigo de mutação (`SWService.setBaseUrl`, loops nos 6 services, `UriInfo` nos construtores dos 6 resources) é removido. Comportamento externo idêntico.

**Tech Stack:** Quarkus 3.33 / Java 25, JUnit 5 + RestAssured (teste de concorrência com ExecutorService).

**Spec:** `docs/superpowers/specs/2026-08-02-baseurl-request-context.md`

## Global Constraints

- Testes: `cd swapi-app && ./mvnw test` (porta 8081); suíte completa antes de cada commit; nunca `mvn clean` com dev mode ativo.
- Branch `refactor/baseurl-request-context`.
- Comportamento externo não muda: mesmas URLs absolutas (REST e MCP), 200/404 intactos. A suíte atual (28 testes) precisa passar sem NENHUMA alteração além do teste novo.
- CRÍTICO: `@JsonbTransient` migra do campo para o getter em `SWObject` — sem isso o JSON-B cria uma propriedade `baseUrl` em todas as respostas (regressão de payload).
- Escopo `@RequestScoped` dos resources NÃO muda neste refactor.
- Implementador Opus; validação do controller (Fable).

---

### Task 0: Branch

- [ ] **Step 1:**

```bash
cd /Users/eldermoraes/git/eldermoraes/swapi.build
git checkout main && git checkout -b refactor/baseurl-request-context
```

(Commitar spec+plano como primeiro commit.)

---

### Task 1: O refactor (atômico — não compila por partes)

**Files:**
- Create: `swapi-app/src/main/java/com/eldermoraes/RequestBaseUrl.java`
- Create: `swapi-app/src/main/java/com/eldermoraes/BaseUrlFilter.java`
- Modify: `swapi-app/src/main/java/com/eldermoraes/SWObject.java` (reescrever)
- Modify: `swapi-app/src/main/java/com/eldermoraes/SWService.java` (remover `setBaseUrl`)
- Modify: os 6 services (`film/FilmService.java`, `people/PeopleService.java`, `planet/PlanetService.java`, `specie/SpecieService.java`, `starship/StarshipService.java`, `vehicle/VehicleService.java`) — remover o override `setBaseUrl` inteiro
- Modify: os 6 resources (`film/FilmResource.java`, `people/PeopleResource.java`, `planet/PlanetResource.java`, `specie/SpecieResource.java`, `starship/StarshipResources.java`, `vehicle/VehicleResource.java`) — construtor sem `UriInfo`
- Modify: `swapi-app/src/main/java/com/eldermoraes/mcp/SwapiTools.java` (`applyBaseUrl`)
- Test (create): `swapi-app/src/test/java/com/eldermoraes/ConcurrentBaseUrlTest.java`

**Interfaces:**
- Consumes: `uriInfo.getBaseUri()` no filtro; `resolveBaseUrl()` existente no MCP (config override → `HttpServerRequest`), que não muda.
- Produces: `RequestBaseUrl.set/get/clear` — contrato interno: todo entry point que serializa entidades seta antes; leitura sem set anterior retorna `null` (mesma semântica de falha visível de hoje).

- [ ] **Step 1: TDD — teste de concorrência que prova o race (RED)**

Criar `swapi-app/src/test/java/com/eldermoraes/ConcurrentBaseUrlTest.java`:

```java
package com.eldermoraes;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
public class ConcurrentBaseUrlTest {

    // Requests concorrentes com hosts diferentes nao podem contaminar as URLs
    // umas das outras — cada resposta carrega apenas o host de quem pediu.
    @Test
    public void concurrentRequestsKeepTheirOwnHost() throws Exception {
        int rounds = 200;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < rounds; i++) {
                final String host = (i % 2 == 0) ? "a.example" : "b.example";
                final String other = (i % 2 == 0) ? "b.example" : "a.example";
                results.add(pool.submit(() -> {
                    String body = given()
                            .header("X-Forwarded-Proto", "https")
                            .header("X-Forwarded-Host", host)
                            .when().get("/api/people/1")
                            .then().statusCode(200)
                            .extract().asString();
                    return body.contains("https://" + host + "/api/people/1")
                            && !body.contains(other);
                }));
            }
            for (Future<Boolean> f : results) {
                assertTrue(f.get(30, TimeUnit.SECONDS),
                        "resposta contaminada com o host de outra request");
            }
        } finally {
            pool.shutdown();
        }
    }
}
```

- [ ] **Step 2: Rodar e confirmar RED**

Run: `cd swapi-app && ./mvnw test -Dtest=ConcurrentBaseUrlTest`
Expected: FAIL — com a implementação atual (mutação compartilhada), respostas saem com o host da request vizinha. Se por azar estatístico passar, subir `rounds` para 500 e repetir; deve falhar. Registrar a saída no report.

- [ ] **Step 3: Criar o holder**

`swapi-app/src/main/java/com/eldermoraes/RequestBaseUrl.java`:

```java
package com.eldermoraes;

/**
 * Base URL da request corrente. Todo entry point que serializa entidades
 * (filtro REST, tools MCP) seta antes de qualquer leitura.
 */
public final class RequestBaseUrl {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private RequestBaseUrl() {
    }

    public static void set(String baseUrl) {
        CURRENT.set(baseUrl);
    }

    public static String get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
```

- [ ] **Step 4: Criar o filtro REST**

`swapi-app/src/main/java/com/eldermoraes/BaseUrlFilter.java`:

```java
package com.eldermoraes;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.ext.Provider;

@Provider
public class BaseUrlFilter implements ContainerRequestFilter {

    @Override
    public void filter(ContainerRequestContext requestContext) {
        String base = requestContext.getUriInfo().getBaseUri().toString();
        RequestBaseUrl.set(base.endsWith("/") ? base.substring(0, base.length() - 1) : base);
    }
}
```

(Serialização roda na mesma virtual thread da request — nada de residue no REST.)

- [ ] **Step 5: Reescrever SWObject**

`SWObject.java` inteiro vira:

```java
package com.eldermoraes;

import jakarta.json.bind.annotation.JsonbTransient;

public class SWObject {

    @JsonbTransient
    public String getBaseUrl() {
        return RequestBaseUrl.get();
    }
}
```

(O `@JsonbTransient` no getter é obrigatório — ver Global Constraints.)

- [ ] **Step 6: Remover o mecanismo antigo**

1. `SWService.java`: remover o método `setBaseUrl(String baseUrl)` da interface (fica só `loadJsonData`).
2. Nos 6 services: remover o override `setBaseUrl` inteiro (o método com o loop `forEach(... -> ....setBaseUrl(cleanUrl))`) e o `@Override` associado.
3. Nos 6 resources: o construtor perde o `UriInfo` e a chamada. Exemplo (`FilmResource`):

```java
    FilmResource(FilmService filmService){
        this.filmService = filmService;
    }
```

Mesmo shape nos outros 5 (People, Planet, Specie, Starship, Vehicle). Remover o import de `jakarta.ws.rs.core.UriInfo` onde ficar sem uso.

4. `SwapiTools.applyBaseUrl()` vira:

```java
    private void applyBaseUrl() {
        RequestBaseUrl.set(resolveBaseUrl());
    }
```

(`resolveBaseUrl()` não muda; as 6 linhas `xService.setBaseUrl(baseUrl)` somem. Import de `RequestBaseUrl` se necessário: `com.eldermoraes.RequestBaseUrl`.)

- [ ] **Step 7: Sanidade de remoção**

`grep -rn "setBaseUrl" swapi-app/src/main/java` → deve retornar APENAS `RequestBaseUrl.set` se o grep pegar (ou vazio para `setBaseUrl` literal). Nenhuma referência a `SWObject.setBaseUrl` ou `service.setBaseUrl` pode sobrar.

- [ ] **Step 8: Suíte completa (GREEN)**

Run: `cd swapi-app && ./mvnw test`
Expected: PASS — 29 testes (28 existentes SEM alteração + `ConcurrentBaseUrlTest`). Se qualquer teste existente exigir mudança, PARAR e reportar (o comportamento externo não podia mudar).

**Contingência (salto de thread no pipeline):** ThreadLocal é compatível com
virtual threads, e a doc do Quarkus REST (guia "rest", seção de filtros,
verificada em 02/08/2026 contra 3.35) afirma que request filters rodam na MESMA
thread do método quando o endpoint é blocking/`@RunOnVirtualThread` — o desenho
primário deve passar direto. O único ponto que só o teste confirma é a
serialização da resposta rodar nessa mesma thread. Se não rodar, o sintoma é
inequívoco: URLs saem `"null/..."` e ForwardedHeadersTest +
ConcurrentBaseUrlTest falham. Nesse caso, trocar SOMENTE a implementação
interna de `RequestBaseUrl` para o duplicated context do Vert.x
(`io.smallrye.common.vertx.ContextLocals`, propagado pela request inteira
independente de thread), mantendo o facade `set/get/clear` — nenhum outro
arquivo muda. Registrar no report qual das duas implementações ficou.

- [ ] **Step 9: Commit**

```bash
git add swapi-app/src
git commit -m "refactor: base url lives in per-request context, entities become read-only"
```

---

### Task 2: Bump de versão 2.0.1

**Files:**
- Modify: `swapi-app/pom.xml` (`<version>2.0.0</version>` → `<version>2.0.1</version>`)
- Modify: `swapi-app/src/main/webui/package.json` (`"version": "2.0.0"` → `"version": "2.0.1"`)
- Modify: `swapi-app/src/main/webui/package-lock.json` (sincronizar via `npm install --package-lock-only`)

(Decisão do usuário: toda alteração deployável gera bump. Refactor interno sem
mudança de contrato → patch.)

- [ ] **Step 1:** Aplicar as duas trocas e rodar `cd swapi-app/src/main/webui && npm install --package-lock-only` para sincronizar o lockfile.
- [ ] **Step 2:** `cd swapi-app && ./mvnw test` → PASS (banner deve logar `swapi-app 2.0.1`).
- [ ] **Step 3: Commit**

```bash
git add swapi-app/pom.xml swapi-app/src/main/webui/package.json swapi-app/src/main/webui/package-lock.json
git commit -m "chore: version 2.0.1"
```

---

### Task 3: Verificação final e handoff (controller)

- [ ] **Step 1:** Suíte do zero (29/29) + grep de sanidade.
- [ ] **Step 2:** Smoke em dev mode (5432): `people/1` com URLs `http://localhost:5432/...`; duas chamadas seguidas com `X-Forwarded-Host` distintos retornam cada uma seu host; MCP `sw_get` com URLs corretas.
- [ ] **Step 3:** Decisão de merge com o usuário; suíte no merge.
- [ ] **Step 4:** Deploy via `docs/DEPLOY.md`; pós-deploy padrão (o comportamento externo é idêntico — os checks atuais do runbook bastam).
