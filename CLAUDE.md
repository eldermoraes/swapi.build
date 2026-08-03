# CLAUDE.md

swapi.build — Star Wars API (Quarkus 3 / Java 25, native image) with a Quinoa/Vite
frontend, deployed as a Vercel container function behind Cloudflare DNS. Also serves
an MCP server (Streamable HTTP) at `/mcp`.

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
7. **Release** — only if the change carries a version bump: changelog entry →
   annotated tag → GitHub Release, per `docs/RELEASE.md`.
8. **Push** — `git push` publishes commits only. **It does not deploy.**
9. **Deploy** — follow `docs/DEPLOY.md` exactly. Preview → verify → production.
10. **Post-deploy verification** — the curl checks in `docs/DEPLOY.md`.

## Non-negotiable facts

- **Deploy always runs from `swapi-app/`**, never from the repo root. From the root
  it fails with `Expected VCR image registry vcr.vercel.com: <detect>` (the
  `container` framework can't find the Dockerfile). See `docs/DEPLOY.md`.
- **There is no git-push auto-deploy.** Deploys are CLI-only (`npx vercel deploy`).
- **Tests:** `cd swapi-app && ./mvnw test`. Never run `mvn clean` while dev mode is
  running. Test HTTP port is 8081.
- **A version bump is a release.** Bump → `CHANGELOG.md` entry → annotated tag →
  GitHub Release → deploy, per `docs/RELEASE.md`. `ChangelogVersionTest` fails the
  suite if the pom version has no changelog section, and `OpenApiVersionTest` fails
  if `/openapi.json` stops advertising the pom version. Tags point at the **last**
  commit of a version line, never at the bump commit.
- **Successful GETs return HTTP 200; nonexistent ids return 404** (the historic
  202 quirk was retired on 2026-08-01 — no external clients depended on it).
- **Container tooling is `podman`** (`/opt/podman/bin`), not `docker`. The podman
  machine needs 8 GB for local native builds.
- **MCP serves stateful and stateless clients on the same `/mcp` endpoint.**
  Streamable HTTP only. `quarkus.mcp.server.http.streamable.auto-init=true`
  serves an unknown or missing `Mcp-Session-Id` with a throwaway session
  instead of 404ing — Vercel has no session affinity, and without this a
  stateful client fails intermittently. A genuine `initialize` still creates a
  real session that lives in one instance's heap until idle (default 30 min).
  `GET /mcp` answers 405 (no server→client stream) and `DELETE` answers 204.
  The legacy HTTP+SSE transport (`/mcp/sse`) is rejected on purpose. Never
  reintroduce session-affine state, and never depend on the legacy transport.
- **Public base URL is discovered per request** (REST via `UriInfo`, MCP via
  `HttpServerRequest`, honoring `X-Forwarded-*`). `swapi.public-base-url` is an
  optional override only — never reintroduce a hardcoded domain default.

## Ports

- Dev: `5432` (`quarkus.http.port=${PORT:5432}`); Vite dev server: `5173` (proxied).
- Tests: `8081`.
