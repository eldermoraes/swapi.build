#!/usr/bin/env bash
# Regression test for scripts/registry-key.sh (run by .github/workflows/ci.yml).
#
# Bug covered: the old runbook recipe kept the "pub:" label of `openssl pkey -text`
# and produced 67 characters instead of 64, so `mcp-publisher login dns` failed
# (2026-10-19). Uses throwaway keys in a temp dir — never the real registry key.
set -euo pipefail

SCRIPT="$(cd "$(dirname "$0")" && pwd)/registry-key.sh"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

FAILURES=0
pass() { printf 'PASS  %s\n' "$1"; }
fail() { printf 'FAIL  %s\n' "$1"; FAILURES=$((FAILURES+1)); }

# 1. A fresh ed25519 key yields exactly 64 hex chars, equal to the private key.
openssl genpkey -algorithm ed25519 -out "$TMP/ed25519.pem"
got="$("$SCRIPT" "$TMP/ed25519.pem")"
# Expected value read independently of the text output: the raw private key is
# the last 32 bytes of the PKCS#8 DER encoding.
expected="$(openssl pkey -in "$TMP/ed25519.pem" -outform DER | tail -c 32 | od -An -v -tx1 | tr -d ' \n')"

[[ "$got" =~ ^[0-9a-f]{64}$ ]] && pass "ed25519: 64 hex characters" \
  || fail "ed25519: expected 64 hex characters, got ${#got}"
[ "$got" = "$expected" ] && pass "ed25519: matches the private key from openssl" \
  || fail "ed25519: does not match the private key from openssl"

# 2. A key of the wrong size (ed448, 57 bytes) is rejected, not printed.
openssl genpkey -algorithm ed448 -out "$TMP/ed448.pem"
if out="$("$SCRIPT" "$TMP/ed448.pem" 2>/dev/null)"; then
  fail "ed448: accepted (printed ${#out} characters)"
else
  [ -z "$out" ] && pass "ed448: rejected with nothing on stdout" \
    || fail "ed448: rejected but printed to stdout"
fi

# 3. A missing file is an error.
if "$SCRIPT" "$TMP/missing.pem" >/dev/null 2>&1; then
  fail "missing file: accepted"
else
  pass "missing file: rejected"
fi

if [ "$FAILURES" -ne 0 ]; then
  echo "$FAILURES check(s) failed"
  exit 1
fi
echo "all checks passed"
