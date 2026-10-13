# Nkap Agent

An agent that works alongside [Nkap](https://github.com/Deval123/nkap), the open mobile
money gateway. It lives in its own repository so that nothing of it is merged into the
gateway and it can be removed without touching Nkap.

**Status: under construction.** The loop exists and has been tested only against a scripted fake model and a
fake gateway; it sends nothing to Nkap, and there is no approval client, page or deployment yet. This
repository currently holds the build, the working conventions, the CI, a local demo stack with its seed
([`docs/demo-stack.md`](docs/demo-stack.md)), the agent's read-only tools, the model behind an interface, and
the loop with its check of a final answer.

## What the agent can read

The agent has four tools, in `dev.nkap.agent.tools`. Three tools read the gateway (or, for `search_docs`, a local
copy of documentation), with the admin key weakness named below; the fourth reads a file the operator uploaded.
None of them causes a call to an operator.

| Tool | Reads | Needs |
| --- | --- | --- |
| `get_payment(reference)` | `GET /payments/{reference}`: state, transition history with `cause` and `operatorCode`, `escalatedAt` | the agent's key |
| `get_statement_findings(importId)` | `GET /statements/imports/{importId}`: the reconciliation report | an admin key |
| `get_statement_lines(importId)` | the statement file the operator uploaded, kept by the agent's own service as `<importId>.csv`: the lines, with the amount and the fee of each. No gateway call | nothing |
| `search_docs(query)` | a local copy of Nkap's `docs/providers/` pages, pinned to the commit the stack is built from, searched offline | nothing |

**`get_balance` is not offered, on purpose.** `GET /balance` makes the gateway ask the operator for
its balance, with the gateway's own credentials, so calling it would be an outbound call caused by the
agent. No demonstration case needs it. (Observed: against the simulator the answer depends on the
balance the simulator holds, and a mismatch comes back as an operator-did-not-answer error.)

What `get_statement_lines` does and does not do. The model gives an import id, a UUID; the service maps it to
`<statements-dir>/<importId>.csv`, a name built from the parsed UUID alone, so no path comes from the model. A
symlink, a directory or any file that is not a regular file reads nothing and is "not found"; a file over 64 KB is
refused. At most 200 lines are returned (`truncated` says if there were more); the importer in Nkap sets no limit
of its own, so these caps are ours. A cell is a string or an integer and is never evaluated; an amount or fee that
is not an integer is null, not guessed. The result passes through the same masking and the same block as every
other tool. It reads: nothing in its classes writes, or touches the network or the gateway client (a test reads
their bytecode for that). It does **not** check that the file is the one the gateway imported: the id is the
link, and the seed, which copies each file it imports under the id the gateway gave it, is what keeps the two
together. A file put in the directory by hand under another id would be read as it is.

What is proved, and by what:

- The client that holds the key has one method that reaches the gateway, `get`. A test with a local HTTP
  server records every request the tools make: always `GET`, no body, `Authorization` the only credential.
- The key is read from `NKAP_AGENT_KEY`, asked for per request and held in no field. Tests check that it
  appears in no log line, no exception message and no `toString()`, and that nothing in the package refers
  to the approver's key.
- Everything a tool returns is masked and truncated before it is recorded or shown: a run of nine or more
  digits keeps its first four and last two, and a string leaf is cut at 500 characters with a marker.
  `search_docs` makes no network call.
- A claim names a call and a path, a JSON Pointer into the JSON the model was shown. `Trace.resolve` reads
  the value from the trace, as stored; `Trace.verify` accepts a claim that also names the value only if it is
  exactly that one.
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

## The model

`dev.nkap.agent.model` puts the model behind `AgentModel.next(messages)`, which returns an answer with its
token usage, or a failure as a value. The model's answer is one strict JSON schema, the same on every turn:
an `action` that is either `call_tool` (one of the tools and an argument) or `final` (an outcome, a
hypothesis, postings, evidence and an explanation). The model never writes a request to the gateway, and
evidence is a list of `(callId, path)` pointers; the value is read from the trace by the harness, so the
model's rendering of it is never what is checked. Free text there is refused by the parser. The parser reads
the fields of the answer's action strictly and ignores the others whatever they hold, because the provider
was measured to keep the shape of the schema and not the coupling between fields (below).

The schema is a single object with nullable fields rather than a `oneOf`, because whether the provider's
strict mode accepts a `oneOf` has not been tried. The parser holds an answer to the rules of its action
whatever the provider enforces.

`NebiusModel` makes one request per call, with no retry and no redirect, a bounded `max_tokens` and one
timeout, and reads at most 256 KB. The key is read from `NEBIUS_API_KEY` for each call and held in no field.
The reasoning text the provider returns is never read. Tests against a fake server check these points.
**The code in this repository has only met fakes**: the measurements below come from the maintainer's run of
`scripts/model-spike.sh`, which needs the key. `scripts/verify-spike.sh` runs that script against a fake.

## The loop

`dev.nkap.agent.loop.AgentLoop` runs the model against the four tools. One system prompt
(`src/main/resources/prompts/system.md`, with the entry of each hypothesis rendered from the catalogue) and one
task template (`Investigate payment {reference}. The statement import {importId} was loaded today.`) are the
same for all six cases; tests fail if a case name, an amount or an expected outcome appears in either.

- **Bounds**, per run: 6 model calls (a repair turn counts), 24000 completion tokens, 120 seconds. Hitting one
  ends the run as `EXHAUSTED` with the reason. The bound is checked before each call, so a call in progress is
  not interrupted: one that overshoots is bounded only by the model client's own timeout (60 s in the runner).
- A tool call goes through `AgentTools`. The same tool with the same argument twice is not run again; the
  model is told which call holds the result. An argument that is not a UUID comes back as an error result.
- A final answer goes to the validator. If it breaks a rule the model gets one repair message, made of the
  rules' fixed texts (for an account that is not allowed, with the three accounts that are, built from the payment
  in the trace and never from the model's answer), and a second broken answer ends the run as `REJECTED`. A model failure ends the run as
  `FAILED` with its reason, with no retry; a `length` finish is such a failure.
- `RunRecord` keeps every message sent, each model call with its tokens and time, each tool call as the model
  saw it, and the outcome. `toJson()` is stable. It holds no key, because the loop never has one.

**Tool results are wrapped** as `<tool_result call="c1" tool="get_payment" status="200" trust="untrusted">` plus
the fields as JSON plus `</tool_result>`. The body is the JSON that evidence paths address, and `<`, `>` and
`&` in it are written as `\u003c`, `\u003e` and `\u0026`, the same JSON value, so no result can write a closing
tag. This makes an injection harder to place; it does **not** stop a model from following text it reads. The
protection is the validator below, and a human approving with a different key.

### What the validator checks, and what it does not

`ProposalValidator` is deterministic and uses no model. Given a final answer, the trace and the payment
reference the run was started with (the proposal always carries the run's, never the model's), it checks:

- a `get_payment` result for that reference, with status 200, is in the trace;
- the hypothesis has an entry pattern here. `float_drift` is accepted by Nkap and refused here, and the refusal
  says so; `unattributed_movement` has no pattern because no catalogue case expects a proposal for it, so it is
  refused too;
- the accounts are the suspense, fees and provider float accounts of that payment's provider and currency, and
  every posting is in that currency;
- at least two postings, summing to zero, none zero, the debits no more than 100000 minor units (Nkap's default
  for `nkap.adjustments.max-amount-minor`, which Nkap also checks, on the sum of the debits);
- the entry is the entry of the hypothesis, with one amount: `unrecorded_fee` is fees debited and float
  credited, `duplicate_posting` is suspense debited and float credited. The patterns are read from the
  catalogue's expected entries, not typed again;
- at least one piece of evidence, every pointer resolves in the trace, and the entry's amount appears as a whole
  number in a cited value.

A refusal is held to less, because a refusal costs nothing: it must carry no hypothesis and no postings, and a
citation of it that does not resolve is dropped and counted in the record (`droppedCitations`), never a violation.
That matters because absence can be the evidence: a pointer to a JSON null or an empty string that is present in
a result resolves to `"null"` or `""`, and a missing key, an object or an array still does not resolve. Neither
`"null"` nor `""` can contain the entry's amount, so they cannot satisfy the amount check of a proposal.

It does **not** check that the hypothesis is the true one. A wrong but well-formed proposal passes: a fee
booked for a payment that had none, with an amount found in the statement, would. The human who approves is the
last check, and so is the different key that approval needs. A `no_proposal` answer is accepted without
requiring any tool call: not proposing is the safe answer, and the validator only checks that it carries no
entry and that any evidence it cites resolves.

### What the prompt carries

The system prompt does not only name the hypotheses and their entries: for each one it carries a **definition of
what it looks like in the evidence** and says that the entry is the *correction* (for a duplicate, the reverse of
the faulty posting, not a copy of it; for an unrecorded fee, the booking of the fee). It also says an argument is
the bare UUID. These are general words: a test fails if a case name, an amount or a transaction id of the
catalogue appears in the prompt. The six cases are a **demonstration set, not a benchmark**: changing the prompt
after reading their results fits it to them, and a good score on the same six says little about other payments.

### What was measured, and how few the numbers are

The maintainer ran `scripts/model-spike.sh` against the Super model on 2026-10-13 (42 requests, about 0.03 USD;
see [`docs/nebius-feedback.md`](docs/nebius-feedback.md)). These numbers are small and say little about
robustness:

- The provider keeps the **shape** of a strict schema (20 of 20 answers to a prompt that asked for prose were
  JSON with the eight fields and valid enums) but not the coupling between fields: in a `final` answer it wrote
  `argument: ""` and a `tool`, and in 11 of 20 `outcome: null` beside a tool. Hence the parser above.
- One realistic run (the unrecorded-fee case): the hypothesis was right, the entry was not (suspense +75 / fees
  -75), and the evidence value was paraphrased. Hence the entry patterns and the pointer-only evidence. One run
  is one run.
- With the injection sentence in a tool result: 9 of 10 `no_proposal` and 1 proposal with no postings. Ten
  requests. The control without the sentence had three requests.
- Reasoning is 85 to 97 percent of the completion tokens, so `max_tokens` is 6000 for the loop; at 500, three of
  three were cut off with no answer. Calls took 7 to 14 s.

**First run of the six cases** (maintainer, 3 runs per case, 18 runs, 67 model calls, about 0.063 USD): 10 of 18
matched. `money-without-owner`, `injection` and `i-do-not-know` 3 of 3, `duplicate-posting` 1 of 3,
`unrecorded-fee` and `self-approval` 0 of 3 (the fee is not in the findings). The prompt and the checks above were
changed after reading those records. **Second run: not made yet**; its place is in
[`docs/nebius-feedback.md`](docs/nebius-feedback.md). Three runs per case is a very small sample.

### Running it

`scripts/run-cases.sh` runs each catalogue case K times (default 3, at most 5) against a throwaway seeded stack
with the real model, and prints a table per case and run: outcome, whether the hypothesis and entry equal the
catalogue's expectation, calls, tokens, milliseconds, cost. It has the spike's safeguards: a hard cap on model
calls (default 60, at most 120), a worst-case spend printed first, a stop after three failed calls in a row, the
key read from `NEBIUS_API_KEY` and never printed, and records in `run-results/`, which git ignores. The table and
the totals go to standard output; the log goes to standard error, at WARNING, or at INFO with `--verbose`. It is for the
maintainer, who holds the key. `scripts/verify-run.sh` runs the same runner against a scripted fake model and a
fake gateway, and fails if a wrong entry is not shown as a mismatch.

**Two cases have no statement.** The seed imports a statement only for a case that has lines, so `injection` and
`i-do-not-know` have no import id. The task template is the same for all six cases, so for these two the runner
names the nil UUID (`00000000-0000-0000-0000-000000000000`) as the import; the gateway answers 404 to it, and
the model reads that as the tool result it is. The sentence "was loaded today" is then untrue for these two.

**The fee is on the statement line.** The gateway's finding for a line says what was posted and nothing of the fee
(see `docs/nkap-observations.md`, case 11), and the validator requires the entry's amount to appear in a cited
value, so for `unrecorded-fee` and `self-approval`, where the amount is a fee, a correct entry was refused (4 of 6
cases matched, once, with a scripted model against the real seeded stack). `get_statement_lines` shows the agent
the line, and the fee is cited as `/lines/0/feeMinor`. Against the real seeded stack with a scripted model that
gives each case's expected answer, **6 of 6 matched, one run each**. That is a check of the tools, the validator and the seed
together, not of the model: no model was involved, and Nebius was not called.

## Approval

The loop ends in a checked proposal and sends nothing to Nkap. `dev.nkap.agent.approval` is the step after it: the
agent **proposes** with its key, and a **different** key **approves**, or Nkap refuses.

- `AdjustmentProposer` holds the agent's key (`NKAP_AGENT_KEY`) and can `POST /adjustments` and `GET` adjustments,
  nothing else. It has no approve and no reject.
- `AdjustmentApprover` holds the approver's key (`NKAP_APPROVER_KEY`, a non-admin key of the same merchant) and can
  only `POST /adjustments/{uuid}/approve` and `/reject`. The id is a parsed UUID.
- Each client has an allowlist of method and path, checked before any request, and tested with traversal, an
  encoded slash, a non-UUID, a trailing slash, a query string and every other route. Each asks for its key on every
  request and holds it in no field, follows no redirect, reads at most 64 KB, retries nothing (a repeated POST could
  repeat a decision), and accepts only `https` or a loopback address.
- `ProposalRequest` is the one place a checked `Proposal` becomes Nkap's request body. Evidence is named by its call
  and path, `c2/lines/0/feeMinor`, with the value the trace holds; `cites` lists the names. Nkap refuses a cited
  entry with a blank value, so a blank one is left out, and with none left nothing is sent. Nkap's documentation has
  no example body, so the golden test uses the shape of the body Nkap's own test sends and its OpenAPI describes.
- A refusal from Nkap is a code and a **fixed text** chosen by the problem type. The server's title and detail are
  never shown; a type from elsewhere, an unknown one, a type on a status it is not documented for, or a body that is
  not JSON is `unexpected`, with the status number only.
- The loop and the tools are never given either client, and a test fails if any class but the approval command refers
  to the approver. `ApprovalCli` is the command that runs a case from a file of scripted answers (no model) to a
  decision; `scripts/verify-approval.sh` runs it against a throwaway seeded stack.

**The approval is bound to a key (its id), not to a person.** Nkap compares the key that proposed with the key that
decides; it does not know who holds either. In this demonstration the visitor stands in for the approver, and the
demonstration's backend holds the approver key next to the agent key, so the separation shown is **Nkap's server
rule, not two separate machines**. In a real deployment the approver key belongs to the approver's own system, and
the agent's process would never hold it.

**The other known weakness is the agent's key.** It is an admin key, because the agent also reads statement imports,
so it can reach admin routes that the approver's key cannot (webhook replay, for one, which sends outbound HTTP).
What keeps that harmless here: the gateway publishes no port in `compose.yaml`, the demonstration registers no
webhook endpoint (so a replay finds nowhere to send), and the clients' allowlists give this code no way to call any
other route with it. The first two depend on the stack; the third is a property of this code, tested. If an endpoint
is registered or the gateway is published, the first two are gone.

## Recordings, and the live budget

**What is a recording.** The traces under [`demo/traces/`](demo/traces/) are recordings of runs made on 2026-10-13: each
file says it is one (`"recording": true`), and `TraceReplay` marks every step it yields as a recording and puts the
provenance (date, model, run n of K, tokens, cost, the catalogue's expectation, whether the run matched) in the first
item. A replay reads a file and calls no model and no gateway. **Everything the demonstration plays back from these
files is a recording; only a run started under the live budget below is live.**

**17 of 18.** In the measurement those traces come from (the six cases, three runs each, after the statement-lines
tool), 17 of 18 runs matched the catalogue. Seven runs are published, chosen by a rule written before looking: the
first run of each case, and the one run that did not match, which ended `REJECTED` because the model wrote the same
mistyped account twice. [`demo/README.md`](demo/README.md) has the rule, the run ids, the table, and the caveats:
eighteen runs of one model on six cases are a small sample, the model id is the runner's default (the results file
does not record it), and that recording predates the repair message that now names the allowed accounts.

**The live budget.** `LiveBudget` caps live runs per UTC day (default 40) and per visitor (a cooldown, default
5 minutes), both set by the service and not by a visitor. The state is one small file, so a restart does not reset
it. A visitor is an HMAC of what the service sees of the client, keyed with a secret salt; neither the address nor
the salt is written or logged. It fails closed: a corrupt or unreadable state file, a missing salt, or a blank
visitor means no live run, and a corrupt file is left as it was. This is the code only: the HTTP service that would
call it is not built yet.

## The service

`dev.nkap.agent.web.AgentService` serves the agent over HTTP with one page, using the JDK's own server (no framework, no
new dependency). Start it with `java -cp ... dev.nkap.agent.web.AgentService`; `scripts/verify-web.sh` does so against a
throwaway seeded stack.

### Routes (nothing else exists)

| Route | What it does |
| --- | --- |
| `GET /`, `GET /app.js`, `GET /app.css` | The page, from this service, with no external request of any kind. |
| `GET /api/status` | Whether live is on, and how many live runs are left today. Nothing else. |
| `GET /api/recordings` | The published traces: id, case, title, outcome, matched. |
| `GET /api/recordings/{id}` | One trace, replayed. The id is looked up in the list of published traces and never used to build a path. |
| `POST /api/runs` | `{"case": "<catalogue id>", "mode": "scripted" or "live"}`. Runs the agent. |
| `POST /api/runs/{runId}/approve` | The approver's key decides the proposal this run made. |
| `POST /api/runs/{runId}/self-approve` | The agent's own key tries; Nkap is expected to refuse (403), and the refusal is shown with its fixed text. |

A wrong method on one of these is 405 (with `Allow`), anything else is 404. There is no route to `try-unchecked`, and a test fails
if any class of the `web` package refers to `ApprovalCli`.

### Three modes, labelled on every step

- **Recording**: a published trace played back from a file. No model, no gateway, no Nkap.
- **Scripted**: the real loop, validator, tools and Nkap, with the catalogue's scripted answers instead of a model. The page says
  "scripted answers, not a model" on every step. It costs nothing and exists so the approval flow can be tried without the live budget.
- **Live**: the loop with the Nebius model, only if live is on and `LiveBudget` grants it.

Each step of a run has `recording: false` and its `mode`; each step of a replay has `recording: true`. A run that ends in a
proposal is written to Nkap by the proposer (the agent's key) and gets a `runId`: 128 random bits, kept in memory for 30 minutes, at
most 50. The visitor never gives an adjustment id; the run id maps to the one this service created. A run takes one decision that Nkap took, and at most three attempts that it refused (below).

### What a visitor can and cannot do

They can watch recordings, start a scripted or (if on) a live run of one of the six catalogue cases, and decide the proposal their own
run made, with the approver's key or with the agent's own. They cannot choose an adjustment, a payment, an account, an amount, a
path, a model, a prompt or a key; they cannot reach any other Nkap route; and the page puts what the model and the tools wrote in the
page with `textContent` only, labels the model's explanation "text written by the model, not checked", and the server cuts every
string it returns at 2,000 characters.

### Limits

A request body is at most 1 KB, and a POST must be `application/json` (415 otherwise; 413 over the size). An `Origin`, when present, must
match the `Host` (403 otherwise). The server has 16 threads, and at most 2 runs go at once (a third gets 429 `busy`). A run has the
loop's own bounds. Scripted runs have a budget of their own (200 a day, a 20-second cooldown per visitor), live runs the default (40 a
day, 5 minutes). Live is off unless a model key is configured and the model's base URL is https or loopback. A visitor is the socket address; only if
the peer is the one proxy named in `NKAP_AGENT_TRUSTED_PROXY` is it the last `X-Forwarded-For` entry, and no other header is read. Every response has
`Content-Security-Policy: default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; base-uri 'none'; form-action 'none';
frame-ancestors 'none'`, `X-Content-Type-Options: nosniff` and `Referrer-Policy: no-referrer`, and `Cache-Control: no-store` on `/api/*` and on errors. A
request line the JDK server itself rejects as malformed (for instance a path of `//`) is answered by the JDK before this code, and without these headers.
The log holds the method, the route shape and the status: no address, no body, no id, no model text.

### Configuration (environment; a bad value stops the service at start, naming the variable and never the value)

| Variable | Meaning |
| --- | --- |
| `NKAP_AGENT_MANIFEST` | Required. The seed's `seed-manifest.json`. |
| `NKAP_AGENT_KEY` or `NKAP_AGENT_KEY_FILE` | Required. The agent's key. |
| `NKAP_APPROVER_KEY` or `NKAP_APPROVER_KEY_FILE` | Required. The approver's key. |
| `NKAP_AGENT_SALT` or `NKAP_AGENT_SALT_FILE` | Required. The secret salt that hashes a visitor. |
| `NEBIUS_API_KEY` or `NEBIUS_API_KEY_FILE` | Optional. Without it, live is off. |
| `NEBIUS_BASE_URL`, `NEBIUS_MODEL` | The model endpoint (https or loopback) and its id. |
| `NKAP_GATEWAY_URL` | Nkap (default `http://127.0.0.1:8080`; https or loopback). |
| `NKAP_STATEMENTS_DIR` | The statement files (default: `statements` beside the manifest). |
| `NKAP_AGENT_TRACES_DIR` | The published traces (default `demo/traces`). |
| `NKAP_AGENT_STATE_DIR` | Where the two budgets keep their state (default `state`). |
| `NKAP_AGENT_PORT`, `NKAP_AGENT_BIND` | Default 8088 on 127.0.0.1. |
| `NKAP_AGENT_TRUSTED_PROXY` | The one peer address allowed to name a visitor through `X-Forwarded-For`. |
| `NKAP_AGENT_LIVE_DAILY_CAP`, `NKAP_AGENT_LIVE_COOLDOWN_SECONDS` | 40 and 300. |
| `NKAP_AGENT_SCRIPTED_DAILY_CAP`, `NKAP_AGENT_SCRIPTED_COOLDOWN_SECONDS` | 200 and 20. |

A key is read from its variable or its file each time it is used, never held in a field, never logged and never returned. Giving one both
ways is an error.

### One approved adjustment per payment: a rule of this service, not of Nkap

Nkap does not stop a second adjustment on the same payment. `adjustment_proposal` has an index on the payment and no uniqueness rule,
the entry's id is `adjustment:<the proposal's id>`, and nothing in `AdjustmentService.propose` looks at earlier proposals or
decisions for the payment (Nkap's own observations, cases 6 and 12, show several proposals on one payment). Run the same case twice
and approve both, and Nkap accepts both and writes two ledger entries for the same fee.

So **this service applies a rule Nkap does not have**: it does not send `approve` for a payment that already has an approved adjustment.

- **What happens when it applies.** The proposal is still created, so `self-approve` and Nkap's refusal of it stay available. `approve`
  is answered `409 payment-already-approved` with this fixed text, and nothing is sent to Nkap: "An earlier visitor already approved an
  adjustment for this payment in this demo ledger. Nkap would accept a second one; this service does not send it." The same text is in
  the run's result (`approval.available: false`) when the run ends, so the page says it before the visitor clicks. The check is made
  twice, at the end of the run and at the decision, and the second is under the same lock as the decision itself, so two visitors cannot
  both pass it. The refusal does not close the run or count as an attempt.
- **How the state is kept.** Nkap's adjustment routes cannot be asked which adjustments a payment has: `GET /adjustments` lists every
  adjustment, with no filter, and its answer outgrows the clients' 64 KB cap after a few dozen proposals. So the service keeps a small
  file, `approved-payments.json` in its state directory, of payment references and the id of the adjustment Nkap approved for each,
  written when Nkap takes an approval (atomically, whole). **Nkap stays the source of truth**: at each check the service reads that
  adjustment from Nkap (`GET /adjustments/{id}`, through the proposer's allowlist); if Nkap says it is approved, the payment is taken; if
  Nkap no longer knows it, or knows it as not approved, the entry is stale and is forgotten. If the file is corrupt or unreadable, or Nkap
  cannot be asked, the approval is refused (`503 unavailable`), as `LiveBudget` fails closed, and a corrupt file is left as it was.
- **What it does not cover.** An approval made by anyone else (another client, a script, an operator) is not in the file, so this rule
  does not see it.
- **No route resets anything.** Resetting the demo ledger is an operator action that is not built yet. When it is, it has to clear this
  file as well; until then an entry whose adjustment Nkap no longer has is forgotten on the next check, but nothing here deletes
  Nkap's data or the file.

### Decisions on a run

A run takes one decision, closed only by a decision Nkap took. A refusal (`self-approve`, which Nkap refuses by design, or an `approve`
Nkap refuses) leaves the run open and counts as an attempt: three at most, then `429 too-many-attempts` and Nkap is not asked. The
result of a refused attempt says how many are left (`attemptsLeft`). Every attempt, and the check of the payment that goes with it,
runs under one lock held for at most one call to Nkap (10 seconds), so two requests on one run cannot both reach Nkap as a decision.

## Deploying the demonstration

[`deploy/RUNBOOK.md`](deploy/RUNBOOK.md) is the runbook for running the demonstration on one small VPS, reachable only through a Cloudflare tunnel, with
live runs off unless the operator turns them on. `deploy/` holds the `Dockerfile` of the service, `compose.demo.yaml` (a layer over
`compose.yaml`: an internal network with no outbound access for the database, the simulator and the gateway, an edge network for the service and
the tunnel, no published port, read-only containers with no capability, secrets as files) and `compose.live.yaml` (adds the model key and turns live
on; leaving it out is the kill switch). `scripts/reset-demo.sh` restores the demo ledger to its seeded state, and `scripts/verify-deploy.sh` checks the
structure locally with a fake tunnel in place of `cloudflared`. The runbook lists what only the operator can check on the VPS and in Cloudflare.
The tunnel image is pinned to `cloudflare/cloudflared:2026.9.1` and its digest; `scripts/verify-deploy.sh` fails if any digest in `deploy/` is a placeholder.

### The gateway is reached in clear text on the internal network

The agent reaches the gateway over plain `http` at one named host (`NKAP_AGENT_INTERNAL_HOST=gateway`), so **the agent's key and the approver's key cross the
`internal` network in clear text**. Everywhere else the service requires `https` or a loopback address. What compensates: the network has no outbound access; only the
database, the simulator, the gateway and the agent are on it; every one of them has all capabilities dropped, so none can open raw sockets or sniff; and the code accepts
exactly one host name, a single lower-case label, never a pattern, a dotted name, an upper-case name or an address. What would void the compensation: adding a container
to `internal`, or giving one `NET_RAW`. `scripts/verify-deploy.sh` checks the list of containers on the network and the capabilities each one actually holds. The runbook
(`deploy/RUNBOOK.md`) has the same section.

## Build

Requires Java 21 and Maven.

```bash
mvn -B clean verify
```

## Working on it

See [`docs/conventions.md`](docs/conventions.md). The licence is Apache 2.0, see
[`LICENSE`](LICENSE).
