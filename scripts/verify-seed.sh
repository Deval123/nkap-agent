#!/usr/bin/env bash
# Proves the seed on a throwaway stack: runs scripts/seed.sh, then asserts where each case ended up,
# runs one adjustment round trip, and checks that the proposing key cannot approve its own proposal.
# Any failed assertion makes the exit code non-zero. The stack and its volumes are removed at the end.
#
# Usage: scripts/verify-seed.sh
set -euo pipefail

export SEED_PROJECT="${SEED_PROJECT:-nkap-agent-verify}"
source "$(dirname "${BASH_SOURCE[0]}")/stack-lib.sh"

out="$(mktemp -d)"
export SEED_OUT="$out"
failures=0
teardown() {
  dc down -v --remove-orphans >/dev/null 2>&1 || true
  rm -rf "$out"
}
trap teardown EXIT

# expect DESCRIPTION ACTUAL EXPECTED
expect() {
  if [ "$2" = "$3" ]; then
    printf '  ok    %s\n' "$1"
  else
    printf '  FAIL  %s: got [%s], expected [%s]\n' "$1" "$2" "$3" >&2
    failures=$((failures + 1))
  fi
}

say "Published ports, from docker compose config"
ports() { docker compose --project-directory "$root" "$@" config --format json \
  | jq -c '[.services | to_entries[] | .key as $s | (.value.ports // [])[] | {service: $s, host_ip, published, target}]'; }
plain="$(ports -f "$root/compose.yaml" --profile tools)"
dev="$(ports -f "$root/compose.yaml" -f "$root/compose.dev.yaml" --profile tools)"
echo "compose.yaml:                  $plain"
echo "compose.yaml + compose.dev.yaml: $dev"
expect "compose.yaml publishes no port" "$plain" "[]"
expect "compose.dev.yaml publishes only on 127.0.0.1" \
  "$(jq -r '[.[] | select(.host_ip != "127.0.0.1")] | length' <<<"$dev")" "0"
expect "compose.dev.yaml publishes the gateway and the simulator" \
  "$(jq -r '[.[].service] | sort | join(",")' <<<"$dev")" "gateway,simulator"

say "Seeding a throwaway stack (project $SEED_PROJECT)"
bash "$root/scripts/seed.sh" >"$out/seed.log" 2>&1 || { tail -n 25 "$out/seed.log" >&2; die "scripts/seed.sh failed"; }
tail -n 4 "$out/seed.log"
set -a; source "$out/seed-keys.env"; set +a
agent_key="$NKAP_AGENT_KEY"; approver_key="$NKAP_APPROVER_KEY"
unset NKAP_AGENT_KEY NKAP_APPROVER_KEY
manifest="$out/seed-manifest.json"

mkdir "$out/files"
generate "$out/files"
cases="$out/files/cases.json"

say "The manifest and the keys file"
expect "the manifest holds six cases" "$(jq '.cases | length' "$manifest")" "6"
expect "the keys file has mode 600" "$(stat -f '%Lp' "$out/seed-keys.env" 2>/dev/null || stat -c '%a' "$out/seed-keys.env")" "600"
expect "the manifest holds no key" "$(grep -c -F -e "$agent_key" -e "$approver_key" "$manifest" || true)" "0"
expect "the seed's output holds no key" "$(grep -c -F -e "$agent_key" -e "$approver_key" "$out/seed.log" || true)" "0"

say "Where each case ended up"
sentence=""
for case_id in $(jq -r '.cases[].id' "$cases"); do
  ref="$(jq -r --arg c "$case_id" '.cases[] | select(.id == $c) | .paymentReference' "$manifest")"
  ending="$(jq -r --arg c "$case_id" '.cases[] | select(.id == $c) | .payments[0].ending' "$cases")"
  api GET "$GATEWAY/payments/$ref" "$agent_key"
  state="$(jq -r .state <<<"$body")"
  case "$ending" in
    ESCALATED)
      expect "$case_id: escalated" "$(jq -r '.escalatedAt != ""' <<<"$body")" "true"
      expect "$case_id: not decided (UNKNOWN)" "$state" "UNKNOWN" ;;
    SETTLED)
      expect "$case_id: settled" "$state" "SUCCEEDED"
      expect "$case_id: not escalated" "$(jq -r '.escalatedAt' <<<"$body")" "" ;;
    FAILED)
      expect "$case_id: failed" "$state" "FAILED"
      msisdn="$(jq -r --arg c "$case_id" '.cases[] | select(.id == $c) | .payments[0].msisdn' "$cases")"
      sentence="$(jq -r --arg m "$msisdn" '.rules[] | select(.match.msisdn == $m) | .scenario.onSubmit.code' "$out/files/simulator.json")"
      expect "$case_id: the sentence is in history[0].operatorCode, whole" "$(jq -r '.history[0].operatorCode' <<<"$body")" "$sentence" ;;
  esac
done

api GET "$MANAGEMENT/actuator/escalatedPayments" ""
want="$(jq '[.cases[].payments[] | select(.ending == "ESCALATED")] | length' "$cases")"
expect "the escalated list holds the escalated payments" "$(jq '.payments | length' <<<"$body")" "$want"
expect "every escalation is window_exhausted" "$(jq -r '[.payments[].reason] | unique | join(",")' <<<"$body")" "window_exhausted"

say "The statement imports"
for case_id in $(jq -r '.cases[].id' "$cases"); do
  lines="$(jq -r --arg c "$case_id" '.cases[] | select(.id == $c) | .statementLines // 0' "$cases")"
  import_id="$(jq -r --arg c "$case_id" '.cases[] | select(.id == $c) | .statementImportId // ""' "$manifest")"
  if [ "$lines" = 0 ]; then
    expect "$case_id: no statement, no import" "$import_id" ""
    continue
  fi
  api GET "$GATEWAY/statements/imports/$import_id" "$agent_key"
  expect "$case_id: the import is readable by the admin key" "$status" "200"
  expect "$case_id: $lines line(s)" "$(jq -r .lineCount <<<"$body")" "$lines"
  expect "$case_id: one SUSPENSE_POSTED per line" \
    "$(jq -r '[.findings[] | select(.kind == "SUSPENSE_POSTED")] | length' <<<"$body")" "$lines"
  expect "$case_id: no other kind of finding" "$(jq -r '.findings | length' <<<"$body")" "$lines"
done

say "The simulator and the balance"
api GET "$SIMULATOR/_nkap/scenarios" ""
expect "the simulator holds the six rules" "$(jq -r '.rules | length' <<<"$body")" "6"
api GET "$GATEWAY/balance?operation=COLLECT&currency=XAF" "$agent_key"
expect "GET /balance answers 200 for the admin key" "$status" "200"
expect "GET /balance reports the declared balance" "$(jq -r .amountMinorUnits <<<"$body")" \
  "$(jq -r '.account.balance.availableBalance' "$out/files/simulator.json")"
api GET "$GATEWAY/balance?operation=COLLECT&currency=XAF" "$approver_key"
expect "GET /balance is refused to the approver key" "$status" "403"

say "The adjustment round trip, on unrecorded-fee"
proposal_for() { # case id -> JSON body for POST /adjustments
  local c="$1" ref
  ref="$(jq -r --arg c "$c" '.cases[] | select(.id == $c) | .paymentReference' "$manifest")"
  jq -nc --arg ref "$ref" --slurpfile cases "$cases" --arg c "$c" '
    ($cases[0].cases[] | select(.id == $c) | .proposal) as $p
    | {paymentReference: $ref, hypothesis: $p.hypothesis,
       postings: ($p.postings | map({account, amountMinor, currency: "XAF"})),
       evidence: {"verify-seed": "an observation made by scripts/verify-seed.sh"},
       cites: ["verify-seed"], explanation: "Checked by the seed verification."}'
}
api POST "$GATEWAY/adjustments" "$agent_key" "$(proposal_for unrecorded-fee)"
expect "the agent key proposes (201)" "$status" "201"
adj="$(jq -r .id <<<"$body")"
expect "the proposal is pending" "$(jq -r .status <<<"$body")" "pending"
expect "no ledger entry before the approval" "$(psql "select count(*) from ledger_entry where id = 'adjustment:$adj'")" "0"
api POST "$GATEWAY/adjustments/$adj/approve" "$approver_key"
expect "the approver key approves (200)" "$status" "200"
expect "the adjustment is approved" "$(jq -r .status <<<"$body")" "approved"
expect "the ledger entry adjustment:<id> exists" "$(psql "select count(*) from ledger_entry where id = 'adjustment:$adj'")" "1"
expect "its postings sum to zero" "$(psql "select coalesce(sum(p.amount_minor), -1) from posting p where p.entry_id = 'adjustment:$adj'")" "0"

say "The self-approval refusal, on self-approval"
api POST "$GATEWAY/adjustments" "$agent_key" "$(proposal_for self-approval)"
expect "the agent key proposes (201)" "$status" "201"
own="$(jq -r .id <<<"$body")"
api POST "$GATEWAY/adjustments/$own/approve" "$agent_key"
expect "the proposing key cannot approve (403)" "$status" "403"
expect "the refusal is adjustment-self-approval" "$(jq -r '.type | split("/") | last' <<<"$body")" "adjustment-self-approval"
expect "no ledger entry was written" "$(psql "select count(*) from ledger_entry where id = 'adjustment:$own'")" "0"
api POST "$GATEWAY/adjustments/$own/reject" "$agent_key"
expect "nor can it reject (403)" "$status" "403"

say "Result"
if [ "$failures" -ne 0 ]; then
  echo "verify-seed: $failures assertion(s) FAILED" >&2
  exit 1
fi
echo "verify-seed: all assertions passed"
