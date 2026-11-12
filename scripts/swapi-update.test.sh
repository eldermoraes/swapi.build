#!/usr/bin/env bash
# Test for the host script swapi-update (run by .github/workflows/ci.yml).
# The script has one source: the code block of docs/DEPLOY.md, section 2.5.
# This test extracts that block and runs it locally, without the host: its
# /opt/swapi becomes a temporary directory, and curl, systemctl, install, ldd
# and sleep are fakes first on PATH. It checks which version directories are
# left and where `current` points, how long the health check waits, and which
# tags are refused.
set -euo pipefail

RUNBOOK="$(cd "$(dirname "$0")/.." && pwd)/docs/DEPLOY.md"
# Physical path: the script compares `readlink -f` with the directory names.
TMP="$(cd "$(mktemp -d)" && pwd -P)"
trap 'rm -rf "$TMP"' EXIT

FAILURES=0
pass() { printf 'PASS  %s\n' "$1"; }
fail() { printf 'FAIL  %s\n' "$1"; FAILURES=$((FAILURES+1)); }

# The fenced sh block that holds the usage line, without the fences.
awk '
  /^```sh$/           { inside = 1; block = ""; wanted = 0; next }
  inside && /^```$/   { if (wanted) printf "%s", block; inside = 0; next }
  inside              { block = block $0 "\n"; if ($0 ~ /^# usage: swapi-update /) wanted = 1 }
' "$RUNBOOK" > "$TMP/extracted"

if [ "$(grep -c '^# usage: swapi-update ' "$TMP/extracted")" = 1 ] &&
   [ "$(head -n 1 "$TMP/extracted")" = '#!/bin/sh' ]; then
  pass "the runbook holds the script exactly once"
else
  fail "the runbook: expected one sh block with the line '# usage: swapi-update ...'"
  echo
  echo "swapi-update: cannot run without the script"
  exit 1
fi

ROOT="$TMP/opt/swapi"
sed "s|/opt/swapi|$ROOT|g" "$TMP/extracted" > "$TMP/swapi-update"

# Fakes. curl serves the "Release" from $TMP/release and answers the health
# check unless $TMP/down exists or the check is one of the first N, N being
# the content of $TMP/failing-checks; a failed check says why on stderr, as
# curl -sS does. It writes one line per health check, the --max-time it was
# given, to $TMP/checks, and creates $TMP/downloaded on a download. sleep writes the seconds it was asked for to $TMP/slept. install
# ignores owner, group and mode.
mkdir -p "$TMP/bin" "$TMP/release"
printf 'a native binary\n' > "$TMP/release/application"
HASH="$(cd "$TMP/release" && sha256sum application | cut -d' ' -f1)"
printf '%s  application\n' "$HASH" > "$TMP/release/application.sha256"

cat > "$TMP/bin/curl" <<EOF
#!/usr/bin/env bash
out=/dev/null; url=; limit=none
while [ \$# -gt 0 ]; do
  case "\$1" in
    -o) out="\$2"; shift 2 ;;
    --proto) shift 2 ;;
    --max-time) limit="\$2"; shift 2 ;;
    -*) shift ;;
    *) url="\$1"; shift ;;
  esac
done
case "\$url" in
  https://github.com/*/application)
    : > "$TMP/downloaded"; cp "$TMP/release/application" "\$out" ;;
  https://github.com/*/application.sha256)
    : > "$TMP/downloaded"; cp "$TMP/release/application.sha256" "\$out" ;;
  http://127.0.0.1:*)
    printf '%s\n' "\$limit" >> "$TMP/checks"
    failing="\$(cat "$TMP/failing-checks" 2>/dev/null || echo 0)"
    if [ -e "$TMP/down" ] || [ "\$(wc -l < "$TMP/checks")" -le "\$failing" ]; then
      echo "curl: (7) Failed to connect" >&2
      exit 7
    fi ;;
  *) exit 22 ;;
esac
EOF
cat > "$TMP/bin/install" <<'EOF'
#!/usr/bin/env bash
directory=0; paths=()
while [ $# -gt 0 ]; do
  case "$1" in
    -d) directory=1; shift ;;
    -o | -g | -m) shift 2 ;;
    *) paths+=("$1"); shift ;;
  esac
done
if [ "$directory" = 1 ]; then mkdir -p "${paths[@]}"; else cp "${paths[0]}" "${paths[1]}"; fi
EOF
# readlink behaves as GNU's on the Ubuntu host, also on macOS: -f of a path
# that does not exist prints the path and exits 0 (BSD's prints nothing).
REAL_READLINK="$(command -v readlink)"
cat > "$TMP/bin/readlink" <<EOF
#!/bin/sh
if [ "\$1" = -f ] && [ ! -e "\$2" ] && [ ! -L "\$2" ]; then
  printf '%s\n' "\$2"
  exit 0
fi
exec "$REAL_READLINK" "\$@"
EOF
for name in systemctl ldd; do
  printf '#!/bin/sh\nexit 0\n' > "$TMP/bin/$name"
done
cat > "$TMP/bin/sleep" <<EOF
#!/bin/sh
printf '%s\n' "\$1" >> "$TMP/slept"
EOF
chmod +x "$TMP/bin"/*

# host <active version> <other version>...: a host with these versions on disk.
# An empty first argument means no `current` link (before the first install).
host() {
  rm -rf "$ROOT" "$TMP/down" "$TMP/failing-checks"
  mkdir -p "$ROOT"
  active="$1"
  for version in "$@"; do
    [ -z "$version" ] || { mkdir "$ROOT/$version"; : > "$ROOT/$version/application"; }
  done
  [ -z "$active" ] || ln -s "$ROOT/$active" "$ROOT/current"
}

update() {
  rm -f "$TMP/checks" "$TMP/slept" "$TMP/downloaded"
  PATH="$TMP/bin:$PATH" sh "$TMP/swapi-update" "$1" "$HASH" > "$TMP/output" 2>&1
}

# The version directories, sorted, on one line.
versions() {
  found=
  for dir in "$ROOT"/v*; do
    [ -d "$dir" ] && found="$found${found:+ }${dir##*/}"
  done
  printf '%s' "$found"
}

# left <description> <expected versions> <expected active version>
left() {
  now="$(versions)"
  target="$(readlink "$ROOT/current" 2>/dev/null || true)"
  if [ "$now" = "$2" ] && [ "$target" = "$ROOT/$3" ]; then
    pass "$1"
  else
    fail "$1: versions '$now' (expected '$2'), current -> '$target' (expected $3)"
    sed 's/^/      /' "$TMP/output"
  fi
}

# deploys <description> <tag> <expected versions>: the script succeeds, the tag
# is the active version and exactly these versions are left.
deploys() {
  if update "$2"; then
    left "$1" "$3" "$2"
  else
    fail "$1: the script failed"
    sed 's/^/      /' "$TMP/output"
  fi
}

# does_not_answer <description> <tag> <expected versions>: the script fails
# after the switch, and exactly these versions are left.
does_not_answer() {
  if update "$2"; then
    fail "$1: exit 0"
  else
    left "$1" "$3" "$2"
  fi
}

# column <file>: the lines of a file on one line, "" when it does not exist.
column() { tr '\n' ' ' < "$1" 2>/dev/null | sed 's/ $//' || true; }

# waited <description> <expected health checks>: the last run checked this
# many times, waited one second before each check and gave each one 2 seconds.
waited() {
  seconds='' limits='' count=0
  while [ "$count" -lt "$2" ]; do
    seconds="$seconds${seconds:+ }1"
    limits="$limits${limits:+ }2"
    count=$((count + 1))
  done
  if [ "$(column "$TMP/slept")" = "$seconds" ] && [ "$(column "$TMP/checks")" = "$limits" ]; then
    pass "$1"
  else
    fail "$1: waits '$(column "$TMP/slept")' (expected '$seconds'), limits '$(column "$TMP/checks")' (expected '$limits')"
    sed 's/^/      /' "$TMP/output"
  fi
}

# prints <description> <line>: the output of the last run has this line.
prints() {
  if grep -qxF -- "$2" "$TMP/output"; then
    pass "$1"
  else
    fail "$1: no line '$2'"
    sed 's/^/      /' "$TMP/output"
  fi
}

# accepts <description> <tag>: the script deploys it on an empty host.
accepts() {
  host ""
  deploys "accepts $1" "$2" "$2"
}

# refuses <description> <tag>: exit 2 with the message, before any download,
# and the host is left as it was (v2.0.0 active, v1.9.0 before it).
refuses() {
  host v2.0.0 v1.9.0
  status=0
  update "$2" || status=$?
  if [ "$status" != 2 ]; then
    fail "refuses $1: exit $status (expected 2)"
  elif ! grep -q '^not a release tag: ' "$TMP/output"; then
    fail "refuses $1: no 'not a release tag' message"
  elif [ -e "$TMP/downloaded" ]; then
    fail "refuses $1: it downloaded something"
  else
    left "refuses $1" "v1.9.0 v2.0.0" v2.0.0
  fi
}

host v2.0.0 v1.0.0 v1.9.0
deploys "an upgrade keeps the new version and the one before it" v2.1.0 "v2.0.0 v2.1.0"

host v2.0.0 v1.9.0
deploys "a redeploy of the active version keeps the previous one" v2.0.0 "v1.9.0 v2.0.0"
# Same host, no reset: the redeploy left nothing behind for good.
deploys "an upgrade after a redeploy prunes again" v2.1.0 "v2.0.0 v2.1.0"

host ""
deploys "the first install leaves one version" v1.0.0 "v1.0.0"

host v2.0.0 v1.9.0
: > "$TMP/down"
does_not_answer "a version that does not answer removes nothing" v2.1.0 "v1.9.0 v2.0.0 v2.1.0"
# The retry of that tag, without a rollback in between.
does_not_answer "a retry that does not answer removes nothing" v2.1.0 "v1.9.0 v2.0.0 v2.1.0"
rm -f "$TMP/down"
deploys "a retry that answers keeps the last working version" v2.1.0 "v1.9.0 v2.0.0 v2.1.0"

# The health check: up to 10 checks, a second apart, 2 seconds each.
host v2.0.0 v1.9.0
deploys "a version that answers at once is deployed" v2.1.0 "v2.0.0 v2.1.0"
waited "a version that answers at once is checked once, after one second" 1

host v2.0.0 v1.9.0
echo 4 > "$TMP/failing-checks"
deploys "a version that answers on the fifth check is deployed" v2.1.0 "v2.0.0 v2.1.0"
waited "the script stops checking when the version answers" 5

host v2.0.0 v1.9.0
echo 9 > "$TMP/failing-checks"
deploys "a version that answers on the last check is deployed" v2.1.0 "v2.0.0 v2.1.0"

host v2.0.0 v1.9.0
: > "$TMP/down"
does_not_answer "a version that never answers is not deployed" v2.1.0 "v1.9.0 v2.0.0 v2.1.0"
waited "a version that never answers is checked 10 times, 30 s at most" 10
if [ "$(grep -c '^curl: ' "$TMP/output")" = 1 ]; then
  pass "it shows the error of the last check, once"
else
  fail "it shows the error of the last check, once: $(grep -c '^curl: ' "$TMP/output") line(s)"
fi
prints "it says the version is not answering" "swapi v2.1.0 is not answering. Roll back with:"
prints "it prints the rollback command with the previous version" \
  "  ln -sfn $ROOT/v2.0.0 $ROOT/current && systemctl restart swapi"

# The rollback hint names the version that served before the tag, never the
# tag itself, and no `ln` at all when none is recorded or it is gone.
NO_PREVIOUS='No version to roll back to: none is recorded for this tag, or the recorded one is gone.'
OLDER='  To go back to an older release: swapi-update <tag> <sha256 from its run summary>'

# no_rollback <description> <tag>: the last run printed no `ln` command, and
# said so.
no_rollback() {
  if grep -q 'ln -sfn' "$TMP/output"; then
    fail "$1: it printed a rollback command"
    sed 's/^/      /' "$TMP/output"
  else
    prints "$1" "swapi $2 is not answering. $NO_PREVIOUS"
    prints "$1: it says how to reach an older release" "$OLDER"
  fi
}

host v2.0.0 v1.9.0
: > "$TMP/down"
does_not_answer "a first attempt that does not answer" v2.1.0 "v1.9.0 v2.0.0 v2.1.0"
does_not_answer "its retry that does not answer" v2.1.0 "v1.9.0 v2.0.0 v2.1.0"
prints "a retry names the version that served before the tag" \
  "  ln -sfn $ROOT/v2.0.0 $ROOT/current && systemctl restart swapi"
does_not_answer "a second retry that does not answer" v2.1.0 "v1.9.0 v2.0.0 v2.1.0"
prints "a second retry still names it" \
  "  ln -sfn $ROOT/v2.0.0 $ROOT/current && systemctl restart swapi"

# The rollback the hint asks for, then the same tag again: the hint again names
# the version that served before that tag.
ln -sfn "$ROOT/v2.0.0" "$ROOT/current"
does_not_answer "a retry after a rollback that does not answer" v2.1.0 "v1.9.0 v2.0.0 v2.1.0"
prints "a retry after a rollback names the version rolled back to" \
  "  ln -sfn $ROOT/v2.0.0 $ROOT/current && systemctl restart swapi"

# A rollback by hand to another version, then the same tag: the hint names
# the version that served before this run, not the one before the first.
ln -sfn "$ROOT/v1.9.0" "$ROOT/current"
does_not_answer "a retry from another version that does not answer" v2.1.0 "v1.9.0 v2.0.0 v2.1.0"
prints "a retry from another version names that version" \
  "  ln -sfn $ROOT/v1.9.0 $ROOT/current && systemctl restart swapi"

# A redeploy of a version the script deployed: the record of its own deploy.
host v2.0.0 v1.9.0
deploys "an upgrade that answers" v2.1.0 "v2.0.0 v2.1.0"
: > "$TMP/down"
does_not_answer "a redeploy of it that does not answer" v2.1.0 "v2.0.0 v2.1.0"
prints "a redeploy names the version before it" \
  "  ln -sfn $ROOT/v2.0.0 $ROOT/current && systemctl restart swapi"

# A recorded version pruned since: no `ln` to a directory that is gone.
rm -f "$TMP/down"
deploys "a retry of that redeploy that answers" v2.1.0 "v2.0.0 v2.1.0"
deploys "the next upgrade prunes the oldest" v2.2.0 "v2.1.0 v2.2.0"
ln -sfn "$ROOT/v2.1.0" "$ROOT/current"
: > "$TMP/down"
does_not_answer "a redeploy whose recorded previous version was pruned" v2.1.0 "v2.1.0 v2.2.0"
no_rollback "a redeploy whose recorded previous version was pruned" v2.1.0

# A redeploy with no record (installed by an older copy of the script): the
# previous version is on disk, but nothing says which one it is.
host v2.0.0 v1.9.0
: > "$TMP/down"
does_not_answer "a redeploy without a record that does not answer" v2.0.0 "v1.9.0 v2.0.0"
no_rollback "a redeploy without a record names nothing" v2.0.0

# The first install: nothing served before it.
host ""
: > "$TMP/down"
does_not_answer "a first install that does not answer" v1.0.0 "v1.0.0"
no_rollback "a first install names nothing" v1.0.0
does_not_answer "a retry of the first install that does not answer" v1.0.0 "v1.0.0"
no_rollback "a retry of the first install names nothing" v1.0.0

# Another tag after one that did not answer (#115): the version that served
# before the failed one stays the rollback target and stays on disk.
host v2.0.0 v1.9.0
: > "$TMP/down"
does_not_answer "a tag that does not answer" v2.1.0 "v1.9.0 v2.0.0 v2.1.0"
rm -f "$TMP/down"
deploys "another tag after it that answers keeps the last working version" \
  v2.2.0 "v2.0.0 v2.2.0"

host v2.0.0 v1.9.0
: > "$TMP/down"
does_not_answer "a tag that does not answer, again" v2.1.0 "v1.9.0 v2.0.0 v2.1.0"
does_not_answer "another tag after it that does not answer" \
  v2.2.0 "v1.9.0 v2.0.0 v2.1.0 v2.2.0"
prints "its hint names the last working version, not the failed tag" \
  "  ln -sfn $ROOT/v2.0.0 $ROOT/current && systemctl restart swapi"
does_not_answer "a third failed tag in a row" \
  v2.3.0 "v1.9.0 v2.0.0 v2.1.0 v2.2.0 v2.3.0"
prints "a third failed tag still names the last working version" \
  "  ln -sfn $ROOT/v2.0.0 $ROOT/current && systemctl restart swapi"
does_not_answer "a retry of the second failed tag" \
  v2.2.0 "v1.9.0 v2.0.0 v2.1.0 v2.2.0 v2.3.0"
prints "the retry of a tag reached from another failed tag names it too" \
  "  ln -sfn $ROOT/v2.0.0 $ROOT/current && systemctl restart swapi"
rm -f "$TMP/down"
deploys "a tag that answers after three failed ones prunes them" \
  v2.4.0 "v2.0.0 v2.4.0"

# A version that answered once and then did not answer on a redeploy: what
# counts is its last run, so the next tag keeps the version before it.
host v2.0.0 v1.9.0
deploys "an upgrade that answers, then" v2.1.0 "v2.0.0 v2.1.0"
: > "$TMP/down"
does_not_answer "its redeploy that does not answer" v2.1.0 "v2.0.0 v2.1.0"
rm -f "$TMP/down"
deploys "the next tag keeps the version before the one that stopped answering" \
  v2.2.0 "v2.0.0 v2.2.0"

# A failed tag that answers on a retry is a working version again.
host v2.0.0 v1.9.0
: > "$TMP/down"
does_not_answer "a tag that does not answer at first" v2.1.0 "v1.9.0 v2.0.0 v2.1.0"
rm -f "$TMP/down"
deploys "its retry answers" v2.1.0 "v1.9.0 v2.0.0 v2.1.0"
deploys "the next tag keeps the retried version as the one before it" \
  v2.2.0 "v2.1.0 v2.2.0"

# A rollback by hand after a failed tag: the next tag keeps that version.
host v2.0.0 v1.9.0
: > "$TMP/down"
does_not_answer "a failed tag before a hand rollback" v2.1.0 "v1.9.0 v2.0.0 v2.1.0"
ln -sfn "$ROOT/v1.9.0" "$ROOT/current"
rm -f "$TMP/down"
deploys "the next tag keeps the version rolled back to by hand" \
  v2.2.0 "v1.9.0 v2.2.0"

# Back to a working version from a failed tag: it never becomes its own
# rollback target, and the failed tag is pruned.
host v2.0.0 v1.9.0
deploys "a working upgrade" v2.1.0 "v2.0.0 v2.1.0"
: > "$TMP/down"
does_not_answer "a failed tag after it" v2.2.0 "v2.0.0 v2.1.0 v2.2.0"
rm -f "$TMP/down"
deploys "the working version again prunes the failed tag" v2.1.0 "v2.0.0 v2.1.0"

host v2.0.0 v1.9.0
deploys "a working upgrade, again" v2.1.0 "v2.0.0 v2.1.0"
: > "$TMP/down"
does_not_answer "a failed tag after it, again" v2.2.0 "v2.0.0 v2.1.0 v2.2.0"
does_not_answer "the working version again, not answering this time" \
  v2.1.0 "v2.0.0 v2.1.0 v2.2.0"
prints "its hint names the version before it, not the failed tag" \
  "  ln -sfn $ROOT/v2.0.0 $ROOT/current && systemctl restart swapi"

# A failed first install: nothing ever answered, so nothing to roll back to.
host ""
: > "$TMP/down"
does_not_answer "a first install that never answers" v1.0.0 "v1.0.0"
does_not_answer "another tag after it that does not answer" v1.1.0 "v1.0.0 v1.1.0"
no_rollback "it names no failed first install" v1.1.0
rm -f "$TMP/down"
deploys "a tag that answers after a failed first install prunes it" v1.2.0 "v1.2.0"

# The tag: v plus three numeric parts of 1 to 4 digits, as the deploy gate.
accepts "a release tag" v2.5.3
accepts "multi-digit version parts" v10.20.300
accepts "zeros" v0.0.0
accepts "parts of four digits" v1234.1234.1234
accepts "leading zeros, as the gate does" v01.002.0003

refuses "a tag without the v" 2.5.3
refuses "an uppercase V" V2.5.3
refuses "two parts" v2.5
refuses "four parts" v2.5.3.1
refuses "a pre-release suffix" v2.5.3-rc1
refuses "letters after the digits" v1a.2b.3c
refuses "a letter as a part" v2.x.3
refuses "an empty part" v2..3
refuses "an empty first part" v.5.3
refuses "an empty last part" v2.5.
refuses "non-ASCII digits" v٢.5.3
refuses "a part of five digits" v12345.0.0
refuses "a last part of five digits" v2.5.12345
refuses "a path after the tag" v2.5.3/../../tmp
refuses "a path inside the tag" v2.5/../v2.0.0
refuses "a trailing dot" v2.5.3.
refuses "a trailing space" "v2.5.3 "
refuses "a leading space" " v2.5.3"
refuses "a line break after the tag" "v2.5.3
x"
refuses "a second v" vv2.5.3
refuses "a glob" 'v*.*.*'

echo
if [ "$FAILURES" -eq 0 ]; then
  echo "swapi-update: all tests green"
else
  echo "swapi-update: $FAILURES test(s) failed"
  exit 1
fi
