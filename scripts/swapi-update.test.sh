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
# the content of $TMP/failing-checks. It writes one line per health check, the
# --max-time it was given, to $TMP/checks, and creates $TMP/downloaded on a
# download. sleep writes the seconds it was asked for to $TMP/slept. install
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
    [ ! -e "$TMP/down" ] && [ "\$(wc -l < "$TMP/checks")" -gt "\$failing" ] ;;
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

# total <file>: the sum of the numbers in a file of one number per line.
total() { awk '{ sum += $1 } END { print sum + 0 }' "$1" 2>/dev/null || echo 0; }

# waited <description> <expected health checks> <most seconds it may take>:
# the last run checked this many times, and the waits between the checks plus
# the limit of every check add up to no more than that.
waited() {
  checks="$(wc -l < "$TMP/checks" 2>/dev/null | tr -d ' ' || true)"
  longest=$(( $(total "$TMP/slept") + $(total "$TMP/checks") ))
  if [ "${checks:-0}" = "$2" ] && [ "$longest" -le "$3" ]; then
    pass "$1"
  else
    fail "$1: ${checks:-0} health check(s) (expected $2), up to $longest s (at most $3)"
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

# accepts <tag>: the script deploys it on an empty host.
accepts() {
  host ""
  deploys "accepts the tag $1" "$1" "$1"
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
waited "a version that answers at once is checked once" 1 3

host v2.0.0 v1.9.0
echo 4 > "$TMP/failing-checks"
deploys "a version that answers on the fifth check is deployed" v2.1.0 "v2.0.0 v2.1.0"
waited "the script stops checking when the version answers" 5 15

host v2.0.0 v1.9.0
echo 9 > "$TMP/failing-checks"
deploys "a version that answers on the last check is deployed" v2.1.0 "v2.0.0 v2.1.0"

host v2.0.0 v1.9.0
: > "$TMP/down"
does_not_answer "a version that never answers is not deployed" v2.1.0 "v1.9.0 v2.0.0 v2.1.0"
waited "a version that never answers is checked 10 times, 30 s at most" 10 30
prints "it says the version is not answering" "swapi v2.1.0 is not answering. Roll back with:"
prints "it prints the rollback command with the previous version" \
  "  ln -sfn $ROOT/v2.0.0 $ROOT/current && systemctl restart swapi"

# The tag: v plus three numeric parts of 1 to 4 digits, as the deploy gate.
accepts v2.5.3
accepts v10.20.300
accepts v0.0.0
accepts v1234.1234.1234

refuses "a tag without the v" 2.5.3
refuses "an uppercase V" V2.5.3
refuses "two parts" v2.5
refuses "four parts" v2.5.3.1
refuses "a pre-release suffix" v2.5.3-rc1
refuses "letters after the digits" v1a.2b.3c
refuses "a letter as a part" v2.x.3
refuses "an empty part" v2..3
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
