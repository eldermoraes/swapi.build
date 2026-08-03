# Exemplo de Client — Quarkus + LangChain4j (MCP + API) — Design

**Data:** 2026-08-03 · **Aprovado pelo usuário** (modelo `gemma4:31b-cloud`, transporte stateless custom)

## Objetivo

Criar o primeiro exemplo de client do swapi.build em `examples/quarkus-langchain4j`: uma aplicação
Quarkus + LangChain4j que responde perguntas em linguagem natural consultando o swapi.build por
**dois caminhos comparáveis** — o MCP server (`/mcp`) e a API REST (`/api`). O exemplo é o menor
possível que ainda seja um caso de uso real, e serve de material didático ("é assim que se consome
nosso MCP") e de conteúdo (blog/Reels).

Todo o artefato — código, comentários, README, nomes — **em inglês**. Esta spec e o plano ficam em
português, como o resto de `docs/`.

## Achado técnico que definiu o desenho

Verificado em 03/08/2026 contra produção e contra o código dos artefatos, não inferido:

**Servidor.** `swapi.build/mcp` roda `quarkus-mcp-server-http` **2.0.0.Beta3** e aceita **apenas** o
envelope stateless da spec 2026-07-28. O handshake clássico foi testado com reuso de conexão TCP
comprovado (`Re-using existing connection`): `initialize` responde 200 mas **não devolve
`Mcp-Session-Id`**, e o `tools/list` seguinte falha com
`-32601 "The first message from the client must be initialize"`. O servidor não guarda estado algum.

**Cliente.** `dev.langchain4j:langchain4j-mcp`, versão mais recente do Maven Central
(**1.18.1-beta28**): constantes de protocolo apenas `2024-11-05` e `2025-11-25`, transporte baseado
em `Mcp-Session-Id`, **zero** ocorrências de `Mcp-Method`/`Mcp-Name`. Não fala o envelope stateless.

**Sem saída pelo servidor.** A doc da extensão MCP server (branch `main`) afirma auto-detecção dos
dois paradigmas no mesmo endpoint, mas isso não está em release: `maven-metadata.xml` de
`io.quarkiverse.mcp:quarkus-mcp-server-http` dá `latest = release = 2.0.0.Beta3`. Não existe GA para
subir.

**Consequência:** nenhum client da era 2025-11-25 — incluindo qualquer app LangChain4j — consegue
consumir `swapi.build/mcp` hoje sem implementar o envelope novo. Decisão do usuário: **não agir**
sobre esse gap fora do exemplo (a postura stateless-only é intencional; os clients acompanharão a
spec). Fica registrado aqui apenas como contexto do desenho.

## Wire format stateless (provado contra produção)

```
POST https://swapi.build/mcp
Headers: Content-Type: application/json
         Accept: application/json, text/event-stream
         MCP-Protocol-Version: 2026-07-28
         Mcp-Method: <método JSON-RPC>          # obrigatório em toda request
         Mcp-Name: <nome da tool>               # obrigatório em tools/call
Body:    {"jsonrpc":"2.0","id":N,"method":"...","params":{ ..., "_meta":{
           "io.modelcontextprotocol/protocolVersion":"2026-07-28",
           "io.modelcontextprotocol/clientInfo":{"name":"...","version":"..."},
           "io.modelcontextprotocol/clientCapabilities":{}
         }}}
```

`tools/list` assim devolveu as 4 tools; `tools/call` com `Mcp-Name: sw_get` e
`arguments {resource: PEOPLE, id: 1}` devolveu Luke Skywalker. Ausência de `Mcp-Method` →
`-32020 Missing required header: Mcp-Method`; ausência de `_meta` → `-32602`; ausência de `Mcp-Name`
em `tools/call` → `-32020 Missing required header: Mcp-Name`.

## Decisões (com o usuário)

1. **Caso de uso: Star Wars Archive Assistant.** Perguntas em linguagem natural sobre um catálogo
   remoto — a forma mais comum de assistente em produção (perguntar sobre produtos/clientes/estoque
   e o LLM consulta a API da empresa). Exemplo canônico do README exige **duas tool calls
   encadeadas** ("Which planet is Luke Skywalker from, and how hot is it?" → `sw_search(PEOPLE)` →
   `sw_get(PLANETS)`), o que demonstra tool calling multi-step de verdade, não um hello-tool.
2. **Dois caminhos lado a lado**, mesma pergunta, mesmos prompts: `POST /ask/mcp` (tools vindas do
   MCP server remoto) e `POST /ask/api` (tools locais sobre REST client). O README compara: no
   caminho MCP não se escreve schema de tool nenhum — o servidor descreve as próprias capacidades.
3. **Transporte stateless custom** para o caminho MCP (uma classe), em vez de esperar release ou
   mexer no servidor.
4. **Modelo: `gemma4:31b-cloud`** via Ollama. Tool calling verificado em 03/08/2026: devolveu
   `tool_calls` com `{"resource":"PEOPLE","id":1}` corretos em ~0,4 s.
5. **Alvo MCP: produção** (`https://swapi.build/mcp`) por padrão; override para
   `http://localhost:5432/mcp` comentado no `application.properties`.
6. **Interface: endpoints REST + curl.** Sem UI, sem WebSocket, sem CLI.
7. **Escopo: no repo do swapi.build**, em `examples/quarkus-langchain4j`, projeto Maven standalone
   fora do build do `swapi-app`.
8. **Sem observabilidade**, sem RAG, sem `langchain4j-agentic`, sem OpenAPI — nada disso serve ao
   exemplo.

## Arquitetura

Projeto Maven standalone, Quarkus 3.33.3 / Java 25 (iguais ao `swapi-app`), pacote raiz
`com.eldermoraes.swapi.assistant`. Extensões: `rest`, `rest-jackson`, `rest-client-jackson`,
`langchain4j-ollama`, `langchain4j-mcp`.

```
examples/quarkus-langchain4j/
  pom.xml
  README.md
  .gitignore
  src/main/java/com/eldermoraes/swapi/assistant/
    ai/Archivist.java              # @RegisterAiService(toolProviderSupplier = SwapiMcpToolProvider.class)
    ai/RestArchivist.java          # @RegisterAiService(tools = SwapiTools.class)
    mcp/StatelessMcpTransport.java # implements McpTransport — a única peça de plumbing
    mcp/SwapiMcpToolProvider.java  # Supplier<ToolProvider>: DefaultMcpClient -> McpToolProvider
    client/SwapiClient.java        # @RegisterRestClient para /api
    tools/SwapiTools.java          # @Tool beans delegando ao SwapiClient
    dto/Answer.java                # record (resposta do endpoint)
    rest/AskResource.java          # POST /ask/mcp · POST /ask/api
  src/main/resources/application.properties
  src/test/java/com/eldermoraes/swapi/assistant/
    StatelessMcpTransportTest.java # headers + _meta (sem rede, sem modelo)
    AssistantWiringTest.java       # smoke de CDI/AI service (sem modelo)
```

### `StatelessMcpTransport`

Implementa `dev.langchain4j.mcp.client.transport.McpTransport` com JDK `HttpClient` (zero
dependência nova, funciona em native):

- `initialize(...)` → POST real de `initialize`; o servidor responde 200 e o cliente lê
  `Mcp-Session-Id` como `Optional`, então a ausência não quebra nada.
- `executeOperationWithResponse(...)` → injeta os três headers stateless e o `_meta`; deriva
  `Mcp-Method` do campo `method` da mensagem JSON-RPC e `Mcp-Name` de `params.name` quando presente.
- `executeOperationWithoutResponse(...)` → **no-op**. Notificações como `notifications/initialized`
  são rejeitadas por servidor stateless; engoli-las é o comportamento correto aqui.
- `start(...)` → no-op (sem canal SSE subsidiário). `checkHealth()` → sem verificação remota.
  `onFailure(...)` → guarda o callback.

### Consequência assumida e documentada

Como o `@McpToolBox` só funciona com o tool provider automático da extensão, o caminho MCP usa
`toolProviderSupplier`. O README explica em duas frases que esse é o preço do gap de spec e que, no
dia em que o `langchain4j-mcp` falar 2026-07-28, o exemplo colapsa para duas properties + uma
annotation — o que reforça a mensagem em vez de enfraquecê-la.

## Testes (TDD)

1. **`StatelessMcpTransportTest`** — o teste que protege a peça nova. Teste JUnit puro (sem
   `@QuarkusTest`): sobe um stub `com.sun.net.httpserver.HttpServer` em porta efêmera, executa
   `tools/list` e `tools/call` pelo transporte e afirma sobre a request capturada:
   `MCP-Protocol-Version`, `Mcp-Method` correto por operação, `Mcp-Name` presente só em `tools/call`,
   e as três chaves de `_meta` no corpo. Sem rede externa, sem modelo, sem boot do Quarkus.
2. **`AssistantWiringTest`** — `@QuarkusTest` que injeta `Archivist` e `RestArchivist` e afirma que
   os proxies existem. Bootar o Quarkus constrói o container CDI e os proxies dos AI services, então
   verde já prova wiring sem chamar modelo e sem Ollama rodando.
3. Teste com modelo vivo (Ollama + MCP de produção) entra **comentado, opt-in**, com instrução no
   README.

O exemplo **não declara client MCP nomeado** em `application.properties` (o `DefaultMcpClient` é
construído em código pelo `SwapiMcpToolProvider`), então não há health check da extensão exigindo o
servidor no ar — e `quarkus.langchain4j.mcp.health.enabled` não é necessário. A extensão
`langchain4j-mcp` entra apenas para trazer `dev.langchain4j:langchain4j-mcp` na versão do BOM e o
registro para native image.

## README do exemplo (em inglês)

Rodar em 3 comandos; os dois curls lado a lado com a mesma pergunta; explicação do que o MCP poupa;
a nota do gap de spec e do transporte custom; como trocar de modelo (`gemma4:31b-cloud` → local ou
OpenAI); nota de que modelo `-cloud` exige `ollama signin` e rede. Link para o exemplo no README
raiz do swapi.build.

## Fora de escopo

Observabilidade, RAG, agentes/multi-agente, guardrails, UI, autenticação, native build do exemplo,
CI para o exemplo, e qualquer mudança no `swapi-app` (inclusive o gap de compatibilidade MCP).

## Riscos

| Risco | Mitigação |
|---|---|
| `McpTransport` é API beta (`1.18.x-beta28`) e pode mudar de assinatura | Uma classe isolada, com teste próprio; o gap desaparece quando o cliente suportar a spec |
| Modelo `-cloud` exige `ollama signin` e rede | Documentado no README, com alternativa local |
| Qualidade da resposta depende do tool calling do modelo | Prompt curto e tools genéricas; verificado que o modelo chama a tool certa |
