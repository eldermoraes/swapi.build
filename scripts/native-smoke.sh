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
# four server-rendered pages (marker, phrase, >= 200 characters of <main>, the
# same main_text the deploy probes use). Content only: without Caddy in front,
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

echo
if [ "$FAILURES" -eq 0 ]; then
  echo "native-smoke $BASE: all checks green"
else
  echo "native-smoke $BASE: $FAILURES check(s) failed"
  exit 1
fi
