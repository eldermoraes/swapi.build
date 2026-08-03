# swapi.build assistant — Quarkus + LangChain4j

A small Quarkus application that answers natural-language Star Wars questions using
only facts it fetched from [swapi.build](https://swapi.build). It reaches the same data
two ways: `POST /ask/mcp` gets its tools from the remote **MCP server** at
`https://swapi.build/mcp`, and `POST /ask/api` gets them from local `@Tool` beans
calling the **REST API**. Both share one system prompt, so the only variable is where
the tools come from — which is the whole point of the example.

## Prerequisites

- **Java 25** (`maven.compiler.release=25`).
- **[Ollama](https://ollama.com)** with the model `gemma4:31b-cloud` pulled.
  The `-cloud` suffix matters: these models run on Ollama's hosted infrastructure, not
  on your machine, so they require `ollama signin` and network access. Quarkus Dev
  Services talks to a local Ollama daemon on port `11434` and that daemon proxies to
  the hosted model. See [Switching models](#switching-models) to run fully local instead.
- **Network access to `swapi.build`** for both paths (and for the `live` tests).

No Maven install needed — the wrapper is included.

## Run

```bash
./mvnw quarkus:dev
```

The app listens on **port 8090** (`quarkus.http.port=8090`), so the Dev UI is at
<http://localhost:8090/q/dev/>.

Ask the same question through both paths:

```bash
curl -s -X POST http://localhost:8090/ask/mcp -H 'Content-Type: application/json' \
  -d '{"question":"Which planet is Luke Skywalker from, and what is its climate?"}'
```

```bash
curl -s -X POST http://localhost:8090/ask/api -H 'Content-Type: application/json' \
  -d '{"question":"Which planet is Luke Skywalker from, and what is its climate?"}'
```

What we got on one run — quoted verbatim, **not** output you should expect to match:

```json
{"path":"mcp","answer":"Luke Skywalker is from Tatooine, which has an arid climate."}
```

```json
{"path":"api","answer":"Luke Skywalker is from the planet Tatooine, which has an arid climate."}
```

Even with `quarkus.langchain4j.ollama.chat-model.temperature=0`, the wording moves
between runs: a second run produced the same two sentences with the paths swapped. The
*content* was stable across runs — Tatooine, arid, two chained tool calls with identical
arguments — but the phrasing is not, and nothing in the test suite asserts on it. Do not
build anything that depends on the exact string.

**On timing:** the first `/ask/mcp` call took about **9.6 s** and later ones about
**1.5 s**. That gap is the MCP handshake against `swapi.build/mcp`, which happens once,
lazily, on the first call — not a standing cost of the MCP path. MCP is not six times
slower than REST here.

## The two paths, side by side

|  | MCP path (`POST /ask/mcp`) | REST path (`POST /ask/api`) |
|---|---|---|
| Tool source | remote MCP server, discovered at runtime | local `@Tool` beans |
| Config | 2 lines in `application.properties` (`transport-type`, `url`) | 1 line (`quarkus.rest-client.swapi-api.url`) |
| Java you write | one annotation: `@McpToolBox("swapi")` on `Archivist.ask` | `SwapiClient` (4 methods) + `SwapiTools` (4 `@Tool` methods with hand-written descriptions, plus 404 handling) |
| Tool code | **none** | ~100 lines across two classes |
| Tool descriptions | written by the server | written by you, and you own their accuracy |
| System prompt | `Prompts.SYSTEM_MESSAGE` | `Prompts.SYSTEM_MESSAGE` (the same constant, not a copy) |

Both answered the same question from live tool output. The point, plainly: **consuming a
remote MCP server from Quarkus is configuration, not code.** The server describes its own
capabilities, so there is nothing to keep in sync when it changes.

### The tool surfaces are genuinely different

This is not only about lines of code. The two paths hand the model different tool designs:

- The MCP server advertises four *generic* tools — `sw_list`, `sw_get`, `sw_random`,
  `sw_search` — each parameterized by a `resource` enum (`PEOPLE`, `PLANETS`, `FILMS`,
  `SPECIES`, `STARSHIPS`, `VEHICLES`). One call shape covers six resource types.
- The REST path declares four *narrow* tools — `searchPeople`, `person`, `searchPlanets`,
  `planet` — one per operation, each with a hand-written description, covering two
  resource types.

That difference is measurable. Asking the canonical question through both endpoints in one
dev-mode session, with `-Dquarkus.langchain4j.ollama.log-responses=true`, the first model
call of each path — the one that carries the tool schemas but no tool results yet — reports:

| Path | 1st call | 2nd call | 3rd call | Total prompt tokens |
|---|---|---|---|---|
| REST (4 narrow tools) | **371** | 635 | 1002 | 2008 |
| MCP (4 generic tools) | **579** | 841 | 1207 | 2627 |

Both paths take three model round trips and two tool calls to answer, so those rows line
up step for step. **The MCP tool surface is the more expensive prompt here** — 208 tokens
more on the first call, and it stays ahead at every step because the larger tool schemas
sit in the conversation for the whole exchange. The `resource` enum is not free: six enum
values across four tools costs more than four narrow signatures with one-line descriptions.

So the trade is not "MCP is cheaper". It is: the MCP path costs **more prompt tokens and
no code**, and what it buys is coverage — the same four tools reach six resource types,
including films, species, starships and vehicles that the REST path never implemented. The
REST path is the leaner prompt precisely because it is the narrower, hand-maintained
surface.

(Token counts come from one run of a cloud-hosted model; treat them as the shape of the
difference, not as constants.)

## How it works

1. A `POST /ask/...` request hits `AskResource`, which calls one of the two AI services
   (`Archivist` or `RestArchivist`).
2. On the MCP path, the MCP client performs its `initialize` + `tools/list` handshake the
   first time its bean is created — **not** at Quarkus startup. That is why the first
   request is slow and why the offline test suite can boot without touching the network.
3. The model receives the tool list and picks calls. The canonical question needs **two
   chained calls**: find the character, then look up their homeworld.
4. The model is *told* how to chain, in two places. `Prompts.SYSTEM_MESSAGE` says "look up
   a character first, then look up their home planet", and on the REST path the `planet`
   tool description spells out the id rule with a worked example: a homeworld of
   `https://swapi.build/api/planets/1` means `id` 1. It did not have to infer that
   convention — the descriptions state it, which is exactly why they are worth writing
   carefully.
5. Observed on the run above:

   ```
   # MCP path
   sw_search {"query":"Luke Skywalker","resource":"PEOPLE"}
   sw_get    {"id":1,"resource":"PLANETS"}

   # REST path
   searchPeople {"name":"Luke Skywalker"}
   planet       {"id":1}
   ```

`SwapiTools` translates a `404` into a readable JSON error instead of letting it abort the
AI call, so a wrong id guess is something the model can recover from. Only `404` is
translated; every other failure still propagates.

## Switching models

One property:

```properties
quarkus.langchain4j.ollama.chat-model.model-name=gemma4:31b-cloud
```

Any tool-calling model works — for example `gpt-oss:20b` or `qwen3.5:35b`, both of which
run fully locally and need neither `ollama signin` nor a network round trip to a hosted
model. **Tool calling is required.** A model without it cannot run this example at all:
there is no fallback path that answers from the model's own knowledge, by design.

## Pointing at a local swapi.build

`application.properties` ships the override commented out:

```properties
quarkus.langchain4j.mcp.swapi.url=https://swapi.build/mcp
# Point at a locally running swapi.build instead:
# quarkus.langchain4j.mcp.swapi.url=http://localhost:5432/mcp
```

`5432` is swapi-app's dev port. For the REST path, point
`quarkus.rest-client.swapi-api.url` at `http://localhost:5432/api` the same way.

## Tests

```bash
./mvnw test          # offline: no network, no model
./mvnw test -Dgroups=live   # hits the public MCP server and REST API
```

**What the offline suite covers.** `ArchivistWiringTest` boots Quarkus, which builds both
AI-service proxies and validates `@McpToolBox("swapi")` against the configured client. It
then injects the `McpClient`, which triggers a real MCP handshake — against
`McpStubServer`, a local stub bound to that one test class via
`@QuarkusTestResource(..., restrictToAnnotatedClass = true)`. The stub advertises exactly
one tool, so if the URL override ever leaked and the suite started talking to the real
server, the assertion fails loudly. The Ollama dev service is off under `%test`, so no
container starts and no model is pulled.

**What the live suite covers.** `McpConnectivityTest` asserts the real server still lists
`sw_list`, `sw_get`, `sw_random` and `sw_search`, and hard-fails if its URL was retargeted
at a stub. `SwapiToolsLiveTest` calls the REST tools against `swapi.build` and checks the
404 handling.

**What nothing covers.** There is no automated test of the `/ask/mcp` or `/ask/api` HTTP
endpoints, and **no test calls the model**. End-to-end behaviour was verified by hand with
the curl commands above. That is a deliberate limit: model output is not stable enough to
assert on (see [Run](#run)), and a test that needed a signed-in cloud model would not be
runnable by most readers.

## Links

- [swapi.build](https://swapi.build) — the API this example consumes
- [swapi.build/docs/mcp](https://swapi.build/docs/mcp) — MCP server docs and client setup
- [Quarkus LangChain4j](https://docs.quarkiverse.io/quarkus-langchain4j/dev/index.html) —
  extension docs, including [MCP client support](https://docs.quarkiverse.io/quarkus-langchain4j/dev/mcp.html)
- [Model Context Protocol](https://modelcontextprotocol.io)
