# What Nkap's demo stack does when it is run

Everything here was read in code first and then observed by running the stack with
`scripts/observe.sh`. A statement that was read but not observed is marked **not observed**.

| | |
| --- | --- |
| Date | 2026-10-13 |
| Docker server / Compose | 29.8.2 / 5.5.1 |
| Nkap | `origin/main` at `1cbd8a4` (`2.2.0-SNAPSHOT`), exported with `git archive`, built from source |
| Reconciler during the run | interval `PT1S`, backoff base `PT1S`, backoff max `PT2S`, window `PT5S` |
| Request timeout | `PT2S`, the demo's own |

How the run differs from Nkap's own demo, all in `scripts/observe.override.compose.yaml` or the
script: the reconciler is shortened and `NKAP_ADJUSTMENTS_ENABLED=true` is set; the host ports are
18080 and 18081 because 8080 and 8081 were already taken by a demo stack that was up; the compose
project is `nkap-observe`; the demo's own fixed key was not used, two keys were minted with
`--nkap.apikey.create` and are never printed. The stack and its volumes were removed afterwards.
Nothing in the Nkap repository was changed.

## Where reading the code was wrong

1. **A `PENDING` query with an unrecognised reason does not make the payment `UNKNOWN`.** Reading
   `MtnStatusMap.stateFor` says `UNKNOWN`. The payment stayed `SUBMITTED`, with no transition in its
   history, and was escalated all the same (case 2c). Why the state did not move was not traced.
2. **A callback that reaches the gateway does not give the payment a `providerTransactionId`.** The
   callback reached the gateway on the compose network, was answered `202`, and settled the payment
   with cause `CALLBACK`, but `providerTransactionId` stayed blank (case 5). An earlier reading of
   this stack said the id would appear once a callback arrived. The gateway's `CallbackController`
   describes a callback as a hint that is confirmed by querying the operator, and the simulator's
   query answer carries no id. That explanation was read, not traced through `SettlementService`.
3. **`POST /payments` answers `201` with state `SUBMITTED`, and `202` with state `UNKNOWN`.** The
   status code depends on the state reached (cases 1 and 2).
4. **A consequence, inferred rather than observed:** no payment in this stack ever holds an operator
   transaction id, so a statement line can never match one. Of the statement finding kinds, only
   `SUSPENSE_POSTED` and `SUSPENSE_ALREADY_POSTED` can be produced here.

Everything else the plan listed held when run: blank `providerTransactionId` after polling, `UNKNOWN`
for no answer and for a server error, `PENDING` for a bare pending query, `window_exhausted` for every
escalation, port 9464 not published, and a suspense posting counted as a discrepancy.

## Case 1: a payment settled by polling: observed

Scenario `{"rules":[{"scenario":{"name":"settles","onQuery":[{"status":"SUCCESSFUL"}]}}]}` posted to
the simulator (`204`), then `POST /payments` with a `COLLECT` of 5000 XAF for country `cm`.

- The POST answered `201`, state `SUBMITTED`. The payment reached `SUCCEEDED` about a second later,
  by cause `RECONCILER`, operator code `SUCCESSFUL`.
- `GET /payments/{reference}` returns exactly these fields: `reference`, `provider`, `merchantId`,
  `state`, `operation`, `amountMinorUnits`, `currency`, `counterpartyMsisdn`, `providerReference`,
  `providerTransactionId`, `createdAt`, `updatedAt`, `detail`, `refundOf`, `escalatedAt`,
  `unresolvedSince`, `history[]` (`from`, `to`, `at`, `cause`, `operatorCode`, `note`).
- `providerTransactionId` is `""`, and so is `providerReference`. `escalatedAt` is `""`;
  `unresolvedSince` is set even for a payment that settled.

## Case 2: three ways to leave a payment unresolved: observed

Each scenario was posted, then one payment of 3000 XAF. Time is from sending the POST to seeing
`escalatedAt` on `GET /payments/{reference}`, polled every half second.

| | Scenario | POST answered | State reached | Escalated after | `reconcileAttempts` |
| --- | --- | --- | --- | --- | --- |
| 2a | `onSubmit: NO_RESPONSE`, query `PENDING` + `SERVICE_UNAVAILABLE` | `202` after 2.1 s | `UNKNOWN` | 8.1 s | 4 |
| 2b | `onSubmit: SERVER_ERROR`, same query | `202` after 0.1 s | `UNKNOWN` | 6.1 s | 4 |
| 2c | accepted, query `PENDING` + unrecognised reason | `201` | `SUBMITTED` (see above) | 6.1 s | 4 |
| 2d | accepted, query bare `PENDING` | `201` | `PENDING` | 5.5 s | 4 |

- All four were escalated with reason `window_exhausted`. None became `FAILED`.
- In 2a and 2b the history holds one transition (`CREATED` to `UNKNOWN`, cause `SUBMIT_RESPONSE`); the
  reconciler's queries did not add any. The `detail` field says the operator did not answer.
- The 2.1 s in 2a is the request timeout. With a 5 s window the escalation came 5.5 to 8.1 s after the
  POST.

## Case 3: `GET /actuator/escalatedPayments`: observed

Reached from inside the compose network:
`docker compose exec gateway wget -qO- http://localhost:9464/actuator/escalatedPayments`. Port 9464 is
not published (`docker compose port gateway 9464` finds nothing).

The answer is `{"payments": [...], "truncated": false}`. Each item has `reference`, `provider`,
`merchantId`, `state`, `escalatedAt`, `unresolvedSince`, `reconcileAttempts`, `reason`. It listed the
four payments of case 2, with states `UNKNOWN`, `UNKNOWN`, `SUBMITTED`, `PENDING`, all `reason:
"window_exhausted"`, `reconcileAttempts: 4`.

## Case 4: a statement import with a line matching no payment: observed

`statement.csv` held the header and one line: `OBSERVE-UNMATCHED-1,5000,75,XAF,2026-10-13T10:00:00Z,SETTLED`.
Run with `docker compose run --rm gateway --nkap.statement.import=/statement.csv
--nkap.statement.provider=mtn-cm`, the file mounted read-only.

- First run: `SUSPENSE_POSTED: 1`, "1 discrepancy — a human must look", **exit code 2**. The ledger went
  from 1 entry to 2. The new entry is `suspense:mtn-cm:OBSERVE-UNMATCHED-1`, postings
  `provider:mtn-cm:float:XAF` +5000 and `suspense:mtn-cm:XAF` -5000. The fee on the line (75) was not
  posted.
- Second run, same file: `SUSPENSE_ALREADY_POSTED: 1`, "nothing written", exit code 2 again, still 2
  ledger entries.
- One payment had settled by then (case 1), with a blank transaction id. It was not reported as
  missing from the statement.
- **Not observed:** `FEE_POSTED`, `MATCHED`, `FEE_ALREADY_POSTED`, `MISSING_FROM_STATEMENT` and
  `AMOUNT_MISMATCH`. They need a settled payment with an operator transaction id, which this stack
  never produces.

## Case 5: a callback from the simulator to the gateway: observed

Scenario with `"callbackUrl":"http://gateway:8080/callbacks/mtn-cm"`, a query answering `SUCCESSFUL`
and one callback `{"after":"PT0S","status":"SUCCESSFUL"}`.

- The simulator logged the delivery as `(202)`. The gateway logged `callback on /callbacks/mtn-cm
  received for reference ...` and `CALLBACK ...: SUBMITTED -> SUCCEEDED (SUCCESSFUL)`.
- The payment's history: `SUBMITTED` to `SUCCEEDED`, cause `CALLBACK`. This time the callback beat the
  reconciler.
- `providerTransactionId` stayed `""`. See "Where reading the code was wrong", item 2.
- **Not observed:** whether the gateway sends an `X-Callback-Url` of its own in this stack (the URL was
  given through the scenario), and the content of the callback body as it travelled.

## Case 6: the adjustment round trip: observed

Against the escalated, `UNKNOWN` payment of case 2a, with two keys for merchant `acme`.

1. Key A: `POST /adjustments` with hypothesis `unrecorded_fee`, postings `fees:mtn-cm:XAF` +150 and
   `provider:mtn-cm:float:XAF` -150, one evidence entry and `cites` naming it. `201`, status
   `pending`. No ledger entry with the `adjustment:` prefix existed.
2. Key A: `GET /adjustments/{id}` returned the same proposal, `200`, with `proposedBy` (a key id) and
   `proposedAt`.
3. Key B: `POST /adjustments/{id}/approve`. `200`, status `approved`, a `decision` block with
   `approved: true`, `decidedBy`, `decidedAt`, `reason: ""`, and `ledgerEntryId`
   `adjustment:<id>`. The ledger then held that entry with the two postings.
4. Key A proposed a second adjustment (`201`), then tried `approve` and `reject` on it. Both were
   **`403`**, problem type `.../adjustment-self-approval`, detail "A different key must approve or
   reject it."
5. After the approval the payment was still `UNKNOWN` and still listed by
   `/actuator/escalatedPayments`.

The proposal was accepted for a payment that had never settled. The off state was **not observed**.

## Case 7: `NKAP_ADJUSTMENTS_ENABLED=true` and a second key: observed

With the variable set in the override, `GET /adjustments` answered `200` with `[]` (a `501` would mean
off). Both keys were minted with `docker compose run --rm gateway --nkap.apikey.create
--nkap.apikey.merchant=acme --nkap.apikey.label=...`; the key line on stdout was captured into a shell
variable and not printed. **Not observed:** the same call with the variable unset.

## Case 8: published images: partly observed

`docker manifest inspect` found manifests for `ghcr.io/deval123/nkap-gateway` and
`ghcr.io/deval123/nkap-simulator`, both at tag `2.1.0` and at `latest`. **Not observed:** running them.
Nkap's changelog lists adjustments under `[Unreleased]` rather than under `2.1.0`, so the published
images should not carry that feature; that is read from the changelog, not tested.

## Plan statements not observed

- That `backoff-max` below `backoff-base` makes startup fail.
- That `key-init` creates its fixed demo key. This run minted its own keys.
- That the demo sets no `NKAP_PUBLIC_BASE_URL` and so sends no callback URL (read in `compose.yaml`).
- That the simulator's callback body carries a `financialTransactionId` derived from the reference.
