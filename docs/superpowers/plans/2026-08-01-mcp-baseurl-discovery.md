# MCP Base URL Discovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminar o domínio hardcoded `https://swapi.build/api` do caminho MCP, derivando o base URL público da própria request HTTP (config explícita vira apenas override/escape hatch).

**Architecture:** O lado REST já descobre o base URL por request via `UriInfo`. O caminho MCP passa a fazer o mesmo injetando o `HttpServerRequest` (Vert.x) — a extensão quarkus-mcp-server ativa contexto CDI de request por invocação de tool e, em transporte HTTP, disponibiliza esse bean request-scoped (client proxy; `SwapiTools` continua `@ApplicationScoped`). Config de proxy do Quarkus é habilitada para que `scheme`/`host` honrem `X-Forwarded-Proto`/`X-Forwarded-Host` atrás do Cloudflare/Vercel.

**Tech Stack:** Quarkus 3.33.3 (Java 25), quarkus-mcp-server-http 2.0.0.Beta3, McpAssured (testes MCP), REST-assured (testes REST), JUnit 5.

**Spec:** `docs/superpowers/specs/2026-08-01-mcp-baseurl-discovery-design.md`

## Global Constraints

- Precedência de resolução: config explícita `swapi.public-base-url` → discovery da request ativa (`scheme://host` + `/api`) → `ToolCallException` com mensagem clara (nunca inventar domínio).
- A propriedade `swapi.public-base-url` fica `Optional<String>` **sem** `defaultValue`; os overrides `%dev`/`%test` são removidos (com discovery eles produziriam exatamente o mesmo valor — redundantes).
- Nenhum service ou entidade muda (a race conhecida do `baseUrl` está fora de escopo).
- Testes rodam de dentro de `swapi-app/`: `./mvnw test` (Maven wrapper; testes existentes devem continuar passando). NÃO rodar `mvn clean` se houver dev mode ativo.
- Porta de teste do Quarkus: `8081` (default de `quarkus.http.test-port`).
- Mensagens de commit terminam com `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.

---

### Task 1: Discovery por request no SwapiTools

**Files:**
- Modify: `swapi-app/src/main/java/com/eldermoraes/mcp/SwapiTools.java` (linhas 30-42: campo config e `applyBaseUrl()`)
- Modify: `swapi-app/src/main/resources/application.properties` (remover linhas 20-21, os overrides `%dev`/`%test`)
- Test: `swapi-app/src/test/java/com/eldermoraes/mcp/SwapiBaseUrlDiscoveryTest.java` (novo)
- Test: `swapi-app/src/test/java/com/eldermoraes/mcp/SwapiBaseUrlOverrideTest.java` (novo)

**Interfaces:**
- Consumes: `SWService.setBaseUrl(String)` (existente, não muda); tools MCP `sw_get` etc. (existentes).
- Produces: método privado `resolveBaseUrl()` em `SwapiTools` — sem interface pública nova; comportamento observável: URLs embutidas no JSON das tools derivam do host da request quando `swapi.public-base-url` não está setada.

**Contexto para quem nunca viu o projeto:** as entidades (ex. `People`) guardam fragmentos como `"/people/1"` no campo `url` e o getter compõe `getBaseUrl() + url`. Os services propagam o base URL às entidades via `setBaseUrl`. No MCP, `SwapiTools.applyBaseUrl()` faz isso por tool call a partir da config. O dado de Luke Skywalker é `id=1`, então o JSON de `sw_get(PEOPLE, 1)` embute `<base>/people/1`.

- [ ] **Step 1: Remover os overrides `%dev`/`%test` de `application.properties`**

Remover estas duas linhas (20-21):

```properties
%dev.swapi.public-base-url=http://localhost:5432/api
%test.swapi.public-base-url=http://localhost:8081/api
```

(Sem isso o teste novo passaria por acidente: o override `%test` tem exatamente o valor que o discovery produzirá.)

- [ ] **Step 2: Escrever o teste de discovery (que deve falhar)**

Criar `swapi-app/src/test/java/com/eldermoraes/mcp/SwapiBaseUrlDiscoveryTest.java`:

```java
package com.eldermoraes.mcp;

import io.quarkiverse.mcp.server.test.McpAssured;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
public class SwapiBaseUrlDiscoveryTest {

    @Test
    public void embeddedUrlsFollowRequestHost() {
        var client = McpAssured.newConnectedStreamableClient();
        try {
            client.when()
                    .toolsCall("sw_get")
                    .withArguments(java.util.Map.of("resource", "PEOPLE", "id", 1))
                    .withAssert(r -> {
                        assertFalse(r.isError());
                        String json = r.content().get(0).asText().text();
                        assertTrue(json.contains("http://localhost:8081/api/people/1"),
                                "esperava URL derivada do host da request, veio: " + json);
                        assertFalse(json.contains("swapi.build/api"),
                                "nao deveria haver dominio de producao hardcoded: " + json);
                    })
                    .send()
                    .thenAssertResults();
        } finally {
            client.disconnect();
        }
    }
}
```

- [ ] **Step 3: Rodar o teste e confirmar que falha**

```bash
cd swapi-app && ./mvnw test -Dtest=SwapiBaseUrlDiscoveryTest
```

Expected: FAIL — com o override `%test` removido, o código atual cai no `defaultValue` e o JSON embute `https://swapi.build/api/people/1`.

- [ ] **Step 4: Implementar o discovery em `SwapiTools`**

Em `SwapiTools.java`, substituir o bloco atual (linhas 30-42):

```java
    @ConfigProperty(name = "swapi.public-base-url", defaultValue = "https://swapi.build/api")
    String publicBaseUrl;

    // Os services montam URLs como baseUrl + path; o REST seta via UriInfo por request,
    // aqui o contexto e fixo e vem de config.
    private void applyBaseUrl() {
        peopleService.setBaseUrl(publicBaseUrl);
        ...
```

por:

```java
    @ConfigProperty(name = "swapi.public-base-url")
    Optional<String> publicBaseUrl;

    // Client proxy request-scoped: resolve para a request MCP ativa no momento
    // da chamada da tool (transporte HTTP ativa o contexto CDI de request).
    @Inject HttpServerRequest request;

    // Os services montam URLs como baseUrl + path; o REST descobre via UriInfo por
    // request. Aqui a config explicita vence (escape hatch operacional); sem ela,
    // o dominio vem da propria request - nada de dominio hardcoded no binario.
    private String resolveBaseUrl() {
        if (publicBaseUrl.isPresent()) {
            return publicBaseUrl.get();
        }
        try {
            return request.scheme() + "://" + request.host() + "/api";
        } catch (RuntimeException e) {
            throw new ToolCallException("Cannot resolve public base URL: "
                    + "no active HTTP request and swapi.public-base-url is not set");
        }
    }

    private void applyBaseUrl() {
        String baseUrl = resolveBaseUrl();
        peopleService.setBaseUrl(baseUrl);
        filmService.setBaseUrl(baseUrl);
        planetService.setBaseUrl(baseUrl);
        specieService.setBaseUrl(baseUrl);
        starshipService.setBaseUrl(baseUrl);
        vehicleService.setBaseUrl(baseUrl);
    }
```

Imports novos: `io.vertx.core.http.HttpServerRequest` e `java.util.Optional`. (`request.host()` do Vert.x inclui a porta quando não é a default; com o proxy forwarding da Task 2, `scheme()`/`host()` passam a refletir `X-Forwarded-*`.)

- [ ] **Step 5: Rodar o teste e confirmar que passa**

```bash
cd swapi-app && ./mvnw test -Dtest=SwapiBaseUrlDiscoveryTest
```

Expected: PASS.

- [ ] **Step 6: Escrever o teste de precedência da config**

Criar `swapi-app/src/test/java/com/eldermoraes/mcp/SwapiBaseUrlOverrideTest.java` (usa `@TestProfile`, que reinicia o Quarkus com a config override — por isso é uma classe separada):

```java
package com.eldermoraes.mcp;

import io.quarkiverse.mcp.server.test.McpAssured;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(SwapiBaseUrlOverrideTest.OverrideProfile.class)
public class SwapiBaseUrlOverrideTest {

    public static class OverrideProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("swapi.public-base-url", "https://config-wins.example/api");
        }
    }

    @Test
    public void explicitConfigBeatsDiscovery() {
        var client = McpAssured.newConnectedStreamableClient();
        try {
            client.when()
                    .toolsCall("sw_get")
                    .withArguments(Map.of("resource", "PEOPLE", "id", 1))
                    .withAssert(r -> {
                        assertFalse(r.isError());
                        String json = r.content().get(0).asText().text();
                        assertTrue(json.contains("https://config-wins.example/api/people/1"),
                                "config explicita deveria vencer o discovery, veio: " + json);
                    })
                    .send()
                    .thenAssertResults();
        } finally {
            client.disconnect();
        }
    }
}
```

- [ ] **Step 7: Rodar os dois testes novos**

```bash
cd swapi-app && ./mvnw test -Dtest='SwapiBaseUrlDiscoveryTest,SwapiBaseUrlOverrideTest'
```

Expected: PASS (2 testes).

- [ ] **Step 8: Rodar a suíte inteira (regressão)**

```bash
cd swapi-app && ./mvnw test
```

Expected: PASS — em especial `SwapiToolsTest`, `SwapiStatelessTest` e `ApiRegressionTest`, que não dependem do valor do base URL.

- [ ] **Step 9: Commit**

```bash
git add swapi-app/src/main/java/com/eldermoraes/mcp/SwapiTools.java \
        swapi-app/src/main/resources/application.properties \
        swapi-app/src/test/java/com/eldermoraes/mcp/SwapiBaseUrlDiscoveryTest.java \
        swapi-app/src/test/java/com/eldermoraes/mcp/SwapiBaseUrlOverrideTest.java
git commit -m "MCP: derive public base URL from the active request, config as override

Removes the hardcoded https://swapi.build/api default; domain migrations
no longer require touching the binary or remembering an env var.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: Honrar X-Forwarded-* atrás do edge (proxy forwarding)

**Files:**
- Modify: `swapi-app/src/main/resources/application.properties` (bloco novo de proxy)
- Test: `swapi-app/src/test/java/com/eldermoraes/ForwardedHeadersTest.java` (novo)

**Interfaces:**
- Consumes: endpoint REST `GET /api/people/1` (existente; responde 202 com JSON de Luke — o 202 é comportamento histórico da API, não mudar).
- Produces: nenhuma API nova — comportamento observável: `scheme`/`host` vistos por `UriInfo` (REST) e `HttpServerRequest` (MCP, Task 1) passam a refletir `X-Forwarded-Proto`/`X-Forwarded-Host`.

**Contexto:** em produção o TLS termina na borda (Cloudflare/Vercel) e o host/scheme reais chegam nesses headers. Sem esta config, o discovery veria o host/scheme internos. O teste usa o caminho REST (REST-assured permite setar headers arbitrários; o cliente McpAssured não expõe isso com a mesma simplicidade) — a config de proxy atua na camada Vert.x, comum aos dois caminhos.

- [ ] **Step 1: Escrever o teste de forwarded headers (que deve falhar)**

Criar `swapi-app/src/test/java/com/eldermoraes/ForwardedHeadersTest.java`:

```java
package com.eldermoraes;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;

@QuarkusTest
public class ForwardedHeadersTest {

    @Test
    public void embeddedUrlsHonorForwardedHostAndProto() {
        given()
                .header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-Host", "migrated.example")
                .when().get("/api/people/1")
                .then().statusCode(202)
                .body(containsString("https://migrated.example/api/people/1"));
    }
}
```

- [ ] **Step 2: Rodar o teste e confirmar que falha**

```bash
cd swapi-app && ./mvnw test -Dtest=ForwardedHeadersTest
```

Expected: FAIL — sem a config de proxy, os headers são ignorados e a URL embutida fica `http://localhost:8081/api/people/1`.

- [ ] **Step 3: Adicionar a config de proxy em `application.properties`**

Logo abaixo do bloco do MCP server (após a linha `quarkus.mcp.server.server-info.name=swapi.build`):

```properties
# Atras do edge (Cloudflare/Vercel) o TLS termina na borda; host/scheme reais
# chegam via X-Forwarded-*. Necessario para o discovery do base URL publico
# (REST via UriInfo, MCP via HttpServerRequest). Seguro: em producao a app
# so e alcancavel atraves do edge.
quarkus.http.proxy.proxy-address-forwarding=true
quarkus.http.proxy.allow-x-forwarded=true
quarkus.http.proxy.enable-forwarded-host=true
```

- [ ] **Step 4: Rodar o teste e confirmar que passa**

```bash
cd swapi-app && ./mvnw test -Dtest=ForwardedHeadersTest
```

Expected: PASS.

- [ ] **Step 5: Rodar a suíte inteira (regressão)**

```bash
cd swapi-app && ./mvnw test
```

Expected: PASS — os testes que não enviam `X-Forwarded-*` (todos os demais) não são afetados pela config.

- [ ] **Step 6: Commit**

```bash
git add swapi-app/src/main/resources/application.properties \
        swapi-app/src/test/java/com/eldermoraes/ForwardedHeadersTest.java
git commit -m "Honor X-Forwarded-Proto/Host behind the edge for base-url discovery

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Verificação pós-deploy (manual, fora do plano de código)

Após o deploy na Vercel:

```bash
curl -s https://swapi.build/api/people/1 | grep -o 'https://swapi.build/api/people/1'
```

e uma chamada de tool MCP em `https://swapi.build/mcp` (ex. via Claude Code com o server `swapi-build` já registrado), conferindo que as URLs embutidas continuam `https://swapi.build/api/...` — atenção ao scheme `https`, por causa da mudança de proxy. Clients stateful podem ver 404 transiente no 1º connect (cold start serverless) — retry resolve.
