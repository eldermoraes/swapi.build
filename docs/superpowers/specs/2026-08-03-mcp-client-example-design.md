# Exemplo de Client — Quarkus + LangChain4j (MCP + API) — Design

**Data:** 2026-08-03 · **Status:** aguardando aprovação
**Revisada em 2026-08-03**, depois do merge `00a8e53` ("MCP serves stateful and stateless clients on
one endpoint", app 2.1.0). A revisão **removeu a peça central da versão anterior**: o
`StatelessMcpTransport` custom não é mais necessário. Ver "Revisão" no fim.

## Objetivo

Criar o primeiro exemplo de client do swapi.build em `examples/quarkus-langchain4j`: uma aplicação
Quarkus + LangChain4j que responde perguntas em linguagem natural consultando o swapi.build por
**dois caminhos comparáveis** — o MCP server (`/mcp`) e a API REST (`/api`). O exemplo é o menor
possível que ainda seja um caso de uso real, e serve de material didático ("é assim que se consome
nosso MCP") e de conteúdo (blog/Reels).

Todo o artefato — código, comentários, README, nomes — **em inglês**. Esta spec e o plano ficam em
português, como o resto de `docs/`.

## Estado verificado do endpoint (2026-08-03, produção 2.1.0)

O exemplo depende de um fato que precisa estar verificado, não suposto: o `/mcp` atende o cliente
LangChain4j como ele é hoje. Medido contra produção:

| Cenário | Resultado |
|---|---|
| `initialize` negociando `2025-11-25` | 200 + header `mcp-session-id` |
| `notifications/initialized` em conexão nova, com sessão | 202 |
| `tools/list` e `tools/call` em conexão nova, com sessão | 200, payload correto (Luke Skywalker) |
| `tools/list` com `Mcp-Session-Id` inválido (efeito do `auto-init=true`) | 200 |
| `tools/list` sem `Mcp-Session-Id` nenhum | 200 |
| Envelope stateless `2026-07-28` (`Mcp-Method`/`Mcp-Name`/`_meta`) | 200, sem regressão |

`dev.langchain4j:langchain4j-mcp` (1.18.x-beta28, o mais recente no Central) fala exatamente
`2025-11-25` com transporte baseado em `Mcp-Session-Id` — que é o que o servidor negocia e serve.
Logo o exemplo usa o caminho **declarativo padrão**, sem plumbing.

Por que isso é seguro na topologia da Vercel (sem afinidade de instância): `auto-init=true` serve um
`Mcp-Session-Id` desconhecido com sessão descartável em vez de 404, e as 4 tools do swapi.build são
read-only e sem estado entre chamadas — nenhuma delas depende da sessão que a atendeu. O detalhamento
está em `docs/superpowers/specs/2026-08-03-mcp-dual-stateful-stateless-design.md`.

## Decisões (com o usuário)

1. **Caso de uso: Star Wars Archive Assistant.** Perguntas em linguagem natural sobre um catálogo
   remoto — a forma mais comum de assistente em produção (perguntar sobre produtos/clientes/estoque
   e o LLM consulta a API da empresa). O exemplo canônico do README exige **duas tool calls
   encadeadas** ("Which planet is Luke Skywalker from, and how hot is it?" → `sw_search(PEOPLE)` →
   `sw_get(PLANETS)`), o que demonstra tool calling multi-step de verdade, não um hello-tool.
2. **Dois caminhos lado a lado**, mesma pergunta, mesmos prompts: `POST /ask/mcp` (tools vindas do
   MCP server remoto) e `POST /ask/api` (tools locais sobre REST client). O README compara: no
   caminho MCP não se escreve cliente, nem tool, nem schema — o servidor descreve as próprias
   capacidades; o caminho API mostra exatamente o que o MCP poupou.
3. **Caminho MCP 100% declarativo:** `@McpToolBox("swapi")` + duas properties. Sem transporte
   custom, sem `toolProviderSupplier` (ver "Revisão").
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
    ai/Archivist.java          # @RegisterAiService + @McpToolBox("swapi")
    ai/RestArchivist.java      # @RegisterAiService(tools = SwapiTools.class)
    client/SwapiClient.java    # @RegisterRestClient para /api
    tools/SwapiTools.java      # @Tool beans delegando ao SwapiClient
    dto/Answer.java            # record devolvido pelos endpoints
    rest/AskResource.java      # POST /ask/mcp · POST /ask/api
  src/main/resources/application.properties
  src/test/java/com/eldermoraes/swapi/assistant/
    AssistantWiringTest.java   # smoke de CDI/AI service (sem modelo, sem rede)
    McpStubServer.java         # QuarkusTestResourceLifecycleManager (stub MCP local)
```

Oito arquivos de projeto. As duas classes de plumbing MCP da versão anterior deixaram de existir.

### Configuração relevante

```properties
quarkus.langchain4j.mcp.swapi.transport-type=streamable-http
quarkus.langchain4j.mcp.swapi.url=https://swapi.build/mcp
# quarkus.langchain4j.mcp.swapi.url=http://localhost:5432/mcp   # servidor local

quarkus.rest-client.swapi.url=https://swapi.build/api

quarkus.langchain4j.ollama.chat-model.model-id=gemma4:31b-cloud
```

### Prompts

Os dois AI services compartilham o mesmo `@SystemMessage`: responder **somente** com base no
resultado das tools, e dizer que não sabe quando as tools não trouxerem a informação. Isso é o que
torna o exemplo uma demonstração de *grounding*, e não de memória do modelo sobre Star Wars.

## Testes (TDD)

1. **`AssistantWiringTest`** — `@QuarkusTest` que injeta `Archivist` e `RestArchivist` e afirma que
   os proxies existem. Bootar o Quarkus constrói o container CDI, o cliente MCP e os proxies dos AI
   services, então verde prova o wiring sem chamar modelo.
2. **`McpStubServer`** — `QuarkusTestResourceLifecycleManager` que sobe um
   `com.sun.net.httpserver.HttpServer` em porta efêmera respondendo `initialize` (com
   `Mcp-Session-Id`) e `tools/list` com uma tool, e sobrescreve
   `quarkus.langchain4j.mcp.swapi.url` para apontar nele. Motivo: a extensão conecta o cliente MCP no
   startup, então sem stub a suíte passaria a depender de rede e do swapi.build no ar. Com o stub, o
   teste é determinístico e offline — e ainda documenta o handshake que o cliente faz.
3. Health check do cliente MCP desligado no perfil de teste
   (`%test.quarkus.langchain4j.mcp.health.enabled=false`), porque o readiness check da extensão
   pingaria o servidor real.
4. Teste com modelo vivo (Ollama + MCP de produção) entra **comentado, opt-in**, com instrução no
   README.

Comando da suíte: `cd examples/quarkus-langchain4j && ./mvnw test`.

## README do exemplo (em inglês)

Rodar em 3 comandos; os dois curls lado a lado com a mesma pergunta; a comparação MCP × API
(quantas linhas cada caminho custou); como trocar de modelo (`gemma4:31b-cloud` → local ou OpenAI);
nota de que modelo `-cloud` exige `ollama signin` e rede. Link para o exemplo no README raiz do
swapi.build.

## Fora de escopo

Observabilidade, RAG, agentes/multi-agente, guardrails, UI, autenticação, native build do exemplo,
CI para o exemplo, e qualquer mudança no `swapi-app`.

## Revisão de 2026-08-03 (o que mudou e por quê)

A versão anterior desta spec foi escrita algumas horas antes do merge `00a8e53` e partia de um
bloqueio real, medido na época: `swapi.build/mcp` (então 2.0.0.Beta3 sem `auto-init`) **não emitia
`Mcp-Session-Id`** e rejeitava a segunda request de um cliente com sessão, mesmo com reuso de conexão
TCP comprovado — enquanto o `langchain4j-mcp` mais recente não falava o envelope stateless
`2026-07-28`. Dali saíam duas classes de plumbing (`StatelessMcpTransport`,
`SwapiMcpToolProvider`), o abandono do `@McpToolBox` e um teste dedicado ao wire format.

Com o `auto-init=true` em produção, o cliente stateful passou a ser atendido de ponta a ponta
(medições na tabela acima). Consequências:

- **Removidos:** `mcp/StatelessMcpTransport.java`, `mcp/SwapiMcpToolProvider.java` e
  `StatelessMcpTransportTest`. O `toolProviderSupplier` sai; entra `@McpToolBox("swapi")`.
- **Removida** a ressalva do README sobre "o preço do gap de spec".
- **Acrescentado** o stub MCP de teste, que na versão anterior não fazia sentido (o transporte custom
  seria testado diretamente).
- **Mantidos** sem alteração: caso de uso, dois caminhos, modelo, alvo, interface, escopo, ausência
  de observabilidade.

O exemplo ficou mais curto e mais fiel ao que se deve ensinar: consumir um MCP server remoto em
Quarkus é configuração, não código.

## Riscos

| Risco | Mitigação |
|---|---|
| Sessão MCP sem afinidade de instância na Vercel | `auto-init=true` cobre; tools read-only não dependem de sessão. Verificado com sessão inválida e sem sessão |
| A extensão conecta o cliente MCP no startup → suíte dependeria de rede | Stub MCP local no perfil de teste (`McpStubServer`) + health check desligado |
| Modelo `-cloud` exige `ollama signin` e rede | Documentado no README, com alternativa local |
| Qualidade da resposta depende do tool calling do modelo | Prompt curto, tools genéricas, tool calling verificado com o modelo escolhido |
