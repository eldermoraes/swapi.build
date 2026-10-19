#!/usr/bin/env bash
# Derives the raw ed25519 private key, in hex, that `mcp-publisher login dns`
# expects, from the PEM file used for the MCP Registry (docs/RELEASE.md, step 10).
#
# usage: PRIVATE_KEY="$(scripts/registry-key.sh <path to ed25519 pem>)"
#
# Prints the key on stdout only so it can be captured in a shell variable — never
# redirect it to a file or a log. Exits non-zero unless the result is exactly 64
# hex characters.
#
# Why the sed range: `openssl pkey -text` prints a "priv:" block followed by a
# "pub:" block. The previous runbook recipe (grep -A3 'priv:' | tail -n +2) kept
# the "pub:" label, yielding 67 characters, and the login failed (2026-10-19).
# Regression test: scripts/registry-key.test.sh.
set -euo pipefail

USAGE="usage: registry-key.sh <path to ed25519 pem>"
PEM="${1:?$USAGE}"

if [ ! -r "$PEM" ]; then
  echo "registry-key.sh: cannot read $PEM" >&2
  exit 2
fi

KEY="$(openssl pkey -in "$PEM" -noout -text \
  | sed -n '/^priv:/,/^pub:/p' \
  | grep -v -e '^priv:' -e '^pub:' \
  | tr -d ' :\n')"

if ! [[ "$KEY" =~ ^[0-9a-f]{64}$ ]]; then
  echo "registry-key.sh: expected 64 hex characters, got ${#KEY} (is this an ed25519 key?)" >&2
  exit 1
fi

printf '%s\n' "$KEY"
