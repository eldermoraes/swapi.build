#!/usr/bin/env bash
# Test for scripts/uptime-run-count.sh (run by .github/workflows/ci.yml).
#
# Bug covered: the release runbook counted the scheduled Uptime runs with
# `gh run list --limit 5000`, and GitHub cuts a list of runs at 1000 results, so
# from early November 2026 the command returned the newest 1000 only (#67).
#
# Runs offline: a fake `gh` on PATH serves a fixture history the way the Actions
# API does (newest first, `created=<=...` filter, the real total in
# `total_count`) but cuts every list at FAKE_CAP runs, and evaluates the
# script's own --jq expression with jq.
set -euo pipefail

SCRIPT="$(cd "$(dirname "$0")" && pwd)/uptime-run-count.sh"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

FAILURES=0
pass() { printf 'PASS  %s\n' "$1"; }
fail() { printf 'FAIL  %s\n' "$1"; FAILURES=$((FAILURES+1)); }

mkdir "$TMP/bin"
cat > "$TMP/bin/gh" <<'EOF'
#!/usr/bin/env python3
import json, os, subprocess, sys

args = sys.argv[1:]
fields, jq, paginate = {}, None, False
i = 0
while i < len(args):
    a = args[i]
    if a == "-f":
        key, _, value = args[i + 1].partition("=")
        fields[key] = value
        i += 2
    elif a == "--jq":
        jq = args[i + 1]
        i += 2
    elif a == "--paginate":
        paginate = True
        i += 1
    else:
        i += 1

if os.environ.get("FAKE_FAIL") == ("list" if paginate else "count"):
    sys.exit("fake gh: HTTP 502")
if "/actions/workflows/uptime.yml/runs" not in " ".join(args) or fields.get("event") != "schedule":
    sys.exit("fake gh: unexpected request: " + " ".join(args))
created = fields.get("created", "")
if not created.startswith("<="):
    sys.exit("fake gh: unsupported created filter: " + created)

runs = []
with open(os.environ["FAKE_RUNS"]) as f:
    for line in f:
        run_id, created_at, status, conclusion = line.split()
        if created_at <= created[2:]:
            runs.append({"id": int(run_id), "created_at": created_at, "status": status,
                         "conclusion": None if conclusion == "-" else conclusion})
runs.sort(key=lambda r: (r["created_at"], r["id"]), reverse=True)
total = len(runs) + int(os.environ.get("FAKE_TOTAL_DELTA", "0"))
per_page = int(fields.get("per_page", "30"))
reachable = runs[: int(os.environ["FAKE_CAP"])]
pages = [reachable[n:n + per_page] for n in range(0, len(reachable), per_page)] or [[]]
if not paginate:
    pages = pages[:1]
for page in pages:
    body = json.dumps({"total_count": total, "workflow_runs": page})
    sys.stdout.write(subprocess.run(["jq", "-r", jq], input=body, text=True,
                                    capture_output=True, check=True).stdout)
EOF
chmod +x "$TMP/bin/gh"

# Twelve scheduled runs, oldest first. Runs 7 and 8 were created in the same
# second, and with a cap of 5 the first window ends exactly between them.
cat > "$TMP/history" <<'EOF'
101 2026-10-19T22:39:05Z completed success
102 2026-10-19T22:49:18Z completed success
103 2026-10-19T23:12:52Z completed failure
104 2026-10-19T23:28:04Z completed success
105 2026-10-19T23:40:58Z completed success
106 2026-10-19T23:50:11Z completed success
107 2026-10-20T00:07:30Z completed success
108 2026-10-20T00:07:30Z completed success
109 2026-10-20T00:21:44Z completed cancelled
110 2026-10-20T00:36:02Z completed success
111 2026-10-20T00:52:19Z completed success
112 2026-10-20T01:05:47Z in_progress -
EOF

# count <fixture> [VAR=value ...]: runs the script against the fake gh; stdout
# goes to $TMP/out, stderr to $TMP/err.
count() {
  local fixture="$1"; shift
  env PATH="$TMP/bin:$PATH" FAKE_RUNS="$fixture" FAKE_CAP=5 "$@" "$SCRIPT" > "$TMP/out" 2> "$TMP/err"
}

# refused <description> <word expected on stderr>: the last count failed, and
# printed no number.
refused() {
  if [ ! -s "$TMP/out" ] && grep -q "$2" "$TMP/err"; then
    pass "$1"
  else
    fail "$1: stdout '$(cat "$TMP/out")', stderr '$(cat "$TMP/err")'"
  fi
}

# 1. A history longer than the cap is counted whole, the same-second pair once.
cat > "$TMP/expected" <<'EOF'
scheduled runs: 12
successful: 9
first: 2026-10-19T22:39:05Z
last: 2026-10-20T01:05:47Z
not successful: 1 cancelled, 1 failure, 1 in_progress
EOF
if count "$TMP/history" && diff "$TMP/expected" "$TMP/out" > "$TMP/diff"; then
  pass "12 runs behind a cap of 5: all counted, down to the first run"
else
  fail "12 runs behind a cap of 5: $(cat "$TMP/diff" "$TMP/err" 2>/dev/null)"
fi

# 2. Every run succeeded: no "not successful" line.
grep -v -e failure -e cancelled -e in_progress "$TMP/history" > "$TMP/all-green"
if count "$TMP/all-green" && [ "$(cat "$TMP/out")" = "scheduled runs: 9
successful: 9
first: 2026-10-19T22:39:05Z
last: 2026-10-20T00:52:19Z" ]; then
  pass "all successful: four lines"
else
  fail "all successful: stdout '$(cat "$TMP/out")', stderr '$(cat "$TMP/err")'"
fi

# 3. The earliest run found is not the first scheduled run: no count.
tail -n +2 "$TMP/history" > "$TMP/cut"
if count "$TMP/cut"; then fail "history without the first run: accepted"
else refused "history without the first run: refused" "2026-10-19T22:39:05Z"; fi

# 4. The runs collected do not add up to the total the API reports: no count.
if count "$TMP/history" FAKE_TOTAL_DELTA=1; then fail "total of 13 for 12 runs: accepted"
else refused "total of 13 for 12 runs: refused" "13"; fi

# 5. gh fails, on the total or in the middle of the walk: no count.
for step in count list; do
  if count "$TMP/history" FAKE_FAIL=$step; then fail "gh fails on the $step request: accepted"
  else refused "gh fails on the $step request: refused" "gh"; fi
done

if [ "$FAILURES" -ne 0 ]; then
  echo "$FAILURES check(s) failed"
  exit 1
fi
echo "all checks passed"
