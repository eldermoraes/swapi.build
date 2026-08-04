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
- `examples/quarkus-langchain4j`, the first client example: a Quarkus + LangChain4j
  assistant that answers natural-language questions about the Star Wars data, serving
  the same question two ways for comparison. `POST /ask/mcp` takes its tools from the
  remote MCP server — two properties and `@McpToolBox`, no tool code, no client code;
  `POST /ask/api` takes them from local `@Tool` beans over a typed REST client. Both
  share one system message, so the only variable is where the tools come from. Measured
  on one run, the MCP path costs *more* prompt tokens than the hand-written one (579
  against 371 on the first call): what it buys is broader coverage and no integration
  code. A standalone Maven project, outside the deployed container — the deploy still
  builds from `swapi-app/`.

### Changed

- The GitHub repository is connected to the Vercel project, which cleared the
  dashboard's "Missing Git Source". Automatic git deployments are disabled in the
  root `vercel.json` (`git.deploymentEnabled: false`): with `rootDirectory` unset,
  a git-triggered build runs from the repo root and fails. Deploys stay CLI-only.

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

[Unreleased]: https://github.com/eldermoraes/swapi.build/compare/v2.1.0...HEAD
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
