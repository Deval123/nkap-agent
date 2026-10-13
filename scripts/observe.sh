#!/usr/bin/env bash
# Starts Nkap's demo stack from a clean export of a Nkap git ref, with the override file kept in
# this repository, runs the cases in docs/nkap-observations.md and prints what it saw.
#
# Nothing in the Nkap repository is changed: the ref is exported with `git archive` into a
# temporary directory, because the working tree there may be on another branch. The stack uses
# its own compose project and a throwaway database, and is removed with `down -v` at the end.
#
# Keys minted here live in shell variables only. They are never printed; commands are shown
# with <KEY_A> and <KEY_B>.
#
# Usage: scripts/observe.sh      (NKAP_REPO and NKAP_REF override ../nkap and origin/main)
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
nkap_repo="${NKAP_REPO:-$here/../../nkap}"
nkap_ref="${NKAP_REF:-origin/main}"
GATEWAY="http://localhost:18080"
SIMULATOR="http://localhost:18081"

for tool in docker curl jq git python3; do
  command -v "$tool" >/dev/null || { echo "this script needs $tool" >&2; exit 1; }
done
docker info >/dev/null 2>&1 || { echo "docker is not available" >&2; exit 1; }

work="$(mktemp -d)"
mkdir "$work/nkap"
git -C "$nkap_repo" archive "$nkap_ref" | tar -x -C "$work/nkap"
nkap_commit="$(git -C "$nkap_repo" rev-parse --short "$nkap_ref")"

dc() { docker compose -f "$work/nkap/compose.yaml" -f "$here/observe.override.compose.yaml" "$@"; }
psql() { dc exec -T db psql -U nkap -d nkap -tAc "$1"; }

teardown() {
  dc down -v --remove-orphans >/dev/null 2>&1 || true
  rm -rf "$work"
}
trap teardown EXIT

say() { printf '\n==== %s\n' "$*"; }
show() { printf '$ %s\n' "$*"; }
now() { python3 -I -c 'import time; print(time.time())'; }

# req METHOD PATH KEY [JSON [IDEMPOTENCY_KEY]] -> sets $status; the body is in $work/body.
# The key goes through a curl config on a pipe, so it is in no process listing.
req() {
  local method="$1" path="$2" key="$3" body="${4:-}" idem="${5:-}"
  local args=(-sS -o "$work/body" -w '%{http_code}' -X "$method" "$GATEWAY$path")
  [ -n "$body" ] && args+=(-H 'Content-Type: application/json' -d "$body")
  [ -n "$idem" ] && args+=(-H "Idempotency-Key: $idem")
  status="$(curl "${args[@]}" -K <(printf 'header = "Authorization: Bearer %s"\n' "$key"))"
}

payment_body() {
  printf '{"operation":"COLLECT","amount":%s,"currency":"XAF","country":"cm","counterpartyMsisdn":"46733123453","payerMessage":"observe","payeeNote":"observe"}' "$1"
}

set_scenario() {
  curl -sS -o /dev/null -w '  scenario POST -> HTTP %{http_code}\n' -X POST "$SIMULATOR/_nkap/scenarios" \
    -H 'Content-Type: application/json' -d "$1"
}

mint() {
  dc run --rm -T gateway --nkap.apikey.create --nkap.apikey.merchant=acme --nkap.apikey.label="$1" 2>/dev/null \
    | awk '/^  key: /{print $2}'
}

summary='{state, detail, providerReference, providerTransactionId, escalatedAt, unresolvedSince,
          history: [.history[] | {from, to, cause, operatorCode}]}'

# --- environment ---------------------------------------------------------------------------

say "Environment"
echo "date:            $(date -u +%Y-%m-%dT%H:%M:%SZ)"
echo "docker server:   $(docker info --format '{{.ServerVersion}}')"
echo "docker compose:  $(docker compose version --short)"
echo "nkap ref:        $nkap_ref ($nkap_commit)"

say "Starting the stack (built from source; this can take several minutes)"
show "docker compose -f <nkap>/compose.yaml -f scripts/observe.override.compose.yaml up -d --build --wait"
dc up -d --build --wait --wait-timeout 900 >"$work/up.log" 2>&1 \
  || { tail -n 15 "$work/up.log" >&2; dc ps >&2; echo "the stack did not come up" >&2; exit 1; }
dc ps --format '{{.Service}}: {{.Status}}'
echo "gateway 8080 published on:   $(dc port gateway 8080)"
echo "simulator 8081 published on: $(dc port simulator 8081)"
echo "port 9464 published?    $(dc port gateway 9464 2>&1 | head -n1)"

KEY_A="$(mint observe-a)"
KEY_B="$(mint observe-b)"
[ -n "$KEY_A" ] && [ -n "$KEY_B" ] || { echo "could not mint the two keys" >&2; exit 1; }
echo "minted two keys with --nkap.apikey.create (not shown)"

# --- case 1: a payment settled by polling ---------------------------------------------------

say "Case 1: a payment settled by polling"
show "curl -X POST $SIMULATOR/_nkap/scenarios -d '{\"rules\":[{\"scenario\":{\"name\":\"settles\",\"onQuery\":[{\"status\":\"SUCCESSFUL\"}]}}]}'"
set_scenario '{"rules":[{"scenario":{"name":"settles","onQuery":[{"status":"SUCCESSFUL"}]}}]}'
show "curl -X POST $GATEWAY/payments -H 'Authorization: Bearer <KEY_A>' -H 'Idempotency-Key: <unique>' -d '$(payment_body 5000)'"
req POST /payments "$KEY_A" "$(payment_body 5000)" "obs-1-$RANDOM"
echo "  HTTP $status, state in the response: $(jq -r .state "$work/body")"
ref1="$(jq -r .reference "$work/body")"
for _ in $(seq 1 40); do
  req GET "/payments/$ref1" "$KEY_A"
  [ "$(jq -r .state "$work/body")" = SUCCEEDED ] && break
  sleep 0.5
done
show "curl $GATEWAY/payments/<reference> -H 'Authorization: Bearer <KEY_A>'"
echo "  HTTP $status"
jq 'del(.history[].note)' "$work/body"
echo "  field names: $(jq -r 'keys | join(", ")' "$work/body")"
echo "  providerTransactionId blank? $(jq -r '.providerTransactionId == ""' "$work/body")"

# --- case 2: three ways to leave a payment unresolved, and the time to escalation ----------------

unresolved_case() { # label, scenario json
  local label="$1" scenario="$2" t0 t1 ref escalated=""
  say "Case 2$label"
  show "curl -X POST $SIMULATOR/_nkap/scenarios -d '$scenario'"
  set_scenario "$scenario"
  show "curl -X POST $GATEWAY/payments -H 'Authorization: Bearer <KEY_A>' -H 'Idempotency-Key: <unique>' -d '$(payment_body 3000)'"
  t0="$(now)"
  req POST /payments "$KEY_A" "$(payment_body 3000)" "obs-2$label-$RANDOM"
  t1="$(now)"
  echo "  HTTP $status after $(python3 -I -c "print(round($t1 - $t0, 1))")s, state in the response: $(jq -r .state "$work/body")"
  ref="$(jq -r .reference "$work/body")"
  echo "$ref" > "$work/ref2$label"
  for _ in $(seq 1 120); do
    req GET "/payments/$ref" "$KEY_A"
    escalated="$(jq -r .escalatedAt "$work/body")"
    [ -n "$escalated" ] && break
    sleep 0.5
  done
  t1="$(now)"
  if [ -n "$escalated" ]; then
    echo "  escalated $(python3 -I -c "print(round($t1 - $t0, 1))")s after the POST was sent"
  else
    echo "  NOT escalated within 60s"
  fi
  jq "$summary" "$work/body"
}

unresolved_case a '{"rules":[{"scenario":{"name":"no-answer","onSubmit":{"outcome":"NO_RESPONSE"},"onQuery":[{"status":"PENDING","reason":"SERVICE_UNAVAILABLE"}]}}]}'
unresolved_case b '{"rules":[{"scenario":{"name":"server-error","onSubmit":{"outcome":"SERVER_ERROR"},"onQuery":[{"status":"PENDING","reason":"SERVICE_UNAVAILABLE"}]}}]}'
unresolved_case c '{"rules":[{"scenario":{"name":"pending-unrecognised-reason","onQuery":[{"status":"PENDING","reason":"NOT_A_REAL_REASON"}]}}]}'
unresolved_case d '{"rules":[{"scenario":{"name":"pending-bare","onQuery":[{"status":"PENDING"}]}}]}'

# --- case 3: the escalated list, from inside the compose network ------------------------------

say "Case 3: GET /actuator/escalatedPayments"
show "docker compose exec gateway wget -qO- http://localhost:9464/actuator/escalatedPayments"
dc exec -T gateway wget -qO- http://localhost:9464/actuator/escalatedPayments | jq .

# --- case 4: a statement import with a line matching no payment -------------------------------

say "Case 4: statement import, one settled line that matches no payment"
cat > "$work/statement.csv" <<'CSV'
operator_transaction_id,amount_minor,fee_minor,currency,occurred_at,status
OBSERVE-UNMATCHED-1,5000,75,XAF,2026-10-13T10:00:00Z,SETTLED
CSV
show "cat statement.csv"
cat "$work/statement.csv"
echo "ledger entries before: $(psql 'select count(*) from ledger_entry')"
for pass in 1 2; do
  show "docker compose run --rm gateway --nkap.statement.import=/statement.csv --nkap.statement.provider=mtn-cm   (run $pass)"
  set +e
  dc run --rm -T -v "$work/statement.csv:/statement.csv:ro" gateway \
    --nkap.statement.import=/statement.csv --nkap.statement.provider=mtn-cm 2>/dev/null \
    | grep -vE '^[0-9]{4}-[0-9]{2}-[0-9]{2}T|^\s+at |^$' > "$work/import.out"
  code="${PIPESTATUS[0]}"
  set -e
  cat "$work/import.out"
  echo "  exit code: $code"
done
echo "ledger entries after:  $(psql 'select count(*) from ledger_entry')"
echo "entries and postings written by the import:"
psql "select e.id || ' | ' || p.account || ' | ' || p.amount_minor from ledger_entry e join posting p on p.entry_id = e.id where e.id like 'suspense:%' or e.id like 'fee:%' order by e.id, p.seq"

# --- case 5: a callback from the simulator to the gateway --------------------------------------

say "Case 5: a callback sent by the simulator to the gateway, on the compose network"
scenario5='{"callbackUrl":"http://gateway:8080/callbacks/mtn-cm","rules":[{"scenario":{"name":"callback-to-gateway","onQuery":[{"status":"SUCCESSFUL"}],"callbacks":[{"after":"PT0S","status":"SUCCESSFUL"}]}}]}'
show "curl -X POST $SIMULATOR/_nkap/scenarios -d '$scenario5'"
set_scenario "$scenario5"
show "curl -X POST $GATEWAY/payments -H 'Authorization: Bearer <KEY_A>' -H 'Idempotency-Key: <unique>' -d '$(payment_body 4000)'"
req POST /payments "$KEY_A" "$(payment_body 4000)" "obs-5-$RANDOM"
ref5="$(jq -r .reference "$work/body")"
for _ in $(seq 1 40); do
  req GET "/payments/$ref5" "$KEY_A"
  [ "$(jq -r .state "$work/body")" = SUCCEEDED ] && break
  sleep 0.5
done
jq "$summary" "$work/body"
echo "gateway log lines about a callback for this reference:"
dc logs gateway 2>/dev/null | grep -F "$ref5" | grep -i callback | sed -e 's/^[^|]*| *//' | cut -c1-240 || echo "  (none)"
echo "simulator log lines mentioning a callback:"
dc logs simulator 2>/dev/null | grep -i callback | tail -n 5 | sed -e 's/^[^|]*| *//' | cut -c1-240 || echo "  (none)"

# --- cases 6 and 7: the adjustment round trip ------------------------------------------------

ref_esc="$(cat "$work/ref2a")"
say "Case 7: adjustments are on, and a second key exists"
show "curl $GATEWAY/adjustments -H 'Authorization: Bearer <KEY_A>'"
req GET /adjustments "$KEY_A"
echo "  HTTP $status (501 would mean adjustments are off)"
echo "  body: $(cat "$work/body" | cut -c1-200)"

say "Case 6: propose with one key, read it back, approve with the other, then the refusal"
proposal="$(jq -n --arg ref "$ref_esc" '{paymentReference:$ref, hypothesis:"unrecorded_fee",
  postings:[{account:"fees:mtn-cm:XAF",amountMinor:150,currency:"XAF"},
            {account:"provider:mtn-cm:float:XAF",amountMinor:-150,currency:"XAF"}],
  evidence:{"statement-line":"observation: fee 150"}, cites:["statement-line"],
  explanation:"Observation run: a fee the ledger never recorded."}')"
show "curl -X POST $GATEWAY/adjustments -H 'Authorization: Bearer <KEY_A>' -d '<proposal for the escalated payment from case 2a>'"
req POST /adjustments "$KEY_A" "$proposal"
echo "  HTTP $status"; jq . "$work/body"
adj1="$(jq -r .id "$work/body")"
echo "ledger entries with the adjustment prefix before approval: $(psql "select count(*) from ledger_entry where id like 'adjustment:%'")"
show "curl $GATEWAY/adjustments/<id> -H 'Authorization: Bearer <KEY_A>'"
req GET "/adjustments/$adj1" "$KEY_A"
echo "  HTTP $status"; jq . "$work/body"
show "curl -X POST $GATEWAY/adjustments/<id>/approve -H 'Authorization: Bearer <KEY_B>'"
req POST "/adjustments/$adj1/approve" "$KEY_B"
echo "  HTTP $status"; jq . "$work/body"
echo "ledger entry written by the approval:"
psql "select e.id || ' | ' || p.account || ' | ' || p.amount_minor from ledger_entry e join posting p on p.entry_id = e.id where e.id like 'adjustment:%' order by p.seq"

show "curl -X POST $GATEWAY/adjustments -H 'Authorization: Bearer <KEY_A>' -d '<a second proposal>'"
req POST /adjustments "$KEY_A" "$proposal"
echo "  HTTP $status"
adj2="$(jq -r .id "$work/body")"
for decision in approve reject; do
  show "curl -X POST $GATEWAY/adjustments/<second id>/$decision -H 'Authorization: Bearer <KEY_A>'   (the proposing key)"
  req POST "/adjustments/$adj2/$decision" "$KEY_A"
  echo "  HTTP $status"; jq -c '{type, title, status, detail}' "$work/body"
done
echo "payment still listed as escalated after an approved adjustment?"
dc exec -T gateway wget -qO- http://localhost:9464/actuator/escalatedPayments | jq -r --arg r "$ref_esc" '[.payments[] | select(.reference == $r)] | length | "  matching entries: \(.)"'
echo "  state of that payment: $(req GET "/payments/$ref_esc" "$KEY_A"; jq -r .state "$work/body")"

# --- case 8: published images -----------------------------------------------------------------

say "Case 8: are published images available?"
for image in nkap-gateway nkap-simulator; do
  for tag in 2.1.0 latest; do
    show "docker manifest inspect ghcr.io/deval123/${image}:${tag}"
    if docker manifest inspect "ghcr.io/deval123/${image}:${tag}" >/dev/null 2>&1; then
      echo "  manifest found"
    else
      echo "  no manifest (or not reachable)"
    fi
  done
done

say "Done; removing the stack and its volumes"
