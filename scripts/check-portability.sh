#!/usr/bin/env bash
# Checks the scripts for what broke on Debian 12 (bash 5.2, GNU tools, jq 1.6), with no Docker and no network:
#   - no jq variable is named after a jq keyword (jq 1.6 refuses `$label`; 1.7 accepts it);
#   - no file mode is read with the BSD form of stat first (on GNU stat, `stat -f` succeeds and prints something else);
#   - the seeded payment's filter compiles under the installed jq and gives the expected body on a sample.
# Usage: scripts/check-portability.sh [DIRECTORY]      (default: the directory of this script)
set -uo pipefail

dir="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)}"
command -v jq >/dev/null || { echo "this check needs jq" >&2; exit 1; }
failures=0
fail() { printf '  FAIL  %s\n' "$*" >&2; failures=$((failures + 1)); }

echo "jq: $(jq --version)"
keywords='__loc__|and|as|break|catch|def|elif|else|end|foreach|if|import|include|label|module|or|reduce|then|try'

# A jq variable is defined by --arg-style options, by `as $name` (also in a destructuring pattern) and as a function parameter.
defs="$(grep -n -o -E "(--(arg|argjson|slurpfile|rawfile) +($keywords)\b|\bas +[\[{(,\$ A-Za-z_]*\\\$($keywords)\b|[(;] *\\\$($keywords)\b)" \
  "$dir"/*.sh "$dir"/*.jq 2>/dev/null)"
if [ -n "$defs" ]; then
  while IFS= read -r line; do fail "a jq variable is named after a jq keyword (jq 1.6 refuses it): $line"; done <<<"$defs"
else
  echo "  ok    no jq variable is named after a jq keyword"
fi

stats="$(grep -n -E "stat -f '%[A-Za-z]+'[^|]*\|\| *stat -c" "$dir"/*.sh 2>/dev/null)"
if [ -n "$stats" ]; then
  while IFS= read -r line; do fail "stat -f is tried before stat -c: $line"; done <<<"$stats"
else
  echo "  ok    stat -c (GNU) is tried before stat -f (BSD) wherever both appear"
fi

if [ -f "$dir/seed-payment.jq" ]; then
  got="$(jq -nc --argjson amount 5000 --arg currency XAF --arg msisdn 237670000001 --arg note 'a note' -f "$dir/seed-payment.jq" 2>&1)"
  want='{"operation":"COLLECT","amount":5000,"currency":"XAF","country":"cm","counterpartyMsisdn":"237670000001","payerMessage":"a note","payeeNote":"a note"}'
  if [ "$got" = "$want" ]; then
    echo "  ok    the seeded payment's filter gives the expected body under $(jq --version)"
  else
    fail "the seeded payment's filter gave [$got], expected [$want]"
  fi
else
  fail "seed-payment.jq is missing from $dir"
fi

[ "$failures" = 0 ] || { echo "$failures check(s) failed" >&2; exit 1; }
