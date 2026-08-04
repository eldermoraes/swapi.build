# SWAPI.build

A free, open-source Star Wars API serving data about People, Films, Planets, Species, Starships, and Vehicles. Built with [Quarkus](https://quarkus.io/) and [GraalVM](https://www.graalvm.org/) for instant startup, minimal memory footprint, and out-of-the-box performance.

Inspired by the original [SWAPI](https://swapi.dev/) (created by Paul Hallett, maintained by Juriy Bura), this project was born out of the need for a Star Wars API that **never goes offline**. If you've ever had a live demo break because a third-party API went down, you know why this exists.

## Quick Start

**Prerequisites:** Java 25 and Maven (or use the included Maven Wrapper).

```bash
cd swapi-app
./mvnw quarkus:dev
```

The API and frontend will be available at **http://localhost:5432**.

## API Endpoints

Base path: `/api`

| Resource | List All | By ID | Random | Search |
|----------|----------|-------|--------|--------|
| People | `GET /api/people` | `GET /api/people/:id` | `GET /api/people/random` | `GET /api/people?search=name` |
| Films | `GET /api/films` | `GET /api/films/:id` | `GET /api/films/random` | `GET /api/films?search=title` |
| Planets | `GET /api/planets` | `GET /api/planets/:id` | `GET /api/planets/random` | `GET /api/planets?search=name` |
| Species | `GET /api/species` | `GET /api/species/:id` | `GET /api/species/random` | `GET /api/species?search=name` |
| Starships | `GET /api/starships` | `GET /api/starships/:id` | `GET /api/starships/random` | `GET /api/starships?search=name` |
| Vehicles | `GET /api/vehicles` | `GET /api/vehicles/:id` | `GET /api/vehicles/random` | `GET /api/vehicles?search=name` |

Ids are the record ids from each entity's `url` field (for films, `1` = A New Hope).
Successful responses return `200`; unknown or non-numeric ids return `404`.

All responses are JSON. Example:

```bash
curl http://localhost:5432/api/people/1
```

## OpenAPI

The full API contract is served at [`/openapi.json`](https://swapi.build/openapi.json)
(OpenAPI 3.x, generated from the code — always in sync). The
[documentation page](https://swapi.build/docs) renders from it, including a
"try it" for every endpoint. Generate a client with, e.g.:

    npx @openapitools/openapi-generator-cli generate -i https://swapi.build/openapi.json -g typescript-fetch

## MCP Server

swapi.build is also a remote [MCP](https://modelcontextprotocol.io) server over
**Streamable HTTP**. Any Streamable HTTP client works: the stateless `2026-07-28`
revision sends self-contained requests, and earlier revisions negotiate a session
through `initialize` — both are served on the same endpoint. The legacy HTTP+SSE
transport (`2024-11-05`) is not supported. First-party, read-only, no authentication:

```
https://swapi.build/mcp
```

Full setup guides: **[swapi.build/docs/mcp](https://swapi.build/docs/mcp)**

| Tool | Arguments | Returns |
|------|-----------|---------|
| `sw_list` | `resource` | All entities of a resource |
| `sw_get` | `resource`, `id` | One entity by id |
| `sw_random` | `resource` | A random entity |
| `sw_search` | `resource`, `query` | Name/title substring match |

`resource` is one of `PEOPLE`, `FILMS`, `PLANETS`, `SPECIES`, `STARSHIPS`, `VEHICLES`.
Ids are the record ids from each entity's `url` field (for `FILMS`, `1` = A New Hope).

<details>
<summary><strong>Claude Code</strong></summary>

```bash
claude mcp add --transport http swapi-build https://swapi.build/mcp
```

Or share via `.mcp.json` at the repo root:

```json
{
  "mcpServers": {
    "swapi-build": { "type": "http", "url": "https://swapi.build/mcp" }
  }
}
```

Verify: `claude mcp list` → `swapi-build ✔ Connected`.
</details>

<details>
<summary><strong>Claude Desktop &amp; claude.ai</strong></summary>

Settings → Connectors → **Add custom connector** → name `swapi-build`, URL
`https://swapi.build/mcp`. No authentication needed. Verify in any chat via the **+** menu → Connectors.
</details>

<details>
<summary><strong>OpenAI Codex</strong></summary>

```bash
codex mcp add swapi-build --url https://swapi.build/mcp
```

Or in `~/.codex/config.toml` (shared by CLI, IDE extension and ChatGPT desktop):

```toml
[mcp_servers.swapi-build]
url = "https://swapi.build/mcp"
```

Verify: `codex mcp list`.
</details>

<details>
<summary><strong>GitHub Copilot (VS Code)</strong></summary>

`.vscode/mcp.json` (top-level key is `servers`):

```json
{
  "servers": {
    "swapi-build": { "type": "http", "url": "https://swapi.build/mcp" }
  }
}
```

Or Command Palette → **MCP: Add Server**. Verify via the **Configure Tools** button in Copilot Chat.
On Business/Enterprise, the "MCP servers in Copilot" org policy must be enabled.
</details>

<details>
<summary><strong>IBM Bob</strong></summary>

`~/.bob/settings/mcp_settings.json` (global) or `.bob/mcp.json` (project):

```json
{
  "mcpServers": {
    "swapi-build": {
      "type": "streamable-http",
      "url": "https://swapi.build/mcp",
      "disabled": false
    }
  }
}
```

Or Bob panel → MCP tab → **Edit Global MCP**. Bob detects the tools automatically.
</details>

> The server scales to zero when idle — if the very first connection attempt fails, retry once
> (the native binary starts in tens of milliseconds; the platform may take a bit longer to
> provision the container; subsequent calls are fast, whether your client is stateless or
> session-based).

## Project Structure

```
swapi-app/
  Dockerfile.vercel           # Native container image used by Vercel deploys
  src/main/
    java/com/eldermoraes/     # Backend (Quarkus + Jakarta REST)
      film/                   # Film model, service, resource
      people/                 # People model, service, resource
      planet/                 # Planet model, service, resource
      specie/                 # Specie model, service, resource
      starship/               # Starship model, service, resource
      vehicle/                # Vehicle model, service, resource
      mcp/                    # MCP server tools (sw_list, sw_get, sw_random, sw_search)
      SWObject.java           # Base model class
      SWService.java          # Service interface
      ApiResource.java        # Root /api endpoint
      ApplicationPath.java    # Jakarta REST base path (/api)
    resources/
      data/                   # Static JSON data files
      application.properties  # Quarkus configuration
    webui/                    # Frontend (TypeScript + Vite)
      src/
        api.ts                # API client with request management
        main.ts               # SPA router
        pages/                # Page renderers (home, resource, docs, mcp, about, privacy, terms)
        json-highlight.ts     # JSON syntax highlighting for result panels
        style.css             # Site styles
        types.ts              # TypeScript interfaces for API resources
        constants.ts          # Shared resource metadata
        utils.ts              # Shared utilities (escapeHtml)
  src/test/
    java/com/eldermoraes/     # Regression suite (REST contracts, MCP tools, forwarded headers)
```

Each backend domain (film, people, planet, etc.) follows the same pattern:

- **Model** (e.g., `Film.java`): POJO with `@RegisterForReflection` for native image support
- **Service** (e.g., `FilmService.java`): loads data from JSON at startup, caches in memory
- **Resource** (e.g., `FilmResource.java`): Jakarta REST controller with `@RunOnVirtualThread`

## Build

```bash
cd swapi-app

# Development mode (live reload)
./mvnw quarkus:dev

# Production JAR
./mvnw package
java -jar target/quarkus-app/quarkus-run.jar

# Native executable (requires GraalVM or container build)
./mvnw package -Dnative
./mvnw package -Dnative -Dquarkus.native.container-build=true
```

The frontend is automatically built and bundled by [Quinoa](https://docs.quarkiverse.io/quarkus-quinoa/dev/index.html) during the Maven build. No separate `npm` step is needed.

## Deployment

The app runs on [Vercel](https://vercel.com/) as a native (GraalVM/Mandrel) container image, built from `swapi-app/Dockerfile.vercel`. DNS is managed on Cloudflare (DNS-only records pointing to Vercel). Deploys are done with `npx vercel deploy --prod` from the `swapi-app/` directory.

## Tech Stack

- **Runtime:** [Quarkus 3.33](https://quarkus.io/) on Java 25 with Virtual Threads
- **MCP server:** [Quarkiverse MCP Server](https://docs.quarkiverse.io/quarkus-mcp-server/dev/index.html) — Streamable HTTP, stateless and session-based clients
- **Serialization:** Jakarta REST + JSON-B
- **Native image:** GraalVM via Mandrel builder
- **Frontend:** TypeScript + [Vite](https://vite.dev/), served by Quinoa

## Contributing

Pull requests are always welcome. Whether it's fixing a bug, improving the docs, or adding new features, jump in and help make the best Star Wars API in the galaxy even better.

1. Fork the repository
2. Create your feature branch (`git checkout -b feature/my-change`)
3. Make your changes (with tests) and run the suite: `cd swapi-app && ./mvnw test`
4. Commit and push
5. Open a Pull Request

## Changelog and releases

Release history lives in [CHANGELOG.md](CHANGELOG.md). Tagged releases are on the
[Releases page](https://github.com/eldermoraes/swapi.build/releases). The version
served in `/openapi.json` (`info.version`) is always the latest released version.
The release process itself is documented in [docs/RELEASE.md](docs/RELEASE.md).

## Credits

- Original [SWAPI](https://swapi.dev/) by **Paul Hallett**, maintained by **Juriy Bura**
- Star Wars data from community-driven sources such as [Wookieepedia](https://starwars.fandom.com/)
- All Star Wars content and imagery are property of **Lucasfilm Ltd.** and **Disney**. This project is not affiliated with or endorsed by Lucasfilm or Disney.

## License

Licensed under the [Apache License 2.0](LICENSE). The website also publishes a
[Privacy Policy](https://swapi.build/privacy) and [Terms of Use](https://swapi.build/terms).
