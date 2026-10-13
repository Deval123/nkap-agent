# Nkap Agent

An agent that works alongside [Nkap](https://github.com/Deval123/nkap), the open mobile
money gateway. It lives in its own repository so that nothing of it is merged into the
gateway and it can be removed without touching Nkap.

**Status: under construction.** There is no model connected yet. This repository currently
holds the build, the working conventions, the CI, a local demo stack with its seed
([`docs/demo-stack.md`](docs/demo-stack.md)), and the agent's read-only tools.

## What the agent can read

The agent has three tools, in `dev.nkap.agent.tools`. Each one reads state the gateway already holds,
and none of them causes an outbound call to an operator.

| Tool | Reads | Needs |
| --- | --- | --- |
| `get_payment(reference)` | `GET /payments/{reference}`: state, transition history with `cause` and `operatorCode`, `escalatedAt` | the agent's key |
| `get_statement_findings(importId)` | `GET /statements/imports/{importId}`: the reconciliation report | an admin key |
| `search_docs(query)` | a local copy of Nkap's `docs/providers/` pages, pinned to the commit the stack is built from, searched offline | nothing |

**`get_balance` is not offered, on purpose.** `GET /balance` makes the gateway ask the operator for
its balance, with the gateway's own credentials, so calling it would be an outbound call caused by the
agent. No demonstration case needs it. (Observed: against the simulator the answer depends on the
balance the simulator holds, and a mismatch comes back as an operator-did-not-answer error.)

What is proved, and by what:

- The client that holds the key has one method that reaches the gateway, `get`. A test with a local HTTP
  server records every request the tools make: always `GET`, no body, `Authorization` the only credential.
- The key is read from `NKAP_AGENT_KEY`, asked for per request and held in no field. Tests check that it
  appears in no log line, no exception message and no `toString()`, and that nothing in the package refers
  to the approver's key.
- Everything a tool returns is masked and truncated before it is recorded or shown: a run of nine or more
  digits keeps its first four and last two, and a string leaf is cut at 500 characters with a marker.
  `search_docs` makes no network call.
- A claim names a call, a field and a value, and `Trace.verify` accepts it only if that call returned that
  field with exactly that value, as stored.
- That `GET /payments/{reference}` and `GET /statements/imports/{importId}` do not call the operator is
  Nkap's design, as read in its code; it is not tested here.

`scripts/verify-tools.sh` runs each tool against a real throwaway stack with the six cases seeded, and
exits non-zero if any expectation fails.

### The weakness beside it

**The agent's key is an admin key**, because `get_statement_findings` needs one. An admin key also opens
`POST /webhooks/events/{eventId}/replay`, and replaying a webhook does send outbound HTTP, to the URL on
file for the merchant. The tools cannot do that, since they can only `GET`; the weakness is the key, in
whatever process holds it.

What stands in the way here, and what each depends on:

- the gateway publishes no port in `compose.yaml` (`scripts/verify-seed.sh` checks it);
- the demo registers no webhook endpoint, so a replay would find nowhere to send. `scripts/verify-tools.sh`
  checks that none exists. It also finds the outbox empty (no endpoint, so no event is queued), which
  means the refusal a replay of a real event would get for want of an endpoint (`no-webhook-endpoint`,
  read in Nkap's code) was **not** exercised; what it does show is that the agent's key reaches the replay
  route (an unknown event is a `404`) and the approver's key does not (`403`).

Switching webhooks off (`NKAP_WEBHOOKS_ENABLED=false`) stops the background relay but **not** a replay: in
Nkap, a replay keeps working with webhooks off. So it is not a compensation, and nothing here relies on it.
If a webhook endpoint is ever registered, or the gateway is published, the protection is gone.

## Build

Requires Java 21 and Maven.

```bash
mvn -B clean verify
```

## Working on it

See [`docs/conventions.md`](docs/conventions.md). The licence is Apache 2.0, see
[`LICENSE`](LICENSE).
