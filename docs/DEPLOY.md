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
needed — the custom domain has no SSO).

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `Expected VCR image registry vcr.vercel.com: <detect>` | Deploy ran from the repo root. Re-run from `swapi-app/`. |
| `vercel promote` hangs or `User force closed the prompt` | Interactive confirmation without tty. Use `vercel deploy --prod` instead. |
| 403 on `*.vercel.app` URLs | Team SSO protection. Use a `_vercel_share` bypass link + cookie jar. |
| Transient 404 on first stateful MCP connect | Serverless cold start. Retry resolves it. |
| Quinoa build fails on Vercel with local artifacts | `swapi-app/.vercelignore` must exclude `dist/` and `target/`. |
| 202 responses from the API | Legacy quirk retired 2026-08-01 — current builds return 200; a 202 means an old deployment is live. |
