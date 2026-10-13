#!/usr/bin/env bash
# Proposes with the agent's key and approves with the approver's, against a throwaway seeded stack, with scripted
# answers and no model (nothing is sent to Nebius, and no model key is read). It checks:
#   (a) unrecorded-fee and duplicate-posting are proposed, accepted by Nkap, approved by the approver's key, and the
#       ledger entry adjustment:<id> exists with the entry the catalogue expects; a second approval is refused as
#       already decided and writes nothing more;
#   (b) self-approval is proposed and the same key's approval is refused (403 adjustment-self-approval); the ledger
#       is read before and after, and does not change;
#   (c) an entry above Nkap's ceiling, sent without our validator, is refused by Nkap and comes back as a fixed
#       text, and no proposal is stored;
#   (d) the three cases that expect no proposal end in no_proposal and send nothing to the adjustment routes;
#   (e) neither key is in anything printed.
# Any failed expectation makes the exit code non-zero. The stack and its volumes are removed at the end.
#
# The stack is started with compose.dev.yaml so that the command, which runs on this machine, can reach the
# gateway; that publishes it on 127.0.0.1 only, on GATEWAY_PORT (default 28080).
#
# Usage: scripts/verify-approval.sh
set -euo pipefail

export SEED_PROJECT="${SEED_PROJECT:-nkap-agent-approval}"
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
approver_key="$(sed -n 's/^NKAP_APPROVER_KEY=//p' "$out/seed-keys.env")"
[ -n "$agent_key" ] && [ -n "$approver_key" ] || die "the seed wrote no keys"
manifest="$out/seed-manifest.json"
(cd "$root" && mvn -B -q compile && mvn -B -q dependency:build-classpath -Dmdep.outputFile="$out/classpath") >&2
CP="$root/target/classes:$(cat "$out/classpath")"
mkdir "$out/files"; generate "$out/files"
cases="$out/files/cases.json"
transcript="$out/transcript"; : >"$transcript"

# approval ARGS... -> sets $lines (stdout), $code. The keys go in the environment, never in an argument.
approval() {
  set +e
  lines="$(NKAP_AGENT_KEY="$agent_key" NKAP_APPROVER_KEY="$approver_key" NKAP_GATEWAY_URL="http://127.0.0.1:$GATEWAY_PORT" \
    java -cp "$CP" dev.nkap.agent.approval.ApprovalCli "$@" 2>"$out/approval.err")"
  code=$?
  set -e
  printf '%s\n' "$lines" >>"$transcript"; cat "$out/approval.err" >>"$transcript"
}
step() { jq -c --arg s "$1" 'select(.step == $s)' <<<"$lines" | head -n 1; }
field() { jq -r "$2" <<<"$(step "$1")"; }
answers_for() { # case -> file
  approval answers --manifest "$manifest" --case "$1"
  printf '%s\n' "$lines" >"$out/$1.answers"
  echo "$out/$1.answers"
}
entries() { psql "select count(*) from ledger_entry"; }
postings() { psql "select count(*) from posting"; }
adjustments() { psql "select count(*) from adjustment_proposal"; }
decisions() { psql "select count(*) from adjustment_decision"; }

for c in unrecorded-fee duplicate-posting; do
  say "$c: proposed with the agent's key, approved with the approver's"
  file="$(answers_for "$c")"
  before="$(entries)"
  approval run --manifest "$manifest" --case "$c" --answers "$file" --approve-twice
  adj="$(field propose .id)"
  expect "the loop ends in a proposal" "$(field loop .outcome)" "PROPOSED"
  expect "Nkap accepts the proposal" "$(field propose .result)" "created"
  expect "the approver's key approves" "$(field approve '.by + " " + .result')" "approver decided"
  expect "the ledger entry is adjustment:<id>" "$(field approve .ledgerEntryId)" "adjustment:$adj"
  expect "the adjustment reads back as approved, with that entry" "$(field read '.status + " " + .ledgerEntryId')" "approved adjustment:$adj"
  expect "a second approval is refused as already decided" "$(field approve-again '.result + " " + .code')" "refused adjustment-already-decided"
  expect "the run's exit is 1, for that refusal" "$code" "1"
  expect "exactly one ledger entry was added" "$(( $(entries) - before ))" "1"
  expect "it has two postings that sum to zero" "$(psql "select count(*) || ' ' || sum(amount_minor) from posting where entry_id = 'adjustment:$adj'")" "2 0"
  expected="$(jq -r --arg c "$c" '.cases[] | select(.id == $c) | .proposal.postings | map("\(.account)=\(.amountMinor)") | sort | join(",")' "$cases")"
  expect "the postings are the catalogue's expected entry" "$(psql "select string_agg(account || '=' || amount_minor, ',' order by account) from posting where entry_id = 'adjustment:$adj'")" "$expected"
done

say "self-approval: proposed, and the same key's approval is refused"
file="$(answers_for self-approval)"
e0="$(entries)"; p0="$(postings)"; a0="$(adjustments)"; d0="$(decisions)"
approval run --manifest "$manifest" --case self-approval --answers "$file" --self-approve
adj="$(field propose .id)"
expect "Nkap accepts the proposal" "$(field propose .result)" "created"
expect "the agent's own key is refused, 403 adjustment-self-approval" "$(field approve '.by + " " + .result + " " + .code')" "agent refused adjustment-self-approval"
expect "with a fixed text" "$(field approve .text | grep -c 'different key')" "1"
expect "the adjustment is still pending" "$(field read .status)" "pending"
expect "the ledger is unchanged: entries" "$(entries)" "$e0"
expect "the ledger is unchanged: postings" "$(postings)" "$p0"
expect "one proposal was stored and no decision was recorded" "$(( $(adjustments) - a0 )) $(( $(decisions) - d0 ))" "1 0"
expect "no ledger entry exists for it" "$(psql "select count(*) from ledger_entry where id = 'adjustment:$adj'")" "0"
approval run --manifest "$manifest" --case self-approval --answers "$file"
adj2="$(field propose .id)"
expect "the same case, approved by the approver's key, is accepted" "$(field approve .result)" "decided"
expect "and its entry exists" "$(psql "select count(*) from ledger_entry where id = 'adjustment:$adj2'")" "1"

say "above Nkap's ceiling: refused by Nkap, as a fixed text"
a0="$(adjustments)"; e0="$(entries)"
approval try-unchecked --manifest "$manifest" --case unrecorded-fee --amount 100001
expect "Nkap refuses it" "$(field propose '.result + " " + .code')" "refused adjustment-over-ceiling"
expect "with a fixed text, not the server's body" "$(field propose .text)" "The adjustment moves more than this deployment allows."
expect "the exit is 1" "$code" "1"
expect "no proposal was stored" "$(adjustments)" "$a0"
expect "the ledger is unchanged" "$(entries)" "$e0"

say "the cases that expect no proposal"
for c in money-without-owner injection i-do-not-know; do
  file="$(answers_for "$c")"
  a0="$(adjustments)"
  approval run --manifest "$manifest" --case "$c" --answers "$file"
  expect "$c: no_proposal, exit 0" "$(field loop .outcome) $code" "NO_PROPOSAL 0"
  expect "$c: nothing was proposed" "$(adjustments)" "$a0"
done

say "no key in anything printed"
expect "neither key is in the transcript" "$(grep -c -e "$agent_key" -e "$approver_key" "$transcript" || true)" "0"

say "Result"
if [ "$failures" -ne 0 ]; then
  echo "verify-approval: $failures expectation(s) FAILED" >&2
  exit 1
fi
echo "verify-approval: all expectations passed"
