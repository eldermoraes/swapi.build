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
# server-rendered pages (five since 2.15.0, with /faq) and, since 2.12.0, /resource/films/1 and
# /resource/people/1 (marker, phrase, >= 200 characters of <main>, the same
# main_text the deploy probes use). Since 2.12.1, MCP too (mcp_probe): tools/list
# with the four tools, sw_get PEOPLE 1, subscriptions/listen answered within 5 s
# with -32601 (McpTransportFilter reads the body: a path new to the native
# image; HTTP 200, as in a session, except in 2.16.1) and, in a 2025-11-25 session, the listen with the method only in the
# body and a resources/subscribe to an unknown URI (-32002), each within 5 s.
# Since 2.13.0: sw_get with expand, sw_list with fields, resources/list and
# resources/read swapi://people/1, prompts/get. Since 2.14.0 (md_probe): the
# Markdown copies /docs/mcp.md and /resource/films/1.md and /llms-full.txt
# (## Records, the copies: five since 2.15.0, with /faq and /faq.md). Since 2.17.0:
# skills/list (the skill's URI and a digest) and resources/read of its SKILL.md,
# the files SwapiSkills reads from mcp/skills/**. Since 2.17.1: a params that is
# not an object (400, -32602, paired with tools/list) and a prompts/get without
# a name (-32602 within 5 s). Since 2.17.2: the capabilities of initialize
# 2025-11-25 and server/discover carry resources {subscribe:true} and no
# listChanged; and one request of each kind the filter now answers in the
# extension's place (a body that is not a JSON object, an Accept without both
# types, an invalid cursor paired with the initial one, a cancel of nothing)
# and a body cut short, for the "Native log" step of native-binary.yml, which
# reads what the binary logged. Content only: without Caddy in front,
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
# The check is a function that reads the body on stdin and returns 0 when it
# holds; the HTTP status of the answer is in MCP_STATUS while it runs.
STATELESS_META='"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientInfo":{"name":"native-smoke","version":"1.0"},"io.modelcontextprotocol/clientCapabilities":{}}'
MCP_STATUS=""
mcp_probe() {
  local label="$1" method="$2" params="$3" name="$4" max="$5" check="$6" expected="$7" extra=() resp rc
  [ -n "$name" ] && extra=(-H "Mcp-Name: $name")
  resp=$(curl -s --max-time "$max" -w '\n%{http_code}' -X POST "$BASE/mcp" \
    -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
    -H "Mcp-Method: $method" -H 'MCP-Protocol-Version: 2026-07-28' ${extra[@]+"${extra[@]}"} \
    -d '{"jsonrpc":"2.0","id":1,"method":"'"$method"'","params":{'"$params${params:+,}$STATELESS_META"'}}')
  rc=$?
  MCP_STATUS="${resp##*$'\n'}"; resp="${resp%$'\n'*}"
  if [ "$rc" = "0" ] && printf '%s' "$resp" | "$check"; then
    pass "$label"
  else
    fail "$label" "$expected" "curl exit $rc, HTTP $MCP_STATUS, $(printf '%s' "$resp" | head -c 160)"
  fi
}
four_tools() { [ "$(grep -o '"name":"sw_[a-z]*"' | wc -l | tr -d ' ')" = "4" ]; }
has_luke() { grep -qF 'Luke Skywalker'; }
listen_refused() { local b; b=$(cat); printf '%s' "$b" | grep -qF '"code":-32601' && printf '%s' "$b" | grep -qF 'subscriptions/listen'; }
# 2.17.0: HTTP 200 with the -32601 body in both eras. 2.16.1 alone answered
# the stateless listen 404 (the 2026-07-28 spec, as extension 2.1.0 answers a
# method it does not offer); Claude Code 2.1.331 reads that 404 as a dropped
# connection, so the listen departs from the spec on purpose.
listen_refused_200() { [ "$MCP_STATUS" = "200" ] && listen_refused; }
mcp_probe "MCP tools/list: the four sw_ tools" tools/list '' '' 20 four_tools '4 "name":"sw_..." entries'
mcp_probe "MCP sw_get PEOPLE 1: Luke Skywalker" tools/call \
  '"name":"sw_get","arguments":{"resource":"PEOPLE","id":1}' sw_get 20 has_luke 'Luke Skywalker'
mcp_probe "MCP subscriptions/listen answered within 5 s: 200, -32601" subscriptions/listen \
  '"notifications":{"toolsListChanged":true}' '' 5 listen_refused_200 \
  'curl ends, HTTP 200, -32601 citing subscriptions/listen'
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
# 2.17.0+: the skill. SwapiSkills reads its files from the classpath at
# startup, from a fixed list of paths; a native image built without
# mcp/skills/** in quarkus.native.resources.includes does not start.
lists_skill() { local b; b=$(cat); [ "$MCP_STATUS" = "200" ] \
  && printf '%s' "$b" | grep -qF '"uri":"skill://star-wars-research/SKILL.md"' \
  && printf '%s' "$b" | grep -qE '"digest":"sha256:[0-9a-f]{64}"'; }
skill_text() { [ "$MCP_STATUS" = "200" ] && grep -qF 'name: star-wars-research'; }
mcp_probe "MCP skills/list: star-wars-research with a digest" skills/list '' '' 20 lists_skill \
  'HTTP 200, "uri":"skill://star-wars-research/SKILL.md", "digest":"sha256:<64 hex>"'
mcp_probe "MCP resources/read skill://star-wars-research/SKILL.md" resources/read \
  '"uri":"skill://star-wars-research/SKILL.md"' 'skill://star-wars-research/SKILL.md' 20 skill_text \
  'HTTP 200, name: star-wars-research'

# The branch only the filter has: a 2025-11-25 session, the listen with the
# method only in the body (no Mcp-Method) and a subscribe that the extension
# would record. Each curl has a time limit, so a stream left open is a FAIL.
init=$("${CURL[@]}" -i -X POST "$BASE/mcp" \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"native-smoke","version":"1.0"}}}' \
  | tr -d '\r')
sid=$(printf '%s\n' "$init" | sed '/^$/q' | awk -F': ' 'tolower($1)=="mcp-session-id"{print $2}')
init_body=$(printf '%s\n' "$init" | sed '1,/^$/d')
# 2.17.2 (#122): the capabilities promise no notification the server never
# sends. The extension decides listChanged at build time, from the injection
# points of the application (McpCapabilitiesTest): the native binary must say
# the same as the jar. The exact text of resources (a one-key map always
# prints the same) and no listChanged anywhere in the answer (it does not
# echo the client's capabilities).
no_list_changed() { local b; b=$(cat); printf '%s' "$b" | grep -qF '"resources":{"subscribe":true}' \
  && ! printf '%s' "$b" | grep -qF '"listChanged"'; }
if printf '%s' "$init_body" | no_list_changed; then
  pass "MCP initialize 2025-11-25: resources {subscribe:true}, no listChanged"
else
  fail "MCP initialize 2025-11-25 capabilities" '"resources":{"subscribe":true} and no "listChanged"' \
    "$(printf '%s' "$init_body" | grep -o '"capabilities":{.*' | head -c 200)"
fi
discover_no_list_changed() { [ "$MCP_STATUS" = "200" ] && no_list_changed; }
mcp_probe "MCP server/discover: resources {subscribe:true}, no listChanged" server/discover '' '' 20 \
  discover_no_list_changed 'HTTP 200, "resources":{"subscribe":true} and no "listChanged"'
session_probe() {
  local label="$1" body="$2" check="$3" expected="$4" resp rc
  resp=$(curl -s --max-time 5 -w '\n%{http_code}' -X POST "$BASE/mcp" \
    -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
    -H "Mcp-Session-Id: $sid" -H 'MCP-Protocol-Version: 2025-11-25' -d "$body")
  rc=$?
  MCP_STATUS="${resp##*$'\n'}"; resp="${resp%$'\n'*}"
  if [ "$rc" = "0" ] && printf '%s' "$resp" | "$check"; then
    pass "$label"
  else
    fail "$label" "$expected" "curl exit $rc, HTTP $MCP_STATUS, $(printf '%s' "$resp" | head -c 160)"
  fi
}
# 2.13.0: swapi://people/1 is a resource; the unknown URI is people/9999.
not_found() { local b; b=$(cat); printf '%s' "$b" | grep -qF '"code":-32002' && printf '%s' "$b" | grep -qF 'swapi://people/9999'; }
if [ -z "$sid" ]; then
  fail "MCP session probes" "a session id on initialize 2025-11-25" "none (probes NOT executed)"
else
  session_probe "MCP session listen, method only in the body: 200, -32601 within 5 s" \
    '{"jsonrpc":"2.0","id":42,"method":"subscriptions/listen","params":{"notifications":{"toolsListChanged":true}}}' \
    listen_refused_200 'curl ends, HTTP 200, -32601 citing subscriptions/listen'
  session_probe "MCP session resources/subscribe unknown URI: -32002 within 5 s" \
    '{"jsonrpc":"2.0","id":43,"method":"resources/subscribe","params":{"uri":"swapi://people/9999"}}' \
    not_found '-32002 citing swapi://people/9999'
fi

# 2.17.1+: McpTransportFilter refuses the malformed requests the extension
# cannot handle (McpRequestShape: new code in the native image), with -32602.
# Up to 2.17.0 some got HTTP 500, and a few no answer at all. A params that is
# not an object, stateless (HTTP 400), paired with a well-formed tools/list.
good_code=$(curl -s --max-time 20 -o /dev/null -w '%{http_code}' -X POST "$BASE/mcp" \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -H 'Mcp-Method: tools/list' -H 'MCP-Protocol-Version: 2026-07-28' \
  -d '{"jsonrpc":"2.0","id":6,"method":"tools/list","params":{'"$STATELESS_META"'}}')
bad=$(curl -s --max-time 5 -w '\n%{http_code}' -X POST "$BASE/mcp" \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -H 'Mcp-Method: tools/list' -H 'MCP-Protocol-Version: 2026-07-28' \
  -d '{"jsonrpc":"2.0","id":7,"method":"tools/list","params":[]}'); rc=$?
bad_code="${bad##*$'\n'}"; bad="${bad%$'\n'*}"
if [ "$good_code" = "200" ] && [ "$rc" = "0" ] && [ "$bad_code" = "400" ] \
   && printf '%s' "$bad" | grep -qF '"code":-32602' && printf '%s' "$bad" | grep -qF '"id":7'; then
  pass "MCP params that is not an object -> 400, -32602 (tools/list itself -> 200)"
else
  fail "MCP params that is not an object" "tools/list 200; params [] 400 with -32602 and id 7" \
    "tools/list HTTP $good_code; curl exit $rc, HTTP $bad_code, $(printf '%s' "$bad" | head -c 160)"
fi
# A prompts/get without a name must end on its own, within 5 s, refused.
invalid_params_200() { [ "$MCP_STATUS" = "200" ] && grep -qF '"code":-32602'; }
mcp_probe "MCP prompts/get without a name -> 200, -32602 within 5 s" prompts/get '' character_profile 5 \
  invalid_params_200 'curl ends within 5 s, HTTP 200, -32602'

# 2.17.2+ (#126): the refusals the extension made itself and logged at every
# request are written by McpTransportFilter, with the extension's answer
# (new code in the native image). One request of each kind, so that the
# "Native log" step of native-binary.yml reads what the binary logs for
# them: a body that is not a JSON object, an Accept without both types, a
# cursor that does not decode (paired with the initial cursor, served), a
# cancel of nothing, and a body that never arrives whole.
mcp_raw() { # <max s> <body> [header ...]: the body, then the HTTP status on a line of its own
  local max="$1" body="$2"; shift 2
  curl -s --max-time "$max" -w '\n%{http_code}' -X POST "$BASE/mcp" -H 'Content-Type: application/json' "$@" -d "$body"
}
raw_probe() { # <label> <expected> <status> <grep -F text, or ''> <max s> <body> [header ...]
  local label="$1" expected="$2" want="$3" text="$4" resp rc code; shift 4
  resp=$(mcp_raw "$@"); rc=$?
  code="${resp##*$'\n'}"; resp="${resp%$'\n'*}"
  if [ "$rc" = "0" ] && [ "$code" = "$want" ] && { [ -z "$text" ] || printf '%s' "$resp" | grep -qF "$text"; }; then
    pass "$label"
  else
    fail "$label" "$expected" "curl exit $rc, HTTP $code, $(printf '%s' "$resp" | head -c 160)"
  fi
}
TRANSPORT_ACCEPT='Accept: application/json, text/event-stream'
raw_probe "MCP body that is not a JSON object -> 200, -32700" 'HTTP 200, -32700' 200 '"code":-32700' 5 \
  '"x"' -H "$TRANSPORT_ACCEPT"
raw_probe "MCP Accept without text/event-stream -> 400" 'HTTP 400' 400 '' 5 \
  '{"jsonrpc":"2.0","id":8,"method":"tools/list"}' -H 'Accept: application/json'
raw_probe "MCP tools/list with an invalid cursor -> 200, -32602" 'HTTP 200, -32602 Invalid cursor' 200 \
  'Invalid cursor detected' 5 '{"jsonrpc":"2.0","id":9,"method":"tools/list","params":{"cursor":"x"}}' \
  -H "$TRANSPORT_ACCEPT"
raw_probe "MCP tools/list with the initial cursor -> 200, the tools" 'HTTP 200 with sw_get' 200 '"name":"sw_get"' 5 \
  '{"jsonrpc":"2.0","id":10,"method":"tools/list","params":{"cursor":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"}}' \
  -H "$TRANSPORT_ACCEPT"
raw_probe "MCP notifications/cancelled of nothing -> 202" 'HTTP 202' 202 '' 5 \
  '{"jsonrpc":"2.0","method":"notifications/cancelled","params":{"requestId":99}}' -H "$TRANSPORT_ACCEPT"
# A body shorter than its Content-Length, then the connection closed, over
# bash's /dev/tcp (an http://host:port base only); then the server must go
# on serving.
hostport="${BASE#http://}"; hostport="${hostport%%/*}"
if [[ "$BASE" == http://*:* ]] && exec 3<>"/dev/tcp/${hostport%:*}/${hostport##*:}"; then
  printf 'POST /mcp HTTP/1.1\r\nHost: %s\r\nContent-Type: application/json\r\n%s\r\nContent-Length: 200\r\n\r\n{"jsonrpc":"2.0"' \
    "$hostport" "$TRANSPORT_ACCEPT" >&3
  exec 3>&-
  sleep 0.5
  raw_probe "MCP served after a body cut short" 'HTTP 200 with sw_get' 200 '"name":"sw_get"' 5 \
    '{"jsonrpc":"2.0","id":11,"method":"tools/list"}' -H "$TRANSPORT_ACCEPT"
else
  fail "MCP body cut short" "an http://host:port base and a /dev/tcp connection" "$BASE (probe NOT executed)"
fi

echo
if [ "$FAILURES" -eq 0 ]; then
  echo "native-smoke $BASE: all checks green"
else
  echo "native-smoke $BASE: $FAILURES check(s) failed"
  exit 1
fi
