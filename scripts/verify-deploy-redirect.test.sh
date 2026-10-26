#!/usr/bin/env bash
# Test for the `redirect` mode of scripts/verify-deploy.sh (run by
# .github/workflows/ci.yml). Runs offline: a fake www host
# (verify-deploy-redirect.server.py) listens on 127.0.0.1 and answers with a
# correct or a broken redirect, and the probes must pass or fail accordingly.
#
# The fake host speaks plain http, so the probes reach it through
# VERIFY_DEPLOY_TEST_SCHEME=http, the test-only override of the scheme used to
# reach <from-host>. The expected Location stays https://<to-host>... either way.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
VERIFY="$HERE/verify-deploy.sh"
SERVER="$HERE/verify-deploy-redirect.server.py"
TMP="$(mktemp -d)"
PID=""
cleanup() { [ -n "$PID" ] && kill "$PID" 2>/dev/null; rm -rf "$TMP"; }
trap cleanup EXIT

FAILURES=0
pass() { printf 'PASS  %s\n' "$1"; }
fail() { printf 'FAIL  %s\n' "$1"; FAILURES=$((FAILURES+1)); }

# start <behaviour>: starts the fake host and sets PORT.
start() {
  rm -f "$TMP/port"
  python3 -I "$SERVER" "$1" "$TMP/port" &
  PID=$!
  for _ in $(seq 1 50); do
    [ -f "$TMP/port" ] && break
    sleep 0.1
  done
  [ -f "$TMP/port" ] || { echo "fake host did not start ($1)"; exit 1; }
  PORT=$(cat "$TMP/port")
}
stop() { kill "$PID" 2>/dev/null; wait "$PID" 2>/dev/null || true; PID=""; }

# probe <behaviour>: runs the redirect mode against the fake host; sets OUT and CODE.
probe() {
  start "$1"
  set +e
  OUT=$(VERIFY_DEPLOY_TEST_SCHEME=http "$VERIFY" redirect "127.0.0.1:$PORT" swapi.build 2>&1)
  CODE=$?
  set -e
  stop
}

# The override is test-only: without it the mode must reach the host over https.
if grep -q 'SCHEME="${VERIFY_DEPLOY_TEST_SCHEME:-https}"' "$VERIFY"; then
  pass "redirect mode defaults to https"
else
  fail "redirect mode: expected SCHEME to default to https"
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
