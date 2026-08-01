# Discovery do base URL público no caminho MCP

**Data:** 2026-08-01
**Status:** Aprovado

## Problema

As URLs embutidas nas respostas da API (ex. `homeworld`, `films[]`) precisam do
base URL público. O lado REST já o descobre por request via
`UriInfo.getBaseUri()`. O lado MCP não tem contexto JAX-RS e usa
`@ConfigProperty(name = "swapi.public-base-url", defaultValue = "https://swapi.build/api")`
em `SwapiTools` — domínio hardcoded que quebraria silenciosamente numa migração
de domínio e acopla o binário ao domínio de produção.

## Decisão

Discovery por request também no caminho MCP, usando o `HttpServerRequest`
(Vert.x) da request MCP ativa — a extensão quarkus-mcp-server ativa o contexto
CDI de request por invocação de tool e, em transporte HTTP, disponibiliza o
`HttpServerRequest` request-scoped. Config passa a ser apenas override/fallback
explícito, sem domínio default.

Alternativas rejeitadas:
- **Config obrigatória sem default:** determinístico, mas não é discovery —
  migração de domínio ainda exige lembrar de atualizar env var.
- **Cache "primeira request vence" em escopo de aplicação:** frágil; se a
  primeira request vier por host alternativo (deploy preview `*.vercel.app`,
  `www` vs apex), a URL errada congela para sempre.

## Resolução do base URL (precedência)

1. `swapi.public-base-url` setada explicitamente → ela vence. A propriedade
   vira `Optional<String>` **sem** `defaultValue` — válvula de escape
   operacional. Os overrides `%dev`/`%test` existentes são removidos: com
   discovery eles produziriam exatamente o mesmo valor (dev serve em
   `localhost:5432`, teste em `localhost:8081`), e manter o `%test` faria o
   teste de discovery passar por acidente.
2. Senão, deriva da request ativa: `scheme://host` + `/api`.
3. Sem config e sem request HTTP ativa (caso teórico, ex. transporte stdio):
   `ToolCallException` com mensagem clara — nunca inventar domínio.

`SwapiTools` continua `@ApplicationScoped`; o `HttpServerRequest` é injetado
como client proxy CDI que resolve para a request ativa no momento da chamada.
O campo `String publicBaseUrl` dá lugar a um método `resolveBaseUrl()` chamado
pelo `applyBaseUrl()` existente, por tool call (mesmo custo de hoje).

## Config de proxy

Em produção o TLS termina na borda (Cloudflare/Vercel) e o host/scheme reais
chegam via `X-Forwarded-Host`/`X-Forwarded-Proto`. Habilitar em
`application.properties`:

```properties
quarkus.http.proxy.proxy-address-forwarding=true
quarkus.http.proxy.allow-x-forwarded=true
quarkus.http.proxy.enable-forwarded-host=true
```

Efeito colateral desejável: o `UriInfo` do lado REST também passa a honrar
esses headers. Seguro no nosso caso porque em produção a app só é alcançável
via edge (headers não são forjáveis por cliente externo).

Bônus: deploy previews da Vercel passam a devolver URLs do próprio preview em
vez de apontar para produção.

## Escopo

**Muda:** `SwapiTools.java` (resolução), `application.properties` (proxy;
propriedade fica sem default). Nenhum service ou entidade muda.

**Fora de escopo:**
- Race conhecida do `baseUrl` (services mutam entidades estáticas
  compartilhadas) — item de backlog separado; fix é compor URL na
  serialização, não faz parte desta mudança.
- Menções a `swapi.build` em `mcp.ts`/`about.ts` do site — texto de
  documentação (snippets de setup), não path de runtime.

## Testes

- Testes atuais continuam passando (não dependem do valor do base URL).
- Novo teste MCP via `McpAssured` (sem override, que deixa de existir),
  assertando que as URLs embutidas no JSON das tools refletem o host real da
  request de teste (`localhost:8081`) — prova o discovery fim a fim.
- Teste de precedência: `@TestProfile` setando `swapi.public-base-url` e
  assertando que a config explícita vence o discovery.
- Teste REST com `X-Forwarded-Proto`/`X-Forwarded-Host` provando a config de
  proxy (REST-assured permite headers arbitrários; a camada Vert.x é comum
  aos dois caminhos).

## Verificação pós-deploy

`curl` no `/mcp` e no `/api` de produção conferindo que as URLs embutidas
continuam `https://swapi.build/api/...` — atenção especial ao scheme (`https`),
por causa da mudança de proxy.
