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
# script's own --jq expression with jq. Needs python3 and jq, both on the CI
# runner.
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

# The request for the total is "total"; the list requests are "list1", "list2"...
request = "total"
if paginate:
    calls = os.path.join(os.environ["FAKE_STATE"], "list-calls")
    with open(calls, "a") as f:
        f.write("x")
    request = "list%d" % os.path.getsize(calls)
if os.environ.get("FAKE_FAIL") == request:
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
        # FAKE_RUNNING_AT_FIRST_LIST: a run still in progress when the first
        # window is read, finished (as the fixture says) from the second on.
        if request == "list1" and run_id == os.environ.get("FAKE_RUNNING_AT_FIRST_LIST"):
            status, conclusion = "in_progress", "-"
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

# run_count <fixture> [VAR=value ...]: runs the script against the fake gh;
# stdout goes to $TMP/out, stderr to $TMP/err.
run_count() {
  local fixture="$1"; shift
  rm -rf "$TMP/state"; mkdir "$TMP/state"
  env FAKE_STATE="$TMP/state" PATH="$TMP/bin:$PATH" FAKE_RUNS="$fixture" FAKE_CAP=5 "$@" "$SCRIPT" > "$TMP/out" 2> "$TMP/err"
}

# refused <description> <text expected on stderr>: the last count failed, and
# printed no number.
refused() {
  if [ ! -s "$TMP/out" ] && grep -q "uptime-run-count.sh: .*$2" "$TMP/err"; then
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
if run_count "$TMP/history" && diff "$TMP/expected" "$TMP/out" > "$TMP/diff"; then
  pass "12 runs behind a cap of 5: all counted, down to the first run"
else
  fail "12 runs behind a cap of 5: $(cat "$TMP/diff" "$TMP/err" 2>/dev/null)"
fi

# 2. Run 108 sits on the edge of the first window, so the second window returns
# it again; it finished in between. Counted once, with its conclusion.
if run_count "$TMP/history" FAKE_RUNNING_AT_FIRST_LIST=108 && diff "$TMP/expected" "$TMP/out" > "$TMP/diff"; then
  pass "a run that finishes between two windows: counted once, as finished"
else
  fail "a run that finishes between two windows: $(cat "$TMP/diff" "$TMP/err" 2>/dev/null)"
fi

# 3. Every run succeeded: no "not successful" line.
grep -v -e failure -e cancelled -e in_progress "$TMP/history" > "$TMP/all-green"
cat > "$TMP/expected-green" <<'EOF'
scheduled runs: 9
successful: 9
first: 2026-10-19T22:39:05Z
last: 2026-10-20T00:52:19Z
EOF
if run_count "$TMP/all-green" && diff "$TMP/expected-green" "$TMP/out" > "$TMP/diff"; then
  pass "all successful: four lines"
else
  fail "all successful: $(cat "$TMP/diff" "$TMP/err" 2>/dev/null)"
fi

# 4. The earliest run found is not the first scheduled run: no count.
tail -n +2 "$TMP/history" > "$TMP/cut"
if run_count "$TMP/cut"; then fail "history without the first run: accepted"
else refused "history without the first run: refused" "not the first scheduled run 2026-10-19T22:39:05Z"; fi

# 5. The runs collected do not add up to the total the API reports: no count.
if run_count "$TMP/history" FAKE_TOTAL_DELTA=1; then fail "total of 13 for 12 runs: accepted"
else refused "total of 13 for 12 runs: refused" "found 12 runs but GitHub reports 13"; fi

# 6. gh fails, on the total or on the second window of the walk: no count, and
# the script says which request failed.
if run_count "$TMP/history" FAKE_FAIL=total; then fail "gh fails on the total: accepted"
else refused "gh fails on the total: refused" "could not read the total"; fi
if run_count "$TMP/history" FAKE_FAIL=list2; then fail "gh fails on the second window: accepted"
else refused "gh fails on the second window: refused" "could not list the scheduled runs created up to 2026-10-20T00:07:30Z"; fi

if [ "$FAILURES" -ne 0 ]; then
  echo "$FAILURES check(s) failed"
  exit 1
fi
echo "all checks passed"
