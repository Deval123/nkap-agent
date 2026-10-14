#!/usr/bin/env bash
# Starts the HTTP service against a throwaway seeded stack, in scripted mode (no model, no Nebius key), and checks the
# routes, the headers and the limits, one propose-and-approve round trip, one propose-and-self-approve round trip
# (refused by Nkap, the ledger read before and after) followed by an approve on the same run, which must succeed, and that
# approving twice for the same payment leaves exactly one adjustment entry (the service does not send the second).
# Any failed expectation makes the exit code non-zero. The service and the stack are removed at the end.
#
# The stack is started with compose.dev.yaml so that the service, which runs on this machine, can reach the gateway; that
# publishes it on 127.0.0.1 only, on GATEWAY_PORT (default 28080). The service listens on 127.0.0.1:WEB_PORT (28088).
#
# Usage: scripts/verify-web.sh
set -euo pipefail

export SEED_PROJECT="${SEED_PROJECT:-nkap-agent-web}"
export GATEWAY_PORT="${GATEWAY_PORT:-28080}" SIMULATOR_PORT="${SIMULATOR_PORT:-28081}"
WEB_PORT="${WEB_PORT:-28088}"
export SEED_COMPOSE_EXTRA="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/compose.dev.yaml"
source "$(dirname "${BASH_SOURCE[0]}")/stack-lib.sh"

out="$(mktemp -d)"; chmod 700 "$out"
export SEED_OUT="$out"
service_pid=""
teardown() {
  [ -z "$service_pid" ] || kill "$service_pid" 2>/dev/null || true
  dc down -v --remove-orphans >/dev/null 2>&1 || true
  rm -rf "$out"
}
trap teardown EXIT

say "Seeding a throwaway stack (project $SEED_PROJECT, gateway on 127.0.0.1:$GATEWAY_PORT)"
bash "$root/scripts/seed.sh" >"$out/seed.log" 2>&1 || { tail -n 25 "$out/seed.log" >&2; die "scripts/seed.sh failed"; }
tail -n 2 "$out/seed.log"
mkdir -m 700 "$out/keys"
for name in AGENT APPROVER; do
  sed -n "s/^NKAP_${name}_KEY=//p" "$out/seed-keys.env" >"$out/keys/$name"
  chmod 600 "$out/keys/$name"
done
head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n' >"$out/keys/SALT"; chmod 600 "$out/keys/SALT"
agent_key="$(cat "$out/keys/AGENT")"; approver_key="$(cat "$out/keys/APPROVER")"; salt="$(cat "$out/keys/SALT")"
[ -n "$agent_key" ] && [ -n "$approver_key" ] || die "the seed wrote no keys"
(cd "$root" && mvn -B -q compile && mvn -B -q dependency:build-classpath -Dmdep.outputFile="$out/classpath") >&2

say "Starting the service on 127.0.0.1:$WEB_PORT, scripted mode only"
env -i PATH="$PATH" HOME="$HOME" \
  NKAP_AGENT_PORT="$WEB_PORT" NKAP_AGENT_MANIFEST="$out/seed-manifest.json" NKAP_GATEWAY_URL="http://127.0.0.1:$GATEWAY_PORT" \
  NKAP_AGENT_KEY_FILE="$out/keys/AGENT" NKAP_APPROVER_KEY_FILE="$out/keys/APPROVER" NKAP_AGENT_SALT_FILE="$out/keys/SALT" \
  NKAP_AGENT_STATE_DIR="$out/state" NKAP_AGENT_TRACES_DIR="$root/demo/traces" NKAP_AGENT_SCRIPTED_COOLDOWN_SECONDS=0 \
  java -cp "$root/target/classes:$(cat "$out/classpath")" dev.nkap.agent.web.AgentService >"$out/service.out" 2>"$out/service.err" &
service_pid=$!
for _ in $(seq 1 60); do curl -s -o /dev/null "http://127.0.0.1:$WEB_PORT/api/status" && break; sleep 0.5; done
base="http://127.0.0.1:$WEB_PORT"

# call METHOD PATH [BODY [CONTENT-TYPE]] -> sets $status, $body, $headers
call() {
  local method="$1" path="$2" data="${3-}" type="${4-application/json}"
  local args=(-s -D "$out/headers" -o "$out/body" -w '%{http_code}' -X "$method")
  [ -n "$data" ] && args+=(-H "Content-Type: $type" --data-raw "$data")
  status="$(curl "${args[@]}" "$base$path")"
  body="$(cat "$out/body")"; headers="$(tr -d '\r' <"$out/headers")"
}
header() { grep -i "^$1:" <<<"$headers" | head -n 1 | sed 's/^[^:]*: *//'; }
entries() { psql "select count(*) from ledger_entry"; }
postings() { psql "select count(*) from posting"; }
decisions() { psql "select count(*) from adjustment_decision"; }

say "The page, the status and the headers"
call GET /
expect "GET / is 200" "$status" "200"
expect "it is HTML" "$(header content-type | cut -d';' -f1)" "text/html"
expect "the content security policy is the one that allows only this service" "$(header content-security-policy)" \
  "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'"
expect "nosniff" "$(header x-content-type-options)" "nosniff"
expect "no referrer" "$(header referrer-policy)" "no-referrer"
expect "the page is no-store, so an edge cache never keeps it" "$(header cache-control)" "no-store"
call GET /app.js; expect "GET /app.js is 200, and no-store" "$status $(header cache-control)" "200 no-store"
call GET /app.css; expect "GET /app.css is 200, and no-store" "$status $(header cache-control)" "200 no-store"
call GET /api/status
expect "the status is 200, and no-store" "$status $(header cache-control)" "200 no-store"
expect "live is off: there is no model key" "$(jq -r '.liveEnabled' <<<"$body")" "false"
expect "the status holds two fields and nothing else" "$(jq -r 'keys | join(",")' <<<"$body")" "liveEnabled,liveRunsLeftToday"

say "Recordings"
call GET /api/recordings
expect "seven published traces" "$(jq -r 'length' <<<"$body")" "7"
call GET /api/recordings/duplicate-posting-2
expect "a recording is replayed, marked as a recording" "$(jq -r '[.mode, .steps[0].recording, .steps[0].mode] | join(" ")' <<<"$body")" "recording true recording"
expect "the rejected one ends REJECTED" "$(jq -r '.steps[-1].data.kind' <<<"$body")" "REJECTED"
call GET "/api/recordings/..%2f..%2fpom.xml"; expect "a traversal is a 404" "$status" "404"

say "Wrong methods, unknown routes, and the limits"
call DELETE /api/runs; expect "DELETE /api/runs is 405" "$status" "405"
expect "with Allow: POST" "$(header allow)" "POST"
call POST /api/status "{}"; expect "POST /api/status is 405" "$status" "405"
call GET /api/runs/x; expect "an unknown route is 404" "$status" "404"
call GET /actuator/health; expect "an actuator route is 404" "$status" "404"
call POST /api/runs '{"case":"unrecorded-fee","mode":"scripted"}' "text/plain"; expect "a bad content type is 415" "$status" "415"
call POST /api/runs "{\"case\":\"unrecorded-fee\",\"mode\":\"scripted\",\"x\":\"$(head -c 1500 /dev/zero | tr '\0' a)\"}"
expect "a body over 1 KB is 413" "$status" "413"
status="$(curl -s -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' -H 'Origin: http://evil.example' \
  --data-raw '{"case":"unrecorded-fee","mode":"scripted"}' "$base/api/runs")"
expect "a foreign Origin is 403" "$status" "403"
call POST /api/runs '{"case":"nope","mode":"scripted"}'; expect "an unknown case is 400" "$status" "400"
call POST /api/runs '{"case":"unrecorded-fee","mode":"live"}'
expect "live is refused when it is off" "$status $(jq -r .error <<<"$body")" "503 live-off"

say "Propose and approve: unrecorded-fee"
e0="$(entries)"
call POST /api/runs '{"case":"unrecorded-fee","mode":"scripted"}'
expect "the run is 200" "$status" "200"
expect "the loop proposes" "$(jq -r '.outcome.kind' <<<"$body")" "PROPOSED"
expect "Nkap recorded the proposal" "$(jq -r '.proposal.status' <<<"$body")" "created"
expect "every step says scripted, and not a recording" "$(jq -r '[.steps[] | .mode + ":" + (.recording|tostring)] | unique | join(",")' <<<"$body")" "scripted:false"
expect "no ledger entry before the approval" "$(entries)" "$e0"
run1="$(jq -r .runId <<<"$body")"
expect "the run id is 128 bits in hex" "$(grep -c -E '^[0-9a-f]{32}$' <<<"$run1")" "1"
call POST "/api/runs/$run1/approve" "{}"
expect "the approver's key approves" "$status $(jq -r '.decision + " " + .result' <<<"$body")" "200 approve decided"
adj1="$(jq -r '.ledgerEntryId | sub("adjustment:"; "")' <<<"$body")"
expect "the ledger entry exists, written by Nkap" "$(psql "select count(*) from ledger_entry where id = 'adjustment:$adj1'")" "1"
expect "its postings are fees +75 and float -75" "$(psql "select string_agg(account || '=' || amount_minor, ',' order by account) from posting where entry_id = 'adjustment:$adj1'")" \
  "fees:mtn-cm:XAF=75,provider:mtn-cm:float:XAF=-75"
call POST "/api/runs/$run1/approve" "{}"; expect "a second decision on the run is 409" "$status" "409"
call POST "/api/runs/$(printf '0%.0s' $(seq 1 32))/approve" "{}"; expect "a guessed run id is 404" "$status" "404"

say "Propose and self-approve: self-approval (a refusal leaves the run open)"
e0="$(entries)"; p0="$(postings)"; d0="$(decisions)"
call POST /api/runs '{"case":"self-approval","mode":"scripted"}'
run2="$(jq -r .runId <<<"$body")"
expect "approval is available for this payment" "$(jq -r '.approval.available' <<<"$body")" "true"
call POST "/api/runs/$run2/self-approve" "{}"
expect "the agent's own key is refused by Nkap" "$(jq -r '.result + " " + .code' <<<"$body")" "refused adjustment-self-approval"
expect "with a fixed text" "$(jq -r '.text' <<<"$body" | grep -c 'different key')" "1"
expect "and two attempts are left" "$(jq -r '.attemptsLeft' <<<"$body")" "2"
expect "the ledger is unchanged: entries" "$(entries)" "$e0"
expect "the ledger is unchanged: postings" "$(postings)" "$p0"
expect "no decision was recorded" "$(decisions)" "$d0"
call POST "/api/runs/$run2/approve" "{}"
expect "the later approve on the same run succeeds" "$status $(jq -r .result <<<"$body")" "200 decided"
expect "and Nkap wrote one entry" "$(( $(entries) - e0 ))" "1"
call POST "/api/runs/$run2/self-approve" "{}"; expect "the run is now closed: 409" "$status" "409"

say "Approving twice for the same payment leaves one adjustment entry"
ref="$(jq -r '.cases[] | select(.id == "unrecorded-fee") | .paymentReference' "$out/seed-manifest.json")"
call POST /api/runs '{"case":"unrecorded-fee","mode":"scripted"}'
expect "a second proposal for the same payment is still created" "$(jq -r '.proposal.status' <<<"$body")" "created"
expect "the run says, before any click, that approval is not available" "$(jq -r '.approval.available' <<<"$body")" "false"
expect "with the fixed text" "$(jq -r '.approval.text' <<<"$body")" \
  "An earlier visitor already approved an adjustment for this payment in this demo ledger. Nkap would accept a second one; this service does not send it."
run3="$(jq -r .runId <<<"$body")"
call POST "/api/runs/$run3/approve" "{}"
expect "approve is refused by the service: 409 payment-already-approved" "$status $(jq -r .error <<<"$body")" "409 payment-already-approved"
expect "with the same fixed text" "$(jq -r .text <<<"$body")" \
  "An earlier visitor already approved an adjustment for this payment in this demo ledger. Nkap would accept a second one; this service does not send it."
expect "the payment has exactly one adjustment entry" "$(psql "select count(*) from ledger_entry where reference = '$ref' and id like 'adjustment:%'")" "1"
call POST "/api/runs/$run3/self-approve" "{}"
expect "self-approve and Nkap's refusal stay available" "$(jq -r '.result + " " + .code' <<<"$body")" "refused adjustment-self-approval"
call POST "/api/runs/$run3/approve" "{}"
expect "the refusal did not close the run, and still holds" "$status" "409"
expect "still one adjustment entry for the payment" "$(psql "select count(*) from ledger_entry where reference = '$ref' and id like 'adjustment:%'")" "1"
expect "the service's record names no secret: payment references and adjustment ids only" \
  "$(grep -c -v -E '^\{"payments":\{("[0-9a-f-]{36}":"[0-9a-f-]{36}",?)+\}\}$' "$out/state/approved-payments.json" || true)" "0"

say "Concurrency and budgets"
call POST /api/runs '{"case":"injection","mode":"scripted"}'; expect "a case with no proposal has no run id" "$(jq -r 'has("runId")' <<<"$body")" "false"

say "No key anywhere"
leftovers="$(cat "$out/service.out" "$out/service.err" "$out/body" "$out/headers")"
expect "no key and no salt in the service's output or the last response" "$(grep -c -e "$agent_key" -e "$approver_key" -e "$salt" <<<"$leftovers" || true)" "0"
expect "no state file holds the salt" "$(cat "$out"/state/* | grep -c "$salt" || true)" "0"

say "Result"
if [ "$failures" -ne 0 ]; then
  echo "verify-web: $failures expectation(s) FAILED" >&2
  exit 1
fi
echo "verify-web: all expectations passed"
