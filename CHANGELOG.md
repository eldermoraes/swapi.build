# Changelog

All notable changes to swapi.build are documented here.

The format is based on [Keep a Changelog 1.1.0](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Versions up to and including 2.1.0 were reconstructed retroactively from git history
in August 2026 — the project had no changelog and no tags before then. Each version's
entry covers the commits between its tag and the previous one, so a change appears
under the version whose line of development contains it. Where a version bump
*declared* something implemented in the previous line, the entry says so.

The version served in [`/openapi.json`](https://swapi.build/openapi.json) (`info.version`)
is inherited from `swapi-app/pom.xml`, so it always matches the latest released version.

## [Unreleased]

## [2.4.3] - 2026-09-09

### Fixed

- Upgrade `quarkus-mcp-server-http` and `quarkus-mcp-server-test` from
  `2.0.0.CR1` to `2.0.0` GA. Stateless discovery and tool-list responses now
  include the required `ttlMs: 0` and `cacheScope: "public"` fields, avoiding
  rejection by clients that validate the 2026-07-28 protocol schema.
  Existing stateful clients, foreign-session calls and tool behavior are preserved.

### Changed

- Add MCP cache-field regression tests and native deploy probes for discovery
  and tool listing. Document the cache evaluation from issue #1: the extension
  supports hints for discovery, lists and resource reads, not `tools/call`;
  tool results (including random results) remain uncached by the application.

## [2.4.2] - 2026-09-06

### Changed

- The SPA HTML of the real routes (`/`, `/docs`, `/docs/mcp`, `/about`, `/privacy`,
  `/terms`, `/resource/<type>`, `/resource/<type>/<id>`) is now edge-cacheable:
  `public, max-age=0, must-revalidate, s-maxage=31536000` (new single-source property
  `swapi.cache-control.html`, deliberately not the `/api` value — the browser must never
  keep HTML across a deploy). Before, the origin sent no `Cache-Control` and the Vercel
  edge injected `max-age=0`, so every page view on a cold PoP executed the function
  (`usage_anomaly` of 2026-09-03: six parallel cold starts for three page views).
  Explicit, anchored route list — unknown paths are still not cached, and `/api/*`,
  `/openapi.json`, `/assets/*` and `/mcp` are untouched. Restricted to `GET`/`HEAD`.
  Note the edge cache key includes the query string, so `/?utm_source=…` and friends
  are separate entries and still execute the function on a cold PoP — the saving
  applies to the bare URLs. Regression tests in `CacheHeadersTest`.
- `SeoRoutes` serves the SEO-injected document for every known SPA route regardless of
  the `Accept` header, so a cached route has exactly one body. Before, a non-HTML
  `Accept` took a different branch, and the first such request after a deploy could pin
  the wrong response at the edge for the whole deployment.
- Deploy probes (`scripts/verify-deploy.sh`, `docs/DEPLOY.md`): `Vary: Origin` on the
  SPA HTML in both modes, `MISS` → `HIT` on `/` and `/resource/planets` in production,
  and a cache-poisoning probe on `/` (now that the HTML is storable at the edge), with a
  Troubleshooting entry for "HTML always MISS". The poisoning probes on `/` and
  `/sitemap.xml` carry a unique query string — the edge key includes it, and without it
  they would read the entry the earlier SEO probes primed clean and pass unconditionally.

## [2.4.1] - 2026-08-15

### Added

- `X-Content-Type-Options: nosniff` on every response (defense in depth
  suggested in issue #12 follow-up: the /api 404s echo the id segment verbatim,
  so the header prevents reinterpretation if an error path ever returns a
  sniffable type), with regression tests pinning the header on 200 and 404.
- Positive-control regression test proving the `ApiNotFoundMapper` does not
  over-reach: `GET /api/people/1` still answers 200 `application/json` with the
  full record.

### Changed

- All remaining Portuguese content translated to English — code comments
  (backend, tests, frontend, `application.properties`), docs (the MCP server
  evaluation, renamed to `docs/2026-07-31-mcp-server-evaluation.md`), scripts
  and workflow comments, and test assertion messages. The project language is
  English.

## [2.4.0] - 2026-08-14

### Added

- SEO metadata for the public SPA pages (issue #8): request-derived canonical
  URLs, route-specific descriptions, Open Graph and Twitter Card tags, JSON-LD
  structured data, and a trademark-safe social preview image.
- Backend-served `robots.txt` and `sitemap.xml` routes so crawlers receive real
  text/XML responses instead of the SPA fallback.

### Changed

- Deploy verification now checks the SEO routes, social preview image and
  server-rendered metadata content types alongside the existing OpenAPI and
  Vercel Analytics probes.

## [2.3.1] - 2026-08-14

### Fixed

- Every `/api` 404 now honors the published contract — `text/plain` with a
  readable message (issue #12, reported by @Circadian-agent). Ids that don't
  parse as a Java `int` ("abc", "1.5", "2147483648") used to fail JAX-RS
  parameter conversion before the resource ran, so the framework's bare 404
  answered with no content type and an empty body, contradicting the
  `openapi.json` and README. A single `ApiNotFoundMapper`
  (`ExceptionMapper<NotFoundException>`, scoped to `/api` by the application
  path) now supplies the same per-resource message the resources already use
  ("No people found with id abc"), and unmapped `/api` routes get a generic
  "No resource found at /api/..." body instead of an empty one.

## [2.3.0] - 2026-08-10

### Added

- Redesigned web UI — the "Holonet Terminal" look voted for by the audience
  (issue #9): a full-width black shell with a framed wordmark header, a hero with
  a greeting line over a static CSS starfield, a live terminal on the home fold
  that queries the real API, and resource index rows in place of the old poster
  cards.
- A frontend design system rather than a restyle: `src/styles/tokens.css` is the
  single source of truth for every colour, face, radius, width and duration, and
  `src/ui/components.ts` + `src/styles/components.css` provide the shared
  components (pill, terminal, index rows, section label, panel, code, tabs,
  table).
- `swapi-app/src/main/webui/DESIGN.md` documenting the tokens, typography scale,
  colour roles, layout and motion rules, the component inventory with usage
  snippets, and a new-page checklist. `CLAUDE.md` points at it.
- A Vitest + jsdom toolchain for the frontend (`npm test`, 22 tests) covering the
  component markup, the token layer and the home page composition. CI runs it
  alongside the Maven suite.

### Changed

- All existing pages — docs, MCP guide, about, privacy, terms and the resource
  browser — migrated onto the design system. Page styles no longer contain raw
  colour; `src/style.css` is now a barrel over the token, base and component
  layers and shrank from 984 to ~570 lines.
- CI now runs the frontend suite. `./mvnw package` builds the frontend through
  Quinoa but never tests it, so the Vitest guards passed locally and gated
  nothing. The workflow reads the Node version from `application.properties`
  rather than pinning a second copy.
- Wide tables on `/docs` and `/docs/mcp` now scroll inside their own container,
  so the page never scrolls sideways on narrow viewports.

### Fixed

- The `NodeToolchainTest` version parser rejected `engines` ranges written with
  the optional `v` prefix (`>=v12.22.7`), which npm accepts. Adding any
  dependency that used that form — `jsdom` does, transitively — failed the suite
  with `unparseable version`. The prefix is now stripped for every form,
  including a bare `v18`, without loosening the check.
- The terminal's `aria-live` region was `display: none` while empty, so the
  first EXEC mutated a region that was absent from the accessibility tree —
  the announcement screen readers most often drop. It is now collapsed rather
  than removed.
- `docs/RELEASE.md` step 10.3 verified the MCP Registry publish through
  `/v0/servers?search=`, whose index lags a few minutes behind a publish. It kept
  reporting the previous version after a successful `mcp-publisher publish`,
  which reads as a failed publish when nothing is wrong — hit while releasing
  2.2.1. The step now queries `/v0/servers/build.swapi%2Fstar-wars/versions`,
  which is authoritative and lists every version with its `isLatest`.

## [2.2.1] - 2026-08-10

### Added

- `server.json` manifest publishing the MCP server to the Official MCP Registry
  as `build.swapi/star-wars` (remote, Streamable HTTP). `ServerJsonVersionTest`
  fails the suite if it drifts from the pom version.
- `NodeToolchainTest`: cross-checks the Node version Quinoa installs against every
  `engines.node` range in the committed `package-lock.json`. It exists because
  `mvnw test` runs with Quinoa disabled, so no test in the suite installs Node or
  runs vite — a Node floor too low for a bumped dependency used to pass the whole
  suite green and only fail during the release deploy.

### Changed

- CI now runs on pull requests and pushes to `main` (`.github/workflows/ci.yml`).
  Until now the suite only ran inside the release pipeline, which is tag-triggered,
  so day-to-day work had no automated gate at all.
- Both CI and the deploy gate run `mvnw package` instead of `mvnw test`: only
  `package` exercises `npm install`, `tsc` and `vite build`, which is what the
  deploy actually does. Costs about 13 seconds.
- Node installed by Quinoa raised from 20.18.1 to 22.23.2 (LTS Jod), required by
  the dependency upgrades below.

### Security

- Frontend development dependencies upgraded: `vite` 6 → 8, `eslint` 9 → 10
  (with `@eslint/js` and `typescript-eslint` aligned as peers), and `nanoid`
  3.3.16 → 3.3.18 via the lockfile ([GHSA-2v37-7h3g-55p8]). `npm audit` reports
  no remaining advisories. These are development dependencies only — none ships
  in the bundle or runs in production — but the ranges declared in `package.json`
  still resolved to versions carrying published advisories. `typescript` stays on
  5.7: `typescript-eslint` requires `<6.1.0`.

[GHSA-2v37-7h3g-55p8]: https://github.com/advisories/GHSA-2v37-7h3g-55p8

## [2.2.0] - 2026-08-05

### Added

- `CHANGELOG.md`, reconstructed retroactively down to 1.1.
- `docs/RELEASE.md`: the release process — version bump, changelog entry, tag,
  GitHub Release, deploy.
- Retroactive git tags `v1.1` … `v2.1.0` and matching GitHub Releases.
- Tests keeping the pom version, the changelog and the published OpenAPI version in sync.
- Vercel Web Analytics and Speed Insights on the site. Both features had been enabled
  on the project but collected nothing, because no script on the page ever reported a
  pageview; the SPA now injects them from `src/main.ts`. Their scripts are served by
  the Vercel edge at `/_vercel/*`, and the edge only routes those paths on deployments
  created after the features were enabled.
- Two client examples under `examples/java/`: `langchain4j-mcp-client`, which answers
  natural-language questions using tools discovered from the MCP server — two properties
  wire the client, one annotation hands its tools to the model, and the project defines no
  tools at all — and `quarkus-rest-client`, which calls the REST API from Java with a typed
  client. Each is two Java files, has one subject, and is deliberately free of tests and
  agent configuration; neither is part of the deployed container, which still builds from
  `swapi-app/`.

### Changed

- The GitHub repository is connected to the Vercel project, which cleared the
  dashboard's "Missing Git Source". Automatic git deployments are disabled in the
  root `vercel.json` (`git.deploymentEnabled: false`): with `rootDirectory` unset,
  a git-triggered build runs from the repo root and fails. Deploys stay CLI-only.
- MCP server extension `quarkus-mcp-server-http` upgraded from `2.0.0.Beta3` to
  `2.0.0.CR1` (`quarkus-mcp-server-test` aligned). Stabilization only: the
  underlying `mcp-server-api` reached 1.0.0 final; the Streamable HTTP options
  (`auto-init`, `lazy-sse-init`, `dns-rebinding-check.enabled`) became true
  runtime config, overridable per environment without a rebuild; the DNS
  rebinding Origin check now matches hosts exactly; responses carry
  `serverInfo` in `_meta`. The stateless `/mcp` behavior is unchanged.

## [2.1.0] - 2026-08-03

### Changed

- MCP serves stateful and stateless clients on the same `/mcp` endpoint. An unknown
  or missing `Mcp-Session-Id` is now served with a throwaway session instead of
  `404` (`quarkus.mcp.server.http.streamable.auto-init=true`), which is what makes
  stateful clients reliable on a platform without session affinity. `GET /mcp`
  answers `405`, `DELETE /mcp` answers `204`, and the legacy HTTP+SSE transport at
  `/mcp/sse` is rejected on purpose.
- The site presents swapi.build as both a REST API and an MCP server.

## [2.0.2] - 2026-08-03

### Added

- OpenAPI spec served at `/openapi.json`, and made the single source of API
  documentation: annotations on every resource and entity, an `info` block, and a
  guard that the advertised server URL is absolute.
- `/docs` page rendered from the OpenAPI spec, with a try-it control on every
  endpoint.
- Edge cache headers on `/api` responses (`CacheControlFilter`), with
  `Vary: Origin` so a CORS-echoed origin can never be served to another caller.

### Fixed

- Docs page: a navigation race that could render the wrong page, and schema
  derivation for response types that the spec describes indirectly.

### Security

- Patched high-severity advisories in development dependencies (`npm audit fix`).
  No runtime dependency was affected.

## [2.0.1] - 2026-08-02

### Changed

- Version alignment only: `swapi-app/pom.xml` and the frontend `package.json`
  carry the same number again. No functional change.

## [2.0.0] - 2026-08-02

### Added

- Privacy Policy and Terms of Use pages.

### Changed

- **Breaking, formally declared here.** The public contract is: successful `GET`s
  return `200` (the historic `202` quirk is retired), ids are the record ids from
  each entity's `url` field, and unknown or non-numeric ids return `404`. The
  change was implemented in the 1.9.1 line on 2026-08-01 — see that entry — and
  this major bump is where it was published as a contract change.
- The public base URL lives in a per-request context, and the entities became
  read-only records.
- README rewritten to match the project as it actually is: `mcp` package, legal
  pages, the 200/404 contract, Apache 2.0.

### Fixed

- MCP page accessibility and robustness: clipboard failures are reported instead
  of silently doing nothing, the client tabs answer `Home`/`End`, and copy
  feedback is announced through `aria-live`.

## [1.9.1] - 2026-08-02

### Changed

- Successful `GET`s return `200` instead of `202`. Published as [2.0.0].
- Ids are the record ids from each entity's `url` field, including
  `/api/films/{id}` and the MCP `sw_get` tool for `FILMS`, which no longer looks
  up films by episode id.
- `People.homeworld` emits an absolute URL like every other link.
- The public base URL is derived from the active request, honoring
  `X-Forwarded-Proto` and `X-Forwarded-Host`, with `swapi.public-base-url` demoted
  to an override. Fixes the first request freezing the base URL for the process
  lifetime.

### Fixed

- Nonexistent ids return `404` instead of a success status.
- Non-numeric ids return `404`, via `int` path params.
- The frontend shows the real HTTP status instead of a hardcoded `200`.

### Added

- `CLAUDE.md` (development cycle and non-negotiables) and `docs/DEPLOY.md`
  (canonical deploy runbook).

## [1.9.0] - 2026-08-01

### Added

- `/docs/mcp` page with per-client setup tabs, a nav link and a callout on the
  home page.
- README section for the MCP server: spec emphasis, tools table, per-client
  guides.

### Changed

- MCP tool hints corrected — `destructiveHint=false` on the read-only tools.

## [1.8.1] - 2026-07-31

### Added

- **MCP server** at `/mcp`, exposing the same data as generic read-only tools over
  the in-memory services, with stateless conformance and REST regression tests.
  The version bump that named it shipped as [1.9.0].

### Changed

- **Deployment moved from DigitalOcean to Vercel** (container function) behind
  Cloudflare DNS: multi-stage native Dockerfile, UBI9 micro runtime matched to the
  Mandrel ubi9 builder's glibc, HTTP port read from `PORT`, immutable cache headers
  on hashed assets, and native build heap parameterized via `NATIVE_XMX`.
- Upgraded to Quarkus 3.33.3 LTS, Java 25 LTS, Quinoa 2.8.3 and Mandrel jdk-25.

### Removed

- Legacy scaffold Dockerfiles, superseded by `Dockerfile.vercel`.

### Fixed

- Wrong ID references across the project.

## [1.8] - 2026-03-09

### Added

- Frontend SPA in TypeScript + Vite with History API routing, served by Quinoa.

### Changed

- Backend services and configuration adjusted for the Quinoa integration.

## [1.7] - 2025-08-05

### Added

- A separate `swapi-ui` module, then abandoned within the same version line.

### Changed

- Fixes to make the application work under native compilation, including moving
  the JSON datasets to `src/main/resources/data`.

### Removed

- The `swapi-ui` module and its Web Bundler assets.

## [1.3] - 2025-06-03

### Changed

- Native image build settings in `application.properties`.

## [1.2] - 2025-06-02

### Added

- Services and resources for the remaining domains.

### Removed

- The generated `GreetingResource` scaffolding and its tests.

<!-- Earlier history (1.0.0-SNAPSHOT, 2025-05-28/29): initial commit, the JSON
datasets for all six resources, and the first REST resources. Not tagged — a
snapshot version is not a release. -->

## [1.1] - 2025-05-29

### Added

- Native image builder properties.

### Fixed

- Id handling across all domains.

[Unreleased]: https://github.com/eldermoraes/swapi.build/compare/v2.4.3...HEAD
[2.4.3]: https://github.com/eldermoraes/swapi.build/compare/v2.4.2...v2.4.3
[2.4.2]: https://github.com/eldermoraes/swapi.build/compare/v2.4.1...v2.4.2
[2.4.1]: https://github.com/eldermoraes/swapi.build/compare/v2.4.0...v2.4.1
[2.4.0]: https://github.com/eldermoraes/swapi.build/compare/v2.3.1...v2.4.0
[2.3.1]: https://github.com/eldermoraes/swapi.build/compare/v2.3.0...v2.3.1
[2.3.0]: https://github.com/eldermoraes/swapi.build/compare/v2.2.1...v2.3.0
[2.2.1]: https://github.com/eldermoraes/swapi.build/compare/v2.2.0...v2.2.1
[2.2.0]: https://github.com/eldermoraes/swapi.build/compare/v2.1.0...v2.2.0
[2.1.0]: https://github.com/eldermoraes/swapi.build/compare/v2.0.2...v2.1.0
[2.0.2]: https://github.com/eldermoraes/swapi.build/compare/v2.0.1...v2.0.2
[2.0.1]: https://github.com/eldermoraes/swapi.build/compare/v2.0.0...v2.0.1
[2.0.0]: https://github.com/eldermoraes/swapi.build/compare/v1.9.1...v2.0.0
[1.9.1]: https://github.com/eldermoraes/swapi.build/compare/v1.9.0...v1.9.1
[1.9.0]: https://github.com/eldermoraes/swapi.build/compare/v1.8.1...v1.9.0
[1.8.1]: https://github.com/eldermoraes/swapi.build/compare/v1.8...v1.8.1
[1.8]: https://github.com/eldermoraes/swapi.build/compare/v1.7...v1.8
[1.7]: https://github.com/eldermoraes/swapi.build/compare/v1.3...v1.7
[1.3]: https://github.com/eldermoraes/swapi.build/compare/v1.2...v1.3
[1.2]: https://github.com/eldermoraes/swapi.build/compare/v1.1...v1.2
[1.1]: https://github.com/eldermoraes/swapi.build/releases/tag/v1.1
