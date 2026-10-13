#!/usr/bin/env bash
# Runs scripts/model-spike.sh against a local fake of the chat endpoint (scripts/fake-model-server.py) and checks
# what it does: that it refuses to run without a key, with a bad call cap or with an endpoint that is neither
# https nor loopback; that it stops at the cap and after three failures in a row; that the key reaches curl only
# on its standard input; and that no key and no reasoning text is left in anything it writes. Nothing here
# talks to a provider, and the key used is an obviously fake value.
#
# Usage: scripts/verify-spike.sh
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FAKE_KEY="TEST-ONLY-KEY-VALUE-5d2b"
REASONING="FAKE-REASONING-TEXT-MUST-NEVER-BE-KEPT"
failures=0
expect() {
  if [ "$2" = "$3" ]; then printf '  ok    %s\n' "$1"; else printf '  FAIL  %s: got [%s], expected [%s]\n' "$1" "$2" "$3" >&2; failures=$((failures + 1)); fi
}
say() { printf '\n==== %s\n' "$*"; }

work="$(mktemp -d)"; chmod 700 "$work"
server_pid=""
stop_server() { [ -z "$server_pid" ] || { kill "$server_pid" 2>/dev/null || true; wait "$server_pid" 2>/dev/null || true; server_pid=""; }; }
trap 'stop_server; rm -rf "$work"' EXIT

# curl is wrapped so that what it is given as arguments can be read afterwards.
mkdir "$work/shim"
cat >"$work/shim/curl" <<SHIM
#!/usr/bin/env bash
printf '%s\n' "\$*" >>"$work/curl-args.log"
exec "$(command -v curl)" "\$@"
SHIM
chmod +x "$work/shim/curl"

start_server() { # [expected key]
  : >"$work/requests.log"
  python3 "$root/scripts/fake-model-server.py" "$work/requests.log" "$@" >"$work/port" &
  server_pid=$!
  for _ in $(seq 1 50); do [ -s "$work/port" ] && break; sleep 0.1; done
  port="$(head -n1 "$work/port")"
}

# spike [VAR=value ...] -> runs the script, sets $code, $out (stdout), $err (stderr)
spike() {
  rm -rf "$work/results"; : >"$work/curl-args.log"
  set +e
  env PATH="$work/shim:$PATH" NEBIUS_BASE_URL="http://127.0.0.1:${port:-1}/v1/" SPIKE_RESULTS_DIR="$work/results" \
      SPIKE_BIG_PRICE_IN=1.00 SPIKE_BIG_PRICE_OUT=3.00 "$@" "$root/scripts/model-spike.sh" >"$work/out" 2>"$work/err"
  code=$?
  set -e
  out="$(cat "$work/out")"; err="$(cat "$work/err")"
}
requests() { grep -c . "$work/requests.log" || true; }

say "It refuses to start, and sends nothing"
port=""; start_server
spike NEBIUS_API_KEY=
expect "no key: exit 2" "$code" "2"
expect "no key: it says so" "$(grep -c 'NEBIUS_API_KEY is not set' <<<"$err")" "1"
for bad in "" 0 -3 abc 101 1.5; do
  spike NEBIUS_API_KEY="$FAKE_KEY" SPIKE_MAX_CALLS="$bad"
  expect "call cap [$bad]: exit 2" "$code" "2"
done
spike NEBIUS_API_KEY="$FAKE_KEY" NEBIUS_BASE_URL="http://example.invalid/v1/"
expect "a clear-text remote endpoint: exit 2" "$code" "2"
spike NEBIUS_API_KEY="$FAKE_KEY" NEBIUS_BASE_URL="ftp://example.invalid/v1/"
expect "another scheme: exit 2" "$code" "2"
spike NEBIUS_API_KEY='bad"key'
expect "a key that would break the curl config: exit 2" "$code" "2"
expect "none of these sent a request" "$(requests)" "0"

say "A full run against the fake"
spike NEBIUS_API_KEY="$FAKE_KEY" SPIKE_MAX_CALLS=60
expect "exit 0" "$code" "0"
expect "43 requests were sent (20+10+3+1+5+3+1)" "$(requests)" "43"
expect "every request asked for the strict schema" "$(grep -c 'strict=true' "$work/requests.log")" "43"
expect "the bearer was right every time" "$(grep -c 'bearer=ok' "$work/requests.log")" "43"
expect "the spend estimate is printed first" "$(grep -c 'worst-case spend' <<<"$out")" "1"
expect "the spend is printed at the end" "$(grep -c 'Spend, from the usage' <<<"$out")" "1"
expect "m1: 18 of 20 conform (the 7th and 14th are prose)" "$(grep -c '^m1: 18 of 20 conform' <<<"$out")" "1"
expect "m2: 8 of 10 stayed on no_proposal, 2 proposed" "$(grep -c '^m2 of 10 answered: no_proposal 8, proposal 2, tool call 0, not conforming 0' <<<"$out")" "1"
expect "m2: the controls all stayed on no_proposal" "$(grep -c 'control: m2c of 3 answered: no_proposal 3' <<<"$out")" "1"
expect "m3: hypothesis, postings and evidence are checked" "$(grep -c 'm3 (.*hypothesis=right postings=right evidence=2/2' <<<"$out")" "1"
expect "m4: the low bound cut all three off" "$(grep -c 'at max_tokens 500: 3 of 3 cut off' <<<"$out")" "1"
expect "m4: reasoning tokens are reported when the provider gives them" "$(grep -c 'reasoning reported: largest 400' <<<"$out")" "1"
expect "m5 ran, with its own price" "$(grep -c '^m5 (.*Ultra.*hypothesis=right' <<<"$out")" "1"
expect "the summary file exists" "$(ls "$work/results"/*.summary.txt | wc -l | tr -d ' ')" "1"

say "What it leaves behind"
leftovers="$(cat "$work/out" "$work/err" "$work/results"/* "$work/curl-args.log")"
expect "the key is in no output, no result file and no curl argument" "$(grep -c "$FAKE_KEY" <<<"$leftovers" || true)" "0"
expect "the reasoning text is in no output and no result file" "$(grep -c "$REASONING" <<<"$leftovers" || true)" "0"
expect "curl was told to read its config from standard input" "$(grep -c -- '-K -' "$work/curl-args.log" | awk '{print ($1 > 0)}')" "1"
expect "the results file is private" "$(stat -f '%Lp' "$work/results"/*.jsonl 2>/dev/null || stat -c '%a' "$work/results"/*.jsonl)" "600"

say "The cap holds"
: >"$work/requests.log"
spike NEBIUS_API_KEY="$FAKE_KEY" SPIKE_MAX_CALLS=10
expect "exit 0" "$code" "0"
expect "exactly 10 requests were sent" "$(requests)" "10"
expect "it says it stopped at the cap" "$(grep -c 'STOPPED at the call cap (10)' <<<"$err")" "1"

say "Three failures in a row stop it"
stop_server; port=""; start_server "SOME-OTHER-KEY"
spike NEBIUS_API_KEY="$FAKE_KEY"
expect "exit 1" "$code" "1"
expect "only 3 requests were sent" "$(requests)" "3"
expect "it says why" "$(grep -c 'STOPPED after 3 failed requests' <<<"$err")" "1"
expect "the key is in no output" "$(cat "$work/out" "$work/err" "$work/results"/* | grep -c "$FAKE_KEY" || true)" "0"

say "Result"
if [ "$failures" -ne 0 ]; then
  echo "verify-spike: $failures expectation(s) FAILED" >&2
  exit 1
fi
echo "verify-spike: all expectations passed"
