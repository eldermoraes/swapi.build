#!/bin/bash
# Forced command of the `deploy` user on the self-hosted origin
# (docs/DEPLOY.md, "2.11 The deploy user"). Installed as
# /usr/local/sbin/swapi-deploy-gate, owner root, mode 755, and wired in
# ~deploy/.ssh/authorized_keys as:
#
#   restrict,command="/usr/local/sbin/swapi-deploy-gate" ssh-ed25519 AAAA...
#
# sshd runs this instead of whatever the client asked for and puts the client's
# request in SSH_ORIGINAL_COMMAND. The only request accepted is
#
#   update <tag vX.Y.Z> <sha256, 64 lowercase hex>
#
# matched as a whole string, single spaces, nothing before or after. Anything
# else is refused without running a thing. The tag and the hash are passed to
# swapi-update as separate arguments, never through a shell.
#
# Tested by scripts/swapi-deploy-gate.test.sh (run in CI).
set -euo pipefail
export LC_ALL=C

SUDO=/usr/bin/sudo
UPDATE=/usr/local/sbin/swapi-update

request="${SSH_ORIGINAL_COMMAND-}"
pattern='^update (v[0-9]{1,4}\.[0-9]{1,4}\.[0-9]{1,4}) ([0-9a-f]{64})$'

if [[ "$request" =~ $pattern ]]; then
  exec "$SUDO" -n "$UPDATE" "${BASH_REMATCH[1]}" "${BASH_REMATCH[2]}"
fi

echo "swapi-deploy-gate: refused; the only accepted request is: update vX.Y.Z <sha256>" >&2
exit 1
