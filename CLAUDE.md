# CLAUDE.md

swapi.build — Star Wars API (Quarkus 3 / Java 25, native image) with a Quinoa/Vite
frontend, served as a native executable on a self-hosted Oracle Cloud server (systemd
behind Caddy), with DNS-only records on Cloudflare. Also serves an MCP server
(Streamable HTTP) at `/mcp`.

## Development cycle (in this order — do not skip steps)

1. **Brainstorm / spec** — explore, verify assumptions in code, write spec to
   `docs/superpowers/specs/`. **This directory is local-only and gitignored on
   purpose** (decided 2026-08-04): specs and plans are process artifacts that rot
   once the code moves on — the code and git history are the record of what
   shipped. Never remove `docs/superpowers/` from `.gitignore` or commit files
   from it.
2. **User approval** of the spec. No implementation before this.
3. **Implementation plan** — `docs/superpowers/plans/` (local-only, same rule as
   the specs), then user approval.
4. **Branch** — never implement directly on `main`.
5. **TDD** — failing test first, then implementation. Run the full suite before
   every commit.
6. **Merge** — ask the user (merge local / PR / keep branch). Run the suite again
   on the merged result.
7. **Release** — only if the change carries a version bump: changelog entry →
   annotated tag → GitHub Release, per `docs/RELEASE.md`.
8. **Push** — `git push` publishes commits (and tags) only. **It does not deploy.**
   Publishing the **GitHub Release** is what deploys: it triggers
   `.github/workflows/native-binary.yml`.
9. **Deploy** — that workflow runs `docs/DEPLOY.md` for you: suite → native
   binary attached to the Release → manual approval (environment `production`)
   → `swapi-update` on the host over SSH → probes on `swapi.build` and
   `www.swapi.build`. Manual fallback: `docs/DEPLOY.md`, "Updating the version
   on the host".
10. **Post-deploy verification** — step 9 of `docs/RELEASE.md`.

## Non-negotiable facts

- **Nothing of this project runs on Vercel** (decided 2026-10-19). Production
  moved to the self-hosted origin that day, and the Vercel deploy pipeline and
  `Dockerfile.vercel` were removed. Do not reintroduce them. The one exception
  is the root `vercel.json` (`git.deploymentEnabled: false`): the repository is
  still connected to the old Vercel project through the Vercel GitHub app, and
  without that file every push starts a Vercel deployment (seen on PR #25).
  It stays until the repository is disconnected on the Vercel side;
  `scripts/vercel-git-off.test.sh` guards it in CI.
- **There is no commit-push auto-deploy.** Only a published Release deploys,
  through `native-binary.yml`, and its `deploy` job waits for manual approval
  (environment `production`). The job reaches the host as the `deploy` user,
  whose key can only run the forced command `swapi-deploy-gate`
  (`scripts/swapi-deploy-gate.sh`, tested by `scripts/swapi-deploy-gate.test.sh`
  in CI): it accepts exactly `update vX.Y.Z <sha256>` and calls
  `sudo swapi-update`. Never loosen that pattern or give the key a shell.
- **One measurement script, no trackers.** The only third-party script is the
  Cloudflare Web Analytics beacon (no cookie, no local storage), injected by the
  server (`WebAnalytics.java`, through `SeoRoutes`) only when
  `CF_WEB_ANALYTICS_TOKEN` is set on the host; the frontend itself loads none.
  Vercel Web Analytics and Speed Insights were removed (2026-10-19) and must not
  come back. `no-tracking.test.ts` fails on a `@vercel/` package, a `/_vercel/`
  script, a known tracker, `document.cookie` or a third-party script in
  `index.html`. Server-side usage counting (`com.eldermoraes.stats`) keeps only
  route templates, counts and server times (count, sum, sum of squares, min,
  max and a fixed-bucket histogram, never a single request's time) per UTC hour
  (14 days) and per UTC day (60 days), never anything about the client; the
  timing filter is the counting filter (`RouteCountingFilter`), so a route is
  timed exactly when it is counted. It is public at `/api/stats` and on the
  `/stats` page. The counters are snapshotted to the systemd state directory
  (`STATE_DIRECTORY`, `/var/lib/swapi`) when the host provides one, and stay in
  memory only otherwise; the snapshot reader accepts only templates
  `RouteNormalizer` produces. The privacy page describes both.
- **Self-hosted origin.** `native-binary.yml` attaches the linux/amd64 native
  binary to each published Release (UBI9 builder, glibc ≤ 2.35 checked); the
  host runbook, the deploy user and the rollback live in `docs/DEPLOY.md`,
  "Self-hosted origin on Oracle Cloud". Probes use
  `scripts/verify-deploy.sh origin <host>`.
- **Tests:** `cd swapi-app && ./mvnw test` (backend) and
  `cd swapi-app/src/main/webui && npm test` (Vitest, frontend). **CI runs both.**
  Quinoa's `vite build` bundles the frontend during `./mvnw package` but never
  tests it, so the separate Vitest step in `.github/workflows/ci.yml` is the only
  thing making the frontend guards a gate — without it they pass locally and
  block nothing. That step reads the Node version from `application.properties`;
  never pin a second copy in the workflow. Never run `mvn clean` while dev mode
  is running. Test HTTP port is 8081.
- **A version bump is a release.** Bump → `CHANGELOG.md` entry → annotated tag →
  GitHub Release → deploy, per `docs/RELEASE.md`. `ChangelogVersionTest` fails the
  suite if the pom version has no changelog section, and `OpenApiVersionTest` fails
  if `/openapi.json` stops advertising the pom version. Tags point at the **last**
  commit of a version line, never at the bump commit.
- **Successful GETs return HTTP 200; nonexistent ids return 404.** This holds for
  the site pages too: an unknown page or entity answers 404 (`SeoRoutes`), and
  Quinoa's SPA routing stays off.
- **Page metadata has one source**: `swapi-app/src/main/resources/seo/site.json`,
  read by `SeoMetadataRegistry` and by `seo.ts`/`routes.ts`; the client never
  rewrites the head on the first load. Record addresses become indexable one type
  per release (`indexable` in that file), each gated on Search Console coverage;
  `/api/*` answers `X-Robots-Tag: noindex`, never the HTML.
- **Server-rendered pages have one source**: the Qute templates in
  `swapi-app/src/main/resources/templates/ssr/` draw the `<main>` of every page
  marked `ssr` in `seo/site.json`; the client adopts it on the first load (never
  redraws it when the `data-ssr-*` marker matches the route) and fetches the
  document on later navigations (a first load without the marker never fetches
  or reloads). Templates never use `raw` or `safe`, and Java never builds a
  `RawString`. Code that renders an entity sets `RequestBaseUrl` and restores the
  previous value. `swapi.public-base-url` is the **API** base
  (`https://host/api`); the site root is derived from it.
- **Container tooling is `podman`** (`/opt/podman/bin`), not `docker`. The podman
  machine needs 8 GB for local native builds.
- **MCP serves stateful and stateless clients on the same `/mcp` endpoint.**
  Streamable HTTP only. `quarkus.mcp.server.http.streamable.auto-init=true`
  serves an unknown or missing `Mcp-Session-Id` with a throwaway session
  instead of 404ing. It was introduced because Vercel had no session affinity;
  on the single self-hosted instance it still keeps a client working after a
  restart drops its session. A genuine `initialize` still creates a
  real session that lives in one instance's heap until idle (default 30 min).
  `GET /mcp` answers 405 (no server→client stream) and `DELETE` answers 204.
  The legacy HTTP+SSE transport (`/mcp/sse`) is rejected on purpose. Never
  reintroduce session-affine state, and never depend on the legacy transport.
- **Public base URL is discovered per request** (REST via `UriInfo`, MCP via
  `HttpServerRequest`, honoring `X-Forwarded-*`). `swapi.public-base-url` is an
  optional override only — never reintroduce a hardcoded domain default. When
  set, it is the **API** base (`https://host/api`): MCP and the pages derive the
  site root from it by dropping the final `/api`; REST ignores it.
- **Frontend design system:** tokens, components and the new-page checklist live
  in `swapi-app/src/main/webui/DESIGN.md`. New pages/features must follow it —
  tokens only, gold means action and cyan means data, and every action is a
  `.sw-pill`. "Tokens only" is enforced, not trusted: `tokens.test.ts` fails on
  any hex, `rgb()` or `hsl()` literal in a stylesheet other than `tokens.css`
  (the starfield's white alphas are the one documented exception), and it globs
  the stylesheets so a new file is policed the day it lands.

## Ports

- Dev: `5432` (`quarkus.http.port=${PORT:5432}`); Vite dev server: `5173` (proxied).
- Tests: `8081`.

## Agent skills

### Issue tracker

Issues live in this repo's GitHub Issues (`gh` CLI). See `docs/agents/issue-tracker.md`.

### Triage labels

Default vocabulary: `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: `CONTEXT.md` at the repo root and ADRs in `docs/adr/`. See `docs/agents/domain.md`.
