#!/usr/bin/env bash
# Counts every scheduled run of the Uptime workflow, for the monitor count of
# the FAQ answer "How do I know it is up?" (docs/RELEASE.md, step 4).
#
# usage: scripts/uptime-run-count.sh        (from a checkout, with `gh` logged in)
#
# Prints the total, the successes, the first and the last run (UTC) and, when
# there are any, the conclusions of the runs that did not succeed.
#
# Why not `gh run list --limit N`: GitHub cuts a list of runs at 1000 results,
# whatever the limit, and the history passed that in early November 2026 (#67).
# So the history is read in windows of `created` date, newest first: each window
# is "created up to the earliest run seen so far", and a window can hold up to
# the cap. The bound is inclusive and the runs are kept once per id, so two runs
# of the same second at a window's edge are neither lost nor counted twice, and
# a run that finishes between two windows is counted once.
#
# Exits non-zero, printing no count, unless the earliest run is the first
# scheduled run and the runs add up to the `total_count` the API reports.
# Regression test: scripts/uptime-run-count.test.sh.
set -euo pipefail

FIRST_RUN=2026-10-19T22:39:05Z
RUNS_API='repos/{owner}/{repo}/actions/workflows/uptime.yml/runs'

die() { echo "uptime-run-count.sh: $1" >&2; exit 1; }

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# One upper bound for the whole walk: a run that starts meanwhile is in neither
# the total nor the windows.
NOW="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

reported="$(gh api -X GET "$RUNS_API" -f event=schedule -f "created=<=$NOW" -f per_page=1 \
  --jq '.total_count')" || die "gh could not read the total of scheduled runs"

# The created_at column of the runs collected, oldest first.
created_dates() { cut -f2 "$TMP/runs" | sort; }

: > "$TMP/runs"
bound="$NOW"
collected=0
while :; do
  gh api -X GET "$RUNS_API" -f event=schedule -f "created=<=$bound" -f per_page=100 --paginate \
    --jq '.workflow_runs[] | [.id, .created_at, .conclusion // .status] | @tsv' > "$TMP/window" \
    || die "gh could not list the scheduled runs created up to $bound"
  # One line per run id, the newest answer first: a run that finished between
  # two windows is counted once, with its conclusion.
  cat "$TMP/window" "$TMP/runs" | awk -F'\t' '!seen[$1]++' > "$TMP/merged"
  mv "$TMP/merged" "$TMP/runs"
  count="$(wc -l < "$TMP/runs" | tr -d ' ')"
  [ "$count" -gt "$collected" ] || break
  collected="$count"
  bound="$(created_dates | sed -n 1p)"
done

first="$(created_dates | sed -n 1p)"
last="$(created_dates | sed -n '$p')"

[ "$first" = "$FIRST_RUN" ] \
  || die "the earliest run found is '$first', not the first scheduled run $FIRST_RUN: the history is incomplete, do not publish a count"
[ "$collected" = "$reported" ] \
  || die "found $collected runs but GitHub reports $reported up to $NOW: do not publish a count"

echo "scheduled runs: $collected"
echo "successful: $(cut -f3 "$TMP/runs" | grep -c -x success || true)"
echo "first: $first"
echo "last: $last"
others="$(cut -f3 "$TMP/runs" | grep -v -x success | sort | uniq -c | awk '{printf "%s%s %s", sep, $1, $2; sep=", "}' || true)"
[ -z "$others" ] || echo "not successful: $others"
