#!/usr/bin/env bash
# Probes from docs/DEPLOY.md as a single source (CI and operator use the same script).
# The runbook explains the why of each check and wins on divergence.
#
# usage: verify-deploy.sh preview <host>   (includes stateful burst; requires bypass or an open host)
#        verify-deploy.sh prod <host>      (no burst; includes edge cache checks)
#
# If VERCEL_AUTOMATION_BYPASS_SECRET is in the environment, every call sends the
# x-vercel-protection-bypass header (replaces the runbook's cookie jar).
set -u

MODE="${1:?usage: verify-deploy.sh <preview|prod> <host>}"
HOST="${2:?usage: verify-deploy.sh <preview|prod> <host>}"
HOST="${HOST#https://}"; HOST="${HOST%%/*}"
BASE="https://${HOST}"

case "$MODE" in preview|prod) ;; *) echo "invalid mode: $MODE"; exit 2 ;; esac

CURL=(curl -s --max-time 90)
if [ -n "${VERCEL_AUTOMATION_BYPASS_SECRET:-}" ]; then
  CURL+=(-H "x-vercel-protection-bypass: ${VERCEL_AUTOMATION_BYPASS_SECRET}")
fi

FAILURES=0
pass() { printf 'PASS  %s\n' "$1"; }
fail() { printf 'FAIL  %s\n      expected: %s\n      got:      %s\n' "$1" "$2" "$3"; FAILURES=$((FAILURES+1)); }

# --- REST -------------------------------------------------------------------
body=$("${CURL[@]}" "$BASE/api/people/1")
code=$("${CURL[@]}" -o /dev/null -w '%{http_code}' "$BASE/api/people/1")
[ "$code" = "200" ] && pass "REST /api/people/1 -> 200" \
  || fail "REST /api/people/1" "200" "$code"
echo "$body" | grep -q "$BASE/api/people/1" && pass "REST embeds https URLs on the host" \
  || fail "REST embedded URLs" "contain $BASE/api/people/1" "missing"

# --- OpenAPI ----------------------------------------------------------------
ct=$("${CURL[@]}" -o /dev/null -w '%{http_code} %{content_type}' -H 'Accept: text/html' "$BASE/openapi.json")
case "$ct" in "200 application/json"*) pass "OpenAPI served by the backend ($ct)";;
  *) fail "OpenAPI content-type" "200 application/json" "$ct";; esac

version=$("${CURL[@]}" "$BASE/openapi.json" | grep -o '"version"[[:space:]]*:[[:space:]]*"[^"]*"' | head -1)
pom=""
if [ -f "$(dirname "$0")/../swapi-app/pom.xml" ]; then
  pom=$(sed -n 's|.*<version>\(.*\)</version>.*|\1|p' "$(dirname "$0")/../swapi-app/pom.xml" | head -1)
fi
if [ -n "$pom" ]; then
  echo "$version" | grep -q "\"$pom\"" && pass "OpenAPI version == pom ($pom)" \
    || fail "OpenAPI version" "\"$pom\"" "$version"
else
  echo "INFO  pom.xml not found; published version: $version"
fi

# --- SEO routes / social preview ---------------------------------------------
ct=$("${CURL[@]}" -o /dev/null -w '%{http_code} %{content_type}' -H 'Accept: text/html' "$BASE/robots.txt")
case "$ct" in "200 text/plain"*) pass "robots.txt served as text ($ct)";;
  *) fail "robots.txt content-type" "200 text/plain" "$ct (text/html = the SPA swallowed it)";; esac

ct=$("${CURL[@]}" -o /dev/null -w '%{http_code} %{content_type}' -H 'Accept: text/html' "$BASE/sitemap.xml")
case "$ct" in "200 application/xml"*|"200 text/xml"*) pass "sitemap.xml served as XML ($ct)";;
  *) fail "sitemap.xml content-type" "200 application/xml or text/xml" "$ct (text/html = the SPA swallowed it)";; esac

robots=$("${CURL[@]}" "$BASE/robots.txt")
echo "$robots" | grep -q "Sitemap: $BASE/sitemap.xml" && pass "robots.txt points at the sitemap on the host" \
  || fail "robots.txt Sitemap" "Sitemap: $BASE/sitemap.xml" "$(echo "$robots" | tr '\n' ' ' | head -c 160)"

sitemap=$("${CURL[@]}" "$BASE/sitemap.xml")
echo "$sitemap" | grep -q "<loc>$BASE/docs</loc>" && pass "sitemap.xml lists /docs on the host" \
  || fail "sitemap.xml /docs" "<loc>$BASE/docs</loc>" "missing"

ct=$("${CURL[@]}" -o /dev/null -w '%{http_code} %{content_type}' "$BASE/og-image.png")
case "$ct" in "200 image/png"*) pass "og-image.png served as PNG ($ct)";;
  *) fail "og-image.png content-type" "200 image/png" "$ct";; esac

home=$("${CURL[@]}" "$BASE/")
echo "$home" | grep -q 'rel="canonical"' && echo "$home" | grep -q 'property="og:url"' && echo "$home" | grep -q 'name="twitter:card"' \
  && pass "home HTML includes canonical, og:url and Twitter Card" \
  || fail "home SEO metadata" 'canonical + og:url + twitter:card' "missing"
echo "$home" | grep -q "content=\"$BASE/\"" && pass "home SEO uses absolute URLs on the host" \
  || fail "home SEO URLs" "content=\"$BASE/\"" "missing"

# --- Analytics / Speed Insights --------------------------------------------
for p in insights speed-insights; do
  ct=$("${CURL[@]}" -o /dev/null -w '%{http_code} %{content_type}' "$BASE/_vercel/$p/script.js")
  case "$ct" in "200 application/javascript"*|"200 text/javascript"*) pass "/_vercel/$p/script.js ($ct)";;
    *) fail "/_vercel/$p/script.js" "200 application/javascript" "$ct (text/html = the SPA swallowed it; collection broken)";; esac
done

# --- Cold start (informational, never fails) ---------------------------------
t=$("${CURL[@]}" -o /dev/null -w '%{time_total}' "$BASE/api/people/1")
echo "INFO  first measured request: ${t}s (floor; the instance may already be warm)"

# --- MCP stateless ----------------------------------------------------------
resp=$("${CURL[@]}" -X POST "$BASE/mcp" \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -H "Mcp-Method: tools/call" -H "Mcp-Name: sw_get" \
  -H "MCP-Protocol-Version: 2026-07-28" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"sw_get","arguments":{"resource":"PEOPLE","id":1},"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientInfo":{"name":"verify-deploy","version":"1.0"},"io.modelcontextprotocol/clientCapabilities":{}}}}')
echo "$resp" | grep -q '"isError":false' && pass "MCP stateless tools/call" \
  || fail "MCP stateless" '"isError":false' "$(echo "$resp" | head -c 200)"
echo "$resp" | grep -q "$BASE/api/" && pass "MCP embeds URLs on the host" \
  || fail "MCP embedded URLs" "contain $BASE/api/" "missing"

# --- MCP stateful burst (preview only — bursts trip the mitigation in prod) --
if [ "$MODE" = "preview" ]; then
  SID=$("${CURL[@]}" -D - -o /dev/null -X POST "$BASE/mcp" \
    -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
    -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"verify-deploy","version":"1.0"}}}' \
    | tr -d '\r' | awk -F': ' 'tolower($1)=="mcp-session-id"{print $2}')
  if [ -z "$SID" ]; then
    fail "MCP stateful burst" "session id issued on initialize" "none (guard: burst NOT executed)"
  else
    burst=$(for i in $(seq 1 12); do
      ( "${CURL[@]}" -o /dev/null -w '%{http_code}\n' -X POST "$BASE/mcp" \
          -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
          -H "Mcp-Session-Id: $SID" \
          -d '{"jsonrpc":"2.0","id":'"$i"',"method":"tools/list"}' ) &
    done; wait)
    ok=$(echo "$burst" | grep -c '^200$')
    [ "$ok" = "12" ] && pass "MCP stateful burst 12x200 (same session)" \
      || fail "MCP stateful burst" "12x 200" "$(echo "$burst" | sort | uniq -c | tr '\n' ' ')"
  fi
fi

# --- MCP foreign session + edges --------------------------------------------
code=$("${CURL[@]}" -o /dev/null -w '%{http_code}' -X POST "$BASE/mcp" \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -H 'Mcp-Session-Id: never-existed' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}')
[ "$code" = "200" ] && pass "MCP foreign session -> 200 (auto-init)" \
  || fail "MCP foreign session" "200" "$code"

code=$("${CURL[@]}" -o /dev/null -w '%{http_code}' "$BASE/mcp")
[ "$code" = "405" ] && pass "GET /mcp -> 405" || fail "GET /mcp" "405" "$code"
code=$("${CURL[@]}" -o /dev/null -w '%{http_code}' "$BASE/mcp/sse")
[ "$code" = "404" ] && pass "GET /mcp/sse -> 404 (legacy rejected)" || fail "GET /mcp/sse" "404" "$code"
edge=$("${CURL[@]}" -w '\n%{http_code}' -X POST "$BASE/mcp/messages/never-existed" \
  -H 'Content-Type: application/json' -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}')
code=$(echo "$edge" | tail -1)
if [ "$code" = "404" ] && echo "$edge" | head -1 | grep -q '/mcp'; then
  pass "POST /mcp/messages/* -> 404 with JSON citing /mcp"
else
  fail "POST /mcp/messages/*" "404 + body citing /mcp" "$code $(echo "$edge" | head -c 120)"
fi

# --- CORS / cache poisoning via Origin --------------------------------------
vary=$("${CURL[@]}" -I -H 'Origin: https://evil.example' "$BASE/api/people/1" | tr -d '\r' | grep -i '^vary')
echo "$vary" | grep -qi 'origin' && pass "Vary contains Origin ($vary)" \
  || fail "Vary: Origin" "Vary header containing Origin" "${vary:-missing}"
acao=$("${CURL[@]}" -o /dev/null -w '%header{access-control-allow-origin}' "$BASE/api/people/1")
[ -z "$acao" ] && pass "no Origin on the request -> no ACAO" \
  || fail "ACAO without Origin" "empty" "$acao"
vary_html=$("${CURL[@]}" -I -H 'Accept: text/html' -H 'Origin: https://evil.example' "$BASE/" | tr -d '\r' | grep -i '^vary')
echo "$vary_html" | grep -qi 'origin' && pass "SPA HTML / Vary contains Origin ($vary_html)" \
  || fail "SPA HTML / Vary: Origin" "Vary header containing Origin" "${vary_html:-missing}"

# --- Prod only: edge cache + poisoning X-Forwarded-Host ---------------------
# MISS -> HIT only makes sense against the production edge: a preview deployment sits
# behind the SSO bypass, whose header suppresses the shared cache, so the probe would
# read MISS forever and say nothing. Preview is covered by the manual runbook step
# before the approval gate (same reasoning as the /api probes above).
if [ "$MODE" = "prod" ]; then
  c1=$("${CURL[@]}" -I "$BASE/api/people/1" | tr -d '\r' | awk -F': ' 'tolower($1)=="x-vercel-cache"{print $2}')
  c2=$("${CURL[@]}" -I "$BASE/api/people/1" | tr -d '\r' | awk -F': ' 'tolower($1)=="x-vercel-cache"{print $2}')
  [ "$c2" = "HIT" ] && pass "edge cache /api/people/1: $c1 -> HIT" \
    || fail "edge cache /api/people/1" "second read HIT" "$c1 -> $c2"
  cr=$("${CURL[@]}" -I "$BASE/api/people/random" | tr -d '\r' | awk -F': ' 'tolower($1)=="x-vercel-cache"{print $2}')
  [ "$cr" = "MISS" ] && pass "/api/people/random: always MISS" \
    || fail "/api/people/random cache" "MISS" "$cr"
  for p in / /resource/planets; do
    h1=$("${CURL[@]}" -I -H 'Accept: text/html' "$BASE$p" | tr -d '\r' | awk -F': ' 'tolower($1)=="x-vercel-cache"{print $2}')
    h2=$("${CURL[@]}" -I -H 'Accept: text/html' "$BASE$p" | tr -d '\r' | awk -F': ' 'tolower($1)=="x-vercel-cache"{print $2}')
    [ "$h2" = "HIT" ] && pass "edge cache SPA HTML $p: $h1 -> HIT" \
      || fail "edge cache SPA HTML $p" "second read HIT" "$h1 -> $h2 (HTML always MISS — see runbook Troubleshooting)"
  done
  n=$("${CURL[@]}" -H 'X-Forwarded-Host: evil.example' "$BASE/api/people/3" | grep -c evil.example)
  m=$("${CURL[@]}" "$BASE/api/people/3" | grep -c evil.example)
  [ "$n" = "0" ] && [ "$m" = "0" ] && pass "poisoning X-Forwarded-Host: 0 occurrences" \
    || fail "poisoning X-Forwarded-Host" "0 and 0" "$n and $m (PURGE THE CACHE NOW — see runbook)"
  # The ?poison=$$ is load-bearing: the edge cache key includes the query string, and the
  # probes above already primed the CLEAN entry for the bare path (lines with $BASE/ and
  # $BASE/sitemap.xml). Without a unique key the spoofed request would just read that HIT
  # and the probe would pass no matter what the origin does.
  seo_poison=$("${CURL[@]}" -H 'X-Forwarded-Host: evil.example' "$BASE/sitemap.xml?poison=$$" | grep -c evil.example)
  seo_clean=$("${CURL[@]}" "$BASE/sitemap.xml?poison=$$" | grep -c evil.example)
  [ "$seo_poison" = "0" ] && [ "$seo_clean" = "0" ] && pass "poisoning SEO X-Forwarded-Host: 0 occurrences" \
    || fail "poisoning SEO X-Forwarded-Host" "0 and 0" "$seo_poison and $seo_clean (PURGE THE CACHE NOW — see runbook)"
  # The SPA HTML embeds canonical/og:url/og:image built from the request host, and it is
  # storable at the edge since 2.4.2 — a poisoned entry would be served for a year.
  home_poison=$("${CURL[@]}" -H 'X-Forwarded-Host: evil.example' "$BASE/?poison=$$" | grep -c evil.example)
  home_clean=$("${CURL[@]}" "$BASE/?poison=$$" | grep -c evil.example)
  [ "$home_poison" = "0" ] && [ "$home_clean" = "0" ] && pass "poisoning SPA HTML X-Forwarded-Host: 0 occurrences" \
    || fail "poisoning SPA HTML X-Forwarded-Host" "0 and 0" "$home_poison and $home_clean (PURGE THE CACHE NOW — see runbook)"
fi

echo
if [ "$FAILURES" -eq 0 ]; then
  echo "verify-deploy [$MODE] $HOST: all probes green"
else
  echo "verify-deploy [$MODE] $HOST: $FAILURES probe(s) failed"
  exit 1
fi
