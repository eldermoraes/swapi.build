#!/usr/bin/env bash
# Release rehearsal only (2.12.1, docs/RELEASE.md): measures what concurrent
# subscriptions/listen cost the server, and the RSS over many tool calls. Never
# run against production: it opens hundreds of connections on purpose.
#
# usage:
#   mcp-listen-load.sh listens <base-url> <target> [count=500] [hold=60]
#   mcp-listen-load.sh calls   <base-url> <target> [count=10000]
#
# <target> is where the RSS is read: a local pid (ps -o rss=), or
# podman:<container> for the native binary in a container (VmRSS of its PID 1).
#
# listens: opens <count> stateless listens at once, each in its own curl in the
# background (no bash -c), keeps them up to <hold> s, and prints the RSS at 0,
# 30 and <hold> s and 5 s after closing them, plus how many answered within 2 s
# and how many were still open at 30 s. 2.12.0: the streams stay open and the
# RSS grows. 2.12.1: every listen is answered at once and the RSS stays flat.
#
# calls: <count> stateless tools/call sw_get PEOPLE 1, one after the other on
# one curl (a config file, keep-alive), with the RSS before and after; the
# only measure here of the request context leak fixed by quarkus-mcp-server
# PR #1060 (2.0.3).
set -u

MODE="${1:?usage: mcp-listen-load.sh listens|calls <base-url> <target> [count] [hold]}"
BASE="${2:?base url}"; BASE="${BASE%/}"
TARGET="${3:?pid or podman:<container>}"
META='"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientInfo":{"name":"listen-load","version":"1.0"},"io.modelcontextprotocol/clientCapabilities":{}}'

rss_kb() {
  case "$TARGET" in
    podman:*) podman exec "${TARGET#podman:}" cat /proc/1/status | awk '/^VmRSS:/{print $2}' ;;
    *) ps -o rss= -p "$TARGET" | tr -d ' ' ;;
  esac
}

WORK=$(mktemp -d "${TMPDIR:-/tmp}/mcp-listen-load.XXXXXX")
trap 'rm -rf "$WORK"' EXIT

case "$MODE" in
listens)
  COUNT="${4:-500}"; HOLD="${5:-60}"
  body='{"jsonrpc":"2.0","id":1,"method":"subscriptions/listen","params":{"notifications":{"toolsListChanged":true},'"$META"'}}'
  echo "rss_kb t=0 (before): $(rss_kb)"
  pids=()
  for i in $(seq 1 "$COUNT"); do
    curl -s -N --max-time "$((HOLD + 5))" -o "$WORK/$i.body" -w '%{exitcode} %{time_total}\n' \
      -X POST "$BASE/mcp" -H 'Content-Type: application/json' \
      -H 'Accept: application/json, text/event-stream' \
      -H 'Mcp-Method: subscriptions/listen' -H 'MCP-Protocol-Version: 2026-07-28' \
      -d "$body" > "$WORK/$i.meta" 2>/dev/null &
    pids+=("$!")
  done
  echo "opened $COUNT listens"
  open_now() { local n=0 p; for p in "${pids[@]}"; do kill -0 "$p" 2>/dev/null && n=$((n + 1)); done; echo "$n"; }
  t=0
  for mark in 30 "$HOLD"; do
    while [ "$t" -lt "$mark" ]; do sleep 1; t=$((t + 1)); done
    echo "rss_kb t=${mark}s: $(rss_kb)  still open: $(open_now)"
  done
  for p in "${pids[@]}"; do kill "$p" 2>/dev/null; done
  wait 2>/dev/null
  sleep 5
  echo "rss_kb 5 s after closing: $(rss_kb)"
  fast=0; refused=0
  for i in $(seq 1 "$COUNT"); do
    read -r code secs < "$WORK/$i.meta" 2>/dev/null || continue
    [ "$code" = "0" ] && awk -v s="$secs" 'BEGIN{exit !(s < 2)}' && fast=$((fast + 1))
    grep -qF '"code":-32601' "$WORK/$i.body" 2>/dev/null && refused=$((refused + 1))
  done
  echo "answered within 2 s: $fast/$COUNT  with -32601: $refused/$COUNT"
  ;;
calls)
  COUNT="${4:-10000}"
  cfg="$WORK/calls.cfg"
  for i in $(seq 1 "$COUNT"); do
    {
      echo "url = \"$BASE/mcp\""
      echo 'request = "POST"'
      echo 'header = "Content-Type: application/json"'
      echo 'header = "Accept: application/json, text/event-stream"'
      echo 'header = "Mcp-Method: tools/call"'
      echo 'header = "Mcp-Name: sw_get"'
      echo 'header = "MCP-Protocol-Version: 2026-07-28"'
      printf 'data = "%s"\n' "$(printf '%s' '{"jsonrpc":"2.0","id":'"$i"',"method":"tools/call","params":{"name":"sw_get","arguments":{"resource":"PEOPLE","id":1},'"$META"'}}' | sed 's/"/\\"/g')"
      echo 'output = "/dev/null"'
      echo 'write-out = "%{http_code}\n"'
      [ "$i" -lt "$COUNT" ] && echo 'next'
    } >> "$cfg"
  done
  echo "rss_kb before $COUNT tool calls: $(rss_kb)"
  start=$(date +%s)
  curl -s -K "$cfg" > "$WORK/codes" 2>/dev/null
  end=$(date +%s)
  ok=$(grep -c '^200$' "$WORK/codes")
  echo "200: $ok/$COUNT in $((end - start)) s"
  sleep 5
  echo "rss_kb after $COUNT tool calls (+5 s): $(rss_kb)"
  ;;
*) echo "invalid mode: $MODE"; exit 2 ;;
esac
