#!/usr/bin/env bash
# Regression test (run by .github/workflows/ci.yml): the repo-root vercel.json
# must keep git.deploymentEnabled false.
#
# Nothing of this project runs on Vercel, but the GitHub repository is still
# connected to the old Vercel project through the Vercel GitHub app. Removing
# vercel.json in PR #25 made that app start a Vercel deployment for the pull
# request at once (2026-10-19). The file stays until the repository is
# disconnected on the Vercel side; only then may this test and the file go.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
FILE="$ROOT/vercel.json"

[ -f "$FILE" ] || { echo "FAIL  vercel.json is missing: the Vercel GitHub app would deploy every push"; exit 1; }

if python3 -I -c '
import json, sys
config = json.load(open(sys.argv[1]))
sys.exit(0 if config.get("git", {}).get("deploymentEnabled") is False else 1)
' "$FILE"; then
  echo "PASS  vercel.json keeps git.deploymentEnabled false"
else
  echo "FAIL  vercel.json must set git.deploymentEnabled to false"
  exit 1
fi
