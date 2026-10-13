#!/usr/bin/env bash
# Runs each of the six catalogue cases K times through the agent loop, against a throwaway seeded stack, with the
# real model, and prints a table of what it did against what the catalogue expects. Run by the maintainer, who
# holds the key; the agent that wrote this never had it and never ran it against the provider.
# scripts/verify-run.sh tests the runner against a fake model.
#
#   set -a; source ~/.nkap/nebius.env; set +a; scripts/run-cases.sh
#
# Needs NEBIUS_API_KEY (the model key) and Docker. The gateway key is minted by the seed and read from its
# file; it does not leave this script's process and the runner's. Settings, all with limits that the runner
# enforces and refuses to exceed:
#   RUN_RUNS       runs per case, default 3, at most 5
#   RUN_MAX_CALLS  hard cap on model calls over the whole run, default 60, at most 120
#   NEBIUS_BASE_URL, RUN_PRICE_IN, RUN_PRICE_OUT  as for scripts/model-spike.sh (USD per million tokens)
# It prints a worst-case spend before the first call, stops after three failed calls in a row, never prints a
# key, and keeps one record per run in run-results/ (git ignores it; no key in the records).
#
# The stack is started with compose.dev.yaml so that the runner, which runs on this machine, can reach the
# gateway; that publishes it on 127.0.0.1 only, on GATEWAY_PORT (default 28080). It is removed at the end.
set -euo pipefail

runs="${RUN_RUNS-3}"; cap="${RUN_MAX_CALLS-60}"
[[ -n "${NEBIUS_API_KEY:-}" ]] || { echo "run-cases: NEBIUS_API_KEY is not set. Nothing was sent." >&2; exit 2; }
[[ "$runs" =~ ^[1-5]$ ]] || { echo "run-cases: RUN_RUNS is a whole number from 1 to 5. Nothing was sent." >&2; exit 2; }
[[ "$cap" =~ ^[1-9][0-9]*$ ]] && (( cap <= 120 )) || { echo "run-cases: RUN_MAX_CALLS is a whole number from 1 to 120. Nothing was sent." >&2; exit 2; }

export SEED_PROJECT="${SEED_PROJECT:-nkap-agent-run}"
export GATEWAY_PORT="${GATEWAY_PORT:-28080}" SIMULATOR_PORT="${SIMULATOR_PORT:-28081}"
export SEED_COMPOSE_EXTRA="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/compose.dev.yaml"
source "$(dirname "${BASH_SOURCE[0]}")/stack-lib.sh"

out="$(mktemp -d)"; chmod 700 "$out"
export SEED_OUT="$out"
teardown() {
  dc down -v --remove-orphans >/dev/null 2>&1 || true
  rm -rf "$out"
}
trap teardown EXIT

say "Seeding a throwaway stack (project $SEED_PROJECT, gateway on 127.0.0.1:$GATEWAY_PORT)"
bash "$root/scripts/seed.sh" >"$out/seed.log" 2>&1 || { tail -n 25 "$out/seed.log" >&2; die "scripts/seed.sh failed"; }
tail -n 2 "$out/seed.log"
agent_key="$(sed -n 's/^NKAP_AGENT_KEY=//p' "$out/seed-keys.env")"
[ -n "$agent_key" ] || die "the seed wrote no agent key"

(cd "$root" && mvn -B -q compile && mvn -B -q dependency:build-classpath -Dmdep.outputFile="$out/classpath") >&2

say "Running the cases"
set +e
NKAP_AGENT_KEY="$agent_key" NKAP_GATEWAY_URL="http://127.0.0.1:$GATEWAY_PORT" \
  java -cp "$root/target/classes:$(cat "$out/classpath")" dev.nkap.agent.loop.RunCli run \
  --manifest "$out/seed-manifest.json" --runs "$runs" --max-calls "$cap" --results-dir "$root/run-results"
code=$?
set -e
exit "$code"
