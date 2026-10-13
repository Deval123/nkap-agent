#!/usr/bin/env bash
# Exercises the agent's three tools against a throwaway stack that the seed has filled with the six
# cases, and checks what each returns. Any failed expectation makes the exit code non-zero. The stack and
# its volumes are removed at the end.
#
# The stack is started with compose.dev.yaml so that the tools, which run on this machine, can reach the
# gateway; that publishes it on 127.0.0.1 only, on GATEWAY_PORT (default 28080).
#
# Usage: scripts/verify-tools.sh
set -euo pipefail

export SEED_PROJECT="${SEED_PROJECT:-nkap-agent-tools}"
export GATEWAY_PORT="${GATEWAY_PORT:-28080}" SIMULATOR_PORT="${SIMULATOR_PORT:-28081}"
export SEED_COMPOSE_EXTRA="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/compose.dev.yaml"
source "$(dirname "${BASH_SOURCE[0]}")/stack-lib.sh"

out="$(mktemp -d)"
export SEED_OUT="$out"
teardown() {
  dc down -v --remove-orphans >/dev/null 2>&1 || true
  rm -rf "$out"
}
trap teardown EXIT

say "Seeding a throwaway stack (project $SEED_PROJECT, gateway on 127.0.0.1:$GATEWAY_PORT)"
bash "$root/scripts/seed.sh" >"$out/seed.log" 2>&1 || { tail -n 25 "$out/seed.log" >&2; die "scripts/seed.sh failed"; }
tail -n 2 "$out/seed.log"
set -a; source "$out/seed-keys.env"; set +a
agent_key="$NKAP_AGENT_KEY"; approver_key="$NKAP_APPROVER_KEY"
unset NKAP_AGENT_KEY NKAP_APPROVER_KEY
manifest="$out/seed-manifest.json"
mkdir "$out/files"; generate "$out/files"
cases="$out/files/cases.json"

(cd "$root" && mvn -B -q dependency:build-classpath -Dmdep.outputFile="$out/classpath" >&2)
GATEWAY_URL="http://127.0.0.1:$GATEWAY_PORT"
transcript="$out/transcript"; : >"$transcript"

# tool KEY URL NAME ARGUMENT [--cite ...] -> sets $lines (all output lines), $result (the first line).
# The key goes to the process through its environment, never through its arguments.
tool() {
  local key="$1" url="$2"; shift 2
  lines="$(NKAP_AGENT_KEY="$key" NKAP_GATEWAY_URL="$url" java -cp "$root/target/classes:$(cat "$out/classpath")" \
    dev.nkap.agent.tools.ToolsCli "$@" 2>"$out/tool.err")"
  printf '%s\n' "$lines" >>"$transcript"
  cat "$out/tool.err" >>"$transcript"   # the log line goes to stderr; it is checked for keys too
  result="$(head -n 1 <<<"$lines")"
}
field() { jq -r "$1" <<<"$result"; }
ref_of() { jq -r --arg c "$1" '.cases[] | select(.id == $c) | .paymentReference' "$manifest"; }
import_of() { jq -r --arg c "$1" '.cases[] | select(.id == $c) | .statementImportId' "$manifest"; }

say "get_payment: an escalated payment (unrecorded-fee)"
tool "$agent_key" "$GATEWAY_URL" get_payment "$(ref_of unrecorded-fee)" \
  --cite /state=UNKNOWN --cite /history/0/cause=SUBMIT_RESPONSE --cite /state=FAILED
expect "the call is c1 and succeeds" "$(field '.callId + " " + (.status|tostring)')" "c1 200"
expect "the state is UNKNOWN" "$(field .fields.state)" "UNKNOWN"
expect "escalatedAt is set" "$(field '.fields.escalatedAt != ""')" "true"
expect "the first transition has a cause" "$(field '.fields.history[0].cause')" "SUBMIT_RESPONSE"
expect "the phone number is masked" "$(field .fields.counterpartyMsisdn)" "4477******01"
expect "a true citation is accepted" "$(sed -n 2p <<<"$lines" | jq -r .ok)" "true"
expect "another true citation is accepted" "$(sed -n 3p <<<"$lines" | jq -r .ok)" "true"
expect "a citation with a wrong value is refused" "$(sed -n 4p <<<"$lines" | jq -r .ok)" "false"

say "get_payment: the settled payment (money-without-owner)"
tool "$agent_key" "$GATEWAY_URL" get_payment "$(ref_of money-without-owner)"
expect "it succeeded" "$(field .fields.state)" "SUCCEEDED"
expect "it was not escalated" "$(field .fields.escalatedAt)" ""

say "get_payment: the injection case"
tool "$agent_key" "$GATEWAY_URL" get_payment "$(ref_of injection)"
msisdn="$(jq -r '.cases[] | select(.id == "injection") | .payments[0].msisdn' "$cases")"
sentence="$(jq -r --arg m "$msisdn" '.rules[] | select(.match.msisdn == $m) | .scenario.onSubmit.code' "$out/files/simulator.json")"
expect "the payment failed at submit" "$(field .fields.state)" "FAILED"
expect "the operator's code is returned whole, being under 500 characters" "$(field '.fields.history[0].operatorCode')" "$sentence"
expect "the phone number is masked" "$(field .fields.counterpartyMsisdn)" "4477******04"

say "get_statement_findings"
tool "$agent_key" "$GATEWAY_URL" get_statement_findings "$(import_of duplicate-posting)" --cite /lineCount=2
expect "the report is the one imported" "$(field .fields.importId)" "$(import_of duplicate-posting)"
expect "it holds two findings" "$(field '.fields.findings | length')" "2"
expect "each finding is a suspense posting" "$(field '[.fields.findings[].kind] | unique | join(",")')" "SUSPENSE_POSTED"
expect "the line count can be cited" "$(sed -n 2p <<<"$lines" | jq -r .ok)" "true"
expect "no finding names a payment" "$(field '[.fields.findings[].paymentReference] | unique | join(",")')" ""

say "errors are results"
tool "$approver_key" "$GATEWAY_URL" get_statement_findings "$(import_of duplicate-posting)"
expect "a non-admin key gets a 403" "$(field .status)" "403"
expect "with the problem type" "$(field '.fields.type | split("/") | last')" "admin-key-required"
other_key="$(mint tools-other admin globex)"
tool "$other_key" "$GATEWAY_URL" get_payment "$(ref_of unrecorded-fee)"
expect "another merchant's admin key gets a 404 for this payment" "$(field .status)" "404"
expect "with the problem type" "$(field '.fields.type | split("/") | last')" "payment-not-found"
tool "$agent_key" "$GATEWAY_URL" get_payment "00000000-0000-0000-0000-000000000000"
expect "an unknown payment is a 404" "$(field .status)" "404"
tool "$agent_key" "$GATEWAY_URL" get_payment "../adjustments"
expect "an argument that is not a UUID is refused locally" "$(field '(.status|tostring) + " " + .fields.error')" "0 invalid-argument"
tool "$agent_key" "http://127.0.0.1:1" get_payment "$(ref_of unrecorded-fee)"
expect "an unreachable gateway is a result" "$(field '(.status|tostring) + " " + .fields.error')" "0 gateway-unreachable"

say "search_docs"
tool "" "http://127.0.0.1:1" search_docs "statement fee suspense" --cite /results/0/heading=nonsense
expect "it answers with no gateway and no key" "$(field .status)" "200"
expect "at most three passages" "$(field '.fields.results | length <= 3 and length >= 1')" "true"
expect "one of them is from mtn.md" "$(field '[.fields.results[].file] | index("mtn.md") != null')" "true"
expect "a citation with the wrong heading is refused" "$(sed -n 2p <<<"$lines" | jq -r .ok)" "false"

say "A code of more than 500 characters, end to end"
long="$(jq -r --arg m "$msisdn" '.rules[] | select(.match.msisdn == $m) | .scenario.onSubmit.code' "$out/files/simulator.json")"
long="$long $long $long $long $long $long $long $long"
api POST "$SIMULATOR/_nkap/scenarios" "" "$(jq -nc --arg code "$long" \
  '{rules:[{match:{msisdn:"447700900099"}, scenario:{name:"long-code", onSubmit:{outcome:"BAD_REQUEST", code:$code}}}]}')"
expect "the simulator accepts the extra rule" "$status" "204"
api POST "$GATEWAY/payments" "$agent_key" \
  '{"operation":"COLLECT","amount":1000,"currency":"XAF","country":"cm","counterpartyMsisdn":"447700900099","payerMessage":"long","payeeNote":"long"}' "tools-long-code"
long_ref="$(jq -r .reference <<<"$body")"
tool "$agent_key" "$GATEWAY_URL" get_payment "$long_ref"
code="$(field '.fields.history[0].operatorCode')"
expect "the payment failed at submit" "$(field .fields.state)" "FAILED"
expect "the code is cut at 500 characters, then the marker" \
  "$(printf '%s' "$code" | sed -E 's/…\[truncated [0-9]+ characters\]$//' | wc -m | tr -d ' ')" "500"
expect "the marker says how much was cut" "$(printf '%s' "$code" | grep -c -E '…\[truncated [0-9]+ characters\]$')" "1"
expect "the stored code is shorter than the original" "$([ ${#code} -lt ${#long} ] && echo yes)" "yes"

say "What the transcript of every call holds"
expect "no agent key" "$(grep -c -F "$agent_key" "$transcript" || true)" "0"
expect "no approver key" "$(grep -c -F "$approver_key" "$transcript" || true)" "0"
expect "no other key" "$(grep -c -F "$other_key" "$transcript" || true)" "0"
expect "no Bearer header" "$(grep -c 'Bearer' "$transcript" || true)" "0"
# The masker leaves UUIDs and ISO timestamps alone on purpose, so they are taken out before looking.
sed -E -e 's/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/UUID/g' \
  -e 's/[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9:.]+(Z|[+-][0-9]{2}:[0-9]{2})?/TIMESTAMP/g' "$transcript" >"$out/transcript.plain"
expect "no run of nine digits outside a UUID or a timestamp" "$(grep -c -E '[0-9]{9,}' "$out/transcript.plain" || true)" "0"
grep -o -E '.{20}[0-9]{9,}.{5}' "$out/transcript.plain" >&2 || true

say "The conditions the agent's admin key relies on"
event="$(psql 'select id from outbox_event limit 1' | head -n 1)"
echo "outbox events in the database: $(psql 'select count(*) from outbox_event')"
if [ -n "$event" ]; then
  api POST "$GATEWAY/webhooks/events/$event/replay" "$agent_key"
  expect "replaying an event is refused because no endpoint is registered" \
    "$status $(jq -r '.type | split("/") | last' <<<"$body")" "404 no-webhook-endpoint"
else
  echo "  note  no outbox event exists, so a replay cannot even find an event; the refusal for a missing endpoint was not exercised"
fi
api POST "$GATEWAY/webhooks/events/00000000-0000-0000-0000-000000000000/replay" "$agent_key"
expect "the agent's admin key does reach the replay route (an unknown event is a 404)" \
  "$status $(jq -r '.type | split("/") | last' <<<"$body")" "404 event-not-found"
api POST "$GATEWAY/webhooks/events/00000000-0000-0000-0000-000000000000/replay" "$approver_key"
expect "the approver's key does not (403)" "$status" "403"
expect "no webhook endpoint is registered" "$(psql 'select count(*) from webhook_endpoint')" "0"

say "Result"
if [ "$failures" -ne 0 ]; then
  echo "verify-tools: $failures expectation(s) FAILED" >&2
  exit 1
fi
echo "verify-tools: all expectations passed"
