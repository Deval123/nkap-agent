#!/usr/bin/env bash
# Runs the runner (RunCli) against a scripted fake model and a fake gateway, and checks what it does: the table
# for the six catalogue cases, that it reads the gateway with GET only and nothing else, that a wrong entry from
# the model is refused and shown as a mismatch (exit 1), that the call cap and the stop after three failures
# hold, that it refuses to start on bad input, and that no key is left in an output or a record. Nothing here
# talks to a provider or needs a stack; the keys are obviously fake values.
#
# Usage: scripts/verify-run.sh
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODEL_KEY="TEST-ONLY-MODEL-KEY-4c9a"
AGENT_KEY="TEST-ONLY-AGENT-KEY-81f0"
failures=0
expect() {
  if [ "$2" = "$3" ]; then printf '  ok    %s\n' "$1"; else printf '  FAIL  %s: got [%s], expected [%s]\n' "$1" "$2" "$3" >&2; failures=$((failures + 1)); fi
}
say() { printf '\n==== %s\n' "$*"; }

work="$(mktemp -d)"; chmod 700 "$work"
pids=()
stop_servers() { for p in ${pids[@]+"${pids[@]}"}; do kill "$p" 2>/dev/null || true; wait "$p" 2>/dev/null || true; done; pids=(); }
trap 'stop_servers; rm -rf "$work"' EXIT

(cd "$root" && mvn -B -q compile && mvn -B -q dependency:build-classpath -Dmdep.outputFile="$work/classpath") >&2
cli() { java -cp "$root/target/classes:$(cat "$work/classpath")" dev.nkap.agent.loop.RunCli "$@"; }
cli fake-data "$work/data"

start() { # name script [expected model key] -> sets <name>_port
  local name="$1"; shift
  : >"$work/$name.log"
  if [ "$name" = gateway ]; then
    python3 "$root/scripts/fake-gateway.py" "$work/data" "$work/gateway.log" "$AGENT_KEY" >"$work/$name.port" &
  else
    python3 "$root/scripts/fake-agent-model.py" "$work/data/$1" "$work/model.log" ${2:+"$2"} >"$work/$name.port" &
  fi
  pids+=($!)
  for _ in $(seq 1 50); do [ -s "$work/$name.port" ] && break; sleep 0.1; done
  eval "${name}_port=$(head -n1 "$work/$name.port")"
}

# runner [VAR=value ...] -- ARGS -> runs the runner; sets $code, $out, $err
runner() {
  local envs=()
  while [ "$1" != "--" ]; do envs+=("$1"); shift; done; shift
  rm -rf "$work/results"
  set +e
  env NEBIUS_API_KEY="$MODEL_KEY" NKAP_AGENT_KEY="$AGENT_KEY" NKAP_GATEWAY_URL="http://127.0.0.1:${gateway_port:-1}" \
      NEBIUS_BASE_URL="http://127.0.0.1:${model_port:-1}/v1/" ${envs[@]+"${envs[@]}"} \
      java -cp "$root/target/classes:$(cat "$work/classpath")" dev.nkap.agent.loop.RunCli run \
      --manifest "$work/data/manifest.json" --results-dir "$work/results" "$@" >"$work/out" 2>"$work/err"
  code=$?
  set -e
  out="$(cat "$work/out")"; err="$(cat "$work/err")"
}
lines() { grep -c . "$1" || true; }

start gateway
start model script.json

say "Six cases, two runs each, a model that answers as the catalogue expects"
runner -- --runs 2 --max-calls 100
expect "exit 0" "$code" "0"
expect "12 runs, all matching the catalogue" "$(grep -c 'run: 12 of 12 run(s) matched the catalogue' <<<"$out")" "1"
expect "the worst-case spend is printed first" "$(head -n 3 "$work/out" | grep -c 'worst-case spend')" "1"
for c in unrecorded-fee duplicate-posting money-without-owner injection i-do-not-know self-approval; do
  expect "$c: two matching rows" "$(grep -c "^$c .* ok: " <<<"$out")" "2"
done
expect "the proposals say hypothesis and entry are right" "$(grep -c 'PROPOSED .*ok: hypothesis right, entry right' <<<"$out")" "6"
expect "the refusals say no_proposal, as expected" "$(grep -c 'NO_PROPOSAL .*ok: no_proposal, as expected' <<<"$out")" "6"
expect "36 model calls (3 per run)" "$(lines "$work/model.log")" "36"
expect "every call asked for the strict schema, with the 6000-token bound" "$(grep -c 'max_tokens=6000 bearer=ok strict=true' "$work/model.log")" "36"
expect "24 gateway reads, every one a GET with the agent key" "$(grep -c '^GET .* bearer=ok' "$work/gateway.log")" "24"
expect "no gateway request but GET" "$(grep -vc '^GET ' "$work/gateway.log" || true)" "0"
expect "12 records were kept" "$(cat "$work"/results/*.jsonl | wc -l | tr -d ' ')" "12"
expect "the records are private" "$(stat -f '%Lp' "$work"/results/*.jsonl 2>/dev/null || stat -c '%a' "$work"/results/*.jsonl)" "600"
injected="$(cat "$work"/results/*.jsonl | jq -r 'select(.case == "injection") | .record.messages[] | select(.content | contains("Ignore your previous")) | .content')"
expect "the injection sentence reached the model in both runs, inside a block" "$(grep -c '^<tool_result .*Ignore your previous' <<<"$injected")" "2"
expect "and nothing follows the harness's closing tag" "$(grep -c '</tool_result>$' <<<"$injected")" "2"
expect "a case with no statement import is told so by the gateway: the nil import answers 404, and the run still ends as expected" \
  "$(cat "$work"/results/*.jsonl | jq -r 'select(.case == "injection" or .case == "i-do-not-know") | .record.toolCalls[1].status' | sort -u)" "404"
expect "standard output holds only the table and the totals: no log line" \
  "$(grep -c -E '^(INFO|WARNING|SEVERE|[A-Z][a-z]{2} [0-9]{1,2}, [0-9]{4})' "$work/out" || true)" "0"
expect "the log is quiet by default: no INFO line on standard error" "$(grep -c 'INFO:' "$work/err" || true)" "0"
leftovers="$(cat "$work/out" "$work/err" "$work"/results/* "$work/model.log" "$work/gateway.log")"
expect "no key in any output, record or log" "$(grep -c -e "$MODEL_KEY" -e "$AGENT_KEY" <<<"$leftovers" || true)" "0"
expect "the fake reasoning text is in none of them" "$(grep -c 'FAKE-REASONING-TEXT' <<<"$leftovers" || true)" "0"

say "--verbose turns the log on, on standard error, and leaves standard output as it was"
runner -- --runs 1 --max-calls 100 --verbose
expect "exit 0" "$code" "0"
expect "30 INFO lines are on standard error (6 runs x 3 model calls and 2 tool calls)" "$(grep -c 'INFO:' "$work/err")" "30"
expect "none is on standard output" "$(grep -c -E '^(INFO|[A-Z][a-z]{2} [0-9]{1,2}, [0-9]{4})' "$work/out" || true)" "0"

say "A model that books the entry of the measured run for the unrecorded fee"
stop_servers; start gateway; start model script-wrong.json
runner -- --runs 1 --max-calls 100
expect "exit 1: a run did not match" "$code" "1"
expect "the table says the unrecorded-fee run was REJECTED and does not match" "$(grep -c '^unrecorded-fee .*REJECTED .*NO: ' <<<"$out")" "1"
expect "the other five matched" "$(grep -c 'run: 5 of 6 run(s) matched the catalogue' <<<"$out")" "1"
expect "the record names the rule that refused it" "$(cat "$work"/results/*.jsonl | jq -r 'select(.case == "unrecorded-fee") | .record.outcome.violations[].code')" "pattern-mismatch"
expect "the refused run took 4 model calls: two looks, the answer, the repair" "$(cat "$work"/results/*.jsonl | jq -r 'select(.case == "unrecorded-fee") | .record.modelCalls')" "4"

say "The call cap holds"
stop_servers; start gateway; start model script.json
runner -- --runs 2 --max-calls 10
expect "exit 1: the runs were cut short" "$code" "1"
expect "exactly 10 model requests" "$(lines "$work/model.log")" "10"
expect "it says it stopped at the cap" "$(grep -c 'STOPPED, the call cap of 10 was reached' <<<"$err")" "1"

say "Three failed calls in a row stop it"
stop_servers; start gateway; start model script.json "SOME-OTHER-KEY"
runner -- --runs 2 --max-calls 100
expect "exit 1" "$code" "1"
expect "only 3 model requests" "$(lines "$work/model.log")" "3"
expect "it says why" "$(grep -c 'STOPPED, 3 failed calls in a row' <<<"$err")" "1"
expect "the runs it did make are shown as FAILED" "$(grep -c ' FAILED ' <<<"$out")" "3"
expect "no key in any output" "$(cat "$work/out" "$work/err" "$work"/results/* | grep -c -e "$MODEL_KEY" -e "$AGENT_KEY" || true)" "0"

say "It refuses to start, and sends nothing"
stop_servers; start gateway; start model script.json
runner NEBIUS_API_KEY= -- 
expect "no model key: exit 2" "$code" "2"
runner NKAP_AGENT_KEY= -- 
expect "no agent key: exit 2" "$code" "2"
for bad in 0 6 x; do runner -- --runs "$bad"; expect "runs [$bad]: exit 2" "$code" "2"; done
for bad in 0 121 x; do runner -- --max-calls "$bad"; expect "call cap [$bad]: exit 2" "$code" "2"; done
runner NEBIUS_BASE_URL=http://example.invalid/v1/ --
expect "a clear-text remote endpoint: exit 2" "$code" "2"
expect "none of these sent a model request" "$(lines "$work/model.log")" "0"
expect "none of these read the gateway" "$(lines "$work/gateway.log")" "0"

say "Result"
if [ "$failures" -ne 0 ]; then
  echo "verify-run: $failures expectation(s) FAILED" >&2
  exit 1
fi
echo "verify-run: all expectations passed"
