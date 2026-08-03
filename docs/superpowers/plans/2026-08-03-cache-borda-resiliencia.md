# Cache de borda e resiliência da API — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fazer a Vercel CDN servir as respostas determinísticas da API a partir da borda, e baixar o teto de duração da função de 300s para 15s.

**Architecture:** Um `ContainerResponseFilter` JAX-RS novo grava `Cache-Control` nas respostas cacheáveis de `/api` (GET/HEAD, status 200/404, exceto `/random`). A rota `/openapi.json` é servida pela extensão smallrye-openapi, fora do `@ApplicationPath("/api")`, e não passa por filtro JAX-RS — recebe o mesmo header via `quarkus.http.filter`, no padrão do filtro de assets que já existe. O timeout é setting de projeto na Vercel, sem deploy. Spec: `docs/superpowers/specs/2026-08-03-cache-borda-resiliencia-design.md`.

**Tech Stack:** Quarkus 3 / Java 25, JAX-RS (`jakarta.ws.rs`), REST Assured + JUnit 5, Vercel CDN.

## Processo de execução (estabelecido pelo Elder)

A implementação é executada por **subagente(s) Opus** (Agent tool, `model: opus`), um por task, enquanto a sessão principal monitora, revisa diffs, roda a suíte e valida cada task antes da próxima.

## Global Constraints

- Trabalhar em branch: `feature/cache-borda-resiliencia` (nunca na `main`).
- Testes: `cd swapi-app && ./mvnw test` (porta de teste 8081). Nunca `mvn clean` com dev mode rodando.
- Rodar a suíte **completa** antes de todo commit.
- Header definido **uma única vez**, na propriedade `swapi.cache-control.public` do
  `application.properties`, com o valor exato:
  `public, max-age=300, s-maxage=31536000, stale-while-revalidate=86400`
  O filtro JAX-RS a injeta com `@ConfigProperty`; o filtro HTTP a referencia com
  `${swapi.cache-control.public}`. Nenhum literal do header em código Java.
- Os seis endpoints `/random` (`people`, `films`, `planets`, `species`, `starships`, `vehicles`) **nunca** recebem `s-maxage`.
- Nenhuma mudança de comportamento dos endpoints: status, corpo e URLs embutidas ficam idênticos. Só entra header novo.
- **Nenhum domínio hardcoded** (invariante do projeto — o base URL é por request).
- Comentários e mensagens de commit em português; nomes de código e conteúdo de API em inglês.
- Todos os caminhos abaixo são relativos a `swapi-app/`, exceto quando indicado.

---

### Task 1: `CacheControlFilter` — cache de borda em `/api`

**Files:**
- Create: `src/main/java/com/eldermoraes/CacheControlFilter.java`
- Modify: `src/main/resources/application.properties`
- Test: `src/test/java/com/eldermoraes/CacheHeadersTest.java` (create)

**Interfaces:**
- Consumes: nada.
- Produces: a propriedade de config `swapi.cache-control.public` (definição única do header) e `com.eldermoraes.CacheControlFilter`, que a injeta com `@ConfigProperty`. A Task 2 referencia a **mesma** propriedade com `${swapi.cache-control.public}` e estende `CacheHeadersTest`.

- [ ] **Step 1: Criar a branch**

```bash
cd /Users/eldermoraes/git/eldermoraes/swapi.build && git checkout -b feature/cache-borda-resiliencia
```

- [ ] **Step 2: Escrever os testes que falham**

Criar `src/test/java/com/eldermoraes/CacheHeadersTest.java`:

```java
package com.eldermoraes;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

@QuarkusTest
class CacheHeadersTest {

    private static final String EDGE_TTL = "s-maxage=31536000";
    private static final String BROWSER_TTL = "max-age=300";
    private static final String STALE = "stale-while-revalidate=86400";

    // Dado estatico invalidado por deploy: a borda pode guardar por muito tempo.
    @Test
    void successfulResourceIsCacheableAtTheEdge() {
        given()
        .when()
                .get("/api/people/1")
        .then()
                .statusCode(200)
                .header("Cache-Control", containsString(EDGE_TTL))
                .header("Cache-Control", containsString(BROWSER_TTL))
                .header("Cache-Control", containsString(STALE));
    }

    // Id inexistente so passa a existir num deploy novo, que ja invalida o cache.
    // Cachear 404 e o que absorve varredura de ids.
    @Test
    void notFoundIsCacheableAtTheEdge() {
        given()
        .when()
                .get("/api/people/9999")
        .then()
                .statusCode(404)
                .header("Cache-Control", containsString(EDGE_TTL));
    }

    // Cachear /random faria a borda devolver sempre o mesmo sorteio.
    @Test
    void everyRandomEndpointStaysUncached() {
        List<String> resources =
                List.of("people", "films", "planets", "species", "starships", "vehicles");

        for (String resource : resources) {
            String path = "/api/" + resource + "/random";
            String cacheControl = given()
                    .when()
                            .get(path)
                    .then()
                            .statusCode(200)
                            .extract().header("Cache-Control");

            Assertions.assertTrue(
                    cacheControl == null || !cacheControl.contains("s-maxage"),
                    path + " nao pode ser cacheado na borda, mas veio: " + cacheControl);
        }
    }

    // A raiz da API tambem e estatica.
    @Test
    void apiRootIsCacheableAtTheEdge() {
        given()
        .when()
                .get("/api")
        .then()
                .statusCode(200)
                .header("Cache-Control", containsString(EDGE_TTL));
    }
}
```

- [ ] **Step 3: Rodar os testes e confirmar que falham**

Run: `cd swapi-app && ./mvnw test -Dtest=CacheHeadersTest`

Expected: FAIL. `successfulResourceIsCacheableAtTheEdge`, `notFoundIsCacheableAtTheEdge` e `apiRootIsCacheableAtTheEdge` falham porque nenhum header `Cache-Control` é devolvido (a Vercel é que preenche o default em produção, não a app). `everyRandomEndpointStaysUncached` **passa** desde já — é o teste que protege contra regressão na Task seguinte.

- [ ] **Step 4: Definir o header como propriedade de config**

Em `src/main/resources/application.properties`, logo abaixo do bloco do filtro de assets
(que termina em `quarkus.http.filter.assets.header."Cache-Control"=...`), acrescentar:

```properties
# Definicao UNICA do Cache-Control das respostas cacheaveis na borda.
# O CacheControlFilter (JAX-RS, cobre /api) injeta esta propriedade com
# @ConfigProperty; o filtro HTTP que cobre /openapi.json a referencia com
# ${swapi.cache-control.public}. Nao duplicar o valor em lugar nenhum.
swapi.cache-control.public=public, max-age=300, s-maxage=31536000, stale-while-revalidate=86400
```

A vírgula no valor é segura aqui: o filtro de assets logo acima já usa um valor com
vírgulas (`public, max-age=31536000, immutable`) e funciona em produção.

- [ ] **Step 5: Implementar o filtro**

Criar `src/main/java/com/eldermoraes/CacheControlFilter.java`:

```java
package com.eldermoraes;

import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.PathSegment;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;

/**
 * Marca como cacheavel na borda tudo que e deterministico em /api.
 *
 * Os dados sao JSONs estaticos embutidos no binario, entao a resposta so muda
 * num deploy novo — e a chave de cache da Vercel inclui a deployment URL, o que
 * invalida a entrada automaticamente. Por isso o TTL da borda e alto e o do
 * browser e curto: o cache do browser NAO e invalidado por deploy.
 */
@Provider
public class CacheControlFilter implements ContainerResponseFilter {

    private static final String RANDOM = "random";

    // Definicao unica em application.properties, compartilhada com o filtro HTTP
    // que cobre /openapi.json. Package-private: o padrao Quarkus para injecao
    // de campo sem reflexao.
    @ConfigProperty(name = "swapi.cache-control.public")
    String cacheControl;

    @Override
    public void filter(ContainerRequestContext request, ContainerResponseContext response) {
        if (isCacheable(request, response)) {
            response.getHeaders().putSingle(HttpHeaders.CACHE_CONTROL, cacheControl);
        }
    }

    private boolean isCacheable(ContainerRequestContext request, ContainerResponseContext response) {
        if (!HttpMethod.GET.equals(request.getMethod())
                && !HttpMethod.HEAD.equals(request.getMethod())) {
            return false;
        }
        // A borda so cacheia 200/404 (5xx nunca) — nao adianta marcar o resto.
        if (response.getStatus() != 200 && response.getStatus() != 404) {
            return false;
        }
        return !isRandom(request);
    }

    private boolean isRandom(ContainerRequestContext request) {
        List<PathSegment> segments = request.getUriInfo().getPathSegments();
        return !segments.isEmpty()
                && RANDOM.equals(segments.get(segments.size() - 1).getPath());
    }
}
```

- [ ] **Step 6: Rodar os testes do arquivo e confirmar que passam**

Run: `cd swapi-app && ./mvnw test -Dtest=CacheHeadersTest`
Expected: PASS — 4 testes.

Se falhar com erro de injeção (`@ConfigProperty` não resolvida), confirme que o campo
`cacheControl` é package-private — no Quarkus, campo `private` exige `@Inject` explícito.

- [ ] **Step 7: Rodar a suíte completa**

Run: `cd swapi-app && ./mvnw test`
Expected: PASS — todos os testes, incluindo os 13 arquivos que já existiam. Nenhum deles afirma nada sobre `Cache-Control`, então nada deve quebrar. Se algum quebrar, **parar e investigar** antes de commitar.

- [ ] **Step 8: Commit**

```bash
cd /Users/eldermoraes/git/eldermoraes/swapi.build
git add swapi-app/src/main/java/com/eldermoraes/CacheControlFilter.java \
        swapi-app/src/main/resources/application.properties \
        swapi-app/src/test/java/com/eldermoraes/CacheHeadersTest.java
git commit -m "feat: cache de borda nas respostas determinísticas de /api

Dados são JSONs estáticos embutidos no binário e a chave de cache da Vercel
inclui a deployment URL, então a entrada é invalidada a cada deploy. TTL da
borda alto, TTL do browser curto (cache do browser não é invalidado por deploy).

Os seis endpoints /random ficam de fora: cachear faria a borda devolver
sempre o mesmo sorteio.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: `/openapi.json` cacheável

**Files:**
- Modify: `src/main/resources/application.properties`
- Test: `src/test/java/com/eldermoraes/CacheHeadersTest.java:modify` (adicionar um teste)

**Interfaces:**
- Consumes: a propriedade `swapi.cache-control.public`, definida na Task 1 no `application.properties`. Esta task a **referencia**, não a redefine.
- Produces: `GET /openapi.json` devolvendo o header. Nada depende disso adiante.

- [ ] **Step 1: Escrever o teste que falha**

Adicionar a `src/test/java/com/eldermoraes/CacheHeadersTest.java`, dentro da classe:

```java
    // A spec e o contrato canonico e so muda em deploy. A pagina /docs busca
    // esse arquivo a cada visita, entao ele e um dos paths mais requisitados.
    @Test
    void openApiSpecIsCacheableAtTheEdge() {
        given()
                .accept("*/*")
        .when()
                .get("/openapi.json")
        .then()
                .statusCode(200)
                .header("Cache-Control", containsString(EDGE_TTL));
    }
```

- [ ] **Step 2: Rodar o teste e confirmar que falha**

Run: `cd swapi-app && ./mvnw test -Dtest=CacheHeadersTest#openApiSpecIsCacheableAtTheEdge`
Expected: FAIL — a rota não passa por filtro JAX-RS, então o `CacheControlFilter` da Task 1 não a alcança. É exatamente por isso que esta task existe.

- [ ] **Step 3: Adicionar o filtro HTTP**

Em `src/main/resources/application.properties`, logo abaixo da propriedade
`swapi.cache-control.public` criada na Task 1, acrescentar:

```properties
# A spec OpenAPI so muda em deploy -> cacheavel na borda como os dados da API.
# Rota servida pela extensao smallrye-openapi, fora do @ApplicationPath("/api"):
# nao passa por ContainerResponseFilter, entao o header vem do filtro HTTP.
# Referencia a MESMA propriedade que o CacheControlFilter injeta — nao duplicar o valor.
quarkus.http.filter.openapi.matches=/openapi\\.json
quarkus.http.filter.openapi.header."Cache-Control"=${swapi.cache-control.public}
```

Dois pontos de atenção:

- `\\.`: `matches` é regex e, em `.properties`, a contrabarra precisa ser escapada — o valor que chega à regex é `/openapi\.json`.
- `${swapi.cache-control.public}` é expansão de propriedade do MicroProfile Config. Se o teste do Step 4 falhar mostrando o header com o texto `${...}` literal em vez do valor expandido, **pare e reporte** — não resolva duplicando o literal, que é justamente o que esta task existe para evitar.

- [ ] **Step 4: Rodar o teste e confirmar que passa**

Run: `cd swapi-app && ./mvnw test -Dtest=CacheHeadersTest`
Expected: PASS — 5 testes.

- [ ] **Step 5: Rodar a suíte completa**

Run: `cd swapi-app && ./mvnw test`
Expected: PASS. Atenção especial a `OpenApiSpecTest` e `OpenApiContractTest`, que batem na mesma rota — devem continuar verdes.

- [ ] **Step 6: Commit**

```bash
cd /Users/eldermoraes/git/eldermoraes/swapi.build
git add swapi-app/src/main/resources/application.properties \
        swapi-app/src/test/java/com/eldermoraes/CacheHeadersTest.java
git commit -m "feat: /openapi.json cacheável na borda

A rota é servida pela extensão smallrye-openapi, fora do @ApplicationPath,
e não passa por ContainerResponseFilter — daí o filtro HTTP, no mesmo padrão
do filtro de assets do Vite.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: Teto de duração da função: 300s → 15s

**Files:** nenhum. É setting de projeto na Vercel — não entra no build nem no repositório.

**Interfaces:**
- Consumes: `.env` na raiz do repo com `VERCEL_API_TOKEN` e `VERCEL_TEAM_ID` (gitignored).
- Produces: nada em código.

Não há teste automatizado: o valor vive na plataforma, não no repositório. A verificação é a leitura do estado depois da escrita.

- [ ] **Step 1: Registrar o estado atual (para rollback)**

```bash
cd /Users/eldermoraes/git/eldermoraes/swapi.build
set -a; source .env; set +a
curl -sS "https://api.vercel.com/v9/projects/swapi-build?teamId=$VERCEL_TEAM_ID" \
  -H "Authorization: Bearer $VERCEL_API_TOKEN" \
  | python3 -c "import json,sys; p=json.load(sys.stdin); print('resourceConfig =', json.dumps(p.get('resourceConfig')))"
```

Expected: `{"fluid": true, "functionDefaultRegions": ["iad1"]}` — sem `functionDefaultTimeout`, porque hoje o projeto herda o default de 300s do plano Hobby. **Anotar essa saída**: o rollback é voltar exatamente a ela.

- [ ] **Step 2: Aplicar o novo teto**

```bash
cd /Users/eldermoraes/git/eldermoraes/swapi.build
set -a; source .env; set +a
curl -sS -X PATCH "https://api.vercel.com/v9/projects/swapi-build?teamId=$VERCEL_TEAM_ID" \
  -H "Authorization: Bearer $VERCEL_API_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"resourceConfig":{"fluid":true,"functionDefaultRegions":["iad1"],"functionDefaultTimeout":15}}' \
  | python3 -c "import json,sys; p=json.load(sys.stdin); print(json.dumps(p.get('error') or p.get('resourceConfig')))"
```

Expected: `{"fluid": true, "functionDefaultRegions": ["iad1"], "functionDefaultTimeout": 15}`.

Se vier `error`: **não insistir**. A alternativa é o dashboard — Settings → Functions → Function Max Duration → Default Max Duration → 15 → Save. Registrar no relatório da task qual dos dois caminhos funcionou.

- [ ] **Step 3: Confirmar que a API segue no ar**

```bash
for i in 1 2 3; do
  curl -sS -o /dev/null -w "tentativa $i: %{http_code} em %{time_total}s\n" https://swapi.build/api/people/1
done
```

Expected: 200 nas três. Se qualquer uma falhar ou passar de 15s, reverter imediatamente com o PATCH do Step 2 sem o campo `functionDefaultTimeout` e reportar.

- [ ] **Step 4: Sem commit**

Esta task não altera arquivo nenhum. Nada a commitar.

---

### Task 4: Merge, deploy e verificação pós-deploy

**Files:** nenhum arquivo novo. Segue `docs/DEPLOY.md` à risca.

**Interfaces:**
- Consumes: branch `feature/cache-borda-resiliencia` com as Tasks 1 e 2 commitadas e a suíte verde.
- Produces: cache de borda ativo em produção.

- [ ] **Step 1: Suíte completa na branch**

Run: `cd swapi-app && ./mvnw test`
Expected: PASS, tudo verde.

- [ ] **Step 2: Perguntar ao Elder como integrar**

Pelo ciclo do `CLAUDE.md`, a forma de integração é decisão dele: merge local, PR, ou manter a branch. **Não decidir sozinho.** Depois do merge, rodar a suíte de novo no resultado merged.

- [ ] **Step 3: Deploy de preview**

```bash
cd /Users/eldermoraes/git/eldermoraes/swapi.build/swapi-app
set -a; source ../.env; set +a
npx vercel deploy --token "$VERCEL_API_TOKEN" --scope algorium
```

Build nativo: 10–25 min. Anotar a URL de `Preview`.

- [ ] **Step 4: Verificar o cache no preview**

URLs `*.vercel.app` são SSO-protegidas: criar link de bypass e usar cookie jar, conforme `docs/DEPLOY.md` passo 2.

```bash
curl -sI -b jar.txt "https://<preview-host>/api/people/1" | grep -i 'cache-control\|x-vercel-cache'
# esperado: cache-control com s-maxage=31536000 e x-vercel-cache: MISS

curl -sI -b jar.txt "https://<preview-host>/api/people/1" | grep -i 'x-vercel-cache'
# esperado: x-vercel-cache: HIT   <- a prova de que a funcao nao foi invocada

curl -sI -b jar.txt "https://<preview-host>/api/people/random" | grep -i 'x-vercel-cache'
curl -sI -b jar.txt "https://<preview-host>/api/people/random" | grep -i 'x-vercel-cache'
# esperado: MISS nas duas

curl -sI -b jar.txt "https://<preview-host>/openapi.json" | grep -i 'cache-control'
# esperado: s-maxage=31536000
```

**Probe adversarial de envenenamento de cache** (recomendado pelo review final). O corpo
das respostas embute URLs absolutas montadas a partir do host descoberto por request, e o
`X-Forwarded-Host` **não** faz parte da chave de cache da Vercel. Se a plataforma repassasse
o header mandado pelo cliente, uma requisição gravaria no cache do host legítimo um corpo
apontando para outro domínio — preso lá até o próximo deploy.

Verificado em produção em 03/08/2026 contra a borda real: a Vercel **sobrescreve** o header
(spoof simples, `Forwarded` RFC 7239 e header duplicado foram todos ignorados; com `Host`
spoofado ela nem roteia). O probe fica aqui como verificação de regressão:

```bash
curl -s -b jar.txt -H 'X-Forwarded-Host: evil.example' "https://<preview-host>/api/people/1" | grep -c evil.example
# esperado: 0
curl -s -b jar.txt "https://<preview-host>/api/people/1" | grep -c evil.example
# esperado: 0 (a entrada de cache do host legitimo nao foi envenenada)
```

Se o primeiro der ≠ 0: **não promover para produção**. Mitigação: devolver
`Vary: X-Forwarded-Host` junto com o `Cache-Control` (a Vercel usa `Vary` como parte da
chave), ou allowlist de host no `BaseUrlFilter`.

- [ ] **Step 5: Verificações padrão do runbook no preview**

Rodar as checagens de REST, OpenAPI, cold start e probe MCP do `docs/DEPLOY.md` passo 2. As URLs embutidas devem apontar para o host do preview com `https` — se o cache tivesse misturado hosts, apareceria aqui.

- [ ] **Step 6: Deploy de produção**

```bash
cd /Users/eldermoraes/git/eldermoraes/swapi.build/swapi-app
set -a; source ../.env; set +a
npx vercel deploy --prod --token "$VERCEL_API_TOKEN" --scope algorium
```

Não usar `vercel promote` (rebuilda e trava sem tty).

- [ ] **Step 7: Verificação pós-deploy em produção**

```bash
curl -sI https://swapi.build/api/people/1 | grep -i 'cache-control\|x-vercel-cache'
curl -sI https://swapi.build/api/people/1 | grep -i 'x-vercel-cache'   # HIT
curl -s https://swapi.build/api/people/1 | grep -c 'https://swapi.build/api/people/1'  # 1
curl -sI https://swapi.build/api/people/random | grep -i 'x-vercel-cache'  # MISS
```

Mais as checagens do `docs/DEPLOY.md` passo 4, incluindo o probe MCP contra `https://swapi.build/mcp`.

- [ ] **Step 8: Atualizar o runbook**

Acrescentar à tabela de Troubleshooting do `docs/DEPLOY.md`:

```markdown
| 403 em todos os paths de `swapi.build`, resposta em ~0,07s com `x-vercel-mitigated: deny` | Mitigação automática bloqueou o IP de origem (típico após teste de carga). A app **não** caiu: confira com `curl https://swapi-build.vercel.app/api/people/1` (200) ou pelo domínio público a partir de outro IP. Expira sozinha; regras de IP `bypass` não existem no plano Hobby. Não redeployar. |
| `x-vercel-cache: MISS` sempre, em path que não é `/random` | O `CacheControlFilter` não está aplicando o header. Confira `curl -sI <host>/api/people/1 \| grep -i cache-control` — deve conter `s-maxage=31536000`. |
| Resposta errada "congelada" na borda (TTL de 1 ano) | Purgar: dashboard → projeto → **CDN** → **Caches** → **Purge**, usando `*` para o projeto inteiro. Prefira **Invalidate** a **Delete** (Delete revalida em foreground e pode causar cache stampede). Um deploy novo também resolve, por usar outra chave de cache. |
```

```bash
cd /Users/eldermoraes/git/eldermoraes/swapi.build
git add docs/DEPLOY.md
git commit -m "docs: runbook cobre mitigação de IP e verificação do cache de borda

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

## Rollback

| O que | Como | Precisa de deploy? |
|---|---|---|
| Timeout | PATCH do Task 3 Step 2 sem `functionDefaultTimeout` | Não — imediato |
| Cache de borda | Reverter os commits das Tasks 1–2 e redeployar | Sim — 10–25 min |
| Cache já gravado na borda | Um deploy novo já usa outra chave de cache; para forçar antes disso, purgar pelo dashboard (CDN → Caches → Purge) | — |
