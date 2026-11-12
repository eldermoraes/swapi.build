#!/usr/bin/env bash
# Regression test (run by .github/workflows/ci.yml): nothing outside the
# application build is left where nobody would find or build it.
#
# 1. Every helper script in scripts/ (the tests aside) is named by a runbook in
#    docs/ or by a workflow, outside its comments. scripts/mcp-listen-load.sh cited docs/RELEASE.md in
#    its header for two releases while the runbook never mentioned it (#69).
# 2. Every Maven project under examples/ is named by a workflow, outside its
#    comments. The examples are standalone projects: `./mvnw package` in
#    swapi-app never compiles them, so .github/workflows/examples.yml does.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

fail=0
pass() { echo "PASS  $1"; }
flunk() { echo "FAIL  $1"; fail=1; }
# What the workflows do, not what their comments say.
workflow_lines() { grep -hv '^[[:space:]]*#' .github/workflows/*.yml; }

for script in scripts/*.sh; do
  case "$script" in *.test.sh) continue ;; esac
  name="$(basename "$script")"
  if grep -qF -- "$name" docs/*.md || workflow_lines | grep -qF -- "$name"; then
    pass "$script is named by a runbook or a workflow"
  else
    flunk "$script is named by no file in docs/ and by no workflow: reference it or remove it"
  fi
done

found=0
while IFS= read -r pom; do
  found=1
  dir="$(dirname "$pom")"
  if workflow_lines | grep -qF -- "$dir"; then
    pass "$dir is named by a workflow"
  else
    flunk "$dir is named by no workflow: add it to .github/workflows/examples.yml"
  fi
done < <(find examples -name pom.xml -not -path '*/target/*' | sort)
[ "$found" = 1 ] || flunk "no Maven project found under examples/: this test is looking in the wrong place"

exit "$fail"
