#!/usr/bin/env bash
# Test for the `redirect` mode of scripts/verify-deploy.sh (run by
# .github/workflows/ci.yml). Runs offline: two fake hosts
# (verify-deploy-redirect.server.py) listen on 127.0.0.1, a www host that
# answers with a correct or a broken redirect to the other, and an apex host
# that answers the page the redirect leads to (2.11.0+: the www follow). The
# probes must pass or fail accordingly.
#
# The fake hosts speak plain http, so the probes reach them through
# VERIFY_DEPLOY_TEST_SCHEME=http, the test-only override of the scheme. The
# expected Location stays https://<to-host>... either way; the follow swaps that
# https for the same override.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
VERIFY="$HERE/verify-deploy.sh"
SERVER="$HERE/verify-deploy-redirect.server.py"
TMP="$(mktemp -d)"
PIDS=()
cleanup() { for p in "${PIDS[@]:-}"; do [ -n "$p" ] && kill "$p" 2>/dev/null; done; rm -rf "$TMP"; }
trap cleanup EXIT

FAILURES=0
pass() { printf 'PASS  %s\n' "$1"; }
fail() { printf 'FAIL  %s\n' "$1"; FAILURES=$((FAILURES+1)); }

# start <behaviour> [<destination>]: starts a fake host; sets STARTED_PORT.
start() {
  rm -f "$TMP/port"
  python3 -I "$SERVER" "$1" "$TMP/port" ${2:+"$2"} &
  PIDS+=("$!")
  for _ in $(seq 1 50); do
    [ -f "$TMP/port" ] && break
    sleep 0.1
  done
  [ -f "$TMP/port" ] || { echo "fake host did not start ($1)"; exit 1; }
  STARTED_PORT=$(cat "$TMP/port")
}
stop_all() {
  for p in "${PIDS[@]:-}"; do
    [ -n "$p" ] || continue
    kill "$p" 2>/dev/null
    wait "$p" 2>/dev/null || true
  done
  PIDS=()
}

# probe <www-behaviour> [<apex-behaviour>]: runs the redirect mode from the fake
# www to the fake apex (default apex); sets OUT and CODE.
probe() {
  start "${2:-apex}"
  local apex="127.0.0.1:$STARTED_PORT"
  start "$1" "$apex"
  local www="127.0.0.1:$STARTED_PORT"
  set +e
  OUT=$(VERIFY_DEPLOY_TEST_SCHEME=http "$VERIFY" redirect "$www" "$apex" 2>&1)
  CODE=$?
  set -e
  stop_all
}

# The override is test-only: without it the mode must reach the host over https.
if grep -q 'SCHEME="${VERIFY_DEPLOY_TEST_SCHEME:-https}"' "$VERIFY"; then
  pass "redirect mode defaults to https"
else
  fail "redirect mode: expected SCHEME to default to https"
fi
# Since 2.10.0 the origin mode honours the same override (the local rehearsal of
# a release); both modes build their base from it, so both default to https.
if grep -q 'BASE="${SCHEME}://${HOST}"' "$VERIFY" && ! grep -q 'BASE="http://' "$VERIFY"; then
  pass "origin mode defaults to https"
else
  fail "origin mode: expected BASE to be built from SCHEME"
fi

probe ok
if [ "$CODE" = "0" ] && ! echo "$OUT" | grep -q '^FAIL' \
   && [ "$(echo "$OUT" | grep -c '^PASS')" -ge 3 ]; then
  pass "correct 308 redirect: every probe passes"
else
  fail "correct 308 redirect should pass (exit $CODE)"; echo "$OUT"
fi
echo "$OUT" | grep -q '^PASS.*POST /mcp' && pass "POST /mcp is probed" \
  || { fail "POST /mcp should be probed"; echo "$OUT"; }
echo "$OUT" | grep -q '^PASS.*?format=json' && pass "a path with a query is probed" \
  || { fail "a path with a query should be probed"; echo "$OUT"; }
# 2.11.0: an agent that gets www and follows the 308 without JavaScript reads text.
echo "$OUT" | grep -q '^PASS.*follow /docs/mcp' && pass "the www follow of /docs/mcp passes" \
  || { fail "the www follow of /docs/mcp should pass"; echo "$OUT"; }
# 2.12.0: a record too.
echo "$OUT" | grep -q '^PASS.*follow /resource/films/1' && pass "the www follow of /resource/films/1 passes" \
  || { fail "the www follow of /resource/films/1 should pass"; echo "$OUT"; }

probe ok empty-main
if [ "$CODE" = "1" ] && echo "$OUT" | grep -q '^FAIL.*follow /docs/mcp' \
   && echo "$OUT" | grep -q '^FAIL.*follow /resource/films/1' \
   && [ "$(echo "$OUT" | grep -c '^FAIL')" = "2" ]; then
  pass "an empty <main> at the end of the follow fails (only the two follows)"
else
  fail "an empty <main> at the end of the follow should fail (exit $CODE)"; echo "$OUT"
fi

probe wrong-status
if [ "$CODE" = "1" ] && echo "$OUT" | grep -q '^FAIL'; then
  pass "301 instead of 308 fails"
else
  fail "301 instead of 308 should fail (exit $CODE)"; echo "$OUT"
fi

probe wrong-host
if [ "$CODE" = "1" ] && echo "$OUT" | grep -q '^FAIL'; then
  pass "Location on the wrong host fails"
else
  fail "Location on the wrong host should fail (exit $CODE)"; echo "$OUT"
fi
# Offline: a Location off the apex is never followed (it would leave the machine).
echo "$OUT" | grep -q '^FAIL.*follow /docs/mcp' && pass "a Location off the apex is not followed" \
  || { fail "a Location off the apex should fail the follow"; echo "$OUT"; }

probe drop-query
if [ "$CODE" = "1" ] && echo "$OUT" | grep -q '^FAIL.*?format=json'; then
  pass "dropped query fails"
else
  fail "dropped query should fail (exit $CODE)"; echo "$OUT"
fi

echo
if [ "$FAILURES" -eq 0 ]; then
  echo "verify-deploy-redirect.test: all checks green"
else
  echo "verify-deploy-redirect.test: $FAILURES check(s) failed"
  exit 1
fi
