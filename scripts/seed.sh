#!/usr/bin/env bash
# Starts the demo stack from a fresh database and puts the six cases into it:
#   1. mints two keys for merchant acme: `agent` (admin) and `approver` (not admin);
#   2. posts the generated simulator document;
#   3. creates the six cases' payments, and waits until each is where it must be;
#   4. imports each case's statement;
#   5. writes seed-manifest.json: for each case the payment reference and the statement import id.
#
# The keys go to seed-keys.env, mode 600, and nowhere else: not to the output, not to the manifest.
# Both files are written to $SEED_OUT (default: the repository root), where git ignores them.
#
# Usage: scripts/seed.sh        (SEED_PROJECT and SEED_OUT override the project name and the directory)
# Reset: docker compose -p nkap-agent-demo down -v
set -euo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/stack-lib.sh"

OUT="${SEED_OUT:-$root}"
WAIT_SECONDS="${SEED_WAIT_SECONDS:-60}"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

say "Generating the catalogue's files"
mkdir "$work/files"
generate "$work/files"
cases="$work/files/cases.json"
echo "cases: $(jq -r '[.cases[].id] | join(", ")' "$cases")"

say "Starting the stack (built from the pinned Nkap commit; the first build takes several minutes)"
dc up -d --build --wait --wait-timeout 900 client >"$work/up.log" 2>&1 \
  || { tail -n 20 "$work/up.log" >&2; die "the stack did not come up"; }
dc ps --format '{{.Service}}: {{.Status}}'
echo "payments already in the database: $(psql 'select count(*) from payment')"
[ "$(psql 'select count(*) from payment')" = 0 ] \
  || die "the database is not fresh; run: docker compose -p $PROJECT down -v"

say "Minting two keys for merchant acme (not shown)"
agent_key="$(mint agent admin)"
approver_key="$(mint approver)"
[ -n "$agent_key" ] && [ -n "$approver_key" ] || die "could not mint the keys"
(
  umask 077
  { printf 'NKAP_AGENT_KEY=%s\n' "$agent_key"; printf 'NKAP_APPROVER_KEY=%s\n' "$approver_key"; } >"$OUT/seed-keys.env"
)
chmod 600 "$OUT/seed-keys.env"
echo "written: seed-keys.env (mode $(stat -f '%Lp' "$OUT/seed-keys.env" 2>/dev/null || stat -c '%a' "$OUT/seed-keys.env"))"

say "Posting simulator.json to the simulator"
api POST "$SIMULATOR/_nkap/scenarios" "" "$(cat "$work/files/simulator.json")"
[ "$status" = 204 ] || die "the simulator answered $status to simulator.json: $body"
echo "simulator accepted simulator.json (204)"
api GET "$GATEWAY/balance?operation=COLLECT&currency=XAF" "$agent_key"
[ "$status" = 200 ] || die "GET /balance answered $status: $body"
echo "GET /balance for the admin key: 200"

say "Creating the payments"
jq -r '.cases[] | .id as $c | .payments[] | [$c, .label, .amountMinor, .currency, .msisdn, .ending] | @tsv' "$cases" \
  >"$work/payments.tsv"
: >"$work/refs.tsv"
while IFS=$'\t' read -r case_id label amount currency msisdn ending; do
  payload="$(jq -nc --argjson amount "$amount" --arg currency "$currency" --arg msisdn "$msisdn" --arg label "$label" \
    '{operation:"COLLECT", amount:$amount, currency:$currency, country:"cm", counterpartyMsisdn:$msisdn,
      payerMessage:$label, payeeNote:$label}')"
  api POST "$GATEWAY/payments" "$agent_key" "$payload" "seed-$case_id-$label"
  case "$status" in 201|202) ;; *) die "POST /payments for $case_id answered $status: $body" ;; esac
  ref="$(jq -r .reference <<<"$body")"
  printf '%s\t%s\t%s\t%s\n' "$case_id" "$label" "$ref" "$ending" >>"$work/refs.tsv"
  echo "  $case_id: HTTP $status, state $(jq -r .state <<<"$body"), expected ending $ending"
done <"$work/payments.tsv"

say "Waiting for each payment to be where it must be (at most ${WAIT_SECONDS}s each)"
while IFS=$'\t' read -r case_id label ref ending; do
  deadline=$((SECONDS + WAIT_SECONDS))
  while :; do
    api GET "$GATEWAY/payments/$ref" "$agent_key"
    [ "$status" = 200 ] || die "GET /payments/$ref answered $status"
    state="$(jq -r .state <<<"$body")"
    escalated="$(jq -r .escalatedAt <<<"$body")"
    case "$ending" in
      ESCALATED) [ -n "$escalated" ] && break ;;
      SETTLED) [ "$state" = SUCCEEDED ] && break ;;
      FAILED) [ "$state" = FAILED ] && break ;;
    esac
    [ "$SECONDS" -lt "$deadline" ] || die "$case_id did not reach $ending in ${WAIT_SECONDS}s (state $state)"
    sleep 0.5
  done
  echo "  $case_id: $ending (state $state)"
done <"$work/refs.tsv"

say "Importing the statements"
: >"$work/imports.tsv"
while IFS=$'\t' read -r case_id statement lines; do
  set +e
  dc run --rm -T -v "$work/files/$statement:/statement.csv:ro" gateway \
    --nkap.statement.import=/statement.csv --nkap.statement.provider=mtn-cm --nkap.statement.source="$statement" \
    2>/dev/null </dev/null >"$work/import.raw"
  code="${PIPESTATUS[0]}"
  set -e
  import_id="$(sed -n 's/^Statement import \([0-9a-f-]\{36\}\) .*/\1/p' "$work/import.raw")"
  [ "$(printf '%s\n' "$import_id" | grep -c .)" = 1 ] || die "no single import id in the output for $case_id"
  printf '%s\t%s\n' "$case_id" "$import_id" >>"$work/imports.tsv"
  echo "  $case_id: import $import_id, $lines line(s), exit code $code"
done < <(jq -r '.cases[] | select(.statement) | [.id, .statement, .statementLines] | @tsv' "$cases")

say "Writing seed-manifest.json"
jq -n --rawfile refs "$work/refs.tsv" --rawfile imports "$work/imports.tsv" '
  ($refs | split("\n") | map(select(. != "") | split("\t") | {key: .[0], value: .[2]}) | from_entries) as $r
  | ($imports | split("\n") | map(select(. != "") | split("\t") | {key: .[0], value: .[1]}) | from_entries) as $i
  | {cases: ($r | keys | map({id: ., paymentReference: $r[.], statementImportId: ($i[.] // null)}))}' \
  >"$OUT/seed-manifest.json"
jq -c '.cases[] | {id, statementImportId}' "$OUT/seed-manifest.json"
grep -q . "$OUT/seed-manifest.json"
say "Seeded. Reset with: docker compose -p $PROJECT down -v"
