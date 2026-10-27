#!/usr/bin/env bash
# Test for scripts/lib/main-text.sh (run by .github/workflows/ci.yml). Offline.
#
# Bug covered (found while planning slice E, 2026-10-27): a range
# `sed -n '/<main/,/<\/main>/p'` on a one-line empty `<main></main>` starts the
# range and never closes it on that line, so it runs to the end of the file and
# counts the footer: on the /docs/mcp of 2.10.0 (empty <main>) it gave 226
# characters, and a ">= 200 characters" probe would have passed on nothing.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=lib/main-text.sh
. "$HERE/lib/main-text.sh"

FAILURES=0
pass() { printf 'PASS  %s\n' "$1"; }
fail() { printf 'FAIL  %s\n' "$1"; FAILURES=$((FAILURES+1)); }

FOOTER="<footer><p>$(printf 'Star Wars and all associated names are trademarks of Lucasfilm Ltd. %.0s' 1 2 3 4)</p></footer>"

# 1. The empty <main> of one line followed by a long footer: empty text.
doc="<html><body><nav>Home Docs</nav><main id=\"main-content\" tabindex=\"-1\"></main>$FOOTER</body></html>"
got=$(printf '%s\n' "$doc" | main_text)
[ -z "$got" ] && pass "empty one-line <main> + footer: empty text" \
  || fail "empty one-line <main> + footer: expected empty, got ${#got} characters"

# 2. A <main> of 300 characters over several lines: the 300, without the tags.
words=$(printf 'abcdefghi %.0s' $(seq 1 30))   # 30 x 10 = 300 characters
words="${words% }x"                             # 300 characters, no trailing space
doc=$(printf '<html><body>\n<main id="main-content" tabindex="-1" data-ssr-page="about">\n<h1>\n%s\n</h1>\n</main>\n%s\n</body></html>\n' \
  "$words" "$FOOTER")
got=$(printf '%s\n' "$doc" | main_text)
[ "$got" = "$words" ] && pass "multi-line <main> of 300 characters: the 300" \
  || fail "multi-line <main>: expected ${#words} characters, got ${#got}: $got"

# 3. A document without the <main>: empty, never the whole document.
got=$(printf '<html><body><p>%s</p>%s</body></html>\n' "$words" "$FOOTER" | main_text)
[ -z "$got" ] && pass "no <main>: empty text" \
  || fail "no <main>: expected empty, got ${#got} characters"

# 4. The opening tag: the marker is read from it, not from the text.
doc='<main id="main-content" tabindex="-1" data-ssr-page="mcp"><p>x</p></main>'
got=$(printf '%s\n' "$doc" | main_open_tag)
[ "$got" = '<main id="main-content" tabindex="-1" data-ssr-page="mcp">' ] \
  && pass "main_open_tag: the opening tag with the marker" \
  || fail "main_open_tag: got $got"

echo
if [ "$FAILURES" -eq 0 ]; then
  echo "main-text.test: all checks green"
else
  echo "main-text.test: $FAILURES check(s) failed"
  exit 1
fi
