#!/usr/bin/env bash
# Probes do docs/DEPLOY.md em fonte única (CI e operador usam o mesmo script).
# O runbook explica o porquê de cada check e vence em caso de divergência.
#
# uso: verify-deploy.sh preview <host>   (inclui burst stateful; exige bypass ou host aberto)
#      verify-deploy.sh prod <host>      (sem burst; inclui checks de edge cache)
#
# Se VERCEL_AUTOMATION_BYPASS_SECRET estiver no ambiente, todas as chamadas
# enviam o header x-vercel-protection-bypass (substitui o cookie jar do runbook).
set -u

MODE="${1:?uso: verify-deploy.sh <preview|prod> <host>}"
HOST="${2:?uso: verify-deploy.sh <preview|prod> <host>}"
HOST="${HOST#https://}"; HOST="${HOST%%/*}"
BASE="https://${HOST}"

case "$MODE" in preview|prod) ;; *) echo "modo inválido: $MODE"; exit 2 ;; esac

CURL=(curl -s --max-time 90)
if [ -n "${VERCEL_AUTOMATION_BYPASS_SECRET:-}" ]; then
  CURL+=(-H "x-vercel-protection-bypass: ${VERCEL_AUTOMATION_BYPASS_SECRET}")
fi

FAILURES=0
pass() { printf 'PASS  %s\n' "$1"; }
fail() { printf 'FAIL  %s\n      esperado: %s\n      obtido:   %s\n' "$1" "$2" "$3"; FAILURES=$((FAILURES+1)); }

# --- REST -------------------------------------------------------------------
body=$("${CURL[@]}" "$BASE/api/people/1")
code=$("${CURL[@]}" -o /dev/null -w '%{http_code}' "$BASE/api/people/1")
[ "$code" = "200" ] && pass "REST /api/people/1 -> 200" \
  || fail "REST /api/people/1" "200" "$code"
echo "$body" | grep -q "$BASE/api/people/1" && pass "REST embute URLs https no host" \
  || fail "REST URLs embutidas" "conter $BASE/api/people/1" "ausente"

# --- OpenAPI ----------------------------------------------------------------
ct=$("${CURL[@]}" -o /dev/null -w '%{http_code} %{content_type}' -H 'Accept: text/html' "$BASE/openapi.json")
case "$ct" in "200 application/json"*) pass "OpenAPI servida pelo backend ($ct)";;
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
  echo "INFO  pom.xml não encontrado; versão publicada: $version"
fi

# --- Analytics / Speed Insights --------------------------------------------
for p in insights speed-insights; do
  ct=$("${CURL[@]}" -o /dev/null -w '%{http_code} %{content_type}' "$BASE/_vercel/$p/script.js")
  case "$ct" in "200 application/javascript"*|"200 text/javascript"*) pass "/_vercel/$p/script.js ($ct)";;
    *) fail "/_vercel/$p/script.js" "200 application/javascript" "$ct (text/html = SPA engoliu; coleta quebrada)";; esac
done

# --- Cold start (informativo, não falha) ------------------------------------
t=$("${CURL[@]}" -o /dev/null -w '%{time_total}' "$BASE/api/people/1")
echo "INFO  primeira request medida: ${t}s (piso, instância pode já estar quente)"

# --- MCP stateless ----------------------------------------------------------
resp=$("${CURL[@]}" -X POST "$BASE/mcp" \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -H "Mcp-Method: tools/call" -H "Mcp-Name: sw_get" \
  -H "MCP-Protocol-Version: 2026-07-28" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"sw_get","arguments":{"resource":"PEOPLE","id":1},"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientInfo":{"name":"verify-deploy","version":"1.0"},"io.modelcontextprotocol/clientCapabilities":{}}}}')
echo "$resp" | grep -q '"isError":false' && pass "MCP stateless tools/call" \
  || fail "MCP stateless" '"isError":false' "$(echo "$resp" | head -c 200)"
echo "$resp" | grep -q "$BASE/api/" && pass "MCP embute URLs no host" \
  || fail "MCP URLs embutidas" "conter $BASE/api/" "ausente"

# --- MCP stateful burst (preview only — bursts tripam mitigação em prod) ----
if [ "$MODE" = "preview" ]; then
  SID=$("${CURL[@]}" -D - -o /dev/null -X POST "$BASE/mcp" \
    -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
    -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"verify-deploy","version":"1.0"}}}' \
    | tr -d '\r' | awk -F': ' 'tolower($1)=="mcp-session-id"{print $2}')
  if [ -z "$SID" ]; then
    fail "MCP stateful burst" "session id emitido no initialize" "nenhum (guard: burst NÃO executado)"
  else
    burst=$(for i in $(seq 1 12); do
      ( "${CURL[@]}" -o /dev/null -w '%{http_code}\n' -X POST "$BASE/mcp" \
          -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
          -H "Mcp-Session-Id: $SID" \
          -d '{"jsonrpc":"2.0","id":'"$i"',"method":"tools/list"}' ) &
    done; wait)
    ok=$(echo "$burst" | grep -c '^200$')
    [ "$ok" = "12" ] && pass "MCP stateful burst 12x200 (mesma sessão)" \
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
[ "$code" = "404" ] && pass "GET /mcp/sse -> 404 (legacy rejeitado)" || fail "GET /mcp/sse" "404" "$code"
edge=$("${CURL[@]}" -w '\n%{http_code}' -X POST "$BASE/mcp/messages/never-existed" \
  -H 'Content-Type: application/json' -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}')
code=$(echo "$edge" | tail -1)
if [ "$code" = "404" ] && echo "$edge" | head -1 | grep -q '/mcp'; then
  pass "POST /mcp/messages/* -> 404 com JSON citando /mcp"
else
  fail "POST /mcp/messages/*" "404 + corpo citando /mcp" "$code $(echo "$edge" | head -c 120)"
fi

# --- CORS / cache poisoning via Origin --------------------------------------
vary=$("${CURL[@]}" -I -H 'Origin: https://evil.example' "$BASE/api/people/1" | tr -d '\r' | grep -i '^vary')
echo "$vary" | grep -qi 'origin' && pass "Vary contém Origin ($vary)" \
  || fail "Vary: Origin" "header Vary contendo Origin" "${vary:-ausente}"
acao=$("${CURL[@]}" -o /dev/null -w '%header{access-control-allow-origin}' "$BASE/api/people/1")
[ -z "$acao" ] && pass "sem Origin na request -> sem ACAO" \
  || fail "ACAO sem Origin" "vazio" "$acao"

# --- Prod only: edge cache + poisoning X-Forwarded-Host ---------------------
if [ "$MODE" = "prod" ]; then
  c1=$("${CURL[@]}" -I "$BASE/api/people/1" | tr -d '\r' | awk -F': ' 'tolower($1)=="x-vercel-cache"{print $2}')
  c2=$("${CURL[@]}" -I "$BASE/api/people/1" | tr -d '\r' | awk -F': ' 'tolower($1)=="x-vercel-cache"{print $2}')
  [ "$c2" = "HIT" ] && pass "edge cache /api/people/1: $c1 -> HIT" \
    || fail "edge cache /api/people/1" "segunda leitura HIT" "$c1 -> $c2"
  cr=$("${CURL[@]}" -I "$BASE/api/people/random" | tr -d '\r' | awk -F': ' 'tolower($1)=="x-vercel-cache"{print $2}')
  [ "$cr" = "MISS" ] && pass "/api/people/random: sempre MISS" \
    || fail "/api/people/random cache" "MISS" "$cr"
  n=$("${CURL[@]}" -H 'X-Forwarded-Host: evil.example' "$BASE/api/people/3" | grep -c evil.example)
  m=$("${CURL[@]}" "$BASE/api/people/3" | grep -c evil.example)
  [ "$n" = "0" ] && [ "$m" = "0" ] && pass "poisoning X-Forwarded-Host: 0 ocorrências" \
    || fail "poisoning X-Forwarded-Host" "0 e 0" "$n e $m (PURGAR O CACHE JÁ — ver runbook)"
fi

echo
if [ "$FAILURES" -eq 0 ]; then
  echo "verify-deploy [$MODE] $HOST: todos os probes verdes"
else
  echo "verify-deploy [$MODE] $HOST: $FAILURES probe(s) reprovado(s)"
  exit 1
fi
