# CLAUDE.md

swapi.build — Star Wars API (Quarkus 3 / Java 25, native image) with a Quinoa/Vite
frontend, deployed as a Vercel container function behind Cloudflare DNS. Also serves
an MCP server (Streamable HTTP, stateless) at `/mcp`.

## Development cycle (in this order — do not skip steps)

1. **Brainstorm / spec** — explore, verify assumptions in code, write spec to
   `docs/superpowers/specs/`.
2. **User approval** of the spec. No implementation before this.
3. **Implementation plan** — `docs/superpowers/plans/`, then user approval.
4. **Branch** — never implement directly on `main`.
5. **TDD** — failing test first, then implementation. Run the full suite before
   every commit.
6. **Merge** — ask the user (merge local / PR / keep branch). Run the suite again
   on the merged result.
7. **Push** — `git push` publishes commits only. **It does not deploy.**
8. **Deploy** — follow `docs/DEPLOY.md` exactly. Preview → verify → production.
9. **Post-deploy verification** — the curl checks in `docs/DEPLOY.md`.

## Non-negotiable facts

- **Deploy always runs from `swapi-app/`**, never from the repo root. From the root
  it fails with `Expected VCR image registry vcr.vercel.com: <detect>` (the
  `container` framework can't find the Dockerfile). See `docs/DEPLOY.md`.
- **There is no git-push auto-deploy.** Deploys are CLI-only (`npx vercel deploy`).
- **Tests:** `cd swapi-app && ./mvnw test`. Never run `mvn clean` while dev mode is
  running. Test HTTP port is 8081.
- **The API returns HTTP 202 (not 200) by design** — historic behavior, do not "fix".
- **Container tooling is `podman`** (`/opt/podman/bin`), not `docker`. The podman
  machine needs 8 GB for local native builds.
- **MCP server is stateless Streamable HTTP (spec 2026-07-28).** Never use legacy
  SSE or stateful patterns.
- **Public base URL is discovered per request** (REST via `UriInfo`, MCP via
  `HttpServerRequest`, honoring `X-Forwarded-*`). `swapi.public-base-url` is an
  optional override only — never reintroduce a hardcoded domain default.

## Ports

- Dev: `5432` (`quarkus.http.port=${PORT:5432}`); Vite dev server: `5173` (proxied).
- Tests: `8081`.
