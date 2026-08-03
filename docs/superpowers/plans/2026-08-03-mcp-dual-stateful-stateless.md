# MCP dual stateful/stateless — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fazer o `/mcp` do swapi.build atender com confiabilidade total tanto clientes MCP stateless (`2026-07-28`) quanto stateful (`2025-03-26` a `2025-11-25`), que hoje recebem 33–58% de HTTP 404 sob concorrência em produção.

**Architecture:** A causa é topológica, não de protocolo: a sessão MCP vive num `ConcurrentMap` na heap de uma instância e o Vercel escala horizontalmente sem afinidade. `quarkus.mcp.server.http.streamable.auto-init=true` (correção recomendada pelo mantenedor da extensão) faz o servidor materializar uma sessão descartável por request, aceitando um `Mcp-Session-Id` de qualquer origem. Um `@RouteFilter` Vert.x sanea as bordas que o `auto-init` não cobre (`GET`/`DELETE` com sessão estrangeira, e o transporte legado HTTP+SSE). CORS é habilitado para clientes de browser, com `Vary: Origin` obrigatório em toda resposta cacheável na borda.

**Tech Stack:** Quarkus 3.33.3, Java 25, `io.quarkiverse.mcp:quarkus-mcp-server-http` 2.0.0.Beta3, `io.quarkus:quarkus-reactive-routes` (novo — provê `@RouteFilter`), rest-assured + McpAssured para testes, deploy container na Vercel.

## Global Constraints

- Spec: `docs/superpowers/specs/2026-08-03-mcp-dual-stateful-stateless-design.md`. Ela é a autoridade; este plano a executa.
- **Branch obrigatória.** Nunca implementar em `main` (`CLAUDE.md`).
- Suíte completa (`cd swapi-app && ./mvnw test`) verde antes de **cada** commit. Porta de teste 8081.
- Nunca rodar `mvn clean` com dev mode ativo.
- Tooling de container é `podman` (`/opt/podman/bin`), não `docker`.
- Deploy **sempre** de `swapi-app/`, nunca da raiz. `git push` não faz deploy. Seguir `docs/DEPLOY.md`.
- GETs bem-sucedidos retornam 200; id inexistente retorna 404.
- Base URL público é descoberto por request. Nunca reintroduzir domínio hardcoded.
- `Cache-Control` das respostas cacheáveis tem **definição única** em `swapi.cache-control.public`. Não duplicar o valor.
- Valor exato do header, travado no `CacheHeadersTest`: `public, max-age=300, s-maxage=31536000, stale-while-revalidate=86400`.
- Consequência aceita de `quarkus-reactive-routes`: ele traz `quarkus-jackson` transitivamente, num projeto que serializa com JSON-B. Dois stacks JSON no binário nativo. Custo aceito em troca de usar a API pública documentada de interceptação; medir o tamanho do binário na Task 6.

## File Structure

| Arquivo | Responsabilidade |
|---|---|
| `swapi-app/pom.xml` | Nova dependência `quarkus-reactive-routes`. |
| `swapi-app/src/main/resources/application.properties` | `auto-init`, CORS, `Vary` no filtro do `/openapi.json`. |
| `swapi-app/src/main/java/com/eldermoraes/mcp/McpTransportFilter.java` | **Novo.** Única responsabilidade: as bordas HTTP do endpoint MCP que a extensão não cobre nesta topologia — `GET`→405, `DELETE`→204, transporte legado→404. Nada de lógica de domínio. |
| `swapi-app/src/main/java/com/eldermoraes/CacheControlFilter.java` | Passa a gravar `Vary: Origin` junto do `Cache-Control`. Uma linha; responsabilidade inalterada. |
| `swapi-app/src/test/java/com/eldermoraes/mcp/McpForeignSessionTest.java` | **Novo.** Prova que sessão estrangeira/ausente é servida. |
| `swapi-app/src/test/java/com/eldermoraes/mcp/McpTransportEdgesTest.java` | **Novo.** Prova 405/204/404 das bordas e o preflight. |
| `swapi-app/src/test/java/com/eldermoraes/CacheHeadersTest.java` | Ganha os testes de `Vary`. |
| `CLAUDE.md`, `docs/DEPLOY.md`, `README.md` | Documentação alinhada ao comportamento real. |
| `swapi-app/src/main/webui/src/pages/{mcp,privacy,home}.ts` | Texto público que hoje afirma stateless-only e "no session ids" — inclusive na política de privacidade. Só texto; nenhuma lógica de página muda. |

---

### Task 1: Branch e sessão estrangeira aceita (`auto-init`)

Esta é a task que entrega a correção crítica. Todo o resto é saneamento.

**Files:**
- Create: `swapi-app/src/test/java/com/eldermoraes/mcp/McpForeignSessionTest.java`
- Modify: `swapi-app/src/main/resources/application.properties` (bloco MCP, linhas 22-25)

**Interfaces:**
- Consumes: as tools existentes de `SwapiTools` — `sw_get(SwResource resource, int id)` e `sw_list(SwResource resource)`; enum `SwResource {PEOPLE, FILMS, PLANETS, SPECIES, STARSHIPS, VEHICLES}`.
- Produces: `auto-init` ligado. As Tasks 2–3 assumem que um `POST` em `/mcp` com qualquer `Mcp-Session-Id` responde 200.

- [ ] **Step 1: Criar a branch**

```bash
cd /Users/eldermoraes/git/eldermoraes/swapi.build
git checkout -b mcp-dual-stateful-stateless
```

- [ ] **Step 2: Conferir se saiu release mais nova que a 2.0.0.Beta3**

```bash
curl -s "https://api.github.com/repos/quarkiverse/quarkus-mcp-server/releases?per_page=5" | grep '"tag_name"'
```

Se existir `2.0.0.Beta4`, `CR1` ou `2.0.0` final, **pare e reporte** — pode mudar o comportamento de `auto-init`. Não faça o bump por conta própria. Se a mais nova for `2.0.0.Beta3`, siga.

- [ ] **Step 3: Escrever o teste que falha**

Criar `swapi-app/src/test/java/com/eldermoraes/mcp/McpForeignSessionTest.java`:

```java
package com.eldermoraes.mcp;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

/**
 * A sessao MCP vive na heap de uma instancia e o Vercel nao tem afinidade de
 * sessao: um Mcp-Session-Id emitido pela instancia A chega na instancia B, que
 * nao o conhece. Sem auto-init isso e 404 e o cliente stateful quebra.
 *
 * Estes testes usam rest-assured, nao McpAssured, de proposito: o McpAssured
 * gerencia a sessao e por isso nunca reproduz o bug.
 */
@QuarkusTest
class McpForeignSessionTest {

    private static final String ACCEPT = "application/json, text/event-stream";

    private static final String TOOLS_CALL = """
            {"jsonrpc":"2.0","id":1,"method":"tools/call",
             "params":{"name":"sw_get","arguments":{"resource":"PEOPLE","id":1}}}
            """;

    private static final String TOOLS_LIST = """
            {"jsonrpc":"2.0","id":2,"method":"tools/list"}
            """;

    @Test
    void sessionIdFromAnotherInstanceIsAccepted() {
        given()
                .contentType("application/json")
                .accept(ACCEPT)
                .header("Mcp-Session-Id", "session-issued-by-another-instance")
                .body(TOOLS_CALL)
        .when()
                .post("/mcp")
        .then()
                .statusCode(200)
                .body(containsString("Luke Skywalker"));
    }

    @Test
    void requestWithoutAnySessionIdIsAccepted() {
        given()
                .contentType("application/json")
                .accept(ACCEPT)
                .body(TOOLS_LIST)
        .when()
                .post("/mcp")
        .then()
                .statusCode(200)
                .body(containsString("sw_get"));
    }

    // auto-init nao pode suprimir o Mcp-Session-Id: um cliente stateful
    // bem-comportado precisa continuar recebendo sessao e negociando a versao
    // que pediu. Este teste tranca esse comportamento.
    @Test
    void wellBehavedStatefulClientStillGetsASessionAndTheVersionItAskedFor() {
        given()
                .contentType("application/json")
                .accept(ACCEPT)
                .body("""
                        {"jsonrpc":"2.0","id":3,"method":"initialize",
                         "params":{"protocolVersion":"2025-06-18","capabilities":{},
                                   "clientInfo":{"name":"well-behaved","version":"1.0"}}}
                        """)
        .when()
                .post("/mcp")
        .then()
                .statusCode(200)
                .header("Mcp-Session-Id", not(emptyOrNullString()))
                .body(containsString("2025-06-18"));
    }
}
```

Imports adicionais necessários para o último teste:

```java
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.not;
```

- [ ] **Step 4: Rodar e ver falhar**

Run: `cd swapi-app && ./mvnw test -Dtest=McpForeignSessionTest`
Expected: FAIL — HTTP 404, e o log do servidor traz
`WARN [...StreamableHttpMcpMessageHandler] Mcp session not found: session-issued-by-another-instance`.

⚠️ Se os testes **passarem** antes da mudança, alguma inicialização implícita já está ativa no profile de teste. Nesse caso acrescente `%test.quarkus.mcp.server.dev-mode.dummy-init=false` ao `application.properties`, rode de novo e confirme o FAIL antes de seguir. Um teste que passa antes da implementação não prova nada.

- [ ] **Step 5: Ligar o `auto-init`**

Em `swapi-app/src/main/resources/application.properties`, **substituir** o bloco de comentário atual do MCP (que afirma "stateless auto-detectado pela extensao 2.x") por:

```properties
# MCP server (Streamable HTTP em /mcp). Os dois paradigmas convivem no mesmo
# endpoint: cliente 2026-07-28 e stateless por spec; cliente anterior faz
# initialize e recebe um Mcp-Session-Id.
#
# auto-init: a sessao passa a ser descartavel por request, entao um
# Mcp-Session-Id emitido por OUTRA instancia e aceito em vez de 404. Sem isso o
# cliente stateful quebra em producao, porque a sessao vive na heap de uma
# instancia e o Vercel nao tem afinidade de sessao. Correcao recomendada pelo
# mantenedor da extensao (quarkiverse/quarkus-mcp-server#876). Custa uma sessao
# descartada por request — irrelevante aqui, porque as tools sao read-only puras
# e nao usam nada de estado de sessao.
# Ver docs/superpowers/specs/2026-08-03-mcp-dual-stateful-stateless-design.md
quarkus.mcp.server.http.streamable.auto-init=true

# Atras da Vercel o Origin nunca e localhost -> o check de DNS rebinding retornaria 403
quarkus.mcp.server.http.dns-rebinding-check.enabled=false
quarkus.mcp.server.server-info.name=swapi.build
```

- [ ] **Step 6: Rodar e ver passar**

Run: `cd swapi-app && ./mvnw test -Dtest=McpForeignSessionTest`
Expected: PASS (3 testes).

- [ ] **Step 7: Suíte completa**

Run: `cd swapi-app && ./mvnw test`
Expected: PASS. Confirme explicitamente que `SwapiStatelessTest` e `SwapiToolsTest` seguem verdes — eles provam que o cliente stateless e o handshake stateful bem-comportado não regrediram.

- [ ] **Step 8: Commit**

```bash
git add swapi-app/src/main/resources/application.properties \
        swapi-app/src/test/java/com/eldermoraes/mcp/McpForeignSessionTest.java
git commit -m "fix: accept MCP session ids issued by another instance

Sessions live in one instance's heap and Vercel has no session affinity,
so a stateful client's Mcp-Session-Id 404s whenever the request lands on
a different replica. Measured in production: 33-58% of concurrent calls
on one session failed. auto-init makes sessions throwaway per request."
```

---

### Task 2: Bordas do endpoint — `GET` → 405, `DELETE` → 204

**Files:**
- Modify: `swapi-app/pom.xml` (nova dependência)
- Create: `swapi-app/src/main/java/com/eldermoraes/mcp/McpTransportFilter.java`
- Create: `swapi-app/src/test/java/com/eldermoraes/mcp/McpTransportEdgesTest.java`

**Interfaces:**
- Consumes: Task 1 completa (`auto-init` ligado).
- Produces: classe `McpTransportFilter` no pacote `com.eldermoraes.mcp`, com um método `void filter(RoutingContext rc)` anotado `@RouteFilter(400)`. A Task 3 **estende esta mesma classe e este mesmo método** com os paths do transporte legado.

**Por quê:** com `auto-init` ligado, `GET /mcp` e `DELETE /mcp` com sessão estrangeira **continuam** respondendo 404 (medido). Pela spec `2025-03-26` o servidor pode responder `405` a um `GET` quando não oferece stream server→client, e todo cliente trata 405 como "sem stream, siga". Um 404 é legitimamente lido como "a sessão morreu" e pode derrubar a conexão de um cliente que só queria abrir o stream opcional. Não emitimos nenhuma mensagem server→client, então 405 é incondicionalmente correto.

- [ ] **Step 1: Adicionar a dependência**

Em `swapi-app/pom.xml`, junto das outras dependências `io.quarkus` (sem `<version>` — o BOM gerencia):

```xml
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-reactive-routes</artifactId>
</dependency>
```

- [ ] **Step 2: Escrever o teste que falha**

Criar `swapi-app/src/test/java/com/eldermoraes/mcp/McpTransportEdgesTest.java`:

```java
package com.eldermoraes.mcp;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

/**
 * Bordas do endpoint MCP nesta topologia.
 *
 * O auto-init cobre o POST, que e onde as tool calls acontecem, mas GET e
 * DELETE com sessao estrangeira continuam 404. Como nao emitimos nenhuma
 * mensagem server->client, o 405 da spec 2025-03-26 e a resposta correta e
 * incondicional para o GET, e o DELETE (teardown) e sempre bem-sucedido.
 */
@QuarkusTest
class McpTransportEdgesTest {

    @Test
    void getIsMethodNotAllowedBecauseThereIsNoServerToClientStream() {
        given()
                .accept("text/event-stream")
        .when()
                .get("/mcp")
        .then()
                .statusCode(405)
                .header("Allow", containsString("POST"));
    }

    @Test
    void getWithAForeignSessionIdIsAlsoMethodNotAllowed() {
        given()
                .accept("text/event-stream")
                .header("Mcp-Session-Id", "session-issued-by-another-instance")
        .when()
                .get("/mcp")
        .then()
                .statusCode(405);
    }

    @Test
    void deleteIsAlwaysSuccessfulTeardown() {
        given()
                .header("Mcp-Session-Id", "session-issued-by-another-instance")
        .when()
                .delete("/mcp")
        .then()
                .statusCode(204);
    }
}
```

- [ ] **Step 3: Rodar e ver falhar**

Run: `cd swapi-app && ./mvnw test -Dtest=McpTransportEdgesTest`
Expected: FAIL — `GET` devolve 404 (esperado 405) e `DELETE` devolve 404 (esperado 204).

- [ ] **Step 4: Implementar o filtro**

Criar `swapi-app/src/main/java/com/eldermoraes/mcp/McpTransportFilter.java`:

```java
package com.eldermoraes.mcp;

import io.quarkus.vertx.web.RouteFilter;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.RoutingContext;

/**
 * Bordas HTTP do endpoint MCP que a extensao nao cobre nesta topologia.
 *
 * A sessao MCP vive na heap de uma instancia e o Vercel nao tem afinidade de
 * sessao. O auto-init resolve o POST — que e onde as tool calls acontecem —
 * criando uma sessao descartavel quando o Mcp-Session-Id e desconhecido. GET e
 * DELETE continuariam respondendo 404, e um 404 num GET e legitimamente lido
 * pelo cliente como "a sessao morreu", derrubando a conexao inteira por causa
 * de um stream que era opcional.
 *
 * Como este servidor nao emite NENHUMA mensagem server->client (sem sampling,
 * elicitation, roots, progress, subscriptions), as respostas abaixo sao
 * incondicionalmente corretas — nao dependem de a sessao existir ou nao.
 */
public class McpTransportFilter {

    private static final String MCP_PATH = "/mcp";

    @RouteFilter(400)
    void filter(RoutingContext rc) {
        if (!MCP_PATH.equals(rc.normalizedPath())) {
            rc.next();
            return;
        }
        HttpMethod method = rc.request().method();
        if (HttpMethod.GET.equals(method)) {
            // 405 e o que a spec 2025-03-26 prescreve para "nao ofereco stream
            // server->client", e todo cliente trata como "siga em frente".
            rc.response().setStatusCode(405).putHeader("Allow", "POST").end();
        } else if (HttpMethod.DELETE.equals(method)) {
            // Teardown de sessao: nao ha sessao real a destruir, entao sempre ok.
            rc.response().setStatusCode(204).end();
        } else {
            rc.next();
        }
    }
}
```

Notas para quem implementa:

- `@RouteFilter(400)` — o valor é a prioridade; **maior roda primeiro**. Não chamar `rc.next()` é o que curto-circuita a cadeia.
- `OPTIONS` cai no `else` e segue para o filtro de CORS da Task 4, que é quem responde preflight. Não trate `OPTIONS` aqui.
- `normalizedPath()` e não `path()`: evita que `/mcp/` ou `/mcp/../mcp` escapem da checagem.

- [ ] **Step 5: Rodar e ver passar**

Run: `cd swapi-app && ./mvnw test -Dtest=McpTransportEdgesTest`
Expected: PASS (3 testes).

⚠️ **Se ainda falhar com 404**, o `@RouteFilter` não conseguiu curto-circuitar a rota da extensão. Não force. Troque o mecanismo por um observer de `Router`, que registra rotas concorrentes com ordem explícita, e **remova a dependência `quarkus-reactive-routes` do `pom.xml`** (ela deixa de ser necessária):

```java
package com.eldermoraes.mcp;

import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.event.Observes;

public class McpTransportFilter {

    void registerRoutes(@Observes Router router) {
        router.get("/mcp").order(-100).handler(McpTransportFilter::noServerStream);
        router.delete("/mcp").order(-100).handler(McpTransportFilter::teardown);
    }

    private static void noServerStream(RoutingContext rc) {
        rc.response().setStatusCode(405).putHeader("Allow", "POST").end();
    }

    private static void teardown(RoutingContext rc) {
        rc.response().setStatusCode(204).end();
    }
}
```

Se **nenhum** dos dois funcionar, pare e reporte: a spec prevê como fallback entregar só a config das Tasks 1 e 4, deixando as bordas como estão. Não invente um terceiro mecanismo.

- [ ] **Step 6: Suíte completa**

Run: `cd swapi-app && ./mvnw test`
Expected: PASS. Atenção especial ao `SwapiToolsTest` e `SwapiStatelessTest`: o McpAssured pode abrir o stream `GET` durante o handshake. Se algum deles quebrar por causa do 405, **pare e reporte** — significa que o cliente de teste depende do stream, e a decisão de responder 405 precisa ser revista com o usuário.

- [ ] **Step 7: Commit**

```bash
git add swapi-app/pom.xml \
        swapi-app/src/main/java/com/eldermoraes/mcp/McpTransportFilter.java \
        swapi-app/src/test/java/com/eldermoraes/mcp/McpTransportEdgesTest.java
git commit -m "fix: answer 405/204 on MCP GET and DELETE instead of 404

With auto-init on, GET and DELETE with a session id from another
instance still 404. This server emits no server-to-client messages, so
405 (spec 2025-03-26) is unconditionally right for GET, and DELETE
teardown always succeeds."
```

---

### Task 3: Rejeição explícita do transporte legado HTTP+SSE

**Files:**
- Modify: `swapi-app/src/main/java/com/eldermoraes/mcp/McpTransportFilter.java`
- Modify: `swapi-app/src/test/java/com/eldermoraes/mcp/McpTransportEdgesTest.java`

**Interfaces:**
- Consumes: `McpTransportFilter.filter(RoutingContext)` da Task 2.
- Produces: nenhuma interface nova. É o último comportamento do filtro.

**Por quê:** o transporte HTTP+SSE (`2024-11-05`) é irrecuperável nesta topologia — exige que o stream SSE aberto e os `POST` subsequentes em `/mcp/messages/<id>` caiam na mesma instância. Verificado em produção: o `GET /mcp/sse` anuncia `/mcp/messages/<id>` e o `POST` nesse endpoint responde 404. O endpoint hoje está exposto, faz o handshake e **morre em silêncio** — o pior comportamento possível. Verificado localmente que o mesmo fluxo funciona em instância única, o que confirma que o defeito é só topológico.

- [ ] **Step 1: Escrever o teste que falha**

Acrescentar a `McpTransportEdgesTest`:

```java
    @Test
    void legacySseTransportIsRejectedWithAPointerToStreamableHttp() {
        given()
                .accept("text/event-stream")
        .when()
                .get("/mcp/sse")
        .then()
                .statusCode(404)
                .body(containsString("/mcp"));
    }

    @Test
    void legacyMessageEndpointIsRejected() {
        given()
                .contentType("application/json")
                .body("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}")
        .when()
                .post("/mcp/messages/whatever-id")
        .then()
                .statusCode(404);
    }
```

- [ ] **Step 2: Rodar e ver falhar**

Run: `cd swapi-app && ./mvnw test -Dtest=McpTransportEdgesTest`
Expected: FAIL — o `GET /mcp/sse` responde 200 e abre um stream SSE, sem o corpo esperado.

- [ ] **Step 3: Estender o filtro**

Em `McpTransportFilter.java`, acrescentar **duas constantes novas** e o corpo da mensagem (`MCP_PATH` já existe da Task 2 — não redeclarar), e reescrever o método `filter`:

```java
    // NOVAS — MCP_PATH ja existe, vindo da Task 2
    private static final String LEGACY_SSE_PATH = "/mcp/sse";
    private static final String LEGACY_MESSAGES_PREFIX = "/mcp/messages/";

    // Transporte legado 2024-11-05: o stream SSE e os POSTs em /mcp/messages/<id>
    // precisam cair na mesma instancia, e nesta topologia isso nao acontece.
    // Rejeitar explicitamente e melhor que fazer o handshake e morrer em silencio.
    private static final String LEGACY_GONE = """
            {"error":"The legacy HTTP+SSE transport (spec 2024-11-05) is not \
            supported. Use the Streamable HTTP endpoint at /mcp."}""";

    @RouteFilter(400)
    void filter(RoutingContext rc) {
        String path = rc.normalizedPath();
        if (LEGACY_SSE_PATH.equals(path) || path.startsWith(LEGACY_MESSAGES_PREFIX)) {
            rc.response()
                    .setStatusCode(404)
                    .putHeader("Content-Type", "application/json")
                    .end(LEGACY_GONE);
            return;
        }
        if (!MCP_PATH.equals(path)) {
            rc.next();
            return;
        }
        HttpMethod method = rc.request().method();
        if (HttpMethod.GET.equals(method)) {
            rc.response().setStatusCode(405).putHeader("Allow", "POST").end();
        } else if (HttpMethod.DELETE.equals(method)) {
            rc.response().setStatusCode(204).end();
        } else {
            rc.next();
        }
    }
```

Atenção à ordem: o ramo do legado vem **antes** da checagem de `MCP_PATH`, porque `/mcp/sse` não é igual a `/mcp` e cairia no `rc.next()`.

Se a Task 2 caiu no fallback do observer de `Router`, acrescente em vez disso:

```java
        router.get("/mcp/sse").order(-100).handler(McpTransportFilter::legacyGone);
        router.post("/mcp/messages/*").order(-100).handler(McpTransportFilter::legacyGone);
```

com o handler devolvendo 404 e o mesmo corpo `LEGACY_GONE`.

- [ ] **Step 4: Rodar e ver passar**

Run: `cd swapi-app && ./mvnw test -Dtest=McpTransportEdgesTest`
Expected: PASS (5 testes).

- [ ] **Step 5: Suíte completa**

Run: `cd swapi-app && ./mvnw test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add swapi-app/src/main/java/com/eldermoraes/mcp/McpTransportFilter.java \
        swapi-app/src/test/java/com/eldermoraes/mcp/McpTransportEdgesTest.java
git commit -m "fix: reject the legacy HTTP+SSE MCP transport explicitly

The 2024-11-05 transport needs the SSE stream and the POSTs to
/mcp/messages/<id> on the same instance, which this topology cannot
provide. Verified in production: handshake succeeds, then the POST 404s.
Failing at connect beats dying silently mid-session."
```

---

### Task 4: CORS com `Vary: Origin` nas respostas cacheáveis

**Files:**
- Modify: `swapi-app/src/main/resources/application.properties`
- Modify: `swapi-app/src/main/java/com/eldermoraes/CacheControlFilter.java:38-45`
- Modify: `swapi-app/src/test/java/com/eldermoraes/CacheHeadersTest.java`
- Modify: `swapi-app/src/test/java/com/eldermoraes/mcp/McpTransportEdgesTest.java`

**Interfaces:**
- Consumes: `CacheControlFilter.filter(ContainerRequestContext, ContainerResponseContext)` existente, e a propriedade `swapi.cache-control.public`.
- Produces: nenhuma interface Java nova.

**Por quê:** sem CORS, nenhum cliente MCP de browser conecta — a extensão avisa no startup. O filtro CORS do Quarkus é global, então alcança `/api` e `/openapi.json`, que desde 2026-08-03 são cacheados na borda com `s-maxage=31536000`. E **medido**: o Quarkus **ecoa o `Origin` da request** no `Access-Control-Allow-Origin` (não emite `*` literal) e **não** emite `Vary: Origin`. Sem `Vary` — que a Vercel usa como parte da chave de cache — uma request sem `Origin` popula a entrada sem header CORS e o próximo cliente de browser é bloqueado; e uma request com `Origin: evil.example` congela esse valor na borda. Mesma classe de bug do `X-Forwarded-Host` que o `docs/DEPLOY.md` já sonda.

- [ ] **Step 1: Escrever os testes que falham**

Acrescentar a `swapi-app/src/test/java/com/eldermoraes/CacheHeadersTest.java`:

```java
    // O filtro CORS ecoa o Origin da request e nao emite Vary. Sem Vary a borda
    // serve a variante de um origin (ou a variante sem origin) para outro
    // cliente — CORS quebrado de forma intermitente, e ACAO de terceiro preso
    // na borda por um ano.
    @Test
    void cacheableResponseVariesByOrigin() {
        given()
                .header("Origin", "https://app.example")
        .when()
                .get("/api/people/1")
        .then()
                .statusCode(200)
                .header("Vary", containsString("Origin"));
    }

    @Test
    void cacheableNotFoundAlsoVariesByOrigin() {
        given()
        .when()
                .get("/api/people/9999")
        .then()
                .statusCode(404)
                .header("Vary", containsString("Origin"));
    }

    @Test
    void openApiSpecVariesByOrigin() {
        given()
        .when()
                .get("/openapi.json")
        .then()
                .statusCode(200)
                .header("Vary", containsString("Origin"));
    }

    // O Vary entra no MESMO if do Cache-Control: o ramo nao-cacheavel nao pode
    // ganhar Vary de carona, senao a decisao "random nao e cacheavel" fica
    // acoplada a decisao de CORS.
    @Test
    void nonCacheableRandomDoesNotGetVary() {
        given()
        .when()
                .get("/api/people/random")
        .then()
                .statusCode(200)
                .header("Vary", nullValue());
    }
```

Import adicional: `import static org.hamcrest.Matchers.nullValue;`

E acrescentar a `McpTransportEdgesTest`:

```java
    @Test
    void mcpAnswersCorsPreflightSoBrowserClientsCanConnect() {
        given()
                .header("Origin", "https://app.example")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "content-type,mcp-session-id")
        .when()
                .options("/mcp")
        .then()
                .statusCode(200)
                .header("Access-Control-Allow-Origin", containsString("app.example"));
    }
```

- [ ] **Step 2: Rodar e ver falhar**

Run: `cd swapi-app && ./mvnw test -Dtest=CacheHeadersTest+McpTransportEdgesTest`
Expected: FAIL — nenhum header `Vary` presente, e o preflight responde 405.

- [ ] **Step 3: Habilitar CORS e o `Vary` do `/openapi.json`**

Em `swapi-app/src/main/resources/application.properties`, **acrescentar** ao filtro `openapi` que já existe (não substituir a linha do `Cache-Control`):

```properties
quarkus.http.filter.openapi.header."Vary"=Origin
```

E acrescentar um bloco novo de CORS:

```properties
# Cliente MCP que roda em browser nao conecta sem isso (a extensao avisa no
# startup). O filtro e global, entao /api tambem passa a aceitar cross-origin —
# desejado: a API e publica, read-only, sem auth e sem cookies.
#
# ATENCAO: o Quarkus ECOA o Origin da request no Access-Control-Allow-Origin (nao
# um "*" literal) e nao emite Vary. Toda resposta cacheavel na borda precisa de
# Vary: Origin — ver o CacheControlFilter e o filtro openapi acima. Sem isso a
# borda serve o header de um origin para outro, e a variante sem Origin para
# clientes de browser, que ficam bloqueados.
quarkus.http.cors.enabled=true
quarkus.http.cors.origins=*
```

- [ ] **Step 4: Gravar `Vary: Origin` no `CacheControlFilter`**

Em `swapi-app/src/main/java/com/eldermoraes/CacheControlFilter.java`, o método `filter` passa a:

```java
    @Override
    public void filter(ContainerRequestContext request, ContainerResponseContext response) {
        // Nao sobrescreve um Cache-Control que o resource tenha setado de proposito.
        if (isCacheable(request, response)
                && !response.getHeaders().containsKey(HttpHeaders.CACHE_CONTROL)) {
            response.getHeaders().putSingle(HttpHeaders.CACHE_CONTROL, cacheControl);
            // O filtro CORS ecoa o Origin da request e nao emite Vary. Sem isto a
            // borda serviria o Access-Control-Allow-Origin de um origin para
            // outro — e a variante sem Origin para um cliente de browser.
            // add, nao putSingle: preserva um Vary que ja exista (ex.: Accept-Encoding).
            response.getHeaders().add(HttpHeaders.VARY, "Origin");
        }
    }
```

Nota: `add` e não `putSingle`. Vários headers `Vary` são equivalentes a uma lista separada por vírgula, e sobrescrever descartaria um `Vary` que outra camada tenha posto.

- [ ] **Step 5: Rodar e ver passar**

Run: `cd swapi-app && ./mvnw test -Dtest=CacheHeadersTest+McpTransportEdgesTest`
Expected: PASS.

- [ ] **Step 6: Conferir se `/assets/*` também precisa de `Vary`**

```bash
grep -n "quarkus.http.filter.assets" swapi-app/src/main/resources/application.properties
```

O filtro de assets marca `public, max-age=31536000, immutable` — `max-age` sem `s-maxage`. Rode o dev mode e confira se um asset devolve header CORS quando a request traz `Origin`:

```bash
cd swapi-app && ./mvnw quarkus:dev > /tmp/dev-assets.log 2>&1 &
# aguarde "Listening on: http://localhost:5432"
ASSET=$(curl -s http://localhost:5432/ | grep -o '/assets/[^"]*\.js' | head -1)
curl -sI -H "Origin: https://app.example" "http://localhost:5432$ASSET" | grep -iE 'access-control|vary|cache-control'
```

Se aparecer `access-control-allow-origin` **sem** `vary`, acrescente `quarkus.http.filter.assets.header."Vary"=Origin` e registre no commit. Se não aparecer header CORS, **não** acrescente nada e registre no commit que foi verificado e não é necessário. Encerre o dev mode.

- [ ] **Step 7: Suíte completa**

Run: `cd swapi-app && ./mvnw test`
Expected: PASS. O `CacheHeadersTest` existente afirma o valor exato do `Cache-Control`; confirme que ele segue verde — o `Vary` é header separado e não deve tê-lo alterado.

- [ ] **Step 8: Commit**

```bash
git add swapi-app/src/main/resources/application.properties \
        swapi-app/src/main/java/com/eldermoraes/CacheControlFilter.java \
        swapi-app/src/test/java/com/eldermoraes/CacheHeadersTest.java \
        swapi-app/src/test/java/com/eldermoraes/mcp/McpTransportEdgesTest.java
git commit -m "feat: enable CORS, with Vary: Origin on edge-cacheable responses

Browser-based MCP clients cannot connect without CORS. Quarkus echoes
the request Origin rather than emitting a literal '*' and adds no Vary,
so with s-maxage=31536000 on /api the edge would serve one origin's
Access-Control-Allow-Origin to another — and the no-Origin variant to
browser clients, blocking them."
```

---

### Task 5: Documentação alinhada ao comportamento real

**Não previsto na spec, descoberto ao escrever o plano:** o site público afirma em quatro lugares que o servidor é stateless-only e que não há session ids — inclusive na **política de privacidade**, que declara "There are no sessions and no server-side state tied to you or your agent". Isso é falso hoje (o servidor emite `Mcp-Session-Id`) e continua falso depois do `auto-init`. Uma afirmação inexata na política de privacidade é o tipo de coisa que precisa ser corrigida com cuidado, não de carona — mas também não pode ficar de fora.

**Files:**
- Modify: `CLAUDE.md` (bullet do MCP em "Non-negotiable facts")
- Modify: `docs/DEPLOY.md` (linha de troubleshooting; verificação pós-deploy)
- Modify: `README.md:51-53` (seção MCP Server)
- Modify: `swapi-app/src/main/webui/src/pages/mcp.ts:130-133` e `:180-182`
- Modify: `swapi-app/src/main/webui/src/pages/privacy.ts:36-37`
- Modify: `swapi-app/src/main/webui/src/pages/home.ts:15`

**Interfaces:**
- Consumes: Tasks 1–4 completas.
- Produces: nada de código. A Task 6 usa os probes adicionados ao `docs/DEPLOY.md`.

- [ ] **Step 1: Corrigir o fato não-negociável no `CLAUDE.md`**

Substituir o bullet atual:

```markdown
- **MCP server is stateless Streamable HTTP (spec 2026-07-28).** Never use legacy
  SSE or stateful patterns.
```

por:

```markdown
- **MCP serves stateful and stateless clients on the same `/mcp` endpoint.**
  Streamable HTTP only. `quarkus.mcp.server.http.streamable.auto-init=true` makes
  sessions throwaway per request, so a `Mcp-Session-Id` issued by another
  instance is accepted instead of 404ing — Vercel has no session affinity, and
  without this a stateful client fails intermittently. `GET /mcp` answers 405
  (no server→client stream) and `DELETE` answers 204. The legacy HTTP+SSE
  transport (`/mcp/sse`) is rejected on purpose. Never reintroduce
  session-affine state, and never depend on the legacy transport.
```

- [ ] **Step 2: Corrigir a linha de troubleshooting do `docs/DEPLOY.md`**

Substituir:

```markdown
| Transient 404 on first stateful MCP connect | Serverless cold start. Retry resolves it. |
```

por:

```markdown
| 404 `Mcp session not found` on a stateful MCP call | **Not a cold start.** Sessions live in one instance's heap and Vercel has no session affinity, so the call landed on a different replica. Fixed by `quarkus.mcp.server.http.streamable.auto-init=true`; if it reappears, that property is off in the running deployment. |
```

- [ ] **Step 3: Acrescentar os probes à verificação pós-deploy do `docs/DEPLOY.md`**

Acrescentar depois do probe MCP stateless que já existe:

```markdown
**MCP stateful** — the probe that catches the instance-affinity bug. Twelve
concurrent calls on one session must all return 200; before `auto-init` this
returned 33–58% `404`:

```bash
SID=$(curl -s -D - -o /dev/null -X POST https://swapi.build/mcp \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"probe","version":"1.0"}}}' \
  | tr -d '\r' | awk -F': ' '/^mcp-session-id/{print $2}')
for i in $(seq 1 12); do
  ( curl -s -o /dev/null -w '%{http_code}\n' -X POST https://swapi.build/mcp \
      -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
      -H "Mcp-Session-Id: $SID" \
      -d '{"jsonrpc":"2.0","id":'$i',"method":"tools/list"}' ) &
done | sort | uniq -c
# esperado: 12 200
```

Run this against the **preview**, not production: bursts of concurrent requests
are what trip the Vercel IP mitigation documented below.

**MCP foreign session** — a session id that never existed must still be served:

```bash
curl -s -o /dev/null -w 'foreign session: %{http_code}\n' -X POST https://swapi.build/mcp \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -H 'Mcp-Session-Id: never-existed' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
# esperado: 200
```

**MCP edges:**

```bash
curl -s -o /dev/null -w 'GET /mcp: %{http_code}\n' https://swapi.build/mcp        # 405
curl -s -o /dev/null -w 'GET /mcp/sse: %{http_code}\n' https://swapi.build/mcp/sse # 404
```

**Cache poisoning via `Origin`** — CORS echoes the request `Origin`, so every
edge-cacheable response must carry `Vary: Origin` or the edge serves one
origin's header to another:

```bash
curl -sI -H 'Origin: https://evil.example' https://swapi.build/api/people/1 | grep -i '^vary'
# deve conter Origin
curl -s -o /dev/null -w '[%header{access-control-allow-origin}]\n' https://swapi.build/api/people/1
# sem Origin na request: deve vir []
```

If `Vary` is missing, purge the cache before going further.
```

- [ ] **Step 4: Atualizar a seção MCP do `README.md`**

Em `README.md:51-53`, substituir exatamente:

```markdown
swapi.build is also a remote [MCP](https://modelcontextprotocol.io) server — built on the
**stateless MCP spec (2026-07-28)**: every request is self-contained, with no `initialize`
handshake and no session ids. First-party, read-only, no authentication:
```

por:

```markdown
swapi.build is also a remote [MCP](https://modelcontextprotocol.io) server over
**Streamable HTTP**. Any Streamable HTTP client works: the stateless `2026-07-28`
revision sends self-contained requests, and earlier revisions negotiate a session
through `initialize` — both are served on the same endpoint. The legacy HTTP+SSE
transport (`2024-11-05`) is not supported. First-party, read-only, no authentication:
```

Mantenha o restante da seção (endpoint, link para `/docs/mcp`, tabela de tools) como está.

- [ ] **Step 5: Corrigir o callout da página `/docs/mcp`**

Em `swapi-app/src/main/webui/src/pages/mcp.ts:130-133`, substituir exatamente:

```html
        <strong>Built on the stateless MCP spec (2026-07-28).</strong>
        Every request is self-contained — no <code>initialize</code> handshake, no session ids,
        nothing to keep alive between calls. That makes it a natural fit for serverless clients
        and for live demos that must never break. First-party, read-only, no authentication.
```

por:

```html
        <strong>Streamable HTTP — both paradigms on one endpoint.</strong>
        Clients on the stateless spec (2026-07-28) send self-contained requests: no
        <code>initialize</code> handshake, no session ids, nothing to keep alive between calls.
        Clients on earlier revisions negotiate a session and work just as well, because sessions
        here are throwaway — nothing is pinned to a single server instance. First-party,
        read-only, no authentication.
```

- [ ] **Step 6: Corrigir o troubleshooting da página `/docs/mcp`**

Em `swapi-app/src/main/webui/src/pages/mcp.ts:180-182`, substituir exatamente:

```html
      <p>The server scales to zero when idle. If the very first connection attempt fails or times out,
      retry once — the native binary starts in tens of milliseconds (the platform may take a bit longer
      to provision the container) and stateless requests are immune after that.</p>
```

por:

```html
      <p>The server scales to zero when idle. The native binary starts in tens of milliseconds, but
      provisioning the container takes several seconds, so the very first call after an idle period is
      slow — retry once if it times out. Subsequent calls are fast, whether your client is stateless
      or session-based.</p>
```

(O texto antigo dizia que "stateless requests are immune", o que sugeria que clientes com sessão não eram. A causa real da falha era afinidade de instância, não o modo do cliente.)

- [ ] **Step 7: Corrigir a política de privacidade**

Em `swapi-app/src/main/webui/src/pages/privacy.ts:36-37`, substituir exatamente:

```html
          The MCP endpoint at /mcp is stateless: each request is processed and answered, and its content
          is not stored. There are no sessions and no server-side state tied to you or your agent.
```

por:

```html
          The MCP endpoint at /mcp stores nothing about you or your agent, and request contents are
          not stored. Clients on the stateless MCP revision (2026-07-28) send fully self-contained
          requests. Clients on earlier revisions are issued a session id; that session exists only in
          memory, holds just the protocol version and the client name your own client sent, is never
          written to disk, and is dropped once idle.
```

Esta redação é a que fica **verdadeira depois** da Task 1. Não a aplique antes das Tasks 1–4 estarem commitadas.

- [ ] **Step 8: Corrigir o callout da home**

Em `swapi-app/src/main/webui/src/pages/home.ts:15`, substituir exatamente:

```html
        Now also a remote MCP server — stateless spec 2026-07-28. Point your AI agent at it &rarr;
```

por:

```html
        Now also a remote MCP server — Streamable HTTP, any client. Point your AI agent at it &rarr;
```

- [ ] **Step 9: Suíte completa**

Run: `cd swapi-app && ./mvnw test`
Expected: PASS (nada de código Java mudou; é a confirmação de que a árvore está limpa antes do commit).

- [ ] **Step 10: Commit**

```bash
git add CLAUDE.md docs/DEPLOY.md README.md swapi-app/src/main/webui/src/pages/
git commit -m "docs: describe what the MCP endpoint actually serves

The non-negotiable fact said stateless-only and 'never stateful'; the
extension never implemented stateless as an exclusive mode, so the
server has been serving stateful clients all along — badly. Also fixes
the troubleshooting row that blamed cold start for the 404s, and adds
the concurrent-session, edge, and Origin-poisoning probes.

The public site claimed stateless-only in four places, including the
privacy policy's 'there are no sessions' — inaccurate then and now, since
the server does issue session ids. Reworded to what is actually true."
```

---

### Task 6: Timeout, build nativo, deploy e verificação

**Files:**
- Modify: `swapi-app/pom.xml` (version), `swapi-app/src/main/resources/application.properties` (`quarkus.container-image.tag`)

**Interfaces:**
- Consumes: Tasks 1–5 completas, suíte verde.
- Produces: `https://swapi.build/mcp` atendendo os dois paradigmas em produção.

- [ ] **Step 1: Subir o `functionDefaultTimeout` (aplica sem deploy)**

O cache de borda baixou o teto para 15s; o cold start medido é ~10,9s, margem de ~4s. `/mcp` é POST e nunca é servido da borda, então é justamente a rota que absorve os cold starts que o cache cria nas outras. Subir para 60s:

```bash
cd /Users/eldermoraes/git/eldermoraes/swapi.build
set -a; source .env; set +a
curl -s -X PATCH "https://api.vercel.com/v9/projects/swapi-build?teamId=$VERCEL_TEAM_ID" \
  -H "Authorization: Bearer $VERCEL_API_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"resourceConfig":{"fluid":true,"functionDefaultRegions":["iad1"],"functionDefaultTimeout":60}}' \
  | python3 -c "import sys,json; print(json.load(sys.stdin).get('resourceConfig'))"
```

Expected: `functionDefaultTimeout: 60`. **Nunca imprimir o token.** Reversível na hora pelo mesmo `PATCH` com `15`.

- [ ] **Step 2: Bump de versão**

`swapi-app/pom.xml`: `<version>2.1.0</version>`.
`swapi-app/src/main/resources/application.properties`: `quarkus.container-image.tag=2.1.0`.

(Versão atual é `2.0.2`; a mudança de comportamento do endpoint justifica minor, não patch.)

- [ ] **Step 3: Commit do bump**

```bash
git add swapi-app/pom.xml swapi-app/src/main/resources/application.properties
git commit -m "chore: bump to 2.1.0 (MCP serves stateful and stateless)"
```

- [ ] **Step 4: Build nativo local**

```bash
export PATH=/opt/podman/bin:$PATH
cd swapi-app && podman build -f Dockerfile.vercel -t swapi-mcp-dual . 2>&1 | tail -20
```

Expected: termina sem erro (10–25 min). Warnings de `sun.misc.Unsafe` no Mandrel jdk-25 são cosméticos. A máquina podman precisa de 8 GB. Se faltar memória, baixe `NATIVE_XMX` para `4g`.

Este é o passo que valida a nova dependência `quarkus-reactive-routes` (e o Jackson que ela traz) no binário nativo. Registre o tamanho do binário para comparar com a decisão de aceitar a dependência:

```bash
podman run --rm --entrypoint ls swapi-mcp-dual -la /work/application
```

- [ ] **Step 5: Smoke no container**

```bash
podman run -d --rm --name swapi-dual-test -p 5432:5432 swapi-mcp-dual
# aguarde ~2s
curl -s -o /dev/null -w 'api: %{http_code}\n' http://localhost:5432/api/people/1
curl -s -o /dev/null -w 'GET /mcp: %{http_code}\n' http://localhost:5432/mcp
curl -s -o /dev/null -w 'GET /mcp/sse: %{http_code}\n' http://localhost:5432/mcp/sse
curl -s -X POST http://localhost:5432/mcp -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -H 'Mcp-Session-Id: foreign-session' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"sw_get","arguments":{"resource":"PEOPLE","id":1}}}' \
  | head -c 120
podman stop swapi-dual-test
```

Expected: `api: 200`, `GET /mcp: 405`, `GET /mcp/sse: 404`, e a tool call com sessão estrangeira devolvendo Luke Skywalker.

- [ ] **Step 6: Deploy preview e verificação**

Seguir `docs/DEPLOY.md` passos 1 e 2 exatamente, **de `swapi-app/`**. Rodar todos os probes do passo 2, incluindo os novos da Task 5: stateful concorrente (12× 200), bordas, e envenenamento por `Origin`. Conferir também `x-vercel-cache` indo de `MISS` a `HIT` — com `Vary`, a segunda request precisa repetir o mesmo `Origin` (ou a ausência dele).

Se o probe stateful concorrente ainda mostrar qualquer 404, **pare e reporte**: `auto-init` não está ativo no deployment.

- [ ] **Step 7: Deploy de produção e verificação**

Seguir `docs/DEPLOY.md` passos 3 e 4. Rodar os probes contra `https://swapi.build`, com uma exceção: **a rajada concorrente de 12 requests, não** — ela vai contra o preview, para não provocar a mitigação de IP.

Fechar com o cliente real:

```bash
claude mcp add --transport http swapi-build https://swapi.build/mcp
# listar tools, chamar uma
claude mcp remove swapi-build
```

- [ ] **Step 8: Medir a latência e o cold start**

```bash
# quente
curl -s -o /dev/null -w 'warm tools/call: %{time_total}s\n' -X POST https://swapi.build/mcp \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -H 'Mcp-Method: tools/call' -H 'Mcp-Name: sw_get' -H 'MCP-Protocol-Version: 2026-07-28' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"sw_get","arguments":{"resource":"PEOPLE","id":1},"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientInfo":{"name":"probe","version":"1.0"},"io.modelcontextprotocol/clientCapabilities":{}}}}'
```

Registrar o número. A spec lista como risco não medido o custo da sessão descartável por request; este é o dado que o fecha. Se a latência quente subir de forma perceptível em relação ao histórico, reportar.

- [ ] **Step 9: Merge**

Perguntar ao usuário: merge local, PR, ou manter a branch. Depois do merge, rodar a suíte de novo no resultado merjado (`cd swapi-app && ./mvnw test`) antes de qualquer push.

---

## Riscos herdados da spec

1. **O `@RouteFilter` é a única peça não validada.** Tasks 1, 4 são config e mudança em filtro existente, ambas medidas. A Task 2 Step 5 traz o mecanismo alternativo e o critério de parada.
2. **`auto-init` é oficialmente um workaround**, com deprecação prometida para quando o modo stateless for codificado. É a ponte para clientes pre-`2026-07-28`: o dia em que sair é o dia em que não precisamos dele.
3. **Extensão em beta.** `2.0.0.Beta3` é a mais nova; Task 1 Step 2 força a checagem.
4. **Custo por request** da sessão descartável — Task 6 Step 8 mede.
5. **Fragmentação de cache por `Origin`** — consequência aceita do `Vary`; Task 6 Step 6 confere a taxa de `HIT`.
6. **`/assets/*` sob CORS** — Task 4 Step 6 verifica em vez de supor.
7. **`quarkus-reactive-routes` traz `quarkus-jackson`** num projeto JSON-B — Task 6 Step 4 mede o tamanho do binário nativo.
