# Helpers shared by seed.sh and verify-seed.sh. Sourced, not run.
#
# The gateway and the simulator publish no port, so every call goes through the `client` container
# on the compose network. A key is handed to curl on its standard input, as a curl config line, so
# it is in no argument list and in no process listing. Keys live in shell variables only.

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROJECT="${SEED_PROJECT:-nkap-agent-demo}"
GATEWAY="http://gateway:8080"
SIMULATOR="http://simulator:8081"
MANAGEMENT="http://gateway:9464"
MSISDN_RULE_SCENARIO=""

for tool in docker jq mvn java sed; do
  command -v "$tool" >/dev/null || { echo "this script needs $tool" >&2; exit 1; }
done
docker info >/dev/null 2>&1 || { echo "docker is not available" >&2; exit 1; }

dc() {
  docker compose -p "$PROJECT" --project-directory "$root" --profile tools \
    -f "$root/compose.yaml" -f "$root/compose.seed.yaml" "$@"
}
psql() { dc exec -T db psql -U nkap -d nkap -tAc "$1"; }

say() { printf '\n==== %s\n' "$*"; }
die() { echo "FAILED: $*" >&2; exit 1; }

# api METHOD URL KEY [JSON [IDEMPOTENCY_KEY]] -> sets $status and $body.
# KEY may be empty for a call that needs none (the simulator).
api() {
  local method="$1" url="$2" key="$3" json="${4:-}" idem="${5:-}" out
  local args=(curl -sS -w '\n%{http_code}' -X "$method")
  [ -n "$json" ] && args+=(-H 'Content-Type: application/json' --data-raw "$json")
  [ -n "$idem" ] && args+=(-H "Idempotency-Key: $idem")
  if [ -n "$key" ]; then
    out="$(printf 'header = "Authorization: Bearer %s"\n' "$key" | dc exec -T client "${args[@]}" -K - "$url")"
  else
    out="$(dc exec -T client "${args[@]}" "$url" </dev/null)"
  fi
  status="${out##*$'\n'}"
  body="${out%$'\n'*}"
}

# mint LABEL [admin] -> prints the key, once. Merchant acme.
mint() {
  local extra=()
  [ "${2:-}" = admin ] && extra=(--nkap.apikey.admin)
  dc run --rm -T gateway --nkap.apikey.create --nkap.apikey.merchant=acme --nkap.apikey.label="$1" \
    ${extra[@]+"${extra[@]}"} 2>/dev/null | awk '/^  key: /{print $2}'
}

# generate DIR -> writes the catalogue's files into the empty directory DIR.
generate() {
  (cd "$root" && mvn -B -q -DskipTests compile >&2 \
    && java -cp target/classes dev.nkap.agent.scenario.SeedFiles "$1" >/dev/null)
}
