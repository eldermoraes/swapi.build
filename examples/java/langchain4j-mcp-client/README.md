# swapi.build MCP client with Quarkus LangChain4j

Ask swapi.build questions in plain English and let the model do the lookups: the tools
it uses are not written here, they are discovered from the swapi.build MCP server.

## Prerequisites

- Java 25
- [Ollama](https://ollama.com) with the `gemma4:31b-cloud` model. A `-cloud` model runs on
  Ollama's hosted infrastructure, so it needs `ollama signin` and network access.
- A model with tool calling. Without it the model cannot use the MCP tools and this
  example does not work.

## Run

```bash
./mvnw quarkus:dev
```

Then ask something that no single lookup can answer:

```bash
curl -s -X POST http://localhost:8080/ask \
  -H 'Content-Type: text/plain' \
  -d 'Which planet is Luke Skywalker from, and what is its climate?'
```

Output from one run:

```
Luke Skywalker is from the planet Tatooine, which has an arid climate.
```

That is one run's output, not something to expect word for word. The model is not
deterministic even at `temperature=0`.

## How it works

The whole integration is two properties in `application.properties`:

```properties
quarkus.langchain4j.mcp.swapi.transport-type=streamable-http
quarkus.langchain4j.mcp.swapi.url=https://swapi.build/mcp
```

`@McpToolBox("swapi")` on the `Archivist` method hands that client's tools to the model.
The server advertises what it can do and the model picks from that list. The example
question takes two chained calls: find the character, then fetch the home planet the
first call returned. Nothing in this project decides that — the model does.

The client connects the first time its bean is used, so the first request pays the
handshake, not startup.

## Switching models

One property:

```properties
quarkus.langchain4j.ollama.chat-model.model-name=gemma4:31b-cloud
```

Any tool-calling model works.

## Pointing at a local server

Uncomment the local URL in `application.properties`:

```properties
quarkus.langchain4j.mcp.swapi.url=http://localhost:5432/mcp
```

## What is not here

No tool definitions, no JSON schemas, no HTTP client code, no response mapping. That is
the point: the MCP server describes its own capabilities, so the client stays this small.

See also `../quarkus-rest-client` for calling the swapi.build REST API directly.

## Links

- [swapi.build](https://swapi.build)
- [swapi.build MCP docs](https://swapi.build/docs/mcp)
- [Quarkus LangChain4j MCP guide](https://docs.quarkiverse.io/quarkus-langchain4j/dev/mcp.html)
