# Deploy runbook (Vercel)

Canonical deploy procedure. The Vercel project is `algorium/swapi-build`,
`framework: container`, built from `swapi-app/Dockerfile.vercel`.

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
probes in either path; the sections below explain what each probe proves.

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

**Analytics / Speed Insights** — both scripts are served by the edge at
`/_vercel/*`, and Quinoa's `enable-spa-routing` answers `index.html` with 200 on
any unknown path. Without checking the *content type*, a broken collection is
indistinguishable from a working one:

```bash
for p in insights speed-insights; do
  curl -s -o /dev/null -w "$p: %{http_code} %{content_type}\n" -b jar.txt \
    "https://<preview-host>/_vercel/$p/script.js"
done
# expected: 200 application/javascript for both
# text/html = the edge did not intercept; Analytics is NOT collecting
```

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
for p in insights speed-insights; do
  curl -s -o /dev/null -w "$p: %{http_code} %{content_type}\n" "https://swapi.build/_vercel/$p/script.js"
done
# expected: 200 application/javascript for both
```

Expect `status: 200` and `1` (embedded URLs on `https://swapi.build`, scheme `https`),
and `200 application/json` for the spec.
The Analytics check is only half the proof: also load `https://swapi.build` in a real
browser and confirm the pageview lands in the dashboard. The script can be served
correctly and still report nowhere.
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
| Dashboard shows zero visitors although the site has traffic | `/_vercel/insights/script.js` is being answered by the SPA fallback (`200 text/html`) instead of the edge, so nothing ever reports. Check with the content-type curl in step 4. Enabling Web Analytics does **not** retrofit existing deployments — the route only appears in deployments created after `webAnalytics.enabledAt` (`GET /v9/projects/swapi-build`). A redeploy is the fix. |
| `vercel promote` hangs or `User force closed the prompt` | Interactive confirmation without tty. Use `vercel deploy --prod` instead. |
| 403 on `*.vercel.app` URLs | Team SSO protection. Use a `_vercel_share` bypass link + cookie jar. |
| 404 `Mcp session not found` on a stateful MCP call | **Not a cold start.** Sessions live in one instance's heap and Vercel has no session affinity, so the call landed on a different replica. Fixed by `quarkus.mcp.server.http.streamable.auto-init=true`; if it reappears, that property is off in the running deployment. |
| Quinoa build fails on Vercel with local artifacts | `swapi-app/.vercelignore` must exclude `dist/` and `target/`. |
| 202 responses from the API | Legacy quirk retired 2026-08-01 — current builds return 200; a 202 means an old deployment is live. |
| 403 on every path of `swapi.build`, answered in ~0.07s with `x-vercel-mitigated: deny` | Automatic mitigation blocked the source IP (typical after a load test). The app is **not** down: check with `curl https://swapi-build.vercel.app/api/people/1` (200) or hit the public domain from another IP. It expires on its own; IP `bypass` rules don't exist on the Hobby plan. **Do not redeploy.** |
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
