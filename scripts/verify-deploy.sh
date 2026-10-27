#!/usr/bin/env bash
# Probes from docs/DEPLOY.md as a single source (CI and operator use the same script).
# The runbook explains the why of each check and wins on divergence.
#
# usage: verify-deploy.sh origin <host>    (the self-hosted origin behind Caddy, docs/DEPLOY.md
#                                           "Self-hosted origin on Oracle Cloud"): REST, OpenAPI, SEO,
#                                           the site pages and assets, the not-found variants,
#                                           MCP (stateless, stateful burst, edges), CORS, the
#                                           X-Forwarded-Host / Forwarded poisoning probes, the
#                                           record addresses (2.10.0+: films indexable with their
#                                           own canonical and title, 20 sitemap URLs, no lastmod),
#                                           X-Robots-Tag noindex on /api only, the server-rendered
#                                           content (2.11.0+: the <main> of /, /docs, /docs/mcp
#                                           and /about with its marker and text, the lean
#                                           JSON-LD), the record content (2.12.0+: films/1
#                                           and people/1 with marker, name, links and the
#                                           samples on the host; people still noindex; the
#                                           poisoning pairs of both and Forwarded on films/1),
#                                           the trailing-slash 301, and the Caddy headers
#                                           (HSTS, no Server)
#        verify-deploy.sh redirect <from-host> <to-host>
#                                          (a name that only redirects, today www.swapi.build ->
#                                           swapi.build): without following redirects, GET /,
#                                           GET with a path and query, GET /sitemap.xml,
#                                           GET /llms.txt, GET /index.html, GET /not-a-real-page,
#                                           GET /resource/films/1 and POST /mcp on
#                                           https://<from-host> must each answer 308 with
#                                           Location exactly https://<to-host> + the same path
#                                           and query; and (2.11.0+) /docs/mcp, and (2.12.0+)
#                                           /resource/films/1, followed once to <to-host> must
#                                           give their text without JavaScript
#
# The `preview` and `prod` modes probed the former Vercel deployments and were
# removed with them.
set -u

USAGE="usage: verify-deploy.sh origin <host> | verify-deploy.sh redirect <from-host> <to-host>"
MODE="${1:?$USAGE}"
HOST="${2:?$USAGE}"
HOST="${HOST#https://}"; HOST="${HOST%%/*}"
# VERIFY_DEPLOY_TEST_SCHEME exists only for tests and rehearsals that speak plain
# http on a local port: scripts/verify-deploy-redirect.test.sh (its fake www host)
# and the local prod-mode rehearsal of a release (origin mode against the jar on
# localhost). CI and the operator never set it: the host is reached over https.
# In redirect mode the expected Location is always https.
SCHEME="${VERIFY_DEPLOY_TEST_SCHEME:-https}"
BASE="${SCHEME}://${HOST}"

case "$MODE" in origin|redirect) ;; *) echo "invalid mode: $MODE"; echo "$USAGE"; exit 2 ;; esac

CURL=(curl -s --max-time 90)

# main_text and main_open_tag: the <main> the server draws (2.11.0+).
# shellcheck source=lib/main-text.sh
. "$(dirname "$0")/lib/main-text.sh"

FAILURES=0
pass() { printf 'PASS  %s\n' "$1"; }
fail() { printf 'FAIL  %s\n      expected: %s\n      got:      %s\n' "$1" "$2" "$3"; FAILURES=$((FAILURES+1)); }

# --- redirect mode ------------------------------------------------------------
if [ "$MODE" = "redirect" ]; then
  TO="${3:?$USAGE}"
  TO="${TO#https://}"; TO="${TO%%/*}"
  FROM_BASE="$BASE"

  # redirect_probe <label> <path+query> [curl args...]: no -L, so the 308 itself is read.
  redirect_probe() {
    local label="$1" target="$2"; shift 2
    local want="https://${TO}${target}"
    local got
    got=$("${CURL[@]}" -o /dev/null -w '%{http_code} %header{location}' "$@" "${FROM_BASE}${target}")
    if [ "$got" = "308 $want" ]; then
      pass "$label $target -> 308 $want"
    else
      fail "$label $target" "308 $want" "${got:-no answer}"
    fi
  }

  redirect_probe GET "/"
  redirect_probe GET "/api/people/1?format=json"
  redirect_probe GET "/sitemap.xml"
  redirect_probe GET "/llms.txt"
  # Caddy must not swallow these on the www name either; the app's own 301 and
  # 404 are proved in origin mode.
  redirect_probe GET "/index.html"
  redirect_probe GET "/not-a-real-page"
  # A record address (2.10.0+, films are indexable): www sends it to the apex.
  redirect_probe GET "/resource/films/1"
  # 308 (not 301) keeps the method and body: an MCP client posting to www must
  # be sent on to the apex still as a POST.
  redirect_probe POST "/mcp" -X POST \
    -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
    -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'

  # follow_probe <path> <phrase> (2.11.0+): the road of an agent that gets the www
  # name and follows the 308 without JavaScript. Reads the Location without -L,
  # fetches it once (https swapped for $SCHEME, which is https outside the tests)
  # and wants 200 text/html whose <main> has the phrase and at least 200
  # characters. A Location off https://<to-host> is never followed.
  follow_probe() {
    local target="$1" phrase="$2" location page meta text
    location=$("${CURL[@]}" -o /dev/null -w '%header{location}' "${FROM_BASE}${target}")
    case "$location" in
      "https://${TO}/"*) ;;
      *) fail "follow $target" "Location on https://${TO}/" "${location:-none}"; return ;;
    esac
    page=$("${CURL[@]}" -H 'Accept: text/html' -w '\n%{http_code} %{content_type}' \
      "${SCHEME}://${location#https://}")
    meta="${page##*$'\n'}"
    text=$(printf '%s\n' "${page%$'\n'*}" | main_text)
    if [[ "$meta" == "200 text/html"* ]] && [[ "$text" == *"$phrase"* ]] && [ "${#text}" -ge 200 ]; then
      pass "follow $target -> $location: 200 text/html, ${#text} characters of <main> with the phrase"
    else
      fail "follow $target" "200 text/html, <main> with \"$phrase\" and >= 200 characters" \
        "$meta, ${#text} characters"
    fi
  }
  follow_probe "/docs/mcp" "claude mcp add --transport http swapi-build https://${TO}/mcp"
  # 2.12.0+: a record too, the page an agent is most often handed.
  follow_probe "/resource/films/1" "George Lucas"

  echo
  if [ "$FAILURES" -eq 0 ]; then
    echo "verify-deploy [$MODE] $HOST -> $TO: all probes green"
    exit 0
  fi
  echo "verify-deploy [$MODE] $HOST -> $TO: $FAILURES probe(s) failed"
  exit 1
fi

# --- REST -------------------------------------------------------------------
body=$("${CURL[@]}" "$BASE/api/people/1")
code=$("${CURL[@]}" -o /dev/null -w '%{http_code}' "$BASE/api/people/1")
[ "$code" = "200" ] && pass "REST /api/people/1 -> 200" \
  || fail "REST /api/people/1" "200" "$code"
echo "$body" | grep -q "$BASE/api/people/1" && pass "REST embeds https URLs on the host" \
  || fail "REST embedded URLs" "contain $BASE/api/people/1" "missing"

# --- Usage counters (2.6.0+) -------------------------------------------------
# The REST probe above was just counted, so /api/people/{id} is in the window.
stats=$("${CURL[@]}" -w '\n%{http_code}' "$BASE/api/stats")
code="${stats##*$'\n'}"
if [ "$code" = "200" ] && echo "$stats" | grep -q '"countingSince"' \
   && echo "$stats" | grep -qF '"/api/people/{id}"'; then
  pass "/api/stats -> 200 with per-route counts"
else
  fail "/api/stats" "200 with countingSince and /api/people/{id}" "$code"
fi

# Windows and the page (2.7.0+).
for spec in 24h:hour:24 30d:day:30; do
  p="${spec%%:*}"; rest="${spec#*:}"; g="${rest%%:*}"; n="${rest#*:}"
  window=$("${CURL[@]}" "$BASE/api/stats?period=$p")
  if echo "$window" | python3 -c '
import json, sys
period, granularity, buckets = sys.argv[1], sys.argv[2], int(sys.argv[3])
v = json.load(sys.stdin)
assert v["period"] == period and v["granularity"] == granularity
assert len(v["series"]) == buckets
assert set(v["groups"]) == {"rest", "mcp", "pages", "seo", "other"}
assert "total" in v["previous"]
' "$p" "$g" "$n" 2>/dev/null; then
    pass "/api/stats?period=$p -> $n $g buckets, groups and previous window"
  else
    fail "/api/stats?period=$p" "$n $g buckets with groups and previous" "$(echo "$window" | head -c 160)"
  fi
done

# Server times (2.8.0+): the REST probe above was timed under its template too.
perf=$("${CURL[@]}" "$BASE/api/stats?period=24h")
if echo "$perf" | python3 -c '
import json, sys
p = json.load(sys.stdin)["performance"]
assert p["unit"] == "ms"
assert set(p["groups"]) == {"rest", "mcp", "pages", "seo", "other"}
assert len(p["series"]) == 24
r = p["routes"]["/api/people/{id}"]
assert r["count"] >= 1
for k in ("mean", "min", "max", "stddev", "p50", "p95", "p99"):
    assert r[k] >= 0
' 2>/dev/null; then
  pass "/api/stats performance -> server times per route and group, 24 latency points"
else
  fail "/api/stats performance" "performance with routes, 5 groups and 24 points" "$(echo "$perf" | head -c 160)"
fi
code=$("${CURL[@]}" -o /dev/null -w '%{http_code}' "$BASE/api/stats?period=1y")
[ "$code" = "400" ] && pass "/api/stats?period=1y -> 400" || fail "/api/stats?period=1y" "400" "$code"
stats_html=$("${CURL[@]}" -H 'Accept: text/html' "$BASE/stats")
# grep -F: the title carries a '|', which must not depend on the grep mode.
echo "$stats_html" | grep -qF '<title>Usage stats | swapi.build</title>' && pass "/stats page title" \
  || fail "/stats page title" "<title>Usage stats | swapi.build</title>" "missing"

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
webmcp_html=$("${CURL[@]}" -H 'Accept: text/html' "$BASE/docs/webmcp")
echo "$webmcp_html" | grep -qF '<title>Star Wars WebMCP tools in the browser | swapi.build</title>' && pass "WebMCP guide title" \
  || fail "WebMCP guide title" "route-specific title" "missing"
echo "$sitemap" | grep -q "<loc>$BASE/docs/webmcp</loc>" && pass "sitemap.xml lists WebMCP guide" \
  || fail "sitemap.xml WebMCP guide" "<loc>$BASE/docs/webmcp</loc>" "missing"

# llms.txt (2.9.0+): served by the app as text even to an HTML Accept (before
# 2.9.0 it fell into the SPA shell), with the connection line on the host.
ct=$("${CURL[@]}" -o /dev/null -w '%{http_code} %{content_type}' -H 'Accept: text/html' "$BASE/llms.txt")
case "$ct" in "200 text/plain"*) pass "llms.txt served as text ($ct)";;
  *) fail "llms.txt content-type" "200 text/plain" "$ct (text/html = the SPA swallowed it)";; esac
llms=$("${CURL[@]}" "$BASE/llms.txt")
if echo "$llms" | grep -qF '# swapi.build' && echo "$llms" | grep -qF "$BASE/mcp"; then
  pass "llms.txt carries the title and $BASE/mcp"
else
  fail "llms.txt body" "# swapi.build and $BASE/mcp" "$(echo "$llms" | head -c 160)"
fi

# The two icons server.json gives the MCP Registry. Before 2.9.0, /icon-512.png
# answered the SPA shell (200 text/html).
ct=$("${CURL[@]}" -o /dev/null -w '%{http_code} %{content_type}' "$BASE/favicon.svg")
case "$ct" in "200 image/svg+xml"*) pass "favicon.svg served as SVG ($ct)";;
  *) fail "favicon.svg content-type" "200 image/svg+xml" "$ct";; esac
ct=$("${CURL[@]}" -o /dev/null -w '%{http_code} %{content_type}' "$BASE/icon-512.png")
case "$ct" in "200 image/png"*) pass "icon-512.png served as PNG ($ct)";;
  *) fail "icon-512.png content-type" "200 image/png" "$ct (text/html = the SPA shell)";; esac

# IndexNow key file (2.9.0+): the one public/*.txt of the checked-out tag, whose
# body is the key itself.
for keyfile in "$(dirname "$0")"/../swapi-app/src/main/webui/public/*.txt; do
  [ -f "$keyfile" ] || { echo "INFO  no IndexNow key file in the checkout"; break; }
  key=$(basename "$keyfile" .txt)
  got=$("${CURL[@]}" -w '\n%{http_code} %{content_type}' "$BASE/$key.txt")
  meta="${got##*$'\n'}"; body="${got%$'\n'*}"
  case "$meta" in "200 text/plain"*)
    if [ "$body" = "$key" ]; then pass "IndexNow key file served as text with the key"
    else fail "IndexNow key file body" "$key" "$(echo "$body" | head -c 80)"; fi;;
  *) fail "IndexNow key file" "200 text/plain" "$meta";; esac
done


ct=$("${CURL[@]}" -o /dev/null -w '%{http_code} %{content_type}' "$BASE/og-image.png")
case "$ct" in "200 image/png"*) pass "og-image.png served as PNG ($ct)";;
  *) fail "og-image.png content-type" "200 image/png" "$ct";; esac

home=$("${CURL[@]}" "$BASE/")
echo "$home" | grep -q 'rel="canonical"' && echo "$home" | grep -q 'property="og:url"' && echo "$home" | grep -q 'name="twitter:card"' \
  && pass "home HTML includes canonical, og:url and Twitter Card" \
  || fail "home SEO metadata" 'canonical + og:url + twitter:card' "missing"
echo "$home" | grep -q "content=\"$BASE/\"" && pass "home SEO uses absolute URLs on the host" \
  || fail "home SEO URLs" "content=\"$BASE/\"" "missing"

# --- Site still works (2.9.1+) -------------------------------------------------
# Quinoa's SPA routing is off since 2.9.1: every page the site has must still be
# served by the app, and the bundle must still load. Any FAIL here after a
# deploy = immediate rollback (docs/DEPLOY.md, "5. Rollback"), before the MCP
# Registry publish.
# Assets by GET: a HEAD carries no Content-Type.
js=0; css=0
for asset in $(echo "$home" | grep -o '/assets/[^"]*' | sort -u); do
  case "$asset" in *.js) want=text/javascript; js=$((js+1));; *.css) want=text/css; css=$((css+1));; *) want=unknown;; esac
  got=$("${CURL[@]}" -o /dev/null -w '%{http_code} %{content_type} | %header{cache-control}' "$BASE$asset")
  case "$got" in "200 $want"*immutable*) pass "asset $asset -> $got";;
    *) fail "asset $asset" "200 $want with immutable" "$got";; esac
done
[ "$js" -ge 1 ] && [ "$css" -ge 1 ] && pass "home references $js JS and $css CSS asset(s)" \
  || fail "home assets" ">= 1 JS and >= 1 CSS under /assets/" "$js JS, $css CSS"

# Every sitemap <loc>: 200 text/html whose canonical is the loc itself.
locs=$(echo "$sitemap" | sed -n 's|.*<loc>\([^<]*\)</loc>.*|\1|p')
[ -n "$locs" ] || fail "sitemap.xml <loc>" "at least one <loc>" "none"
for loc in $locs; do
  page=$("${CURL[@]}" -H 'Accept: text/html' -w '\n%{http_code} %{content_type}' "$loc")
  meta="${page##*$'\n'}"
  if [[ "$meta" == "200 text/html"* ]] && echo "$page" | grep -qF "<link rel=\"canonical\" href=\"$loc\" />"; then
    pass "sitemap $loc -> 200 text/html, canonical = loc"
  else
    fail "sitemap $loc" "200 text/html with canonical $loc" "$meta $(echo "$page" | grep -o '<link rel="canonical"[^>]*>' | head -1)"
  fi
done

# The highest id of each type in the dataset: the edges of KnownEntities.
for p in /resource/people/83 /resource/films/6 /resource/planets/1 /resource/species/1 \
         /resource/starships/2 /resource/vehicles/4; do
  code=$("${CURL[@]}" -o /dev/null -w '%{http_code}' -H 'Accept: text/html' "$BASE$p")
  [ "$code" = "200" ] && pass "$p -> 200" || fail "$p" "200" "$code"
done
# A trailing slash on a page (2.10.0+): 301 to the bare path, query kept. Before
# 2.10.0 it answered 200 with the canonical of the bare path.
got=$("${CURL[@]}" -o /dev/null -w '%{http_code} %header{location}' -H 'Accept: text/html' "$BASE/docs/")
[ "$got" = "301 /docs" ] && pass "/docs/ -> 301 Location /docs" || fail "/docs/" "301 /docs" "$got"
got=$("${CURL[@]}" -o /dev/null -w '%{http_code} %header{location}' -H 'Accept: text/html' "$BASE/resource/films/1/?probe=$$")
[ "$got" = "301 /resource/films/1?probe=$$" ] && pass "/resource/films/1/?probe=$$ -> 301 Location /resource/films/1?probe=$$" \
  || fail "/resource/films/1/?probe=$$" "301 /resource/films/1?probe=$$" "$got"
code=$("${CURL[@]}" -o /dev/null -I -w '%{http_code}' "$BASE/")
[ "$code" = "200" ] && pass "HEAD / -> 200" || fail "HEAD /" "200" "$code"

got=$("${CURL[@]}" -o /dev/null -w '%{http_code} %header{location}' "$BASE/index.html")
[ "$got" = "301 /" ] && pass "/index.html -> 301 Location /" || fail "/index.html" "301 /" "$got"
got=$("${CURL[@]}" -o /dev/null -w '%{http_code} %header{location}' "$BASE/index.html?probe=$$")
[ "$got" = "301 /?probe=$$" ] && pass "/index.html?probe=$$ -> 301 Location /?probe=$$" \
  || fail "/index.html?probe=$$" "301 /?probe=$$" "$got"

cc=$("${CURL[@]}" -o /dev/null -w '%header{cache-control}' -H 'Accept: text/html' "$BASE/")
[ "$cc" = "public, max-age=0, must-revalidate, s-maxage=300" ] && pass "/ Cache-Control: $cc" \
  || fail "/ Cache-Control" "public, max-age=0, must-revalidate, s-maxage=300" "${cc:-missing}"

# --- Not found and variants (2.9.1+) --------------------------------------------
# Before 2.9.1 every unknown path answered 200 with the home (a soft 404), a
# dotted path came back index,follow, a missing asset got the HTML shell, and
# each of those was counted twice in /stats (Quinoa's reroute). The app's own
# not-found document carries the swapi:status marker; the framework 404 (dotted
# paths, assets, /.well-known/) is Vert.x's bare page, without the shell marks.
SHELL_MARKS='id="app"|id="main-content"|swapi:status|<title>'
# not_found_doc <path>: 404 text/html, noindex, the marker, no canonical, no-cache.
not_found_doc() {
  local p="$1" page meta
  page=$("${CURL[@]}" -H 'Accept: text/html' -w '\n%{http_code} %{content_type} | %header{cache-control}' "$BASE$p")
  meta="${page##*$'\n'}"
  if [[ "$meta" == "404 text/html"*"| no-cache" ]] && echo "$page" | grep -qF 'content="noindex"' \
     && echo "$page" | grep -qF 'name="swapi:status" content="404"' && ! echo "$page" | grep -qF 'rel="canonical"'; then
    pass "$p -> 404 not-found document, noindex, no canonical, no-cache"
  else
    fail "$p" "404 text/html, noindex, swapi:status, no canonical, Cache-Control no-cache" \
      "$meta (200 text/html = the soft 404 is back)"
  fi
}
# framework_404 <path> [want-cache-control]: 404 without any shell mark.
framework_404() {
  local p="$1" want_cc="${2:-}" page meta marks
  page=$("${CURL[@]}" -H 'Accept: text/html' -w '\n%{http_code} | %header{cache-control}' "$BASE$p")
  meta="${page##*$'\n'}"
  marks=$(echo "${page%$'\n'*}" | grep -cE "$SHELL_MARKS")
  if [[ "$meta" == "404 |"* ]] && [ "$marks" = "0" ] && { [ -z "$want_cc" ] || [ "$meta" = "404 | $want_cc" ]; }; then
    pass "$p -> framework 404 without the shell${want_cc:+, Cache-Control $want_cc}"
  else
    fail "$p" "404 without the shell marks${want_cc:+, Cache-Control $want_cc}" \
      "$meta, $marks shell mark(s) (200 or marks = the SPA reroute is back)"
  fi
}
not_found_doc /not-a-real-page
not_found_doc /resource/people/9999
framework_404 /foo.json
framework_404 /assets/does-not-exist.js no-cache
framework_404 /.well-known/oauth-protected-resource
# Control: an entity that exists keeps its page and its canonical.
page=$("${CURL[@]}" -H 'Accept: text/html' -w '\n%{http_code}' "$BASE/resource/people/1")
if [ "${page##*$'\n'}" = "200" ] && echo "$page" | grep -qF "<link rel=\"canonical\" href=\"$BASE/resource/people\" />"; then
  pass "/resource/people/1 -> 200, canonical $BASE/resource/people"
else
  fail "/resource/people/1" "200 with canonical $BASE/resource/people" "${page##*$'\n'}"
fi

# --- Record addresses (2.10.0+) ------------------------------------------------
# Indexing is a rule per type in seo/site.json; films is the first batch. Each
# film: 200 text/html, index,follow, its own canonical. The exact title of film 1
# proves site.json reached the native image (its absence stops the app instead).
for id in 1 2 3 4 5 6; do
  page=$("${CURL[@]}" -H 'Accept: text/html' -w '\n%{http_code} %{content_type}' "$BASE/resource/films/$id")
  meta="${page##*$'\n'}"
  if [[ "$meta" == "200 text/html"* ]] && echo "$page" | grep -qF 'name="robots" content="index,follow"' \
     && echo "$page" | grep -qF "<link rel=\"canonical\" href=\"$BASE/resource/films/$id\" />"; then
    pass "/resource/films/$id -> 200, index,follow, canonical = itself"
  else
    fail "/resource/films/$id" "200 text/html, index,follow, canonical $BASE/resource/films/$id" \
      "$meta $(echo "$page" | grep -o '<link rel="canonical"[^>]*>' | head -1) $(echo "$page" | grep -o 'name="robots" content="[^"]*"' | head -1)"
  fi
  if [ "$id" = "1" ]; then
    echo "$page" | grep -qF '<title>A New Hope — Star Wars film JSON | swapi.build</title>' \
      && pass "/resource/films/1 title from seo/site.json" \
      || fail "/resource/films/1 title" "<title>A New Hope — Star Wars film JSON | swapi.build</title>" \
        "$(echo "$page" | grep -o '<title>[^<]*</title>' | head -1)"
  fi
done
# Control: a type outside the batch stays noindex,follow with its list canonical.
page=$("${CURL[@]}" -H 'Accept: text/html' -w '\n%{http_code}' "$BASE/resource/people/1")
if [ "${page##*$'\n'}" = "200" ] && echo "$page" | grep -qF 'name="robots" content="noindex,follow"' \
   && echo "$page" | grep -qF "<link rel=\"canonical\" href=\"$BASE/resource/people\" />"; then
  pass "/resource/people/1 stays noindex,follow, canonical $BASE/resource/people"
else
  fail "/resource/people/1 robots" "200, noindex,follow, canonical $BASE/resource/people" "${page##*$'\n'}"
fi
# The sitemap: the 14 pages and lists plus the six films, nothing else, no lastmod.
# Counted by occurrence, not by line: the count must not depend on the layout.
nlocs=$(echo "$sitemap" | grep -o '<loc>' | wc -l | tr -d ' ')
[ "$nlocs" = "20" ] && pass "sitemap.xml has 20 <loc>" || fail "sitemap.xml <loc> count" "20" "$nlocs"
missing=""
for id in 1 2 3 4 5 6; do
  echo "$sitemap" | grep -qF "<loc>$BASE/resource/films/$id</loc>" || missing="$missing $id"
done
[ -z "$missing" ] && pass "sitemap.xml lists the six films" \
  || fail "sitemap.xml films" "<loc>$BASE/resource/films/1..6</loc>" "missing:$missing"
others=$(echo "$sitemap" | grep -cE '<loc>[^<]*/resource/(people|planets|species|starships|vehicles)/[0-9]')
[ "$others" = "0" ] && pass "sitemap.xml lists no record of a type outside the batch" \
  || fail "sitemap.xml other records" "0" "$others"
lastmod=$(echo "$sitemap" | grep -o '<lastmod>' | wc -l | tr -d ' ')
[ "$lastmod" = "0" ] && pass "sitemap.xml carries no <lastmod>" || fail "sitemap.xml <lastmod>" "0" "$lastmod"

# --- Robots header (2.10.0+) ---------------------------------------------------
# X-Robots-Tag: noindex on the API only (the JSON is not a page to rank). It must
# never reach the HTML, robots.txt, the sitemap, llms.txt or the assets: on a
# page it would deindex the site.
for p in /api/people/1 /api/films/9; do
  xrt=$("${CURL[@]}" -o /dev/null -w '%header{x-robots-tag}' "$BASE$p")
  [ "$xrt" = "noindex" ] && pass "$p X-Robots-Tag: noindex" || fail "$p X-Robots-Tag" "noindex" "${xrt:-missing}"
done
for p in / /resource/films/1 /openapi.json /robots.txt /sitemap.xml /llms.txt; do
  xrt=$("${CURL[@]}" -o /dev/null -w '%header{x-robots-tag}' -H 'Accept: text/html' "$BASE$p")
  [ -z "$xrt" ] && pass "$p has no X-Robots-Tag" || fail "$p X-Robots-Tag" "absent" "$xrt"
done
cc=$("${CURL[@]}" -o /dev/null -w '%header{cache-control}' "$BASE/api/people/1")
[ "$cc" = "public, max-age=300, s-maxage=31536000, stale-while-revalidate=86400" ] \
  && pass "/api/people/1 Cache-Control: $cc" \
  || fail "/api/people/1 Cache-Control" "public, max-age=300, s-maxage=31536000, stale-while-revalidate=86400" "${cc:-missing}"

# --- Server-rendered content (2.11.0+) --------------------------------------------
# The <main> of the four fixed pages is drawn by the server (Qute), for whoever
# does not run JavaScript. Each page: 200 text/html, its data-ssr-page marker on
# the opening tag, its phrase in the text and at least 200 characters of text
# (main_text cuts at the first </main>: the footer never counts). /docs/mcp
# carries the request host, so its phrase proves the host is the right one. Any
# FAIL after a deploy = immediate rollback (docs/DEPLOY.md, "5. Rollback").
ssr_probe() {
  local p="$1" page="$2" phrase="$3" doc meta open text
  doc=$("${CURL[@]}" -H 'Accept: text/html' -w '\n%{http_code} %{content_type}' "$BASE$p")
  meta="${doc##*$'\n'}"
  doc="${doc%$'\n'*}"
  open=$(printf '%s\n' "$doc" | main_open_tag)
  text=$(printf '%s\n' "$doc" | main_text)
  if [[ "$meta" == "200 text/html"* ]] && [[ "$open" == *"data-ssr-page=\"$page\""* ]] \
     && [[ "$text" == *"$phrase"* ]] && [ "${#text}" -ge 200 ]; then
    pass "$p drawn on the server: data-ssr-page=\"$page\", \"$phrase\", ${#text} characters"
  else
    fail "$p server-rendered" "200 text/html, data-ssr-page=\"$page\", \"$phrase\", >= 200 characters" \
      "$meta, ${open:-no <main>}, ${#text} characters"
  fi
}
ssr_probe / home 'The Star Wars API'
ssr_probe /docs docs '/api/people/{id}'
ssr_probe /docs/mcp mcp "claude mcp add --transport http swapi-build $BASE/mcp"
ssr_probe /about about 'Why build another one?'
# The 404 draws nothing.
n=$("${CURL[@]}" -H 'Accept: text/html' "$BASE/not-a-real-page" | grep -c 'data-ssr-')
[ "$n" = "0" ] && pass "/not-a-real-page has no data-ssr- marker" \
  || fail "/not-a-real-page marker" "no data-ssr-" "$n line(s)"
cc=$("${CURL[@]}" -o /dev/null -w '%header{cache-control}' -H 'Accept: text/html' "$BASE/docs/mcp")
[ "$cc" = "public, max-age=0, must-revalidate, s-maxage=300" ] && pass "/docs/mcp Cache-Control: $cc" \
  || fail "/docs/mcp Cache-Control" "public, max-age=0, must-revalidate, s-maxage=300" "${cc:-missing}"
# The texts of the Vercel era are gone (Decision 1 of slice E), and the five
# guides read without JavaScript: the Codex one sits in an inactive panel.
mcp_doc=$("${CURL[@]}" -H 'Accept: text/html' "$BASE/docs/mcp")
mcp_text=$(printf '%s\n' "$mcp_doc" | main_text)
if [[ "$mcp_text" != *"scales to zero"* ]] && [[ "$mcp_text" != *"different instance"* ]]; then
  pass "/docs/mcp says neither \"scales to zero\" nor \"different instance\""
else
  fail "/docs/mcp Vercel-era texts" "neither \"scales to zero\" nor \"different instance\"" "present"
fi
[[ "$mcp_text" == *"codex mcp add swapi-build --url $BASE/mcp"* ]] \
  && pass "/docs/mcp carries the Codex guide in the HTML (five panels readable without JavaScript)" \
  || fail "/docs/mcp Codex guide" "codex mcp add swapi-build --url $BASE/mcp in <main>" "missing"
# JSON-LD (2.11.0+), read inside its <script>.
jsonld() {
  "${CURL[@]}" -H 'Accept: text/html' "$BASE$1" | tr '\n' ' ' \
    | sed -n 's|.*<script type="application/ld+json">\(.*\)</script>.*|\1|p' | sed 's|</script>.*||'
}
ld_home=$(jsonld /)
missing=""
for want in '"alternateName"' '"@type":"Person"' '"@type":"Dataset"'; do
  printf '%s' "$ld_home" | grep -qF "$want" || missing="$missing $want"
done
[ -z "$missing" ] && pass "home JSON-LD: alternateName, Person and Dataset" \
  || fail "home JSON-LD" '"alternateName", "@type":"Person", "@type":"Dataset"' "missing:$missing"
jsonld /docs/mcp | grep -qF '"@type":"BreadcrumbList"' && pass "/docs/mcp JSON-LD: BreadcrumbList" \
  || fail "/docs/mcp JSON-LD" '"@type":"BreadcrumbList"' "missing"

# --- Record content (2.12.0+) ------------------------------------------------------
# Every record address of the six types is drawn by the one template of detail:
# marker resource-detail (with type and id), the name, the attributes, the
# related records as links, the samples on the request host and the JSON.
# Indexing does not change (slice D): people stay noindex,follow with the list
# canonical, now with content. Any FAIL after a deploy = rollback to 2.11.0.
# record_probe <path> <type> <id> <need>...: each <need> is searched in the
# markup of the <main> (main_html: the href of a link lives in a tag).
record_probe() {
  local p="$1" type="$2" id="$3" doc meta open html text missing="" need
  shift 3
  doc=$("${CURL[@]}" -H 'Accept: text/html' -w '\n%{http_code} %{content_type}' "$BASE$p")
  meta="${doc##*$'\n'}"
  doc="${doc%$'\n'*}"
  open=$(printf '%s\n' "$doc" | main_open_tag)
  html=$(printf '%s\n' "$doc" | main_html)
  text=$(printf '%s\n' "$doc" | main_text)
  for need in "$@"; do
    [[ "$html" == *"$need"* ]] || missing="$missing \"$need\""
  done
  if [[ "$meta" == "200 text/html"* ]] \
     && [[ "$open" == *"data-ssr-page=\"resource-detail\" data-ssr-type=\"$type\" data-ssr-id=\"$id\""* ]] \
     && [ -z "$missing" ] && [ "${#text}" -ge 200 ]; then
    pass "$p drawn on the server: resource-detail $type/$id, ${#text} characters, $# phrases"
  else
    fail "$p record content" "200 text/html, resource-detail $type/$id, >= 200 characters, all phrases" \
      "$meta, ${open:-no <main>}, ${#text} characters, missing:${missing:- none}"
  fi
}
record_probe /resource/films/1 films 1 'A New Hope' 'George Lucas' 'href="/resource/people/1"' \
  "curl $BASE/api/films/1"
record_probe /resource/people/1 people 1 'Luke Skywalker' 'href="/resource/planets/1"'
# The batch rule of slice D holds with content: people/1 stays out of the index.
page=$("${CURL[@]}" -H 'Accept: text/html' "$BASE/resource/people/1")
if printf '%s' "$page" | grep -qF 'name="robots" content="noindex,follow"' \
   && printf '%s' "$page" | grep -qF "<link rel=\"canonical\" href=\"$BASE/resource/people\" />"; then
  pass "/resource/people/1 with content stays noindex,follow, canonical $BASE/resource/people"
else
  fail "/resource/people/1 robots with content" "noindex,follow, canonical $BASE/resource/people" "other"
fi
# A record that does not exist draws nothing.
n=$("${CURL[@]}" -H 'Accept: text/html' "$BASE/resource/people/9999" | grep -c 'data-ssr-')
[ "$n" = "0" ] && pass "/resource/people/9999 has no data-ssr- marker" \
  || fail "/resource/people/9999 marker" "no data-ssr-" "$n line(s)"
jsonld /resource/films/1 | grep -qF '"@type":"BreadcrumbList"' && pass "/resource/films/1 JSON-LD: BreadcrumbList" \
  || fail "/resource/films/1 JSON-LD" '"@type":"BreadcrumbList"' "missing"

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

# --- MCP stateless cacheable results (also checks the native wire shape) -----
for method in server/discover tools/list; do
  resp=$("${CURL[@]}" -X POST "$BASE/mcp" \
    -H 'Content-Type: application/json' \
    -H 'Accept: application/json, text/event-stream' \
    -H "Mcp-Method: $method" -H 'MCP-Protocol-Version: 2026-07-28' \
    -d '{"jsonrpc":"2.0","id":1,"method":"'"$method"'","params":{"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientInfo":{"name":"verify-deploy","version":"1.0"},"io.modelcontextprotocol/clientCapabilities":{}}}}')
  if echo "$resp" | python3 -c '
import json, sys
response = json.load(sys.stdin)
result = response["result"]
assert "error" not in response
assert type(result.get("ttlMs")) is int and result["ttlMs"] == 0
assert result.get("cacheScope") == "public"
assert "cacheControl" not in result
' 2>/dev/null; then
    pass "MCP $method: flat ttlMs=0, cacheScope=public"
  else
    fail "MCP $method cache fields" "flat ttlMs=0 and cacheScope=public" "$(echo "$resp" | head -c 200)"
  fi
done

# --- MCP instructions and tool descriptions (2.9.0+), parsed as JSON ------------
# Both answer application/json today; if a body ever comes as SSE, the parser
# reads the last "data:" line.
MCP_JSON='
import json, sys
raw = sys.stdin.read().strip()
if not raw.startswith("{"):
    raw = [l[5:].strip() for l in raw.splitlines() if l.startswith("data:")][-1]
response = json.loads(raw)
'
resp=$("${CURL[@]}" -X POST "$BASE/mcp" \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"verify-deploy","version":"1.0"}}}')
if echo "$resp" | python3 -c "$MCP_JSON"'
instructions = response["result"]["instructions"]
assert "sw_search" in instructions and "not an episode number" in instructions
' 2>/dev/null; then
  pass "MCP initialize carries the instructions"
else
  fail "MCP initialize instructions" "result.instructions with sw_search and the id trap" "$(echo "$resp" | head -c 200)"
fi
resp=$("${CURL[@]}" -X POST "$BASE/mcp" \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -H 'Mcp-Method: tools/list' -H 'MCP-Protocol-Version: 2026-07-28' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientInfo":{"name":"verify-deploy","version":"1.0"},"io.modelcontextprotocol/clientCapabilities":{}}}}')
if echo "$resp" | python3 -c "$MCP_JSON"'
tools = {t["name"]: t for t in response["result"]["tools"]}
assert "sw_get" in tools["sw_search"]["description"]
' 2>/dev/null; then
  pass "MCP tools/list: sw_search description points to sw_get"
else
  fail "MCP tools/list descriptions" "sw_search description citing sw_get" "$(echo "$resp" | head -c 200)"
fi

# --- MCP stateful burst: 12 concurrent calls on one session ---------------------
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

# --- MCP 2.12.1: listen and subscribe answered without state; extension 2.0.3 --
# One stateless request: <method> <params without _meta, or ''> [Mcp-Name] [max seconds].
STATELESS_META='"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientInfo":{"name":"verify-deploy","version":"1.0"},"io.modelcontextprotocol/clientCapabilities":{}}'
mcp_stateless() {
  local method="$1" params="$2" name="${3:-}" max="${4:-90}" extra=()
  [ -n "$name" ] && extra=(-H "Mcp-Name: $name")
  curl -s --max-time "$max" -X POST "$BASE/mcp" \
    -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
    -H "Mcp-Method: $method" -H 'MCP-Protocol-Version: 2026-07-28' ${extra[@]+"${extra[@]}"} \
    -d '{"jsonrpc":"2.0","id":1,"method":"'"$method"'","params":{'"$params${params:+,}$STATELESS_META"'}}'
}
# 2.12.0 left this stream open with no limit; the curl must end on its own.
resp=$(mcp_stateless subscriptions/listen '"notifications":{"toolsListChanged":true}' '' 5); rc=$?
if [ "$rc" = "0" ] && printf '%s' "$resp" | grep -qF '"code":-32601' \
   && printf '%s' "$resp" | grep -qF 'subscriptions/listen'; then
  pass "MCP subscriptions/listen answered at once: -32601"
else
  fail "MCP subscriptions/listen" "curl ends within 5 s, -32601 citing subscriptions/listen" \
    "curl exit $rc, $(printf '%s' "$resp" | head -c 160)"
fi
resp=$(mcp_stateless resources/subscribe '"uri":"swapi://people/1"')
printf '%s' "$resp" | grep -qF '"code":-32601' && pass "MCP stateless resources/subscribe -> -32601" \
  || fail "MCP stateless resources/subscribe" '"code":-32601' "$(printf '%s' "$resp" | head -c 160)"
# The branch only the filter has: a session of the 2025-11-25 era, where the
# listen comes with the method only in the body (no Mcp-Method) and a
# subscribe would leave a subscriber in the extension.
SESSION_ERA_SID=$("${CURL[@]}" -D - -o /dev/null -X POST "$BASE/mcp" \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"verify-deploy","version":"1.0"}}}' \
  | tr -d '\r' | awk -F': ' 'tolower($1)=="mcp-session-id"{print $2}')
mcp_session() {
  curl -s --max-time "${2:-90}" -X POST "$BASE/mcp" \
    -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
    -H "Mcp-Session-Id: $SESSION_ERA_SID" -H 'MCP-Protocol-Version: 2025-11-25' -d "$1"
}
if [ -z "$SESSION_ERA_SID" ]; then
  fail "MCP session listen and subscribe" "session id issued on initialize 2025-11-25" \
    "none (guard: probes NOT executed)"
else
  resp=$(mcp_session '{"jsonrpc":"2.0","id":42,"method":"subscriptions/listen","params":{"notifications":{"toolsListChanged":true}}}' 5); rc=$?
  if [ "$rc" = "0" ] && printf '%s' "$resp" | grep -qF '"code":-32601' \
     && printf '%s' "$resp" | grep -qF '"id":42'; then
    pass "MCP session listen, method only in the body: -32601 at once"
  else
    fail "MCP session listen (no Mcp-Method)" "curl ends within 5 s, -32601 with id 42" \
      "curl exit $rc, $(printf '%s' "$resp" | head -c 160)"
  fi
  resp=$(mcp_session '{"jsonrpc":"2.0","id":43,"method":"resources/subscribe","params":{"uri":"swapi://people/1"}}' 5); rc=$?
  if [ "$rc" = "0" ] && printf '%s' "$resp" | grep -qF '"code":-32002' \
     && printf '%s' "$resp" | grep -qF 'swapi://people/1'; then
    pass "MCP session resources/subscribe -> -32002, nothing recorded"
  else
    fail "MCP session resources/subscribe" "-32002 citing swapi://people/1 within 5 s" \
      "curl exit $rc, $(printf '%s' "$resp" | head -c 160)"
  fi
fi
# 2.12.1: the body limit is 256K (it was the Quarkus default, 10240K).
code=$(head -c 300000 /dev/zero | tr '\0' 'y' | curl -s --max-time 20 -o /dev/null -w '%{http_code}' \
  -X POST "$BASE/mcp" -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' --data-binary @-)
[ "$code" = "413" ] && pass "MCP body over 256K -> 413" || fail "MCP body over 256K" "413" "$code"
# The state of 2.12.1 (no resources yet); 2.13.0 turns these two around.
resp=$(mcp_stateless resources/list '')
if printf '%s' "$resp" | grep -qF '"resources":[]' && printf '%s' "$resp" | grep -qF '"jsonrpc":"2.0"'; then
  pass "MCP resources/list: an empty list"
else
  fail "MCP resources/list" '"resources":[] in a JSON-RPC answer' "$(printf '%s' "$resp" | head -c 160)"
fi
resp=$(mcp_stateless resources/read '"uri":"swapi://films"' 'swapi://films')
if printf '%s' "$resp" | grep -qF '"code":-32602' && printf '%s' "$resp" | grep -qF 'swapi://films'; then
  pass "MCP resources/read swapi://films -> -32602 (not a resource yet)"
else
  fail "MCP resources/read swapi://films" '"code":-32602 citing swapi://films' "$(printf '%s' "$resp" | head -c 160)"
fi
# Extension 2.0.3 (PR #1037): the serverInfo of server/discover is in _meta.
resp=$(mcp_stateless server/discover '')
if printf '%s' "$resp" | grep -qF 'io.modelcontextprotocol/serverInfo' && printf '%s' "$resp" | grep -qF 'swapi.build'; then
  pass "MCP server/discover: serverInfo in _meta"
else
  fail "MCP server/discover serverInfo" 'io.modelcontextprotocol/serverInfo with swapi.build' \
    "$(printf '%s' "$resp" | head -c 160)"
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
# The SEO files vary by Origin too (2.9.1+).
vary_seo=$("${CURL[@]}" -o /dev/null -D - -H 'Origin: https://evil.example' "$BASE/robots.txt" | tr -d '\r' | grep -i '^vary')
echo "$vary_seo" | grep -qi 'origin' && pass "robots.txt Vary contains Origin ($vary_seo)" \
  || fail "robots.txt Vary: Origin" "Vary header containing Origin" "${vary_seo:-missing}"

# --- Poisoning via X-Forwarded-Host ---------------------------------------------
# The app trusts X-Forwarded-* (application.properties); Caddy's reverse_proxy
# overwrites the header (clients are not trusted proxies). A spoofed header must
# never reach the response.
n=$("${CURL[@]}" -H 'X-Forwarded-Host: evil.example' "$BASE/api/people/3" | grep -c evil.example)
m=$("${CURL[@]}" "$BASE/api/people/3" | grep -c evil.example)
[ "$n" = "0" ] && [ "$m" = "0" ] && pass "poisoning X-Forwarded-Host: 0 occurrences" \
  || fail "poisoning X-Forwarded-Host" "0 and 0" "$n and $m (see runbook)"
# The ?poison=$$ keeps each pair on its own URL: should a shared cache ever sit in
# front of the host again, the probes above would already have primed the CLEAN entry
# for the bare path, and a spoofed request would just read that hit and pass no
# matter what the origin does.
seo_poison=$("${CURL[@]}" -H 'X-Forwarded-Host: evil.example' "$BASE/sitemap.xml?poison=$$" | grep -c evil.example)
seo_clean=$("${CURL[@]}" "$BASE/sitemap.xml?poison=$$" | grep -c evil.example)
[ "$seo_poison" = "0" ] && [ "$seo_clean" = "0" ] && pass "poisoning SEO X-Forwarded-Host: 0 occurrences" \
  || fail "poisoning SEO X-Forwarded-Host" "0 and 0" "$seo_poison and $seo_clean (see runbook)"
llms_poison=$("${CURL[@]}" -H 'X-Forwarded-Host: evil.example' "$BASE/llms.txt?poison=$$" | grep -c evil.example)
llms_clean=$("${CURL[@]}" "$BASE/llms.txt?poison=$$" | grep -c evil.example)
[ "$llms_poison" = "0" ] && [ "$llms_clean" = "0" ] && pass "poisoning llms.txt X-Forwarded-Host: 0 occurrences" \
  || fail "poisoning llms.txt X-Forwarded-Host" "0 and 0" "$llms_poison and $llms_clean (see runbook)"
# The SPA HTML embeds canonical/og:url/og:image built from the request host.
home_poison=$("${CURL[@]}" -H 'X-Forwarded-Host: evil.example' "$BASE/?poison=$$" | grep -c evil.example)
home_clean=$("${CURL[@]}" "$BASE/?poison=$$" | grep -c evil.example)
[ "$home_poison" = "0" ] && [ "$home_clean" = "0" ] && pass "poisoning SPA HTML X-Forwarded-Host: 0 occurrences" \
  || fail "poisoning SPA HTML X-Forwarded-Host" "0 and 0" "$home_poison and $home_clean (see runbook)"
# The not-found document (2.9.1+) is built from the same shell.
nf_poison=$("${CURL[@]}" -H 'X-Forwarded-Host: evil.example' "$BASE/not-a-real-page?poison=$$" | grep -c evil.example)
nf_clean=$("${CURL[@]}" "$BASE/not-a-real-page?poison=$$" | grep -c evil.example)
[ "$nf_poison" = "0" ] && [ "$nf_clean" = "0" ] && pass "poisoning 404 document X-Forwarded-Host: 0 occurrences" \
  || fail "poisoning 404 document X-Forwarded-Host" "0 and 0" "$nf_poison and $nf_clean (see runbook)"
# 2.11.0+: the <main> of /docs/mcp carries the request host (the endpoint).
mcp_poison=$("${CURL[@]}" -H 'X-Forwarded-Host: evil.example' "$BASE/docs/mcp?poison=$$" | grep -c evil.example)
mcp_clean=$("${CURL[@]}" "$BASE/docs/mcp?poison=$$" | grep -c evil.example)
[ "$mcp_poison" = "0" ] && [ "$mcp_clean" = "0" ] && pass "poisoning /docs/mcp X-Forwarded-Host: 0 occurrences" \
  || fail "poisoning /docs/mcp X-Forwarded-Host" "0 and 0" "$mcp_poison and $mcp_clean (see runbook)"
# 2.12.0+: the <main> of a record carries the request host (the URLs of the
# record, the samples), on an indexable type and on a noindex one.
for p in /resource/films/1 /resource/people/1; do
  r_poison=$("${CURL[@]}" -H 'X-Forwarded-Host: evil.example' "$BASE$p?poison=$$" | grep -c evil.example)
  r_clean=$("${CURL[@]}" "$BASE$p?poison=$$" | grep -c evil.example)
  [ "$r_poison" = "0" ] && [ "$r_clean" = "0" ] && pass "poisoning $p X-Forwarded-Host: 0 occurrences" \
    || fail "poisoning $p X-Forwarded-Host" "0 and 0" "$r_poison and $r_clean (see runbook)"
done

# --- What Caddy itself must add or remove -----------------------------------------
# RFC 7239 Forwarded is not honoured by the app (allow-forwarded is off); prove it
# stays that way through the proxy too.
f=$("${CURL[@]}" -H 'Forwarded: host=evil.example;proto=https' "$BASE/api/people/3" | grep -c evil.example)
[ "$f" = "0" ] && pass "poisoning Forwarded (RFC 7239): 0 occurrences" \
  || fail "poisoning Forwarded (RFC 7239)" "0" "$f"
# 2.12.0+: the same on a record page, whose <main> carries the host.
f=$("${CURL[@]}" -H 'Forwarded: host=evil.example;proto=https' "$BASE/resource/films/1" | grep -c evil.example)
[ "$f" = "0" ] && pass "poisoning Forwarded (RFC 7239) on /resource/films/1: 0 occurrences" \
  || fail "poisoning Forwarded (RFC 7239) on /resource/films/1" "0" "$f"
headers=$("${CURL[@]}" -o /dev/null -D - "$BASE/api/people/1" | tr -d '\r')
hsts=$(echo "$headers" | awk -F': ' 'tolower($1)=="strict-transport-security"{print $2}')
[ "$hsts" = "max-age=63072000" ] && pass "HSTS max-age=63072000" \
  || fail "HSTS" "max-age=63072000" "${hsts:-missing}"
server=$(echo "$headers" | awk -F': ' 'tolower($1)=="server"{print $2}')
[ -z "$server" ] && pass "no Server header" || fail "Server header" "absent" "$server"

echo
if [ "$FAILURES" -eq 0 ]; then
  echo "verify-deploy [$MODE] $HOST: all probes green"
else
  echo "verify-deploy [$MODE] $HOST: $FAILURES probe(s) failed"
  exit 1
fi
