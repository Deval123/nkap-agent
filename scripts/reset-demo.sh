#!/usr/bin/env bash
# Restores the demo ledger of the demonstration deployment to its seeded state, and forgets the payments the service has
# seen approved (approved-payments.json), which its README says a reset must clear as well.
#
#   scripts/reset-demo.sh --this-is-the-demo-deployment snapshot   # once, right after seeding: dumps the seeded database
#   scripts/reset-demo.sh --this-is-the-demo-deployment reset      # any time: restores that dump
#
# It refuses to run unless the flag says it is the demo deployment. It works on one compose project only, named by
# DEMO_PROJECT (default nkap-agent-demo, and always nkap-agent-<something>): it finds that project's containers and volume
# by their compose labels, and touches nothing else. It stops the agent and the gateway, drops and restores the demo
# database from the dump, removes approved-payments.json from the agent's state volume, and starts them again. The
# live and scripted budgets are not touched: they limit spending and abuse, not the demo's data. The gateway's keys, the
# payments and the statements are those of the dump, so the secret files and the seed's output stay valid.
#
# The dump is DEMO_DATA_DIR/seed.dump (default /srv/nkap-agent), mode 0600. It holds the demo ledger and the hashes of the
# demo's keys, and is kept on the VPS only.
set -euo pipefail

FLAG="--this-is-the-demo-deployment"
refuse() { echo "reset-demo: $*" >&2; exit 2; }

[ "${1-}" = "$FLAG" ] || refuse "this restores a ledger. Say that this is the demo deployment: scripts/reset-demo.sh $FLAG snapshot|reset"
mode="${2-}"
[ "$mode" = snapshot ] || [ "$mode" = reset ] || refuse "the mode is snapshot or reset"

PROJECT="${DEMO_PROJECT:-nkap-agent-demo}"
[[ "$PROJECT" =~ ^nkap-agent-[a-z0-9-]+$ ]] || refuse "DEMO_PROJECT must be a project named nkap-agent-<something>"
DATA="${DEMO_DATA_DIR:-/srv/nkap-agent}"
DUMP="$DATA/seed.dump"

# The one container of a service of this project, found by label; none or several is an error.
container() {
  local ids count
  ids="$(docker ps -a -q --filter "label=com.docker.compose.project=$PROJECT" --filter "label=com.docker.compose.service=$1")"
  count="$(printf '%s\n' "$ids" | grep -c . || true)"
  [ "$count" = 1 ] || { echo "reset-demo: expected one $1 container in project $PROJECT, found $count" >&2; exit 1; }
  printf '%s\n' "$ids"
}
healthy() { # CONTAINER SECONDS
  local deadline=$((SECONDS + $2))
  while [ "$(docker inspect -f '{{.State.Health.Status}}' "$1" 2>/dev/null || true)" != healthy ]; do
    [ "$SECONDS" -lt "$deadline" ] || { echo "reset-demo: a container did not become healthy in $2 seconds" >&2; exit 1; }
    sleep 1
  done
}

db="$(container db)"
[ "$(docker inspect -f '{{.State.Running}}' "$db")" = true ] || { echo "reset-demo: the database is not running" >&2; exit 1; }

if [ "$mode" = snapshot ]; then
  umask 077
  mkdir -p "$DATA"
  docker exec "$db" pg_dump -U nkap -Fc nkap >"$DUMP.partial"
  mv "$DUMP.partial" "$DUMP"
  echo "reset-demo: the seeded database is saved in $DUMP"
  exit 0
fi

[ -s "$DUMP" ] || { echo "reset-demo: there is no snapshot ($DUMP). Take one right after seeding." >&2; exit 1; }
agent="$(container agent)"
gateway="$(container gateway)"
volume="$(docker volume ls -q --filter "label=com.docker.compose.project=$PROJECT" --filter "label=com.docker.compose.volume=agent-state")"
[ "$(printf '%s\n' "$volume" | grep -c . || true)" = 1 ] || { echo "reset-demo: expected one agent-state volume in project $PROJECT" >&2; exit 1; }
image="$(docker inspect -f '{{.Image}}' "$agent")"

echo "reset-demo: stopping the agent and the gateway of $PROJECT"
docker stop "$agent" "$gateway" >/dev/null

echo "reset-demo: restoring the database from the snapshot"
docker exec -i "$db" psql -U nkap -d postgres -v ON_ERROR_STOP=1 -q \
  -c "DROP DATABASE IF EXISTS nkap WITH (FORCE)" -c "CREATE DATABASE nkap OWNER nkap"
docker exec -i "$db" pg_restore -U nkap -d nkap --no-owner --exit-on-error <"$DUMP"

echo "reset-demo: forgetting the approved payments"
docker run --rm --network none --read-only --cap-drop ALL --security-opt no-new-privileges:true \
  -v "$volume:/state" --entrypoint /bin/rm "$image" -f /state/approved-payments.json

echo "reset-demo: starting the gateway and the agent"
docker start "$gateway" >/dev/null
healthy "$gateway" 180
docker start "$agent" >/dev/null
healthy "$agent" 120
echo "reset-demo: done. The demo ledger is as it was seeded."
