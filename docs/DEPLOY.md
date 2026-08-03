# Deploy runbook (Vercel)

Canonical deploy procedure. The Vercel project is `algorium/swapi-build`,
`framework: container`, built from `swapi-app/Dockerfile.vercel`.

## Prerequisites

- `.env` at the repo root (gitignored) with `VERCEL_API_TOKEN` and `VERCEL_TEAM_ID`.
- `swapi-app/.vercel/project.json` exists (project already linked).
- Full test suite green: `cd swapi-app && ./mvnw test`.

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
# esperado: 200 application/json (Quinoa não pode engolir a rota)
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

**MCP stateful** — the probe that catches the instance-affinity bug. Twelve
concurrent calls on one session must all return 200; before `auto-init` this
returned 33–58% `404`:

```bash
SID=$(curl -s -D - -o /dev/null -b jar.txt -X POST "https://<preview-host>/mcp" \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"probe","version":"1.0"}}}' \
  | tr -d '\r' | awk -F': ' 'tolower($1)=="mcp-session-id"{print $2}')
test -n "$SID" || { echo "FAIL: no session id issued"; false; }
for i in $(seq 1 12); do
  ( curl -s -o /dev/null -w '%{http_code}\n' -b jar.txt -X POST "https://<preview-host>/mcp" \
      -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
      -H "Mcp-Session-Id: $SID" \
      -d '{"jsonrpc":"2.0","id":'$i',"method":"tools/list"}' ) &
done | sort | uniq -c
# esperado: 12 200
```

The empty-SID guard matters: with `auto-init` on, a request carrying an empty or
missing session id also returns 200, so a failed extraction would print `12 200`
and look like a pass without ever having sent a real session id.

Run the concurrent burst against the **preview only** — bursts are what trip the
Vercel IP mitigation documented in Troubleshooting.

**MCP foreign session** — a session id that never existed must still be served:

```bash
curl -s -o /dev/null -w 'foreign session: %{http_code}\n' -b jar.txt \
  -X POST "https://<preview-host>/mcp" \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -H 'Mcp-Session-Id: never-existed' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
# esperado: 200
```

**MCP edges:**

```bash
curl -s -o /dev/null -w 'GET /mcp: %{http_code}\n' -b jar.txt "https://<preview-host>/mcp"
# esperado: 405
curl -s -o /dev/null -w 'GET /mcp/sse: %{http_code}\n' -b jar.txt "https://<preview-host>/mcp/sse"
# esperado: 404
curl -s -b jar.txt -X POST "https://<preview-host>/mcp/messages/never-existed" \
  -H 'Content-Type: application/json' -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
# esperado: 404 com corpo JSON mencionando /mcp
```

**Cache poisoning via `Origin`** — CORS echoes the request `Origin`, so every
edge-cacheable response must carry `Vary: Origin` or the edge serves one
origin's header to another:

```bash
curl -sI -H 'Origin: https://evil.example' https://swapi.build/api/people/1 | grep -i '^vary'
# deve conter Origin
curl -s -o /dev/null -w '[%header{access-control-allow-origin}]\n' https://swapi.build/api/people/1
# sem Origin na request: deve vir []
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
# esperado: 200 application/json (Quinoa não pode engolir a rota)
```

Expect `status: 200` and `1` (embedded URLs on `https://swapi.build`, scheme `https`),
and `200 application/json` for the spec.
Then run the MCP probe from step 2 against `https://swapi.build/mcp` (no cookie jar
needed — the custom domain has no SSO). Re-run the foreign-session and edges probes
against `https://swapi.build` too, without the concurrent burst.

**Edge cache** — the response reaching the client shows `cache-control: public, max-age=300`:
the CDN consumes and strips `s-maxage`/`stale-while-revalidate` before forwarding. The proof
the edge stored it is `x-vercel-cache` going `MISS` → `HIT` on a second request to the same
path. Note the cache key includes the HTTP method, so a `GET` and a `HEAD` on the same path
are separate entries — don't read the first `HEAD` MISS as a failure.

```bash
curl -sI https://swapi.build/api/people/1 | grep -i 'x-vercel-cache'   # MISS
curl -sI https://swapi.build/api/people/1 | grep -i 'x-vercel-cache'   # HIT
curl -sI https://swapi.build/api/people/random | grep -i 'x-vercel-cache'  # MISS, sempre
```

**Cache poisoning probe** — responses embed absolute URLs built from the per-request host,
and `X-Forwarded-Host` is *not* part of the cache key. Vercel overwrites the header (verified
2026-08-03: plain spoof, RFC 7239 `Forwarded`, and a duplicated header were all ignored), so
this is a regression check:

```bash
curl -s -H 'X-Forwarded-Host: evil.example' https://swapi.build/api/people/3 | grep -c evil.example  # 0
curl -s https://swapi.build/api/people/3 | grep -c evil.example                                      # 0
```

If either is non-zero, purge the cache immediately and add `Vary: X-Forwarded-Host` to the
cacheable response before redeploying.

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `Expected VCR image registry vcr.vercel.com: <detect>` | Deploy ran from the repo root. Re-run from `swapi-app/`. |
| `vercel promote` hangs or `User force closed the prompt` | Interactive confirmation without tty. Use `vercel deploy --prod` instead. |
| 403 on `*.vercel.app` URLs | Team SSO protection. Use a `_vercel_share` bypass link + cookie jar. |
| 404 `Mcp session not found` on a stateful MCP call | **Not a cold start.** Sessions live in one instance's heap and Vercel has no session affinity, so the call landed on a different replica. Fixed by `quarkus.mcp.server.http.streamable.auto-init=true`; if it reappears, that property is off in the running deployment. |
| Quinoa build fails on Vercel with local artifacts | `swapi-app/.vercelignore` must exclude `dist/` and `target/`. |
| 202 responses from the API | Legacy quirk retired 2026-08-01 — current builds return 200; a 202 means an old deployment is live. |
| 403 on every path of `swapi.build`, answered in ~0.07s with `x-vercel-mitigated: deny` | Automatic mitigation blocked the source IP (typical after a load test). The app is **not** down: check with `curl https://swapi-build.vercel.app/api/people/1` (200) or hit the public domain from another IP. It expires on its own; IP `bypass` rules don't exist on the Hobby plan. **Do not redeploy.** |
| `x-vercel-cache: MISS` always, on a path that isn't `/random` | The `CacheControlFilter` isn't applying the header. Check `curl -sI <host>/api/people/1 \| grep -i cache-control` — it must contain `max-age=300` (the edge strips `s-maxage` before the client sees it). Remember `GET` and `HEAD` are separate cache entries. |
| A wrong response "frozen" at the edge (1-year TTL) | Purge: dashboard → project → **CDN** → **Caches** → **Purge**, `*` for the whole project. Prefer **Invalidate** over **Delete** (Delete revalidates in the foreground and risks a cache stampede). A new deployment also clears it, since the cache key includes the deployment URL. |
| First request after a deploy takes ~11s | Container cold start (image pull + boot). Measured 10.9s on 2026-08-03. Edge cache makes the function idle more, so cold starts now hit the uncached `/random` endpoints more often than before. |
| `504` / `FUNCTION_INVOCATION_TIMEOUT` on the first request after a deploy | The cold start (~11s measured) exceeded `functionDefaultTimeout`, currently **15s** — a deliberate call, but the margin is only ~4s. Fix without redeploying: `PATCH /v9/projects/swapi-build` with `resourceConfig` `{"fluid":true,"functionDefaultRegions":["iad1"],"functionDefaultTimeout":60}`. Takes effect immediately. |
