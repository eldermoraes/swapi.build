#!/usr/bin/env bash
# Test for the host script swapi-update (run by .github/workflows/ci.yml).
# The script has one source: the code block of docs/DEPLOY.md, section 2.5.
# This test extracts that block and runs it locally, without the host: its
# /opt/swapi becomes a temporary directory, and curl, systemctl, install, ldd
# and sleep are fakes first on PATH. It checks which version directories are
# left and where `current` points.
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
# check unless $TMP/down exists; install ignores owner, group and mode.
mkdir -p "$TMP/bin" "$TMP/release"
printf 'a native binary\n' > "$TMP/release/application"
HASH="$(cd "$TMP/release" && sha256sum application | cut -d' ' -f1)"
printf '%s  application\n' "$HASH" > "$TMP/release/application.sha256"

cat > "$TMP/bin/curl" <<EOF
#!/usr/bin/env bash
out=/dev/null; url=
while [ \$# -gt 0 ]; do
  case "\$1" in
    -o) out="\$2"; shift 2 ;;
    --proto | --max-time) shift 2 ;;
    -*) shift ;;
    *) url="\$1"; shift ;;
  esac
done
case "\$url" in
  https://github.com/*/application) cp "$TMP/release/application" "\$out" ;;
  https://github.com/*/application.sha256) cp "$TMP/release/application.sha256" "\$out" ;;
  http://127.0.0.1:*) [ ! -e "$TMP/down" ] ;;
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
for name in systemctl ldd sleep; do
  printf '#!/bin/sh\nexit 0\n' > "$TMP/bin/$name"
done
chmod +x "$TMP/bin"/*

# host <active version> <other version>...: a host with these versions on disk.
# An empty first argument means no `current` link (before the first install).
host() {
  rm -rf "$ROOT" "$TMP/down"
  mkdir -p "$ROOT"
  active="$1"
  for version in "$@"; do
    [ -z "$version" ] || { mkdir "$ROOT/$version"; : > "$ROOT/$version/application"; }
  done
  [ -z "$active" ] || ln -s "$ROOT/$active" "$ROOT/current"
}

update() { PATH="$TMP/bin:$PATH" sh "$TMP/swapi-update" "$1" "$HASH" > "$TMP/output" 2>&1; }

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

echo
if [ "$FAILURES" -eq 0 ]; then
  echo "swapi-update: all tests green"
else
  echo "swapi-update: $FAILURES test(s) failed"
  exit 1
fi
