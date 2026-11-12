#!/usr/bin/env bash
# Regression test (run by .github/workflows/ci.yml): nothing outside the
# application build is left where nobody would find or build it.
#
# 1. Every script directly in scripts/ (the tests aside) is named by a document
#    in docs/ or by a workflow, outside its comments. What lives in scripts/lib
#    or serves a test is used by the scripts themselves and is not checked.
#    scripts/mcp-listen-load.sh cited docs/RELEASE.md in its header for two
#    releases while the runbook never mentioned it (#69).
# 2. Every Maven project under examples/ is named by
#    .github/workflows/examples.yml, outside its comments. The examples are
#    standalone projects: `./mvnw package` in swapi-app never compiles them, so
#    that workflow does, one matrix entry per project.
#
# No `set -e`: every rule is checked and reported before the exit status.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

fail=0
pass() { echo "PASS  $1"; }
flunk() { echo "FAIL  $1"; fail=1; }

# What a workflow does, not what its comments say.
without_comments() { grep -hv '^[[:space:]]*#' "$@"; }

# mentions <text> <name>: <name> occurs in <text> as a whole name, so that
# verify-deploy.sh does not stand for a deploy.sh, nor quarkus-rest-client for
# a quarkus-rest. The text is a variable, not a pipe: grep -q leaves at the
# first match, and under pipefail a writer cut short would read as a failure.
mentions() {
  local name
  name="$(printf '%s' "$2" | sed 's/[][\.*^$]/\\&/g')"
  grep -qE "(^|[^[:alnum:]_-])${name}([^[:alnum:]_-]|\$)" <<< "$1"
}

docs="$(cat docs/*.md)"
workflows="$(without_comments .github/workflows/*.yml)"
examples_workflow="$(without_comments .github/workflows/examples.yml 2>/dev/null)"

for script in scripts/*.sh; do
  case "$script" in *.test.sh) continue ;; esac
  name="$(basename "$script")"
  if mentions "$docs" "$name" || mentions "$workflows" "$name"; then
    pass "$script is named by a document in docs/ or by a workflow"
  else
    flunk "$script is named by no document in docs/ and by no workflow: reference it or remove it"
  fi
done

projects=0
while IFS= read -r pom; do
  projects=$((projects + 1))
  dir="$(dirname "$pom")"
  if mentions "$examples_workflow" "$dir"; then
    pass "$dir is named by .github/workflows/examples.yml"
  else
    flunk "$dir is not named by .github/workflows/examples.yml: add it to the matrix"
  fi
done < <(find examples -name pom.xml -not -path '*/target/*' | sort)
[ "$projects" -gt 0 ] || flunk "no Maven project found under examples/: this test is looking in the wrong place"

exit "$fail"
