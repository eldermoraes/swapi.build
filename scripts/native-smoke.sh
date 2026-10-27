#!/usr/bin/env bash
# The "Native smoke" of .github/workflows/native-binary.yml (2.11.0+): the
# binary that is about to be attached to the Release must start and serve. The
# swapi-update on the host switches the link and restarts before it knows the
# new version answers (docs/DEPLOY.md), so a native image that does not start
# would take /api and /mcp down until a manual rollback. Run here, nothing is
# attached and the deploy never begins.
#
# usage: native-smoke.sh <base-url>    e.g. http://127.0.0.1:8099
#
# Waits up to 20 s for GET /api/people/1, then checks /api/people/1 (Luke
# Skywalker), /openapi.json (paths not empty, the version of the pom) and the
# four server-rendered pages and, since 2.12.0, /resource/films/1 and
# /resource/people/1 (marker, phrase, >= 200 characters of <main>, the same
# main_text the deploy probes use). Since 2.12.1, MCP too (mcp_probe): tools/list
# with the four tools, sw_get PEOPLE 1, subscriptions/listen answered within 5 s
# with -32601 (McpTransportFilter reads the body: a path new to the native
# image) and an empty resources/list. Content only: without Caddy in front,
# the poisoning checks belong to verify-deploy.sh. Also run against the jar and
# the local native build in the release rehearsal (docs/RELEASE.md).
set -u

BASE="${1:?usage: native-smoke.sh <base-url>}"
BASE="${BASE%/}"
HERE="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=lib/main-text.sh
. "$HERE/lib/main-text.sh"

CURL=(curl -s --max-time 20)
FAILURES=0
pass() { printf 'PASS  %s\n' "$1"; }
fail() { printf 'FAIL  %s\n      expected: %s\n      got:      %s\n' "$1" "$2" "$3"; FAILURES=$((FAILURES+1)); }

up=""
for _ in $(seq 1 40); do
  if [ "$("${CURL[@]}" -o /dev/null -w '%{http_code}' "$BASE/api/people/1")" = "200" ]; then up=yes; break; fi
  sleep 0.5
done
if [ -z "$up" ]; then
  echo "FAIL  the application did not answer GET $BASE/api/people/1 with 200 within 20 s"
  exit 1
fi

body=$("${CURL[@]}" "$BASE/api/people/1")
[[ "$body" == *'"Luke Skywalker"'* ]] && pass "/api/people/1 -> 200, Luke Skywalker" \
  || fail "/api/people/1" '"Luke Skywalker"' "$(printf '%s' "$body" | head -c 120)"

spec=$("${CURL[@]}" -w '\n%{http_code}' "$BASE/openapi.json")
code="${spec##*$'\n'}"; spec=$(printf '%s' "${spec%$'\n'*}" | tr '\n\r' '  ')
pom=$(sed -n 's|.*<version>\(.*\)</version>.*|\1|p' "$HERE/../swapi-app/pom.xml" | head -1)
if [ "$code" = "200" ] && printf '%s' "$spec" | grep -q '"paths"[[:space:]]*:[[:space:]]*{[[:space:]]*"/' \
   && printf '%s' "$spec" | grep -q "\"version\"[[:space:]]*:[[:space:]]*\"$pom\""; then
  pass "/openapi.json -> 200, paths not empty, version $pom"
else
  fail "/openapi.json" "200, a non-empty paths, version $pom" \
    "$code $(printf '%s' "$spec" | grep -o '"version"[[:space:]]*:[[:space:]]*"[^"]*"' | head -1)"
fi

page_probe() {
  local p="$1" page="$2" phrase="$3" doc meta open text
  doc=$("${CURL[@]}" -H 'Accept: text/html' -w '\n%{http_code} %{content_type}' "$BASE$p")
  meta="${doc##*$'\n'}"; doc="${doc%$'\n'*}"
  open=$(printf '%s\n' "$doc" | main_open_tag)
  text=$(printf '%s\n' "$doc" | main_text)
  if [[ "$meta" == "200 text/html"* ]] && [[ "$open" == *"data-ssr-page=\"$page\""* ]] \
     && [[ "$text" == *"$phrase"* ]] && [ "${#text}" -ge 200 ]; then
    pass "$p drawn on the server: data-ssr-page=\"$page\", ${#text} characters"
  else
    fail "$p" "200 text/html, data-ssr-page=\"$page\", \"$phrase\", >= 200 characters" \
      "$meta, ${open:-no <main>}, ${#text} characters"
  fi
}
page_probe / home 'The Star Wars API'
page_probe /docs docs '/api/people/{id}'
page_probe /docs/mcp mcp "claude mcp add --transport http swapi-build $BASE/mcp"
page_probe /about about 'Why build another one?'
# 2.12.0+: the record addresses, drawn by the one template of detail, whose
# views (RecordViews, the entity getters) are new to the native image.
page_probe /resource/films/1 resource-detail 'George Lucas'
page_probe /resource/people/1 resource-detail 'Luke Skywalker'

# 2.12.1+: the MCP endpoint, stateless, one request per check.
# mcp_probe <label> <method> <params without _meta, or ''> <Mcp-Name or ''> <max s> <check> <expected>
# The check is a function that reads the body on stdin and returns 0 when it holds.
STATELESS_META='"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientInfo":{"name":"native-smoke","version":"1.0"},"io.modelcontextprotocol/clientCapabilities":{}}'
mcp_probe() {
  local label="$1" method="$2" params="$3" name="$4" max="$5" check="$6" expected="$7" extra=() resp rc
  [ -n "$name" ] && extra=(-H "Mcp-Name: $name")
  resp=$(curl -s --max-time "$max" -X POST "$BASE/mcp" \
    -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
    -H "Mcp-Method: $method" -H 'MCP-Protocol-Version: 2026-07-28' ${extra[@]+"${extra[@]}"} \
    -d '{"jsonrpc":"2.0","id":1,"method":"'"$method"'","params":{'"$params${params:+,}$STATELESS_META"'}}')
  rc=$?
  if [ "$rc" = "0" ] && printf '%s' "$resp" | "$check"; then
    pass "$label"
  else
    fail "$label" "$expected" "curl exit $rc, $(printf '%s' "$resp" | head -c 160)"
  fi
}
four_tools() { [ "$(grep -o '"name":"sw_[a-z]*"' | wc -l | tr -d ' ')" = "4" ]; }
has_luke() { grep -qF 'Luke Skywalker'; }
listen_refused() { local b; b=$(cat); printf '%s' "$b" | grep -qF '"code":-32601' && printf '%s' "$b" | grep -qF 'subscriptions/listen'; }
no_resources() { grep -qF '"resources":[]'; }
mcp_probe "MCP tools/list: the four sw_ tools" tools/list '' '' 20 four_tools '4 "name":"sw_..." entries'
mcp_probe "MCP sw_get PEOPLE 1: Luke Skywalker" tools/call \
  '"name":"sw_get","arguments":{"resource":"PEOPLE","id":1}' sw_get 20 has_luke 'Luke Skywalker'
mcp_probe "MCP subscriptions/listen answered within 5 s: -32601" subscriptions/listen \
  '"notifications":{"toolsListChanged":true}' '' 5 listen_refused 'curl ends, -32601 citing subscriptions/listen'
mcp_probe "MCP resources/list: empty" resources/list '' '' 20 no_resources '"resources":[]'

echo
if [ "$FAILURES" -eq 0 ]; then
  echo "native-smoke $BASE: all checks green"
else
  echo "native-smoke $BASE: $FAILURES check(s) failed"
  exit 1
fi
