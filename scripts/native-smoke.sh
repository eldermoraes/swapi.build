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
# image) and, in a 2025-11-25 session, the listen with the method only in the
# body and a resources/subscribe to an unknown URI (-32002), each within 5 s.
# Since 2.13.0: sw_get with expand, sw_list with fields, resources/list and
# resources/read swapi://people/1, prompts/get. Since 2.14.0 (md_probe): the
# Markdown copies /docs/mcp.md and /resource/films/1.md and /llms-full.txt
# (## Records, the copies: five since 2.15.0, with /faq and /faq.md). Content only: without Caddy in front,
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
page_probe /faq faq 'How does swapi.build compare?'
page_probe /about about 'Why build another one?'
# 2.12.0+: the record addresses, drawn by the one template of detail, whose
# views (RecordViews, the entity getters) are new to the native image.
page_probe /resource/films/1 resource-detail 'George Lucas'
page_probe /resource/people/1 resource-detail 'Luke Skywalker'

# 2.14.0+: the Markdown copies (jsoup converts the drawn <main>: new code in the
# native image) and /llms-full.txt, which joins them.
md_probe() {
  local p="$1" phrase="$2" got meta body
  got=$("${CURL[@]}" -w '\n%{http_code} %{content_type}' "$BASE$p")
  meta="${got##*$'\n'}"; body="${got%$'\n'*}"
  if [[ "$meta" == "200 text/markdown"* ]] && printf '%s' "$body" | grep -qF "$phrase" \
     && ! printf '%s' "$body" | grep -qF '<main'; then
    pass "$p -> text/markdown with \"$phrase\", no <main"
  else
    fail "$p" "200 text/markdown, \"$phrase\", no <main" "$meta"
  fi
}
md_probe /docs/mcp.md "claude mcp add --transport http swapi-build $BASE/mcp"
md_probe /faq.md 'How does swapi.build compare?'
md_probe /resource/films/1.md 'George Lucas'
full=$("${CURL[@]}" -w '\n%{http_code} %{content_type}' "$BASE/llms-full.txt")
full_meta="${full##*$'\n'}"; full="${full%$'\n'*}"
full_missing=""
for want in '## Records' 'The Star Wars API' '/api/people/{id}' 'Connect your client' 'How does swapi.build compare?' \
  'Why build another one?'; do
  printf '%s' "$full" | grep -qF "$want" || full_missing="$full_missing \"$want\""
done
full_lines=$(printf '%s\n' "$full" | grep -c '^> HTML version:')
if [[ "$full_meta" == "200 text/plain"* ]] && [ -z "$full_missing" ] && [ "$full_lines" = "5" ]; then
  pass "/llms-full.txt -> text/plain, ## Records, the five copies"
else
  fail "/llms-full.txt" "200 text/plain, ## Records, the five phrases, 5 '> HTML version:' lines" \
    "$full_meta, missing:${full_missing:- none}, $full_lines lines"
fi

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
mcp_probe "MCP tools/list: the four sw_ tools" tools/list '' '' 20 four_tools '4 "name":"sw_..." entries'
mcp_probe "MCP sw_get PEOPLE 1: Luke Skywalker" tools/call \
  '"name":"sw_get","arguments":{"resource":"PEOPLE","id":1}' sw_get 20 has_luke 'Luke Skywalker'
mcp_probe "MCP subscriptions/listen answered within 5 s: -32601" subscriptions/listen \
  '"notifications":{"toolsListChanged":true}' '' 5 listen_refused 'curl ends, -32601 citing subscriptions/listen'
# 2.13.0+: expand and fields (RecordExpansion builds its valid fields at
# startup, JSON-P projects), the resources registered by the ResourceManager
# at startup, and the prompt: paths new to the native image.
expanded_tatooine() { local b; b=$(cat); printf '%s' "$b" | grep -qF 'expanded' && printf '%s' "$b" | grep -qF 'Tatooine'; }
compact_index() { local b; b=$(cat); [ "${#b}" -lt 10000 ] && printf '%s' "$b" | grep -qF 'Luke Skywalker' \
  && ! printf '%s' "$b" | grep -qF 'hair_color'; }
lists_luke() { grep -qF '"uri":"swapi://people/1"'; }
vader_profile() { local b; b=$(cat); printf '%s' "$b" | grep -qF 'sw_search' && printf '%s' "$b" | grep -qF 'Darth Vader'; }
mcp_probe "MCP sw_get expand [homeworld]: Tatooine" tools/call \
  '"name":"sw_get","arguments":{"resource":"PEOPLE","id":1,"expand":["homeworld"]}' sw_get 20 expanded_tatooine \
  'expanded with Tatooine'
mcp_probe "MCP sw_list PEOPLE fields [name]: a compact index" tools/call \
  '"name":"sw_list","arguments":{"resource":"PEOPLE","fields":["name"]}' sw_list 20 compact_index \
  'under 10000 bytes, Luke Skywalker, no hair_color'
mcp_probe "MCP resources/list: swapi://people/1" resources/list '' '' 20 lists_luke '"uri":"swapi://people/1"'
mcp_probe "MCP resources/read swapi://people/1: Luke Skywalker" resources/read '"uri":"swapi://people/1"' \
  'swapi://people/1' 20 has_luke 'Luke Skywalker'
mcp_probe "MCP prompts/get character_profile: Darth Vader" prompts/get \
  '"name":"character_profile","arguments":{"name":"Darth Vader"}' character_profile 20 vader_profile \
  'sw_search and Darth Vader'

# The branch only the filter has: a 2025-11-25 session, the listen with the
# method only in the body (no Mcp-Method) and a subscribe that the extension
# would record. Each curl has a time limit, so a stream left open is a FAIL.
sid=$("${CURL[@]}" -D - -o /dev/null -X POST "$BASE/mcp" \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"native-smoke","version":"1.0"}}}' \
  | tr -d '\r' | awk -F': ' 'tolower($1)=="mcp-session-id"{print $2}')
session_probe() {
  local label="$1" body="$2" check="$3" expected="$4" resp rc
  resp=$(curl -s --max-time 5 -X POST "$BASE/mcp" \
    -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
    -H "Mcp-Session-Id: $sid" -H 'MCP-Protocol-Version: 2025-11-25' -d "$body")
  rc=$?
  if [ "$rc" = "0" ] && printf '%s' "$resp" | "$check"; then
    pass "$label"
  else
    fail "$label" "$expected" "curl exit $rc, $(printf '%s' "$resp" | head -c 160)"
  fi
}
# 2.13.0: swapi://people/1 is a resource; the unknown URI is people/9999.
not_found() { local b; b=$(cat); printf '%s' "$b" | grep -qF '"code":-32002' && printf '%s' "$b" | grep -qF 'swapi://people/9999'; }
if [ -z "$sid" ]; then
  fail "MCP session probes" "a session id on initialize 2025-11-25" "none (probes NOT executed)"
else
  session_probe "MCP session listen, method only in the body: -32601 within 5 s" \
    '{"jsonrpc":"2.0","id":42,"method":"subscriptions/listen","params":{"notifications":{"toolsListChanged":true}}}' \
    listen_refused 'curl ends, -32601 citing subscriptions/listen'
  session_probe "MCP session resources/subscribe unknown URI: -32002 within 5 s" \
    '{"jsonrpc":"2.0","id":43,"method":"resources/subscribe","params":{"uri":"swapi://people/9999"}}' \
    not_found '-32002 citing swapi://people/9999'
fi

echo
if [ "$FAILURES" -eq 0 ]; then
  echo "native-smoke $BASE: all checks green"
else
  echo "native-smoke $BASE: $FAILURES check(s) failed"
  exit 1
fi
