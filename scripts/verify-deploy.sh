#!/usr/bin/env bash
# Checks the deployment locally, with Docker, and with no Cloudflare and no model key: it builds the agent image, seeds a throwaway
# copy of the demo stack under the deployment's compose layer, puts a curl container where cloudflared would be, and checks
#   - the image pins and the files (no placeholder digest remains, the compose files are valid);
#   - nothing publishes a port, and the agent answers only on the edge network;
#   - the gateway is not reachable from the edge network, and the internal network has no outbound access (each against a
#     positive control, so a failure to connect means something);
#   - the agent runs as a non-root user on a read-only file system with no capability, and says live is off;
#   - the tunnel's address is the one peer that can name a visitor;
#   - no key is in an image layer, an environment or a log;
#   - scripts/reset-demo.sh refuses without its flag and restores the seeded ledger, forgetting the approved payments.
# Any failed expectation makes the exit code non-zero. The stack, its volumes and its images' containers are removed at the end.
#
# What it cannot check, and only the operator can on the VPS, is in deploy/RUNBOOK.md ("What only you can check").
#
# Usage: scripts/verify-deploy.sh
set -euo pipefail

export SEED_PROJECT="${SEED_PROJECT:-nkap-agent-deploy-verify}"
export GATEWAY_PORT=0 SIMULATOR_PORT=0
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export SEED_COMPOSE_EXTRA="$root/deploy/compose.demo.yaml"
source "$(dirname "${BASH_SOURCE[0]}")/stack-lib.sh"

out="$(mktemp -d)"; chmod 700 "$out"
export SEED_OUT="$out" DEMO_DATA_DIR="$out" DEMO_SECRETS_DIR="$out/secrets"
mkdir -m 700 "$DEMO_SECRETS_DIR"
for f in agent-key approver-key visitor-salt tunnel-token; do : >"$DEMO_SECRETS_DIR/$f"; done   # seed.sh needs the files to exist
listener_pid=""
dc2() {
  docker compose -p "$PROJECT" -f "$root/compose.yaml" -f "$root/deploy/compose.demo.yaml" -f "$root/deploy/verify/compose.fake-tunnel.yaml" "$@"
}
teardown() {
  [ -z "$listener_pid" ] || kill "$listener_pid" 2>/dev/null || true
  dc2 --profile tools down -v --remove-orphans >/dev/null 2>&1 || true
  rm -rf "$out"
}
trap teardown EXIT

say "The files: no placeholder digest, and a valid compose configuration"
if grep -rn -E 'sha256:0{64}' "$root/deploy" "$root/Dockerfile" 2>/dev/null | grep -v "^$root/deploy/RUNBOOK.md" >"$out/placeholders"; then
  while read -r line; do echo "  placeholder: ${line#"$root"/}" >&2; done <"$out/placeholders"
  expect "no image digest is still a placeholder (fill it in, see deploy/RUNBOOK.md)" "$(wc -l <"$out/placeholders" | tr -d ' ') placeholder(s)" "0 placeholder(s)"
else
  expect "no image digest is still a placeholder" "0 placeholder(s)" "0 placeholder(s)"
fi
expect "the merged compose configuration is valid" "$(dc2 config -q >/dev/null 2>&1 && echo valid || echo invalid)" "valid"
expect "the live override is valid too" "$(docker compose -p "$PROJECT" -f "$root/compose.yaml" -f "$root/deploy/compose.demo.yaml" -f "$root/deploy/compose.live.yaml" config -q >/dev/null 2>&1 && echo valid || echo invalid)" "valid"
for ref in $(grep -h -o -E '[A-Za-z0-9./_-]+:[A-Za-z0-9._-]+@sha256:[0-9a-f]{64}' "$root/deploy/Dockerfile" "$root/deploy/compose.demo.yaml" "$root/deploy/verify/compose.fake-tunnel.yaml" | sort -u); do
  case "$ref" in cloudflare/*) continue ;; esac
  digest="${ref#*@}"
  # Where the image is here, its recorded digest must be the pinned one (the pin is not a different image).
  have="$(docker images --digests --format '{{.Repository}}:{{.Tag}} {{.Digest}}' | awk -v r="${ref%@*}" '$1 == r {print $2}' | head -n 1)"
  [ -z "$have" ] || expect "the pin of ${ref%@*} is the digest this machine has" "$have" "$digest"
done

say "Building the agent image"
dc2 build agent >"$out/build.log" 2>&1 || { tail -n 30 "$out/build.log" >&2; die "the image did not build"; }
image="$(docker image inspect -f '{{.Id}}' "${PROJECT}-agent" 2>/dev/null || dc2 images -q agent | head -n 1)"
echo "built: ${image:0:19}"

say "Seeding a throwaway stack under the deployment's layer (project $PROJECT)"
bash "$root/scripts/seed.sh" >"$out/seed.log" 2>&1 || { tail -n 25 "$out/seed.log" >&2; die "scripts/seed.sh failed under the deployment's layer"; }
tail -n 2 "$out/seed.log"
agent_key="$(sed -n 's/^NKAP_AGENT_KEY=//p' "$out/seed-keys.env")"
approver_key="$(sed -n 's/^NKAP_APPROVER_KEY=//p' "$out/seed-keys.env")"
salt="$(head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n')"
[ -n "$agent_key" ] && [ -n "$approver_key" ] || die "the seed wrote no keys"
printf '%s' "$agent_key" >"$DEMO_SECRETS_DIR/agent-key"
printf '%s' "$approver_key" >"$DEMO_SECRETS_DIR/approver-key"
printf '%s' "$salt" >"$DEMO_SECRETS_DIR/visitor-salt"
printf '%s' "not-a-token-this-file-is-not-read" >"$DEMO_SECRETS_DIR/tunnel-token"
# On a desktop the container reads these through a bind mount whatever its user; on the VPS they belong to the container's user (RUNBOOK.md).
chmod 644 "$DEMO_SECRETS_DIR"/*
entries0="$(psql 'select count(*) from ledger_entry')"
expect "the ledger was seeded: there are no adjustment entries yet" "$(psql "select count(*) from ledger_entry where id like 'adjustment:%'")" "0"

say "Taking the snapshot, then starting the deployment (a fake tunnel, no model key)"
bash "$root/scripts/reset-demo.sh" --this-is-the-demo-deployment snapshot >"$out/snapshot.log" 2>&1 || { cat "$out/snapshot.log" >&2; die "the snapshot failed"; }
expect "the snapshot is private" "$(stat -f '%Lp' "$out/seed.dump" 2>/dev/null || stat -c '%a' "$out/seed.dump")" "600"
dc2 --profile tools stop client >/dev/null 2>&1 || true
dc2 up -d --wait --wait-timeout 300 >"$out/up.log" 2>&1 || { tail -n 30 "$out/up.log" >&2; die "the deployment did not come up"; }
dc2 ps --format '{{.Service}}: {{.Status}}'
cid() { dc2 ps -q "$1"; }
tunnel_exec() { dc2 exec -T tunnel "$@"; }
status_via_tunnel() { tunnel_exec curl -s -m 5 -o /dev/null -w '%{http_code}' "http://agent:8088$1"; }

say "Nothing publishes a port; the agent answers on the edge network"
published=0
for service in db simulator gateway agent tunnel; do
  n="$(docker inspect -f '{{range $p, $b := .NetworkSettings.Ports}}{{if $b}}{{len $b}} {{end}}{{end}}' "$(cid $service)" | wc -w | tr -d ' ')"
  published=$((published + n))
done
expect "no service has a published port" "$published" "0"
expect "nothing answers on the host at the agent's port" "$(curl -s -m 2 -o /dev/null -w '%{http_code}' http://127.0.0.1:8088/api/status || true)" "000"
expect "the tunnel's place reaches the agent over edge" "$(status_via_tunnel /api/status)" "200"
expect "/api/status says live is off" "$(tunnel_exec curl -s -m 5 http://agent:8088/api/status | jq -r .liveEnabled)" "false"
expect "a live run is refused: there is no model key" "$(tunnel_exec curl -s -m 5 -H 'Content-Type: application/json' -d '{"case":"unrecorded-fee","mode":"live"}' http://agent:8088/api/runs | jq -r .error)" "live-off"
expect "the agent has no model key in its environment" "$(docker inspect -f '{{.Config.Env}}' "$(cid agent)" | grep -c NEBIUS || true)" "0"

say "Networks: who is on which"
nets() { docker inspect -f '{{range $n, $_ := .NetworkSettings.Networks}}{{$n}} {{end}}' "$(cid "$1")" | tr ' ' '\n' | sed "s/^${PROJECT}_//" | sort | grep . | tr '\n' ',' ; }
expect "db is on internal only" "$(nets db)" "internal,"
expect "the simulator is on internal only" "$(nets simulator)" "internal,"
expect "the gateway is on internal only" "$(nets gateway)" "internal,"
expect "the agent is on both" "$(nets agent)" "edge,internal,"
expect "the tunnel is on edge only" "$(nets tunnel)" "edge,"
expect "internal is an internal network" "$(docker network inspect -f '{{.Internal}}' "${PROJECT}_internal")" "true"
expect "edge is not" "$(docker network inspect -f '{{.Internal}}' "${PROJECT}_edge")" "false"

say "The gateway is not reachable from edge; internal has no way out (each against a control)"
curl_image="$(grep -h -o -E 'curlimages/curl:[0-9.]+@sha256:[0-9a-f]{64}' "$root/deploy/compose.demo.yaml" | head -n 1)"
throwaway() { # NETWORK URL -> the HTTP status, or 000
  docker run --rm --network "$1" --add-host host.docker.internal:host-gateway --cap-drop ALL --entrypoint curl "$curl_image" \
    -s -m 4 -o /dev/null -w '%{http_code}' "$2" 2>/dev/null || true
}
gateway_ip="$(docker inspect -f "{{(index .NetworkSettings.Networks \"${PROJECT}_internal\").IPAddress}}" "$(cid gateway)")"
code_control="$(throwaway "${PROJECT}_internal" "http://$gateway_ip:8080/")"
expect "control: a container on internal reaches the gateway by address" "$([ "$code_control" != 000 ] && echo reaches || echo "does not reach ($code_control)")" "reaches"
expect "control: it reaches the gateway by name too" "$([ "$(throwaway "${PROJECT}_internal" "http://gateway:8080/")" != 000 ] && echo reaches || echo no)" "reaches"
expect "a container on edge cannot reach the gateway by name" "$(throwaway "${PROJECT}_edge" "http://gateway:8080/")" "000"
expect "nor by its address on internal" "$(throwaway "${PROJECT}_edge" "http://$gateway_ip:8080/")" "000"
expect "nor its management port" "$(throwaway "${PROJECT}_edge" "http://$gateway_ip:9464/actuator/health")" "000"
mkdir "$out/www"; echo ok >"$out/www/index.html"
python3 -m http.server 0 --bind 0.0.0.0 --directory "$out/www" >"$out/listener.log" 2>&1 &
listener_pid=$!
for _ in $(seq 1 30); do grep -q "port" "$out/listener.log" && break; sleep 0.2; done
outside_port="$(sed -n 's/.*port \([0-9]*\).*/\1/p' "$out/listener.log" | head -n 1)"
bridge="${PROJECT}_outside_control"
docker network create "$bridge" >/dev/null
expect "control: a container on an ordinary network reaches a service outside" "$(throwaway "$bridge" "http://host.docker.internal:$outside_port/")" "200"
docker network rm "$bridge" >/dev/null
expect "a container on edge can go out (the agent needs the model; the tunnel dials out)" "$(throwaway "${PROJECT}_edge" "http://host.docker.internal:$outside_port/")" "200"
expect "a container on internal cannot go out" "$(throwaway "${PROJECT}_internal" "http://host.docker.internal:$outside_port/")" "000"
host_ip="$(docker run --rm --add-host host.docker.internal:host-gateway --entrypoint sh "$curl_image" -c 'getent hosts host.docker.internal' | awk '{print $1}')"
expect "control: that outside address answers a container on an ordinary network" "$(docker run --rm --entrypoint curl "$curl_image" -s -m 4 -o /dev/null -w '%{http_code}' "http://$host_ip:$outside_port/" 2>/dev/null || true)" "200"
expect "the gateway container itself cannot go out, by address either" "$(docker exec "$(cid gateway)" wget -q -T 3 -O /dev/null "http://$host_ip:$outside_port/" >/dev/null 2>&1 && echo went-out || echo cannot)" "cannot"

say "The agent container: non-root, read-only, no capability"
agent="$(cid agent)"
expect "it runs as 10001:10001" "$(docker inspect -f '{{.Config.User}}' "$agent")" "10001:10001"
expect "the process is not root" "$(dc2 exec -T agent id -u)" "10001"
expect "the root file system is read-only" "$(docker inspect -f '{{.HostConfig.ReadonlyRootfs}}' "$agent")" "true"
expect "a write to the image fails" "$(dc2 exec -T agent sh -c 'touch /app/x 2>/dev/null && echo wrote || echo refused')" "refused"
expect "a write to /tmp works (tmpfs)" "$(dc2 exec -T agent sh -c 'touch /tmp/x && echo wrote || echo refused')" "wrote"
expect "a write to /state works (the volume)" "$(dc2 exec -T agent sh -c 'touch /state/x && rm /state/x && echo wrote || echo refused')" "wrote"
expect "all capabilities are dropped" "$(docker inspect -f '{{.HostConfig.CapDrop}}' "$agent")" "[ALL]"
expect "new privileges are forbidden" "$(docker inspect -f '{{.HostConfig.SecurityOpt}}' "$agent")" "[no-new-privileges:true]"
expect "it has a memory limit and a process limit" "$(docker inspect -f '{{if gt .HostConfig.Memory 0}}memory{{end}} {{if gt .HostConfig.PidsLimit 0}}pids{{end}}' "$agent")" "memory pids"
expect "its health check says healthy" "$(docker inspect -f '{{.State.Health.Status}}' "$agent")" "healthy"
expect "its restart policy is unless-stopped" "$(docker inspect -f '{{.HostConfig.RestartPolicy.Name}}' "$agent")" "unless-stopped"
expect "the secrets are files under /run/secrets" "$(dc2 exec -T agent ls /run/secrets | tr '\n' ' ')" "agent_key approver_key visitor_salt "
for service in db simulator gateway tunnel; do
  expect "$service: read-only root, no capability but what it needs, no new privileges" \
    "$(docker inspect -f '{{.HostConfig.ReadonlyRootfs}} {{.HostConfig.SecurityOpt}}' "$(cid $service)")" "true [no-new-privileges:true]"
done
expect "the gateway runs with every capability dropped" "$(docker inspect -f '{{.HostConfig.CapDrop}}' "$(cid gateway)")" "[ALL]"

say "The trusted proxy is the tunnel's fixed address, and only a request from there can name a visitor"
expect "the tunnel is at the address the agent trusts" "$(docker inspect -f "{{(index .NetworkSettings.Networks \"${PROJECT}_edge\").IPAddress}}" "$(cid tunnel)")" "172.28.40.10"
expect "the agent is told to trust that address" "$(docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' "$agent" | sed -n 's/^NKAP_AGENT_TRUSTED_PROXY=//p')" "172.28.40.10"
run() { # FORWARDED-FOR CASE -> the status
  tunnel_exec curl -s -m 60 -o /dev/null -w '%{http_code}' -H 'Content-Type: application/json' -H "X-Forwarded-For: $1" \
    -d "{\"case\":\"$2\",\"mode\":\"scripted\"}" http://agent:8088/api/runs
}
expect "a scripted run from visitor A" "$(run 203.0.113.10 injection)" "200"
expect "the same visitor again, inside the cooldown, is refused" "$(run 203.0.113.10 injection)" "429"
expect "a different visitor is not" "$(run 203.0.113.11 injection)" "200"

say "A proposal approved through the agent, with the ledger read in the database"
tunnel_exec curl -s -m 60 -H 'Content-Type: application/json' -H 'X-Forwarded-For: 203.0.113.20' -d '{"case":"unrecorded-fee","mode":"scripted"}' http://agent:8088/api/runs >"$out/run.json"
expect "the run proposes and Nkap records it" "$(jq -r '.outcome.kind + " " + .proposal.status' "$out/run.json")" "PROPOSED created"
run_id="$(jq -r .runId "$out/run.json")"
decision="$(tunnel_exec curl -s -m 30 -H 'Content-Type: application/json' -d '{}' "http://agent:8088/api/runs/$run_id/approve")"
expect "the approver's key approves" "$(jq -r .result <<<"$decision")" "decided"
expect "the ledger has one adjustment entry" "$(psql "select count(*) from ledger_entry where id like 'adjustment:%'")" "1"
expect "the agent's state volume holds the record of the approved payment" "$(dc2 exec -T agent ls /state | tr '\n' ' ')" "approved-payments.json live-budget.json scripted-budget.json "

say "No key in an image layer, a container's configuration or a log"
history="$(docker history --no-trunc "$(docker image inspect -f '{{.Id}}' "$image")" 2>/dev/null || true)"
inspect="$(for s in db simulator gateway agent tunnel; do docker inspect "$(cid $s)"; done)"
logs="$(for s in db simulator gateway agent tunnel; do docker logs "$(cid $s)" 2>&1; done)"
expect "no key or salt in the image's history" "$(grep -c -e "$agent_key" -e "$approver_key" -e "$salt" <<<"$history" || true)" "0"
expect "no key or salt in any container's configuration" "$(grep -c -e "$agent_key" -e "$approver_key" -e "$salt" <<<"$inspect" || true)" "0"
expect "no key or salt in any container's log" "$(grep -c -e "$agent_key" -e "$approver_key" -e "$salt" <<<"$logs" || true)" "0"
expect "no key or salt in the agent's process list" "$(docker top "$agent" | grep -c -e "$agent_key" -e "$approver_key" -e "$salt" || true)" "0"

say "The reset"
others_before="$(docker volume ls -q | grep -v "^${PROJECT}_" | sort | cksum)"
mine_before="$(docker volume ls -q | grep -c "^${PROJECT}_" | tr -d ' ')"
bash "$root/scripts/reset-demo.sh" snapshot >"$out/r1.log" 2>&1 && code=0 || code=$?
expect "without the flag it refuses (exit 2) and says why" "$code $(grep -c 'demo deployment' "$out/r1.log")" "2 1"
DEMO_PROJECT=somebody-elses bash "$root/scripts/reset-demo.sh" --this-is-the-demo-deployment reset >"$out/r2.log" 2>&1 && code=0 || code=$?
expect "for a project that is not a demo project it refuses (exit 2)" "$code" "2"
mkdir "$out/empty"
DEMO_DATA_DIR="$out/empty" bash "$root/scripts/reset-demo.sh" --this-is-the-demo-deployment reset >"$out/r3.log" 2>&1 && code=0 || code=$?
expect "with no snapshot it refuses (exit 1) and stops nothing" "$code $(docker inspect -f '{{.State.Running}}' "$(cid gateway)")" "1 true"
bash "$root/scripts/reset-demo.sh" --this-is-the-demo-deployment reset >"$out/reset.log" 2>&1 || { cat "$out/reset.log" >&2; die "the reset failed"; }
tail -n 1 "$out/reset.log"
expect "after the reset there is no adjustment entry" "$(psql "select count(*) from ledger_entry where id like 'adjustment:%'")" "0"
expect "the ledger has the entries it had when seeded" "$(psql 'select count(*) from ledger_entry')" "$entries0"
expect "and no proposal" "$(psql 'select count(*) from adjustment_proposal')" "0"
expect "the record of approved payments is gone" "$(dc2 exec -T agent ls /state | tr '\n' ' ')" "live-budget.json scripted-budget.json "
expect "the budgets are kept" "$(dc2 exec -T agent sh -c 'test -s /state/scripted-budget.json && echo kept')" "kept"
agent="$(cid agent)"
expect "the agent is healthy again" "$(docker inspect -f '{{.State.Health.Status}}' "$agent")" "healthy"
expect "the keys still work: a new run proposes" "$(tunnel_exec curl -s -m 60 -H 'Content-Type: application/json' -H 'X-Forwarded-For: 203.0.113.30' -d '{"case":"unrecorded-fee","mode":"scripted"}' http://agent:8088/api/runs | jq -r '.proposal.status + " " + (.approval.available|tostring)')" "created true"
expect "the reset touched no volume outside the project" "$(docker volume ls -q | grep -v "^${PROJECT}_" | sort | cksum)" "$others_before"
expect "the project's own volumes remain" "$(docker volume ls -q | grep -c "^${PROJECT}_" | tr -d ' ')" "$(printf '%s\n' "$mine_before")"

say "Result"
if [ "$failures" -ne 0 ]; then
  echo "verify-deploy: $failures expectation(s) FAILED" >&2
  exit 1
fi
echo "verify-deploy: all expectations passed"
