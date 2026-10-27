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

## [2.11.0] - 2026-10-27

### Added

- The home, `/docs`, `/docs/mcp` and `/about` come with their content in the
  HTML, drawn on the server (Qute): a reader that does not run JavaScript
  (GPTBot, ClaudeBot, PerplexityBot, agents that `fetch`) gets the whole page,
  and the site adopts that content without drawing it again.
- JSON-LD: `WebSite` with `alternateName`, a `Person` (the author), a
  `BreadcrumbList` on every page but the home and on every listing, and a
  `Dataset` on the home.

### Changed

- The endpoint shown on `/docs/mcp` comes from the request host.
- `llms.txt` no longer says that `/docs` and `/docs/mcp` need JavaScript.
- A navigation inside the site to one of these four pages fetches the document,
  and the one to `/docs` no longer fetches `/openapi.json`. **The `pages` group
  of `/stats` now adds up full loads and in-site navigations** (the group's
  text on `/stats` says so).
- The live count line of the home reserves its space, so it no longer pushes
  the page down when it arrives; if the count does not come, the line stays
  empty in place.
- `WebSite` in the JSON-LD describes the site with the home description on
  every document.
- `/docs/mcp` no longer says the server "scales to zero" nor mentions a
  "different instance" (texts from the Vercel days), and its five guides are
  readable without JavaScript.
- The "Try it" buttons send nothing without JavaScript.
- Until the web fonts arrive, the text is drawn with local fallback faces
  scaled to their widths and metrics (`Arial` for Inter and Pathway Gothic One,
  `Courier New` for JetBrains Mono), so the font swap no longer moves the page
  (phone, 360 px: home 0.0000, `/docs` 0.0012, `/about` 0.0000, `/docs/mcp`
  0.0485). Nothing changes once the web fonts load.

### Known issues

- `npm run test:browser` (`scripts/verify-webmcp.cjs:27`) fails on current
  Chrome before any check runs: it passes the tool arguments to
  `document.modelContext.executeTool` as a JSON string, and Chrome now wants an
  object. It predates 2.11.0 (the same failure against 2.10.0); with an object
  the script passes against both versions.

## [2.10.0] - 2026-10-27

### Added

- The six film addresses (`/resource/films/1` to `/6`) are indexable: each has
  its own title ("A New Hope — Star Wars film JSON | swapi.build"), a
  description with the episode, the director and the release date,
  `index,follow`, its own canonical, and an entry in `sitemap.xml` (20 URLs,
  without `lastmod`). The other types follow one per release. Every record has
  a title with its name ("Luke Skywalker — Star Wars character JSON |
  swapi.build"); the types not yet released stay `noindex,follow` with the
  canonical of their list. A new record of a released type is indexable with
  no code change.
- Every response of `/api` and `/api/*` carries `X-Robots-Tag: noindex`: the
  page ranks, the JSON does not. Cache, CORS and MCP do not change, and the
  header never reaches the HTML, `robots.txt`, `sitemap.xml`, `llms.txt` or the
  assets.

### Changed

- The metadata of every page comes from one file, `seo/site.json`, read by the
  server and by the site; the app does not start if it is invalid or missing.
  The site no longer rewrites the head on the first load, and on a navigation
  to a record it writes the head once, after the record arrives, already with
  its name.
- Pages with a trailing slash redirect (301) to the address without it
  (`/docs/` → `/docs`), query kept. A visit like that counts twice in
  `/stats` (the 301 and the page it leads to).

## [2.9.1] - 2026-10-27

### Fixed

- Pages that do not exist answer 404 with a "Page not found" page and
  `noindex`. Before, they answered 200 with the home page (a soft 404 for
  search engines), and paths with a dot came back `index,follow`.
- Ids that do not exist, or are not in canonical form, in
  `/resource/<type>/<id>` answer 404 (for example `/resource/people/9999` and
  `/resource/people/%31`).
- Assets that do not exist answer 404 instead of the HTML of the home page.
- `/index.html` redirects (301) to `/`.
- Quinoa's SPA routing is off, and with it the double count in `/stats`: the
  count of `/`, the latency of `/`, `pages` and the total drop after this
  version, because scanners inflated them; `other` stays the same.
- `Vary: Origin` on `robots.txt`, `sitemap.xml` and `llms.txt`.

### Changed

- The HTML `s-maxage` goes from 31536000 to 300.

## [2.9.0] - 2026-10-26

### Added

- `/llms.txt` (llmstxt.org format), served by the app as `text/plain` with the
  SEO cache policy and URLs built from the request host. It carries the
  essentials itself, because `/docs` and `/docs/mcp` render only with
  JavaScript: the MCP connection line, the REST URL patterns, that lists and
  searches return a plain JSON array, and that ids are record ids, not episode
  numbers. It is counted as a route in the `seo` group of `/stats` and is no
  longer answered with the SPA shell.
- MCP `instructions` in the `initialize` and `server/discover` results: the
  scope (Episodes I-VI only), the `sw_search` → `sw_get` workflow and the id
  trap. No URL in them, so the domain is still discovered per request.
- `icon-512.png`, a 512×512 PNG of the favicon, and `icons` in `server.json`
  (the PNG first, the SVG favicon second) for the MCP Registry listing.
- An IndexNow key file in `public/`. The key is public by design: a search
  engine fetches it to check that whoever announces URLs controls the host.

### Changed

- MCP tool descriptions say when to use each tool against its siblings, what
  an empty result and an error look like, and what each argument accepts
  (`sw_search` is the starting point; an unknown id or resource is a tool
  error).
- Page titles and descriptions lead with what the page is and end with the
  brand (`... | swapi.build`); the home page and `/docs` carry the "SWAPI
  alternative" / "SWAPI" search term. The server and the client now agree on
  every route, including privacy and terms, which had drifted.
- The brand in `og:site_name`, the JSON-LD `name` and the Open Graph image alt
  is `swapi.build`.
- `server.json` has a new `title` and `description` (with "No API key").

## [2.8.2] - 2026-10-26

### Changed

- The deploy job probes `www.swapi.build` as a 308 redirect to the apex
  (`scripts/verify-deploy.sh redirect www.swapi.build swapi.build`): `GET /`, a
  path with a query, `/sitemap.xml` and `POST /mcp` must each answer 308 with
  `Location` exactly `https://swapi.build` + the same path and query.
  `scripts/verify-deploy-redirect.test.sh` covers the mode in CI (#30).

### Fixed

- A usage counters snapshot with a line this version does not know (a route or
  a record type a later version added) no longer discards the whole file. The
  line is skipped with one warning, which gives the count and the first line
  number but not the content, and every known counter and time is restored, so
  a rollback keeps the counts. A malformed known line, an unknown header or an
  oversized file is still ignored as corrupt, as before.

## [2.8.1] - 2026-10-20

### Added

- A "Stats" item in the header menu, right after "Documentation", linking to
  `/stats` and marked active there.
- A live line under the home page's actions: "N requests in the last 24 h · see
  stats", with the total of `GET /api/stats?period=24h` formatted in the site's
  locale and a link to `/stats`. It starts hidden and is removed if the request
  fails, takes longer than 3 seconds or returns no usable total, so the rest of
  the home never depends on it. The count is reused for 60 seconds (the
  endpoint's own `max-age`) across visits within the site, and the request
  carries its own signal, so a query in the home terminal does not cancel it.

## [2.8.0] - 2026-10-20

### Added

- Server response times in the usage stats. The counting filter now also times
  every request it counts, from the first route filter to the end of the
  response (server time, network excluded), under the same route template;
  static files, `OPTIONS` and `HEAD` are neither counted nor timed. Per route,
  per UTC hour (14 days) and per UTC day (60 days) the server keeps a count,
  the sum, the sum of squares, the minimum, the maximum and a fixed-bucket
  histogram (84 buckets, four per doubling from 0.05 ms to about 74 s), never
  the time of a single request. Mean, minimum, maximum and standard deviation
  are exact; p50, p95 and p99 come from the buckets, within about 9 %. One
  timing cell is about 0.5 KB, under 9 MB with every route busy every retained
  hour and day.
- `GET /api/stats` carries a `performance` block in every window: per route and
  per group (all five), requests, mean, min, max, standard deviation, p50, p95
  and p99 in milliseconds, and p50/p95 over all routes per hour or per day. All
  2.7.0 fields are unchanged.
- `/stats` page: a Performance section with the same filters and grouping, a
  table per route or per group in milliseconds, a p50/p95 line chart over time
  (one inline SVG, no chart library) with its numbers, and the note "server
  time, network excluded".

### Changed

- Snapshot file format 2 (`swapi-route-stats 2`) with `th`/`td` lines for the
  server times; a format 1 file written by 2.7.0 still loads, with its counts
  and no times. The size cap for the file went from 4 MB to 16 MB.
- Privacy policy: the server's response times, added up per route, are kept and
  published with the counts.
- `verify-deploy.sh` checks the `performance` block of `/api/stats?period=24h`.

## [2.7.0] - 2026-10-19

### Added

- `/stats` page: a small analytics view over the anonymous counters, in the
  site's design. Filters for the last 24 hours, 7 days or 30 days, grouping by
  route or by group (REST API, MCP, pages and docs, SEO, other), a trend chart
  per hour (24 hours) or per day, totals with the change against the window
  before, and a ranking of the routes or groups. CSS bars, no chart library;
  the filters are kept in the query string. Linked from the footer and listed
  in the sitemap.
- `GET /api/stats?period=24h|7d|30d` and `&granularity=hour|day` (7 days accepts
  both): series per bucket with totals per group, totals per group, the group of
  each route and the previous window. Without parameters it answers the last 7
  days per day, keeping every field of 2.6.0. Invalid values answer 400 with a
  fixed message.
- The counters survive restarts: when systemd gives the app a state directory
  (`StateDirectory=swapi`, `STATE_DIRECTORY`), they are written to
  `/var/lib/swapi/route-stats.txt` every 10 minutes when they changed and on
  shutdown (temporary file and rename), and restored at startup. A corrupt file
  is ignored with a warning; without the directory the app keeps them in memory
  only and says so once in the log. `docs/DEPLOY.md` has the drop-in.

### Changed

- Counters are kept per UTC hour for 14 days and per UTC day for 60 days, up
  from 7 days per day, so every window can be compared with the one before.
- Privacy policy: the counts are kept per hour and per day, for 14 and 60 days,
  and copied to a small file on the server that holds only routes, times and
  counts.
- `scripts/verify-deploy.sh` also probes the 24-hour and 30-day windows, a 400
  for an invalid period and the `/stats` page title.

### Fixed

- Links from the site to server paths (`/api/...`, `/mcp`, `/openapi.json`,
  `robots.txt`, `sitemap.xml`) are no longer taken over by the SPA router: the
  `/api/stats` link on the privacy page rendered the home page.

## [2.6.0] - 2026-10-19

### Added

- `GET /api/stats`: request counts per route template (for example
  `/api/people/{id}`, `/mcp`) and per UTC day for the last seven days. Counted in
  memory only, with no IP address, no User-Agent and no other client data; the
  counters restart from zero on a server restart (`countingSince`). Once a day,
  at 00:00 UTC, and on shutdown, the app writes one `route-stats` summary line to
  the journal. `scripts/verify-deploy.sh` probes the endpoint.
- Cloudflare Web Analytics beacon (no cookie, no local storage), injected by the
  server into the site's HTML only when `CF_WEB_ANALYTICS_TOKEN` is set on the
  host. Read at runtime: turning it on or off needs a restart, not a release
  (`docs/DEPLOY.md`).

### Changed

- Privacy policy: page visits are measured by Cloudflare Web Analytics, without
  cookies or personal data, and the server counts requests per route and per day,
  without IP addresses.
- `no-tracking.test.ts` now also fails on known trackers, `document.cookie` and
  any third-party script in `index.html`; the Cloudflare beacon is the one
  exception, and it is never part of the frontend.

- Deploy: publishing a GitHub Release now deploys to the self-hosted origin on
  Oracle Cloud. `native-binary.yml` runs the full suite, builds and attaches the
  native binary, waits for approval (environment `production`), then logs in as
  the restricted `deploy` user, whose key can only run `swapi-update <tag> <sha256>`
  through the forced command `scripts/swapi-deploy-gate.sh`, and runs the probes
  on `swapi.build` and `www.swapi.build`. The gate is covered by
  `scripts/swapi-deploy-gate.test.sh`, run in CI.
- Privacy policy: the paragraph about requests still served by the previous host
  is gone; the move is finished.

### Removed

- Everything that published to Vercel: the `deploy.yml` workflow (preview,
  production), `swapi-app/Dockerfile.vercel`, `swapi-app/.vercelignore`
  (the root `vercel.json` with `git.deploymentEnabled: false` stays while the
  repository is still connected to the Vercel project, guarded by
  `scripts/vercel-git-off.test.sh`), and the Vercel-only `preview` and `prod` modes of
  `scripts/verify-deploy.sh`. Rollback is now the previous version on the host.

### Fixed

- Release runbook: the MCP Registry key is derived by `scripts/registry-key.sh`,
  which rejects anything but 64 hex characters. The previous inline recipe kept the
  `pub:` label, produced 67 characters and broke `mcp-publisher login`. Covered by
  `scripts/registry-key.test.sh`, now run in CI.

## [2.5.2] - 2026-10-19

### Changed

- Infrastructure: each published GitHub Release gets the linux/amd64 native
  binary and its SHA-256 (`native-binary.yml`, UBI9 builder,
  `march=compatibility`, glibc ≤ 2.35 checked), preparing the move to a
  self-hosted origin on Oracle Cloud. Runbook, cutover and rollback in
  `docs/DEPLOY.md`.
- Privacy policy: hosting on a maintainer-operated Oracle Cloud server, no
  access log, technical logs kept up to 30 days.
- `scripts/verify-deploy.sh` gains an `origin` mode for the self-hosted origin;
  the Vercel Analytics probes are gone.

### Removed

- Vercel Web Analytics and Speed Insights from the SPA.
- The per-request `Thread name` INFO log of the `/random` endpoints.

## [2.5.1] - 2026-09-09

### Fixed

- Restore readable WebMCP guide tables with spaced columns on desktop and stacked tool entries on mobile.
- Separate resource, record ID, search and response notes, and improve guide paragraph and list spacing.

## [2.5.0] - 2026-09-09

### Added

- Experimental browser WebMCP tools (`sw_list`, `sw_get`, `sw_search`,
  `sw_random`) that share the Star Wars explorer with the user, with complete
  resource results, explicit page actions and a setup guide at `/docs/webmcp`.
- Browser tool discovery is optional: unsupported browsers retain the existing
  website, REST API and remote MCP server.

### Fixed

- Isolate resource request cancellation and prevent superseded agent responses
  from overwriting human navigation or input. Random selections are displayed
  without making a second request.

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

[Unreleased]: https://github.com/eldermoraes/swapi.build/compare/v2.11.0...HEAD
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

[2.5.0]: https://github.com/eldermoraes/swapi.build/compare/v2.4.3...v2.5.0

[2.5.1]: https://github.com/eldermoraes/swapi.build/compare/v2.5.0...v2.5.1

[2.5.2]: https://github.com/eldermoraes/swapi.build/compare/v2.5.1...v2.5.2

[2.6.0]: https://github.com/eldermoraes/swapi.build/compare/v2.5.2...v2.6.0

[2.7.0]: https://github.com/eldermoraes/swapi.build/compare/v2.6.0...v2.7.0

[2.8.0]: https://github.com/eldermoraes/swapi.build/compare/v2.7.0...v2.8.0

[2.8.1]: https://github.com/eldermoraes/swapi.build/compare/v2.8.0...v2.8.1

[2.8.2]: https://github.com/eldermoraes/swapi.build/compare/v2.8.1...v2.8.2

[2.9.0]: https://github.com/eldermoraes/swapi.build/compare/v2.8.2...v2.9.0

[2.9.1]: https://github.com/eldermoraes/swapi.build/compare/v2.9.0...v2.9.1

[2.10.0]: https://github.com/eldermoraes/swapi.build/compare/v2.9.1...v2.10.0

[2.11.0]: https://github.com/eldermoraes/swapi.build/compare/v2.10.0...v2.11.0
