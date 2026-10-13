# Running the demonstration on one VPS

What you do, in order, and no more. The repository builds and checks everything it can on a laptop
(`scripts/verify-deploy.sh`); this file is the part only you can do, on the VPS and in Cloudflare.

The VPS holds nothing private. The gateway's keys are the demo's own and open nothing else. The two values of any worth are the
**tunnel token** and, only while live is on, the **model key**: both are files, and both are yours to revoke.

What runs: a database, Nkap's simulator and gateway on a network with no outbound access (`internal`); the agent service on that
network and on `edge`; and `cloudflared` on `edge`, which dials out to Cloudflare. **Nothing publishes a port.** Nothing dials in.

## 1. Harden the VPS

Do this before anything else is installed.

- **SSH keys only.** In `/etc/ssh/sshd_config`: `PasswordAuthentication no`, `KbdInteractiveAuthentication no`,
  `PermitRootLogin prohibit-password` (or `no`, with a normal user and `sudo`). Restart `sshd` and open a second session to be sure you
  can still get in before you close the first.
- **A firewall that denies all inbound.** The demo needs no inbound port at all. With `ufw`: `ufw default deny incoming`,
  `ufw default allow outgoing`, and, only if you want SSH from outside, one rule allowing it from your own address and no other
  (`ufw allow from <your address> to any port 22 proto tcp`); otherwise use the provider's console. Docker's own rules bypass `ufw` for
  published ports, which is another reason nothing here publishes one.
- **Unattended security updates.** On Debian or Ubuntu: `apt install unattended-upgrades`, then `dpkg-reconfigure -plow unattended-upgrades`.
- **Containers must not reach the provider's metadata address, `169.254.169.254`.** Cloud providers answer instance credentials
  there. Drop it from containers before Docker starts anything: `iptables -I DOCKER-USER -d 169.254.169.254 -j DROP`, and make it persist
  across reboots (`iptables-persistent`, or a small systemd unit that runs the line). If your provider also has an IPv6 metadata address,
  drop that in `ip6tables` the same way. `internal` already gives the database, the simulator and the gateway no way out; this rule is for
  the containers that do have one (the agent and the tunnel).
- Install Docker Engine and the Compose plugin from your distribution or from Docker's own instructions, and a JDK 21, Maven and `jq`
  (the seed needs them on the host; you can remove them afterwards).

## 2. Get the code

```bash
git clone https://github.com/Deval123/nkap-agent.git /opt/nkap-agent
cd /opt/nkap-agent
git checkout <the tag or commit you reviewed>
```

## 3. The directories and the secret files

```bash
install -d -m 0750 /srv/nkap-agent
install -d -m 0750 /etc/nkap-agent/secrets
```

Compose mounts each secret file into its container at `/run/secrets/<name>`. With Compose on a single host the file keeps the host's
owner and mode (the `uid`, `gid` and `mode` options are for Swarm and are ignored), so **a file that is `root`-owned and `0600` cannot be
read by the container's non-root user.** Each file is owned by the user of the container that reads it, mode `0400`, and no one else
has any access:

| File in `/etc/nkap-agent/secrets` | Read by | Owner | What it is |
| --- | --- | --- | --- |
| `agent-key` | the agent (uid 10001) | `10001:10001`, `0400` | the agent's key (admin), minted by the seed |
| `approver-key` | the agent (uid 10001) | `10001:10001`, `0400` | the approver's key (not admin), minted by the seed |
| `visitor-salt` | the agent (uid 10001) | `10001:10001`, `0400` | a random secret that hashes a visitor; make it with `head -c 24 /dev/urandom \| od -An -tx1 \| tr -d ' \n'` |
| `tunnel-token` | `cloudflared` | the user the pinned image runs as (see 7), `0400` | the tunnel token from Cloudflare |
| `model-key` | the agent (uid 10001) | `10001:10001`, `0400` | the model key; **only while live is on** |

Never put a secret on a command line or in an environment value. To write one without it entering your shell history or the process
list: `umask 077`, then `read -rs secret` and `printf '%s' "$secret" > /etc/nkap-agent/secrets/<file>`, then `unset secret`.

## 4. Build the stack and seed it

Compose needs the four files to exist before it will read the layer; create them empty, and fill them after the seed.

```bash
export DEMO_SECRETS_DIR=/etc/nkap-agent/secrets DEMO_DATA_DIR=/srv/nkap-agent
for f in agent-key approver-key visitor-salt tunnel-token; do touch "$DEMO_SECRETS_DIR/$f"; done

SEED_PROJECT=nkap-agent-demo SEED_OUT=/srv/nkap-agent SEED_COMPOSE_EXTRA=deploy/compose.demo.yaml scripts/seed.sh
```

The seed builds the gateway and the simulator from the Nkap commit pinned in `compose.yaml` (the first build takes several minutes), mints
the two keys into `/srv/nkap-agent/seed-keys.env`, creates the six cases and writes `seed-manifest.json` and `statements/` beside it.
Then put the keys where the agent reads them, and remove the file that held them:

```bash
cd /srv/nkap-agent
grep '^NKAP_AGENT_KEY' seed-keys.env | cut -d= -f2- | tr -d '\n' > /etc/nkap-agent/secrets/agent-key
grep '^NKAP_APPROVER_KEY' seed-keys.env | cut -d= -f2- | tr -d '\n' > /etc/nkap-agent/secrets/approver-key
head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n' > /etc/nkap-agent/secrets/visitor-salt
chown 10001:10001 /etc/nkap-agent/secrets/{agent-key,approver-key,visitor-salt}
chmod 0400 /etc/nkap-agent/secrets/{agent-key,approver-key,visitor-salt}
rm seed-keys.env
```

Stop the seed's helper container and take the snapshot that the reset restores. Do it now, before any visitor:

```bash
cd /opt/nkap-agent
docker compose -p nkap-agent-demo -f compose.yaml -f deploy/compose.demo.yaml --profile tools stop client
scripts/reset-demo.sh --this-is-the-demo-deployment snapshot
```

## 5. Cloudflare (by hand)

In the Cloudflare dashboard (`dash.cloudflare.com`, Zero Trust, Networks, Tunnels; the documentation is at `developers.cloudflare.com`):

1. **Create a tunnel** of the Docker kind. It shows a token: write it to `/etc/nkap-agent/secrets/tunnel-token` as in step 3, and chown it
   (step 7).
2. **One public hostname, one route:** your hostname, service type HTTP, URL `agent:8088`. No other route. The tunnel container is on
   `edge` with the agent and nothing else, so it could not reach the gateway even if a route said so.
3. **A rate-limit rule** for `POST /api/runs` (the one route that costs anything): match the method `POST` and the path `/api/runs`, count
   by client address, for instance 10 requests per minute, action block for ten minutes. The service has its own cooldown and daily cap;
   this rule keeps the traffic from reaching it.

## 6. Start it

```bash
cd /opt/nkap-agent
export DEMO_SECRETS_DIR=/etc/nkap-agent/secrets DEMO_DATA_DIR=/srv/nkap-agent
docker compose -f compose.yaml -f deploy/compose.demo.yaml up -d --build --wait
```

This starts the stack with **live off**. Check the agent from inside (it publishes nothing):

```bash
docker compose -f compose.yaml -f deploy/compose.demo.yaml exec agent wget -qO- http://127.0.0.1:8088/api/status
```

## 7. The images and how each is pinned

Every image reference in `deploy/` carries a digest, and a test fails if one does not. The tag is for the reader; the digest is what is pulled.

| Image | Where | Pinned by | Status |
| --- | --- | --- | --- |
| `maven:3.9.9-eclipse-temurin-21` | `deploy/Dockerfile` (build stage) | tag and digest | pinned |
| `eclipse-temurin:21.0.5_11-jre-alpine` | `deploy/Dockerfile` (run stage) | tag and digest | pinned |
| `postgres:16.6-alpine` | `deploy/compose.demo.yaml` | tag and digest | pinned |
| `curlimages/curl:8.11.1` | `deploy/compose.demo.yaml`, `deploy/verify/` | tag and digest | pinned |
| `cloudflare/cloudflared:2026.9.1` | `deploy/compose.demo.yaml` | version and digest | pinned |
| the gateway and the simulator | built from the Nkap commit in `compose.yaml` | the commit | their base images are pinned by tag in Nkap's own Dockerfiles, not by digest; pinning them by digest is a change in Nkap |

**The tunnel image** is `cloudflare/cloudflared:2026.9.1`, pinned by that version and by its digest in `deploy/compose.demo.yaml`. The maintainer checked,
on his own machine, that this release lists `--token-file` in `tunnel run --help`; that is how the token reaches `cloudflared` from a file instead of an
argument or an environment value. This repository did not fetch the image or contact Cloudflare, so the digest is the maintainer's, and the check is
yours to repeat once you have the image: `docker run --rm cloudflare/cloudflared:2026.9.1 tunnel run --help` must list `--token-file`. The image is
distroless, so there is no shell to read the file into a variable instead, and the token must never be put on the command line. The file's owner is the
user the image runs as (`docker image inspect --format '{{.Config.User}}' cloudflare/cloudflared:2026.9.1`).

**Refreshing it.** Choose a release of `cloudflared` on its releases page on `github.com/cloudflare/cloudflared`, then:

```bash
docker pull cloudflare/cloudflared:<version>
docker image inspect --format '{{index .RepoDigests 0}}' cloudflare/cloudflared:<version>
```

Put the version and that digest in `deploy/compose.demo.yaml` (and in the table above), and confirm that the new version still lists `--token-file`
in `tunnel run --help`; if it does not, do not use it.

**Refreshing the others.** Choose the new tag, then get its digest from the registry and replace it in the file:
`docker buildx imagetools inspect <image>:<tag> --format '{{.Manifest.Digest}}'`. Rebuild and run `scripts/verify-deploy.sh`.

## 8. What only you can check on the VPS

`scripts/verify-deploy.sh` proves the structure on a laptop. These need the real machine and the real Cloudflare account:

- The secret files can be read by the containers, with the owners in step 3: `docker compose ... exec agent sh -c 'cat /run/secrets/agent_key > /dev/null'`
  must succeed, and the same for the tunnel's token file with the tunnel container. (A desktop mounts files in a way that hides a wrong owner.)
- The pinned `cloudflared` lists `--token-file` in `tunnel run --help` (the maintainer saw it; repeat it, section 7), and the tunnel shows healthy in the dashboard.
- The public hostname shows the page, from outside your network, and `POST /api/runs` is refused after the rate-limit rule's threshold.
- **The firewall denies all inbound:** scan the VPS's public address from another machine; every port must be closed or filtered.
- **SSH refuses a password:** `ssh -o PreferredAuthentications=password -o PubkeyAuthentication=no <host>` must be refused.
- **The metadata address is blocked from a container:** `docker run --rm --entrypoint curl curlimages/curl:8.11.1 -m 3 http://169.254.169.254/` must fail
  (use the pinned reference from `deploy/compose.demo.yaml`), after a reboot too.
- Unattended upgrades have run (`/var/log/unattended-upgrades/`), and `docker inspect` shows the log rotation (10 MB, 3 files) on every container.
- Time: the daily budgets reset at midnight UTC, so the VPS clock must be right (`timedatectl`).

## 9. Live on, live off

**On.** Write the model key to `/etc/nkap-agent/secrets/model-key` (owner `10001:10001`, `0400`), then:

```bash
docker compose -f compose.yaml -f deploy/compose.demo.yaml -f deploy/compose.live.yaml up -d
```

The agent restarts with the key mounted and live on, under its daily cap (40) and cooldown (5 minutes). `/api/status` says so.

**Off, the kill switch.** Run the start command of step 6 again, without `compose.live.yaml`: the agent is recreated with no model key and no
live runs, and recordings and scripted runs go on. Then delete the file: `rm /etc/nkap-agent/secrets/model-key`.

## 10. Logs

`docker compose -f compose.yaml -f deploy/compose.demo.yaml logs -f agent` (and `gateway`, `tunnel`). Each container keeps 3 files of 10 MB.
The agent logs the method, the route shape and the status of each request, and nothing else: no address, no body, no id, no model text. The
gateway logs what Nkap logs.

## 11. Reset the demo ledger

Visitors approve adjustments; the service lets one approval through per payment, so after a while the cases cannot be approved again. Restore
the ledger to its seeded state:

```bash
scripts/reset-demo.sh --this-is-the-demo-deployment reset
```

It stops the agent and the gateway, restores the database from the snapshot of step 4, removes `approved-payments.json` from the agent's state
volume, and starts them again. The keys and the budgets are not touched. It refuses to run without the flag, works on the project named by
`DEMO_PROJECT` (default `nkap-agent-demo`) only, and refuses a project that is not named `nkap-agent-<something>`.

## 12. Rotate every key

- **Model key:** create a new one at the provider, write it to `model-key`, restart the agent with the live override, then revoke the old one at the provider.
- **Tunnel token:** roll the token in the Cloudflare dashboard, write the new one to `tunnel-token`, `docker compose ... restart tunnel`.
- **Visitor salt:** write a new `visitor-salt` and restart the agent. Every visitor becomes a new visitor, so cooldowns start again.
- **Agent key and approver key:** mint new ones the way the seed does, with the gateway's command
  (`docker compose ... run --rm gateway --nkap.apikey.create --nkap.apikey.merchant=acme --nkap.apikey.label=<label>`, with
  `--nkap.apikey.admin` for the agent's), write them to `agent-key` and `approver-key`, restart the agent, and revoke the old keys in Nkap.
  How a key is revoked is Nkap's: its migration V10 adds revocation; read the documentation of the pinned commit for the command before relying on this.
  Rotating them does not change the ledger.

## 13. After the submission

1. **Revoke the model key at the provider**, and delete `model-key` from the VPS. Switch live off first (step 9).
2. **Take the tunnel down:** `docker compose ... stop tunnel`, then delete the tunnel and its public hostname in Cloudflare, so the name stops answering.
3. Stop the stack: `docker compose -f compose.yaml -f deploy/compose.demo.yaml down`. Add `-v` only if you mean to delete the demo ledger.
4. Delete the secret files (`shred -u /etc/nkap-agent/secrets/*`), and the snapshot `/srv/nkap-agent/seed.dump` if you are done with it.
5. If you are finished with the machine, destroy the VPS at the provider.
