#!/usr/bin/env bash
# Test for scripts/swapi-deploy-gate.sh (run by .github/workflows/ci.yml).
# Runs locally, without the host: a copy of the gate calls a fake sudo that
# only records its arguments.
#
# usage: swapi-deploy-gate.test.sh [install-script]
#   With an install script (the one that provisions the deploy user on the
#   host), also checks that the gate embedded in it, between the
#   <<'GATE' and GATE lines, is byte-for-byte this repository's gate.
set -euo pipefail

GATE="$(cd "$(dirname "$0")" && pwd)/swapi-deploy-gate.sh"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

FAILURES=0
pass() { printf 'PASS  %s\n' "$1"; }
fail() { printf 'FAIL  %s\n' "$1"; FAILURES=$((FAILURES+1)); }

# The fake sudo writes one argument per line to $TMP/called.
cat > "$TMP/fake-sudo" <<EOF
#!/usr/bin/env bash
printf '%s\n' "\$@" > "$TMP/called"
EOF
chmod +x "$TMP/fake-sudo"

if grep -q '^SUDO=/usr/bin/sudo$' "$GATE"; then
  pass "gate calls sudo by absolute path"
else
  fail "gate: expected the line SUDO=/usr/bin/sudo"
fi
sed "s|^SUDO=.*|SUDO=$TMP/fake-sudo|" "$GATE" > "$TMP/gate"
chmod +x "$TMP/gate"

HASH=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef

# accept <description> <request> <expected tag> <expected hash>
accept() {
  rm -f "$TMP/called"
  if SSH_ORIGINAL_COMMAND="$2" "$TMP/gate" >/dev/null 2>&1; then
    expected="$(printf '%s\n' -n /usr/local/sbin/swapi-update "$3" "$4")"
    if [ -f "$TMP/called" ] && [ "$(cat "$TMP/called")" = "$expected" ]; then
      pass "accepts $1"
    else
      fail "accepts $1: sudo got '$(tr '\n' ' ' < "$TMP/called" 2>/dev/null)'"
    fi
  else
    fail "accepts $1: refused"
  fi
}

# refuse <description> <request>
refuse() {
  rm -f "$TMP/called"
  if SSH_ORIGINAL_COMMAND="$2" "$TMP/gate" >/dev/null 2>&1; then
    fail "refuses $1: exit 0"
  elif [ -f "$TMP/called" ]; then
    fail "refuses $1: sudo was called"
  else
    pass "refuses $1"
  fi
}

accept "a release tag and a sha256" "update v2.5.3 $HASH" v2.5.3 "$HASH"
accept "multi-digit version parts" "update v10.20.300 $HASH" v10.20.300 "$HASH"

# No request at all (interactive login, scp, sftp).
rm -f "$TMP/called"
if env -u SSH_ORIGINAL_COMMAND "$TMP/gate" >/dev/null 2>&1 || [ -f "$TMP/called" ]; then
  fail "refuses a login without a command"
else
  pass "refuses a login without a command"
fi
refuse "an empty command" ""
refuse "an interactive shell request" "bash"
refuse "scp" "scp -t /tmp"

# Shell injection.
refuse "';' after the hash" "update v2.5.3 $HASH; rm -rf /"
refuse "';' with no space" "update v2.5.3 $HASH;id"
refuse "\$( ) in the hash" "update v2.5.3 \$(id)"
refuse "\$( ) in the tag" "update \$(id) $HASH"
refuse "backticks" "update v2.5.3 \`id\`"
refuse "a pipe" "update v2.5.3 $HASH | sh"
refuse "&&" "update v2.5.3 $HASH && id"
refuse "a newline and a second command" "update v2.5.3 $HASH
id"
refuse "a trailing newline" "update v2.5.3 $HASH
"

# Spacing.
refuse "two spaces between the words" "update  v2.5.3 $HASH"
refuse "a leading space" " update v2.5.3 $HASH"
refuse "a trailing space" "update v2.5.3 $HASH "
refuse "a tab separator" "update	v2.5.3	$HASH"
refuse "an extra argument" "update v2.5.3 $HASH extra"

# Invalid tags.
refuse "a tag without v" "update 2.5.3 $HASH"
refuse "a two-part tag" "update v2.5 $HASH"
refuse "a pre-release tag" "update v2.5.3-rc1 $HASH"
refuse "a path traversal in the tag" "update v2.5.3/../../tmp $HASH"
refuse "a version part with five digits" "update v12345.0.0 $HASH"
refuse "non-ASCII digits" "update v٢.5.3 $HASH"

# Invalid hashes.
refuse "a 63-character hash" "update v2.5.3 ${HASH:1}"
refuse "a 65-character hash" "update v2.5.3 ${HASH}0"
refuse "an uppercase hash" "update v2.5.3 $(printf '%s' "$HASH" | tr 'a-f' 'A-F')"
refuse "a non-hex character" "update v2.5.3 ${HASH%?}g"
refuse "a missing hash" "update v2.5.3"

# Other verbs.
refuse "another verb" "rollback v2.5.3 $HASH"
refuse "a capitalised verb" "Update v2.5.3 $HASH"

# Optional: the copy embedded in the install script is this gate.
if [ $# -ge 1 ]; then
  sed -n "/<<'GATE'\$/,/^GATE\$/p" "$1" | sed '1d;$d' > "$TMP/embedded"
  if [ -s "$TMP/embedded" ] && cmp -s "$GATE" "$TMP/embedded"; then
    pass "the install script embeds this gate unchanged"
  else
    fail "the install script's gate differs from $GATE"
    diff "$GATE" "$TMP/embedded" || true
  fi
fi

echo
if [ "$FAILURES" -eq 0 ]; then
  echo "swapi-deploy-gate: all tests green"
else
  echo "swapi-deploy-gate: $FAILURES test(s) failed"
  exit 1
fi
