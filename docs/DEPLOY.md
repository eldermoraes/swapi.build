# Deploy runbook

Canonical deploy procedure. Two hosts, one at a time:

- **Vercel** (production until the DNS cutover): the project is
  `algorium/swapi-build`, `framework: container`, built from
  `swapi-app/Dockerfile.vercel`. Sections 1 to 4 below.
- **Self-hosted origin on Oracle Cloud** (the migration target, and production
  after the cutover): the native binary from the GitHub Release, run by systemd
  behind Caddy. See [Self-hosted origin on Oracle Cloud](#self-hosted-origin-on-oracle-cloud).

`scripts/verify-deploy.sh <preview|prod|origin> <host>` runs the probes for
either host: `preview` and `prod` against Vercel, `origin` against the
self-hosted origin (no Vercel-only checks).

## Automated path (default since 2026-08-05)

Pushing a release tag (`v*`) — or manually dispatching **Deploy** in the Actions
tab — runs `.github/workflows/deploy.yml`: full suite → preview deploy from
`swapi-app/` → `scripts/verify-deploy.sh preview <url>` (all the probes below,
including the stateful burst) → waits for **manual approval** (environment
`production`) → production deploy → `scripts/verify-deploy.sh prod swapi.build`.
CI authenticates against SSO-protected previews with the project's Protection
Bypass for Automation secret (`x-vercel-protection-bypass` header) instead of a
share link. Commit pushes never trigger it.

The manual runbook below remains canonical: the workflow is an executor of this
document, and this document wins on divergence. Use the manual path when the
workflow is unavailable, when debugging, or for a deploy without a tag.
`scripts/verify-deploy.sh <preview|prod> <host>` is the canonical way to run the
Vercel probes in either path; the sections below explain what each probe proves.

## Prerequisites

- `.env` at the repo root (gitignored) with `VERCEL_API_TOKEN` and `VERCEL_TEAM_ID`.
- `swapi-app/.vercel/project.json` exists (project already linked).
- Full test suite green: `cd swapi-app && ./mvnw test`.
- If this deploy carries a version bump, `docs/RELEASE.md` steps 1–7 are done
  (changelog entry, annotated tag and GitHub Release exist).

## 1. Preview deploy

**Always from `swapi-app/` — never from the repo root** (root fails with
`Expected VCR image registry vcr.vercel.com: <detect>`).

```bash
cd swapi-app && set -a; source ../.env; set +a; npx vercel deploy --token "$VERCEL_API_TOKEN" --scope algorium
```

Native-image build takes ~10–25 min. Note the `Preview` URL in the output.

## 2. Verify the preview

`*.vercel.app` URLs are SSO-protected. Create a bypass link (Vercel MCP tool
`get_access_to_vercel_url`, or the dashboard "Share" button), then load it once
with a cookie jar:

```bash
curl -sL -c jar.txt "<shareable-url-with-_vercel_share>" -o /dev/null
```

**REST** — expect HTTP 200 and embedded URLs pointing at the preview host with
`https` (proves per-request base-url discovery through `X-Forwarded-*`):

```bash
curl -s -b jar.txt "https://<preview-host>/api/people/1"
```

**OpenAPI** — the spec must be served by the backend, not intercepted by Quinoa:

```bash
curl -s -o /dev/null -w '%{http_code} %{content_type}\n' -H 'Accept: text/html' \
  -b jar.txt https://<preview-host>/openapi.json
# expected: 200 application/json (Quinoa must not swallow the route)
```

**SEO routes / social preview** — `robots.txt`, `sitemap.xml` and the OG image
must also bypass the SPA fallback and carry request-derived absolute URLs:

```bash
curl -s -o /dev/null -w 'robots: %{http_code} %{content_type}\n' \
  -H 'Accept: text/html' -b jar.txt https://<preview-host>/robots.txt
# expected: 200 text/plain (text/html = the SPA swallowed it)
curl -s -o /dev/null -w 'sitemap: %{http_code} %{content_type}\n' \
  -H 'Accept: text/html' -b jar.txt https://<preview-host>/sitemap.xml
# expected: 200 application/xml or text/xml (text/html = the SPA swallowed it)
curl -s -b jar.txt https://<preview-host>/robots.txt \
  | grep -c 'Sitemap: https://<preview-host>/sitemap.xml'
# expected: 1
curl -s -b jar.txt https://<preview-host>/sitemap.xml \
  | grep -c '<loc>https://<preview-host>/docs</loc>'
# expected: 1
curl -s -o /dev/null -w 'og image: %{http_code} %{content_type}\n' \
  -b jar.txt https://<preview-host>/og-image.png
# expected: 200 image/png
curl -s -b jar.txt https://<preview-host>/ \
  | grep -E 'rel="canonical"|property="og:url"|name="twitter:card"'
# expected: all three lines appear with absolute URLs on the preview host
```

**Analytics / Speed Insights** — removed from the SPA in preparation for the
self-hosted origin (Vercel's collection scripts only exist behind its edge, and
the privacy page promises no trackers). There is nothing to probe;
`no-tracking.test.ts` keeps the beacons from coming back.

**Cold start** — after any deploy that changes Quarkus extensions (e.g.
`smallrye-openapi`), measure and record the cold start of the first request so the
extension's impact stays tracked:

```bash
curl -s -o /dev/null -w 'cold start: %{time_total}s\n' -b jar.txt "https://<preview-host>/api/people/1"
```

**MCP** — stateless probe wire format (all headers and `_meta` keys are required;
`Mcp-Name` must match the tool name):

```bash
curl -s -b jar.txt -X POST "https://<preview-host>/mcp" \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -H "Mcp-Method: tools/call" \
  -H "Mcp-Name: sw_get" \
  -H "MCP-Protocol-Version: 2026-07-28" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"sw_get","arguments":{"resource":"PEOPLE","id":1},"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientInfo":{"name":"probe","version":"1.0"},"io.modelcontextprotocol/clientCapabilities":{}}}}'
```

Expect `isError: false` and embedded URLs on the preview host.

**MCP discovery / tool-list cache fields** — since 2.4.3, the automated probes
also send stateless `server/discover` and `tools/list` requests. Use the same
protocol headers and `_meta` as above, set `Mcp-Method` and JSON `method` to the
method being checked, and omit `Mcp-Name`, `name` and `arguments`.
Both responses must have flat `result.ttlMs: 0` and
`result.cacheScope: "public"`, with no nested `result.cacheControl`.
These fields are required by the 2026-07-28 protocol even when no positive
cache lifetime is configured. CR1 omitted them; checking the native preview
and production response guards against serialization regressions.
This does not enable caching of `tools/call` results.

**MCP stateful** — the probe that catches the instance-affinity bug. Twelve
concurrent calls on one session must all return 200; before `auto-init` this
returned 33–58% `404`:

```bash
SID=$(curl -s -D - -o /dev/null -b jar.txt -X POST "https://<preview-host>/mcp" \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"probe","version":"1.0"}}}' \
  | tr -d '\r' | awk -F': ' 'tolower($1)=="mcp-session-id"{print $2}')
if [ -z "$SID" ]; then
  echo "FAIL: no session id issued - do not read the burst below as a pass"
else
  for i in $(seq 1 12); do
    ( curl -s -o /dev/null -w '%{http_code}\n' -b jar.txt -X POST "https://<preview-host>/mcp" \
        -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
        -H "Mcp-Session-Id: $SID" \
        -d '{"jsonrpc":"2.0","id":'$i',"method":"tools/list"}' ) &
  done | sort | uniq -c
fi
# expected: 12 200
```

The empty-SID guard matters: with `auto-init` on, a request carrying an empty or
missing session id also returns 200, so a failed extraction would otherwise print
`12 200` and look like a pass without ever having sent a real session id. The
`if`/`else` skips the burst entirely on failure rather than relying on `set -e`
or `exit`, which would be wrong in a runbook block an operator pastes into their
own interactive shell.

Run the concurrent burst against the **preview only** — bursts are what trip the
Vercel IP mitigation documented in Troubleshooting.

**MCP foreign session** — a session id that never existed must still be served:

```bash
curl -s -o /dev/null -w 'foreign session: %{http_code}\n' -b jar.txt \
  -X POST "https://<preview-host>/mcp" \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -H 'Mcp-Session-Id: never-existed' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
# expected: 200
```

**MCP edges:**

```bash
curl -s -o /dev/null -w 'GET /mcp: %{http_code}\n' -b jar.txt "https://<preview-host>/mcp"
# expected: 405
curl -s -o /dev/null -w 'GET /mcp/sse: %{http_code}\n' -b jar.txt "https://<preview-host>/mcp/sse"
# expected: 404
curl -s -b jar.txt -X POST "https://<preview-host>/mcp/messages/never-existed" \
  -H 'Content-Type: application/json' -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
# expected: 404 with a JSON body mentioning /mcp
```

**Cache poisoning via `Origin`** — CORS echoes the request `Origin`, so every
edge-cacheable response must carry `Vary: Origin` or the edge serves one
origin's header to another:

```bash
curl -sI -b jar.txt -H 'Origin: https://evil.example' "https://<preview-host>/api/people/1" | grep -i '^vary'
# must contain Origin
curl -s -o /dev/null -b jar.txt -w '[%header{access-control-allow-origin}]\n' "https://<preview-host>/api/people/1"
# without Origin on the request: must come back []
```

If `Vary` is missing, purge the cache before going further.

## 3. Production deploy

```bash
cd swapi-app && set -a; source ../.env; set +a; npx vercel deploy --prod --token "$VERCEL_API_TOKEN" --scope algorium
```

Do **not** use `vercel promote` on a preview deployment: it rebuilds anyway (preview
and production environments differ) and asks an interactive question that fails
without a tty.

## 4. Post-deploy verification

```bash
curl -s -o /dev/null -w 'status: %{http_code}\n' https://swapi.build/api/people/1
curl -s https://swapi.build/api/people/1 | grep -c 'https://swapi.build/api/people/1'
curl -s -o /dev/null -w '%{http_code} %{content_type}\n' -H 'Accept: text/html' https://swapi.build/openapi.json
# expected: 200 application/json (Quinoa must not swallow the route)
curl -s -o /dev/null -w 'robots: %{http_code} %{content_type}\n' -H 'Accept: text/html' https://swapi.build/robots.txt
curl -s -o /dev/null -w 'sitemap: %{http_code} %{content_type}\n' -H 'Accept: text/html' https://swapi.build/sitemap.xml
curl -s https://swapi.build/robots.txt | grep -c 'Sitemap: https://swapi.build/sitemap.xml'
curl -s https://swapi.build/sitemap.xml | grep -c '<loc>https://swapi.build/docs</loc>'
curl -s -o /dev/null -w 'og image: %{http_code} %{content_type}\n' https://swapi.build/og-image.png
curl -s https://swapi.build/ | grep -E 'rel="canonical"|property="og:url"|name="twitter:card"'
# expected: robots 200 text/plain; sitemap 200 application/xml or text/xml; counts 1; og image 200 image/png
```

Expect `status: 200` and `1` (embedded URLs on `https://swapi.build`, scheme `https`),
and `200 application/json` for the spec.
Then run the MCP probe from step 2 against `https://swapi.build/mcp` (no cookie jar
needed — the custom domain has no SSO). Re-run the foreign-session, edges, and
`Origin`/`Vary` cache-poisoning probes against `https://swapi.build` too, without the
concurrent burst.

**Edge cache** — the response reaching the client shows `cache-control: public, max-age=300`:
the CDN consumes and strips `s-maxage`/`stale-while-revalidate` before forwarding. The proof
the edge stored it is `x-vercel-cache` going `MISS` → `HIT` on a second request to the same
path. Note the cache key includes the HTTP method, so a `GET` and a `HEAD` on the same path
are separate entries — don't read the first `HEAD` MISS as a failure.

```bash
curl -sI https://swapi.build/api/people/1 | grep -i 'x-vercel-cache'   # MISS
curl -sI https://swapi.build/api/people/1 | grep -i 'x-vercel-cache'   # HIT
curl -sI https://swapi.build/api/people/random | grep -i 'x-vercel-cache'  # MISS, always
```

The SPA HTML of the real routes (`/`, `/docs`, `/docs/mcp`, `/docs/webmcp`, `/about`, `/privacy`, `/terms`,
`/resource/<type>`, `/resource/<type>/<id>`) is edge-cached too since 2.4.2. The client
sees `public, max-age=0, must-revalidate` — indistinguishable from the pre-2.4.2 default
the edge injected — so the **only** proof is `MISS` → `HIT`. Unknown paths are not cached
on purpose (unique by definition; the filter is an explicit route list).

```bash
curl -sI -H 'Accept: text/html' https://swapi.build/ | grep -i 'x-vercel-cache'                  # MISS
curl -sI -H 'Accept: text/html' https://swapi.build/ | grep -i 'x-vercel-cache'                  # HIT
curl -sI -H 'Accept: text/html' https://swapi.build/resource/planets | grep -i 'x-vercel-cache'  # MISS
curl -sI -H 'Accept: text/html' https://swapi.build/resource/planets | grep -i 'x-vercel-cache'  # HIT
curl -sI -H 'Accept: text/html' https://swapi.build/does-not-exist | grep -i 'x-vercel-cache'    # MISS, always
```

**Cache poisoning probe** — responses embed absolute URLs built from the per-request host,
and `X-Forwarded-Host` is *not* part of the cache key. Vercel overwrites the header (verified
2026-08-03: plain spoof, RFC 7239 `Forwarded`, and a duplicated header were all ignored), so
this is a regression check:

```bash
curl -s -H 'X-Forwarded-Host: evil.example' https://swapi.build/api/people/3 | grep -c evil.example  # 0
curl -s https://swapi.build/api/people/3 | grep -c evil.example                                      # 0
curl -s -H 'X-Forwarded-Host: evil.example' "https://swapi.build/?poison=$$" | grep -c evil.example   # 0
curl -s "https://swapi.build/?poison=$$" | grep -c evil.example                                       # 0
```

The `?poison=$$` on the SPA HTML pair is not decoration: the edge cache key **includes the
query string**, and the SEO probes earlier in this runbook already primed the clean entry for
the bare `/` (and for `/sitemap.xml`). Without a unique key the spoofed request reads that
primed `HIT` and the probe passes unconditionally — it would prove nothing. The same query is
applied to the `/sitemap.xml` pair in `scripts/verify-deploy.sh` for exactly this reason. The
`/` pair matters more since 2.4.2: the SPA HTML embeds `canonical`, `og:url` and `og:image`
built from the request host and is now storable at the edge for a year.

If any is non-zero, purge the cache immediately and add `Vary: X-Forwarded-Host` to the
cacheable response before redeploying.

### Questions settled on the 2026-08-03 production deploy

1. **Per-request cost of `auto-init`:** a warm `tools/call` measured 0.19–0.36s over five
   runs (~0.26s median), in line with the historical warm-instance figure. Serving every
   call with a session costs nothing measurable at this scale.
2. **`Vary: Origin` did not fragment the `/api/*` cache:** six distinct paths all went
   `MISS` → `HIT` on the second request. Varying by `Origin` is safe here because clients
   that send no `Origin` — curl, servers, MCP — share one entry.
3. **The edge *does* cache a response carrying `max-age` without `s-maxage`:** verified on
   `/assets/index-*.js` (`cache-control: public, max-age=31536000, immutable`, no
   `s-maxage`), which went `MISS` → `HIT`. So `Vary: Origin` on the SPA's static responses
   is load-bearing, not decorative — without it the edge could serve one origin's
   `Access-Control-Allow-Origin` to another. A local container could not answer this.

Note: `functionDefaultTimeout` is **60s** since 2026-08-03, as the spec had decided. It is a
project setting (`resourceConfig`), applied by `PATCH` without a redeploy — see Troubleshooting.

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `Expected VCR image registry vcr.vercel.com: <detect>` | Deploy ran from the repo root. Re-run from `swapi-app/`. |
| The Deploy workflow sits "Waiting" after the preview job | Expected: the `production` environment requires manual approval. Review the preview probe output in the job summary, then approve in the Actions UI. |
| CI preview probes all fail with an SSO/auth page | The Protection Bypass secret was regenerated on Vercel. Update the `VERCEL_AUTOMATION_BYPASS_SECRET` repo secret (`gh secret set`). |
| A `git push` triggered a Vercel build | The GitHub repo has been linked to the project since 2026-08-03. Auto-deploy is off via `git.deploymentEnabled: false` in the **root** `vercel.json` — if a build fired, that file was removed or the setting was overridden in the dashboard. Note the build would fail anyway: `rootDirectory` is `null`, so it builds from the repo root (row above). |
| Vercel Analytics dashboard shows zero visitors | Expected since the SPA stopped injecting the Web Analytics and Speed Insights scripts (self-hosted origin preparation). Not a fault. |
| `vercel promote` hangs or `User force closed the prompt` | Interactive confirmation without tty. Use `vercel deploy --prod` instead. |
| 403 on `*.vercel.app` URLs | Team SSO protection. Use a `_vercel_share` bypass link + cookie jar. |
| 404 `Mcp session not found` on a stateful MCP call | **Not a cold start.** Sessions live in one instance's heap and Vercel has no session affinity, so the call landed on a different replica. Fixed by `quarkus.mcp.server.http.streamable.auto-init=true`; if it reappears, that property is off in the running deployment. |
| Quinoa build fails on Vercel with local artifacts | `swapi-app/.vercelignore` must exclude `dist/` and `target/`. |
| 202 responses from the API | Legacy quirk retired 2026-08-01 — current builds return 200; a 202 means an old deployment is live. |
| 403 on every path of `swapi.build`, answered in ~0.07s with `x-vercel-mitigated: deny` | Automatic mitigation blocked the source IP (typical after a load test). The app is **not** down: check with `curl https://swapi-build.vercel.app/api/people/1` (200) or hit the public domain from another IP. It expires on its own. **Do not redeploy.** (The team is on the Pro plan since 2026-08-07, not Hobby.) |
| `x-vercel-cache: MISS` always, on a path that isn't `/random` | The `CacheControlFilter` isn't applying the header. Check `curl -sI <host>/api/people/1 \| grep -i cache-control` — it must contain `max-age=300` (the edge strips `s-maxage` before the client sees it). Remember `GET` and `HEAD` are separate cache entries. |
| A wrong response "frozen" at the edge (1-year TTL) | Purge: dashboard → project → **CDN** → **Caches** → **Purge**, `*` for the whole project. Prefer **Invalidate** over **Delete** (Delete revalidates in the foreground and risks a cache stampede). A new deployment also clears it, since the cache key includes the deployment URL. |
| First request after a deploy takes ~11s | Container cold start (image pull + boot). Measured 10.9s on 2026-08-03. Edge cache makes the function idle more, so cold starts now hit the uncached `/random` endpoints more often than before. The first external request after the second 2026-08-03 deploy took only 1.67s, but that is **not** a counter-measurement: nothing rules out a platform health check having booted the instance first. Treat ~11s as the number to budget against. |
| `Error: fetch failed` / `"reason": "deploy_failed"` from the CLI mid-build | The CLI lost its log stream — **the remote build usually keeps running**. Do not paste the `retry deploy` command the CLI suggests: that starts a second native build in parallel. Get the deployment id from the CLI output (or `list_deployments`) and poll `GET /v13/deployments/<id>` until `readyState` leaves `BUILDING`. Seen on 2026-08-03: the CLI errored out, the build finished `READY` normally, and the preview verified clean. |
| `504` / `FUNCTION_INVOCATION_TIMEOUT` on the first request after a deploy | The cold start (~11s measured) exceeded `functionDefaultTimeout`, now **60s** since 2026-08-03 — the earlier 15s left only ~4s of margin. Read the current value with `GET /v9/projects/swapi-build`; change it with `PATCH` and the same `resourceConfig` shape (`{"fluid":true,"functionDefaultRegions":["iad1"],"functionDefaultTimeout":60}`). It is a project setting: it takes effect immediately, without a redeploy. |
| SPA HTML (`/`, `/resource/*`) always `x-vercel-cache: MISS` | The `quarkus.http.filter.spa` block in `application.properties` is missing or its `matches` regex no longer covers the route (the list is explicit and anchored — a new SPA route must be added there and to `CacheHeadersTest.SPA_ROUTES`). Without it the edge injects `public, max-age=0, must-revalidate` and every cold-PoP page view executes the function (usage_anomaly of 2026-09-03). Note the client never sees `s-maxage` (the CDN strips it): only MISS → HIT proves the cache. If the header is present at the origin and the edge still refuses to store, drop `must-revalidate` from `swapi.cache-control.html` and redeploy. |


## WebMCP verification (2.5.0+)

Confirm `/docs/webmcp` returns HTML with title `WebMCP in the browser - SWAPI`
and appears in the sitemap. The automated probes check both. Browser tool
execution must also be verified with WebMCP enabled: discover `sw_list`,
`sw_get`, `sw_search`, `sw_random`; list PEOPLE, search Luke, open FILMS ID 1,
and show a random starship. Confirm the displayed record matches the returned
record and manual navigation supersedes pending work. Test the unsupported
browser path too. HTTP probes alone do not prove browser API compatibility.

The initial implementation was validated with Chrome 152, WebMCP testing
enabled, against the GraalVM native application. Chrome 152 can omit the
execution callback's cancellation options; the adapter accepts that version.
The browser API remains experimental, so record the tested browser version
when validating future changes.

To repeat browser verification against a running native binary, install the
frontend dev dependencies, use an installed compatible Google Chrome, and run:

```bash
cd swapi-app/src/main/webui
SWAPI_TEST_URL=http://127.0.0.1:5545 npm run test:browser
```

The script launches isolated Chrome sessions with WebMCP enabled and disabled;
it does not use your personal browser profile or a model API key. It covers
all six resource lists, the four tools, cancellation, history, manual search,
and a 360px viewport. It must run against a trusted test instance: the script
performs ordinary read-only queries. The local native instance needs to be
started on the corresponding port beforehand.

## Self-hosted origin on Oracle Cloud

The migration target, and production after the DNS cutover. Everything needed
to rebuild the host from scratch is in this section, so a reinstall never
depends on anyone's memory.

**Shape.** One Always Free micro VM (x86_64, 1 GB RAM, Ubuntu 22.04, glibc
2.35) that already runs a Discord bot (`invite-role-bot.service`). The bot is
the tenant that must never be starved: every limit below exists to protect it.

```
internet ──► Caddy :80/:443 (TLS, Let's Encrypt) ──► swapi 127.0.0.1:5432 (native binary, systemd)
```

- swapi listens on loopback only; only Caddy talks to the internet.
- DNS stays on Cloudflare, **DNS-only (grey cloud)**, no proxy, no tunnel, no
  firewall rules ported from Vercel.
- The Vercel project stays as the rollback target during the reserve period.

**Who does what.** Work on the host (SSH) is done by the maintainer, or by a
session he opens for that purpose. DNS changes need his explicit go, record by
record. Each step below says who owns it.

**Kept out of this public repository:** the host's public IP (the uptime
monitor reads it from the repository variable `SWAPI_ORIGIN_IP`), SSH keys, the
Discord webhook, and where the bot's token lives. Record those in the
maintainer's private notes.

### Step 0: host lost, recreated, or account closed (any time)

**Before anything else, take the DNS away from the old IP.** An ephemeral
Oracle IP returns to the pool when its instance is terminated and may be handed
to another tenant, who could then obtain a valid Let's Encrypt certificate for
`swapi.build` (the records are DNS-only) and answer in its place, including on
`/mcp` for third-party agents.

- During the reserve period: point both names back to Vercel (values under
  [Rollback](#rollback)).
- After it: delete the `swapi.build` and `www.swapi.build` A records.

Then rebuild from this runbook on a new VM. The uptime monitor's DNS check is
what raises this alarm.

### 1. The binary: built by CI, attached to the Release

`.github/workflows/native-binary.yml` runs on `release: published` (after the
manual Release step of `docs/RELEASE.md`, so it never races the tag) and on
`workflow_dispatch` with an existing tag. It:

- builds with `-Dquarkus.native.container-build=true`, inside the same UBI9
  Mandrel builder the Vercel image uses (`quarkus.native.builder-image`, glibc
  2.34). Building on the `ubuntu-latest` runner itself (glibc 2.39) would
  produce a binary that does not start on Ubuntu 22.04;
- passes `-Dquarkus.native.march=compatibility`, so the binary does not require
  AVX2/BMI2/FMA (the default `x86-64-v3` target), which the host CPU is not
  known to have;
- fails if `objdump -T` shows any `GLIBC_` symbol version above 2.35 (the
  regression test for the glibc trap);
- uploads `application` and `application.sha256` to the Release, and prints
  the hash in the run summary: **a second source** for the checksum, so a
  binary swapped on the Release together with its `.sha256` is still caught.

A pull request that changes the workflow runs the build and the GLIBC check
without uploading anything.

While the reserve period lasts, a release tag still deploys to Vercel as well
(`deploy.yml`). See [Reserve period](#5-reserve-period-and-after).

### 2. Host preparation (one SSH window with write access)

**Before the window** (DNS, maintainer's go): create `oracle.swapi.build`, A →
host IP, DNS-only, TTL 300. The rehearsal certificate is then issued in the
same window.

**Owner of the window:** the maintainer, or a session he opens for it.

#### 2.1 Read-only checks first

1. Effective sshd settings (the drop-ins in `/etc/ssh/sshd_config.d/` win over
   `sshd_config`):
   `sudo sshd -T | grep -Ei 'passwordauthentication|kbdinteractive|permitrootlogin|allowusers'`
2. Where the bot token lives:
   `systemctl show invite-role-bot -p Environment -p EnvironmentFiles`, plus a
   search for the token's first characters under `/etc/systemd` and
   `/home/ubuntu`. Note **where** (private notes), never the value.
3. `stat -c '%a %U %n' /home/ubuntu <token file>`.
4. `sudo iptables -S INPUT` and `sudo cat /etc/iptables/rules.v4`: confirm
   the current rules reject everything except 22.
5. `systemctl cat invite-role-bot` and the content of its existing failure
   alert drop-in, to know how it alerts today.
6. `grep -m1 -o avx2 /proc/cpuinfo` (record only; `march=compatibility`
   already covers a CPU without it).

#### 2.2 SSH

Port 22 stays open to the internet, key-only, with fail2ban (already
installed).

1. Generate a new ed25519 key on the maintainer's machine, used for this host
   only. Append the public key to `~ubuntu/.ssh/authorized_keys`, **test a
   login with it**, and only then remove the old key.
2. If check 2.1.1 showed other values, set them in
   `/etc/ssh/sshd_config.d/10-hardening.conf`, then `sudo sshd -t` and
   `sudo systemctl restart ssh`:

   ```
   PasswordAuthentication no
   KbdInteractiveAuthentication no
   PermitRootLogin no
   AllowUsers ubuntu
   ```

#### 2.3 The bot token (touches the bot: maintainer's go on the spot)

`ProtectHome` only hides `/home`. A token in an `Environment=` line of the
unit file is readable by every local user, both from the file (mode 644) and
over D-Bus (`systemctl show`).

- If check 2.1.2 found it in `Environment=`: move it to an
  `EnvironmentFile=` owned by `ubuntu`, mode 600. `systemctl show` then shows
  the path, not the value. The bot restarts once.
- `sudo chmod 750 /home/ubuntu` and `chmod 600` on the token file.

#### 2.4 Failure alert (one service for every unit)

The webhook lives in a root-only file; no unit file holds it, and the
sandboxed services cannot read it.

`/usr/local/sbin/discord-alert` (owner root, mode 755):

```sh
#!/bin/sh
# usage: discord-alert <unit>   (run by discord-alert@.service)
set -eu
unit="$1"
webhook=$(cat /etc/discord-alert/webhook)
msg="$(hostname): ${unit} failed at $(date -u '+%Y-%m-%d %H:%M UTC')"
curl -fsS --max-time 10 -H 'Content-Type: application/json' \
  -d "{\"content\":\"${msg}\"}" "$webhook" >/dev/null
```

`/etc/systemd/system/discord-alert@.service`:

```ini
[Unit]
Description=Discord alert: %i failed

[Service]
Type=oneshot
ExecStart=/usr/local/sbin/discord-alert %i
```

The webhook file, typed or pasted so it never lands in shell history:

```bash
sudo install -d -o root -g root -m 700 /etc/discord-alert
sudo sh -c 'umask 077; cat > /etc/discord-alert/webhook'   # paste the URL, Enter, Ctrl-D
```

Each monitored unit gets only `OnFailure=discord-alert@%n.service` in its
`[Unit]` section: the swapi unit below, the Caddy drop-in below, and, replacing
the bot's current alert drop-in (touches the bot: maintainer's go),
`/etc/systemd/system/invite-role-bot.service.d/alert.conf`:

```ini
[Unit]
OnFailure=discord-alert@%n.service
```

The message names the failed unit, so a swapi alert never reads as a bot
outage. Whether `OnFailure=` fires on every crash or only when systemd gives up
restarting depends on the systemd version: the rehearsal proves it (step 3.4).

#### 2.5 The `swapi` user and the binary

```bash
sudo useradd --system --no-create-home --home-dir /nonexistent --shell /usr/sbin/nologin swapi
sudo install -d -o root -g root -m 755 /opt/swapi
```

`/usr/local/sbin/swapi-update` (owner root, mode 755). It is used for the first
install and for every later version:

```sh
#!/bin/sh
# usage: swapi-update <tag> <sha256 from the Actions run summary>
set -eu
tag="${1:?usage: swapi-update <tag> <sha256 from the run summary>}"
expected="${2:?usage: swapi-update <tag> <sha256 from the run summary>}"
case "$tag" in v[0-9]*.[0-9]*.[0-9]*) ;; *) echo "not a release tag: $tag"; exit 2 ;; esac
base="https://github.com/eldermoraes/swapi.build/releases/download/$tag"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

curl -fsSL --proto '=https' -o "$tmp/application" "$base/application"
curl -fsSL --proto '=https' -o "$tmp/application.sha256" "$base/application.sha256"
(cd "$tmp" && sha256sum -c application.sha256)                       # source 1: the Release
actual=$(sha256sum "$tmp/application" | cut -d' ' -f1)
[ "$actual" = "$expected" ] || { echo "hash differs from the run summary"; exit 1; }  # source 2

if ldd "$tmp/application" | grep -q 'not found'; then
  echo "missing shared libraries:"; ldd "$tmp/application"; exit 1
fi

install -d -o root -g root -m 755 "/opt/swapi/$tag"
install -o root -g root -m 755 "$tmp/application" "/opt/swapi/$tag/application"
previous=$(readlink -f /opt/swapi/current || true)
ln -sfn "/opt/swapi/$tag" /opt/swapi/current
systemctl restart swapi
sleep 3
if ! curl -fsS --max-time 10 -o /dev/null http://127.0.0.1:5432/api/people/1; then
  echo "swapi $tag is not answering. Roll back with:"
  echo "  ln -sfn ${previous:-<previous version dir>} /opt/swapi/current && systemctl restart swapi"
  exit 1
fi
echo "swapi $tag is up"

# Keep only the active version and the previous one.
for dir in /opt/swapi/v*; do
  case "$dir" in "/opt/swapi/$tag" | "$previous") ;; *) rm -rf "$dir" ;; esac
done
```

Rolling back a version is `sudo ln -sfn /opt/swapi/<previous tag> /opt/swapi/current`
and `sudo systemctl restart swapi`.

#### 2.6 `swapi.service`

`/etc/systemd/system/swapi.service`:

```ini
[Unit]
Description=swapi.build (native)
After=network-online.target
Wants=network-online.target
OnFailure=discord-alert@%n.service

[Service]
Type=simple
User=swapi
Group=swapi
ExecStart=/opt/swapi/current/application -Dquarkus.http.host=127.0.0.1 -Dquarkus.http.port=5432 -Xmx200m
Restart=always
# Without a delay a crash loop exhausts systemd's default start limit
# (5 starts in 10 s) in under a second and the unit gives up.
RestartSec=2

# Ceiling: under pressure swapi slows down or restarts; the bot is not touched.
MemoryHigh=250M
MemoryMax=300M
MemorySwapMax=0
CPUWeight=50

# No privileges, read-only disk.
NoNewPrivileges=yes
CapabilityBoundingSet=
AmbientCapabilities=
ProtectSystem=strict
ProtectHome=yes
PrivateTmp=yes
PrivateDevices=yes

# Away from the bot: unit files, the alert webhook, D-Bus (no AF_UNIX) and
# other processes are out of reach.
InaccessiblePaths=/etc/systemd/system /etc/discord-alert
RestrictAddressFamilies=AF_INET AF_INET6 AF_NETLINK
ProtectProc=invisible
ProcSubset=pid

# No outbound network: answers Caddy on loopback, cannot reach the internet.
IPAddressDeny=any
IPAddressAllow=localhost

ProtectKernelTunables=yes
ProtectKernelModules=yes
ProtectControlGroups=yes
LockPersonality=yes
RestrictNamespaces=yes
SystemCallFilter=@system-service

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo swapi-update <tag> <sha256 from the run summary>
sudo systemctl enable --now swapi
```

If a hardening directive keeps the native binary from starting, remove **only
that one** (`journalctl -u swapi` names it). Do not spend more than 10 minutes
on it. First suspect: `ProcSubset=pid`, which also hides `/proc/meminfo` and
`/proc/cpuinfo`.

#### 2.7 Caddy

Install from the official repository:

```bash
sudo apt install -y debian-keyring debian-archive-keyring apt-transport-https curl
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' | sudo gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' | sudo tee /etc/apt/sources.list.d/caddy-stable.list
sudo chmod o+r /usr/share/keyrings/caddy-stable-archive-keyring.gpg /etc/apt/sources.list.d/caddy-stable.list
sudo apt update && sudo apt install caddy
```

**Automatic updates.** unattended-upgrades ignores third-party repositories
unless told otherwise; without this Caddy never receives security fixes. Read
the repository's `o=` (origin) and `a=` (archive) from `apt-cache policy caddy`
and add them in `/etc/apt/apt.conf.d/51unattended-upgrades-caddy`:

```
Unattended-Upgrade::Origins-Pattern {
        "origin=<o>,archive=<a>";
};
```

Confirm with `sudo unattended-upgrade --dry-run --debug 2>&1 | grep -i caddy`.

**Drop-in** `/etc/systemd/system/caddy.service.d/hardening.conf`:

```ini
[Unit]
OnFailure=discord-alert@%n.service

[Service]
ProtectHome=yes
# The packaged unit is Type=notify, which needs an AF_UNIX socket, so D-Bus is
# closed by path instead of by address family.
InaccessiblePaths=/etc/systemd/system /etc/discord-alert /run/dbus/system_bus_socket
RestrictAddressFamilies=AF_UNIX AF_INET AF_INET6 AF_NETLINK
MemoryMax=200M
MemorySwapMax=0
CPUWeight=50
```

Caddy needs outbound network (Let's Encrypt), so it gets no `IPAddressDeny`.

**`/etc/caddy/Caddyfile`** (rehearsal form: only `oracle.swapi.build`):

```
{
	admin off
	servers {
		protocols h1 h2
		timeouts {
			read_header 10s
		}
	}
}

oracle.swapi.build {
	header -Server
	header Strict-Transport-Security "max-age=63072000"
	request_body {
		max_size 1MB
	}
	reverse_proxy 127.0.0.1:5432
}
```

- `admin off`: closes the admin API on `localhost:2019`, which swapi could
  otherwise reach.
- `protocols h1 h2`: no HTTP/3 advertised on UDP 443, which stays closed.
- Only a header read timeout: no `write` or `idle` timeout, which would cut the
  streamed responses of `/mcp`.
- HSTS: the same value Vercel sends today, no `preload`, no
  `includeSubDomains`.
- `reverse_proxy` overwrites `X-Forwarded-*` coming from clients, which is what
  makes the app's `quarkus.http.proxy.allow-x-forwarded=true` safe here
  (`verify-deploy.sh origin` checks it).
- No access log: do not add a `log` directive except for a fixed, short
  investigation, and remove it afterwards.

**Rule: a name enters the `Caddyfile` only after its DNS already points at the
host.** Otherwise the ACME challenge lands on Vercel, fails, and Let's Encrypt
rate-limits the name (5 failed validations per hour), which can delay the
certificate by hours on cutover day.

**Changing the `Caddyfile`:** `systemctl reload caddy` does **not** work with
`admin off` (reload goes through the admin API). Always:

```bash
sudo caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile
sudo systemctl restart caddy
sudo journalctl -u caddy -f    # wait for "certificate obtained successfully" on a new name
```

The restart takes under a second; certificates stay on disk.

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now caddy
sudo systemctl restart caddy
```

#### 2.8 iptables (ports 80 and 443)

Oracle's Ubuntu images reject everything but 22 in iptables. The new rules go
**before** the `REJECT` line (an `-A` after it has no effect).

Live, where `N` is the position of the `REJECT` line:

```bash
sudo iptables -L INPUT --line-numbers -n
sudo iptables -I INPUT N -p tcp -m state --state NEW -m tcp --dport 443 -j ACCEPT
sudo iptables -I INPUT N -p tcp -m state --state NEW -m tcp --dport 80 -j ACCEPT
```

Persisted: edit `/etc/iptables/rules.v4` by hand and add, right before the
`-A INPUT -j REJECT ...` line:

```
-A INPUT -p tcp -m state --state NEW -m tcp --dport 80 -j ACCEPT
-A INPUT -p tcp -m state --state NEW -m tcp --dport 443 -j ACCEPT
```

Check with `sudo iptables-restore --test /etc/iptables/rules.v4`. **Never**
`netfilter-persistent save` (it would also save fail2ban's chain and its bans
of the moment) and never `ufw`. `rules.v6` only if the network gets IPv6
(there is no AAAA record today).

#### 2.9 Log retention

`/etc/systemd/journald.conf.d/retention.conf`, then
`sudo systemctl restart systemd-journald`:

```ini
[Journal]
MaxRetentionSec=30day
SystemMaxUse=500M
```

The privacy page states this 30-day limit.

#### 2.10 Oracle console (maintainer)

Security List of the VCN: ingress TCP 22, 80 and 443 only. Keep the ICMP rules
Oracle adds by default (type 3 code 4 is needed for path MTU discovery).

### 3. Rehearsal on `oracle.swapi.build` (same window)

1. `sudo journalctl -u caddy -f` until "certificate obtained successfully" for
   `oracle.swapi.build`.
2. `scripts/verify-deploy.sh origin oracle.swapi.build`: REST with `https://`
   URLs, OpenAPI, robots, sitemap, preview image, MCP stateless, MCP stateful
   burst, foreign session, edges, `Origin`/`Vary`, `X-Forwarded-Host` and
   `Forwarded` poisoning, HSTS, no `Server`.
3. `systemd-analyze security swapi` (record the score) and
   `journalctl -u swapi` (confirm MCP logs nothing about the client).
4. **Prove the alert:** `sudo systemctl kill -s KILL swapi` and confirm the
   message reaches Discord. Same for the bot (its alert was never tested).
5. **Reboot test**, with the maintainer around: `sudo reboot`. Afterwards:
   `systemctl status swapi caddy invite-role-bot fail2ban`,
   `sudo iptables -S INPUT`, and the probes of step 2 again. This covers the
   automatic 02:00 BRT reboot of unattended-upgrades, the persisted iptables
   rules and the enabled units in one go.
6. `free -m`: the bot keeps its usual memory.

### 4. Cutover

**The day before:**

- DNS (maintainer's go): TTL of `swapi.build` and `www.swapi.build` down to 60 s.
  `swapi.build` sends HSTS for 2 years, so browsers will not let a visitor click
  through a certificate error: the window between the DNS switch and the
  certificate must be as short as possible.
- Note the expiry of Vercel's certificates
  (`echo | openssl s_client -connect swapi.build:443 -servername swapi.build 2>/dev/null | openssl x509 -noout -enddate`,
  same for `www`). It bounds the fast rollback (see [Rollback](#rollback)).
- Maintainer, Oracle console: is the account Always Free only or Pay As You Go?

**On the day:** a 30-minute window on a weekday morning, maintainer present.

| # | Step | Owner |
|---|---|---|
| 1 | The host serves the same version as Vercel (`/openapi.json`, `info.version`) | Read-only |
| 2 | `www.swapi.build`: A → host IP, DNS-only, TTL 60 (an A, not a CNAME, so rollback only changes values) | DNS, maintainer's go |
| 3 | Wait 90 s; add `www.swapi.build` to the `Caddyfile` site address; validate; restart; wait for "certificate obtained successfully" | Host |
| 4 | `scripts/verify-deploy.sh origin www.swapi.build` | Read-only |
| 5 | `swapi.build`: A → host IP, DNS-only, TTL 60 | DNS, maintainer's go |
| 6 | Same as step 3 for `swapi.build` | Host |
| 7 | `scripts/verify-deploy.sh origin swapi.build`; `dig @1.1.1.1` and `dig @8.8.8.8` for both names; `systemctl status swapi caddy invite-role-bot` | Read-only and host |
| 8 | Remove `oracle.swapi.build` from the `Caddyfile` (restart) and delete its DNS record, so no indexable copy of the site is left behind | Host; DNS, maintainer's go |
| 9 | Turn the uptime monitor on (below) | Maintainer |
| 10 | After 24 h without trouble: TTL back to 300 | DNS, maintainer's go |

Final site address in the `Caddyfile`: `swapi.build, www.swapi.build {`. The
`www` name is served as-is (200, no redirect), as on Vercel.

**Uptime monitor.** `.github/workflows/uptime.yml` was merged with only a
manual trigger, because before the cutover its DNS check fails by design. On
cutover day: set the repository variable `SWAPI_ORIGIN_IP` to the host's IP,
uncomment its `schedule` (every 15 minutes) in a PR, merge, and run it once
from the Actions tab. It checks the API, more than 20 days left on both
certificates (Caddy renews 30 days ahead), and that both names resolve only to
the host. GitHub emails the owner on failure, and disables scheduled
workflows after 60 days without repository activity: re-enable it if so.

**Rollback criteria** (any one): certificate not issued within 10 minutes; a
probe failing for more than 15 minutes without a clear cause; swapi restarting
in a loop; the bot down.

#### Rollback

`swapi.build` A `76.76.21.21` and `www.swapi.build` CNAME
`cname.vercel-dns.com`, both DNS-only (values read on 2026-10-19). Takes 5 to
10 minutes for nearly everyone. The Vercel domains stay attached to the
project, so Vercel answers at once (one cold start of a few seconds).

The fast rollback holds until **2026-12-23**, when Vercel's certificate for
`www` expires (the apex one on 2026-12-25): with the DNS pointing elsewhere,
Vercel cannot renew them. After that date, a rollback includes a few minutes
of TLS errors while Vercel issues new certificates.

Expect 1 to 2 minutes of TLS errors, for whoever resolves the new address,
between each name's DNS switch and its certificate. That is the cost of having
no tunnel.

### 5. Reserve period and after

- **No releases during the reserve period.** A tag would deploy the new
  version to Vercel (no traffic) while the host keeps the old one, and a
  rollback would silently change versions. If a release cannot wait, update the
  host the same day (`swapi-update`).
- After 14 days without a rollback: a PR removes `deploy.yml` and the Vercel
  files, and the privacy page drops its "previous host" paragraph. **The
  Vercel project is kept, idle, not deleted**: deleting it ends the DNS
  rollback, and recreating it means a new deploy.
- Check once a month on the Vercel bill that `swapi-build.vercel.app` is not
  waking the container.

### Updating the version on the host

After a release (outside the reserve period): open the **Native binary** run of
that release in the Actions tab, copy the hash from its summary, then on the
host:

```bash
sudo swapi-update <tag> <sha256 from the run summary>
curl -fsS https://swapi.build/openapi.json | grep -o '"version"[^,]*'
```

### Troubleshooting (self-hosted origin)

| Symptom | Cause / fix |
|---|---|
| `systemctl reload caddy` fails | Expected with `admin off`. Use `caddy validate` and `systemctl restart caddy`. |
| No certificate for a name; ACME errors in `journalctl -u caddy` | The name entered the `Caddyfile` before its DNS pointed at the host, or the DNS is still propagating. Check `dig @1.1.1.1 <name>`; if Let's Encrypt is rate-limiting, remove the name, wait an hour, add it back once DNS is right. |
| swapi does not start after an install | `journalctl -u swapi`. A hardening directive: remove only that one (first suspect `ProcSubset=pid`). A missing library: `ldd /opt/swapi/current/application`. `GLIBC_2.xx not found`: the binary was not built by `native-binary.yml`. |
| swapi restarts in a loop | `journalctl -u swapi`; an out-of-memory kill shows as `oom-kill` in `journalctl -k`. The ceilings are deliberate: they protect the bot. |
| The uptime monitor fails on DNS | Someone changed the records, or the VM was recreated: see [Step 0](#step-0-host-lost-recreated-or-account-closed-any-time). |
| The bot is slow or down | `free -m` and `systemctl status invite-role-bot`. If swapi or Caddy are the cause, their ceilings are too high: lower `MemoryMax`, never touch the bot first. |
