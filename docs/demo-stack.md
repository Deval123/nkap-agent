# The demo stack

Nkap's gateway, Nkap's simulator and a database, in `compose.yaml`, with the six cases put into
them by `scripts/seed.sh`. It is local. It deploys nothing.

## Starting it

```bash
scripts/seed.sh                 # starts the stack from a fresh database, then seeds it
docker compose -p nkap-agent-demo down -v   # resets it
```

The first run builds the gateway and the simulator from the Nkap commit pinned in `compose.yaml`
(`x-nkap-source`, the one place it is written); this takes several minutes. The published Nkap images
are not used: the `latest` image is 2.1.0 and answers `404` to `GET /adjustments`, because the
adjustment API is unreleased.

`scripts/seed.sh` writes two files, to the repository root or to `$SEED_OUT`, and git ignores both:

- `seed-keys.env`, mode `600`: `NKAP_AGENT_KEY` (admin) and `NKAP_APPROVER_KEY` (not admin), both for
  merchant `acme`. This is the only place the keys exist. They are never printed.
- `seed-manifest.json`: for each case, the payment reference and the statement import id (`null` when
  the case has no statement).

`scripts/verify-seed.sh` does the same on a throwaway project, asserts where every case ended up, runs an
adjustment round trip on `unrecorded-fee`, checks that the proposing key cannot approve its own proposal
on `self-approval`, and removes everything. It exits non-zero if any assertion fails.

## What it publishes

Nothing. `docker compose -f compose.yaml config` shows no `ports` on any service: not the gateway, not
the simulator, not the database, not the management port. The scripts reach the gateway and the
simulator from the `client` container (`--profile tools`), which is on the compose network.

For local work, `compose.dev.yaml` adds `127.0.0.1:8080` and `127.0.0.1:8081` and nothing else
(`GATEWAY_PORT` and `SIMULATOR_PORT` change the host side):

```bash
docker compose -f compose.yaml -f compose.dev.yaml up -d
```

## What the seed creates

| Case | Payment | Statement |
| --- | --- | --- |
| `unrecorded-fee` | escalated | one suspense finding |
| `duplicate-posting` | escalated | two suspense findings |
| `money-without-owner` | settled | one suspense finding |
| `injection` | failed, the sentence in `history[0].operatorCode` | none |
| `i-do-not-know` | escalated | none |
| `self-approval` | escalated | one suspense finding |

The simulator also holds an `XAF` balance, so `GET /balance` answers `200` for the admin key. The seed
starts the stack with `compose.seed.yaml`, which shortens the reconciler (window five seconds) so that
escalation takes seconds. A real deployment must not copy those values.

## Named weaknesses

These are known and accepted for a local demonstration. Each is only acceptable while the condition
beside it holds.

- **The agent's key is an admin key.** An admin key reads every merchant's payments, balances and
  statement reports, and it also opens webhook replay. *Condition:* the gateway is not reachable by
  anyone but the scripts and the agent, and no webhook endpoint is subscribed. Here the gateway
  publishes no port (or only on loopback), and the stack creates no webhook endpoint and runs no relay.
- **The approver key can be used by anyone who holds it.** Separation of duties rests on two keys being
  held by two parties; Nkap compares key ids and cannot know who holds a key. *Condition:*
  `seed-keys.env` stays on one machine, mode `600`, and the approver key is not given to the agent's
  process. If both keys sit in one environment, the separation is a formality.
- **The credentials in `secrets/` are fakes** that only the simulator accepts. *Condition:* this stack
  is never pointed at a real operator.
- **Nothing here is hardened for a network.** The database password is `nkap` and the gateway has no
  TLS. *Condition:* the compose network is not reachable from outside the machine.

If any condition stops holding, the mitigation is gone and these become real weaknesses.
