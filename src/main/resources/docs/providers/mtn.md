# MTN MoMo — what the API actually does

Facts observed against the sandbox on 2026-09-08, not copied from documentation. Where the
two disagree, this file wins and says so. The design decisions that follow from these facts
are in [ADR 0004](../adr/0004-mtn-adapter.md).

Keep this file honest and specific. An operator's undocumented quirks are worth more to a
contributor than the adapter's code, which they can read.

The only other operator page today is
[`docs/providers/orange-money.md`](orange-money.md) — read, not implemented, and worth
reading first for the contrast: everything on that page is *assumed*, more weakly sourced
than anything here, because nobody on this project holds an Orange developer account. This
file's observed/assumed discipline is what makes that admission meaningful instead of a
formality.

## Getting credentials

Each country has its own developer portal with its own user store: an account on one does
not exist on another. The generic sandbox lives at `momodeveloper.mtn.com`; a country
portal such as `momodeveloper.mtn.co.rw` is a separate installation with separate
credentials.

Products are subscribed to separately — Collections, Disbursements, Remittances — so each
carries its own subscription key. A subscription name is a label for the developer's own
benefit; it is never sent and never validated.

Sandbox provisioning, once a Collections subscription exists:

```
POST /v1_0/apiuser                      X-Reference-Id: <uuid you choose>  → 201
POST /v1_0/apiuser/{X-Reference-Id}/apikey                                 → 201 {"apiKey": …}
POST /collection/token/                 Basic auth: apiUser:apiKey         → 200 {"access_token", "expires_in": 3600}
```

The UUID you send as `X-Reference-Id` when creating the user *is* the API user id from then
on. `providerCallbackHost` is fixed at creation and cannot be changed; it is an allow-list,
and a later `X-Callback-Url` outside it is rejected with `INVALID_CALLBACK_URL_HOST`.

## Multiple countries in one deployment

Issue #82: one deployment configures several country installations at once, under
`nkap.provider.mtn.installations` — each entry is everything above (its own portal, its own
subscription keys, its own currency) plus the `country` field that names it. A country
installation is registered as `mtn-<country>` (`ProviderId`), derived from that field, never
configured separately — see `MtnConfiguration`. `POST /payments` names the country it wants;
an unconfigured one is a `400` naming what is configured, not a server error.

**Configured in this repository's demo and default reference deployment:**

| Country | `ProviderId` | Currency |
| --- | --- | --- |
| Cameroon | `mtn-cm` | XAF |
| Ghana | `mtn-gh` | GHS |

**Merely possible — MTN operates there, `Currency` has the right minor-unit entry, but no
installation is configured here**: Benin, Republic of Congo, Côte d'Ivoire, Guinea,
Guinea-Bissau, Liberia, Nigeria, Rwanda, South Africa, Uganda, Zambia, and others MTN's own
footprint covers. `Currency` is the gateway's vocabulary, kept in `core` and answering only
"can this gateway count in it, with its minor units verified" — this page is the separate
record of what MTN coverage this adapter configures. The two lists overlap, since most of
what MTN settles in has to be countable, but neither defines the other: `Currency` also
holds members no MTN installation will ever use (`EUR`, `USD`, `KES` — Kenya is Safaricom's
M-Pesa, not an MTN market), and a country appearing below is not itself a claim that
`Currency` was extended *for* it. A `Currency` member is not a claim that the country
works — it is only ever a claim that, if an installation for that country were configured,
its minor-unit count would be right. Wiring one in is adding an entry to
`nkap.provider.mtn.installations` with its own credentials; nothing about the adapter, the
ledger accounts (per installation — see
[ADR 0009](../adr/0009-accounts-are-per-installation.md)) or the conformance kit changes to
support it.

## Quirks that cost time

**A bodyless POST needs an explicit `Content-Length: 0`.** Both the API-key call and the
token call carry no body, and the gateway answers **HTTP 411 Length Required** — with an
HTML error page, not JSON — when the header is absent. Some HTTP clients send
`Transfer-Encoding: chunked` instead, which is refused just the same. This affects the token
call, the most frequent call the adapter makes.

**Error bodies are `{"message": …, "code": …}`.** Map on `code`. `message` is prose for a
human and must never be parsed.

**Response fields are conditional.** A `PENDING` status returns:

```json
{"externalId":"probe-1","amount":"5000","currency":"EUR",
 "payer":{"partyIdType":"MSISDN","partyId":"46733123453"},
 "payerMessage":"…","payeeNote":"…","status":"PENDING"}
```

No `financialTransactionId`, no `reason` — those appear only once the payment settles. An
adapter that requires them fails on every pending payment, which is most of them.

**A 202 really is empty.** `Content-Length: 0`, no body at all. The outcome is only ever
available through the query.

**The sandbox settles in EUR** whatever country you think you are testing. `MtnSandboxIT`
defaults to MSISDN `56733123453`, which settled quickly enough for a manual test to poll for
it; override it with `46733123453` — this test's own default before issue #117, and not one
of MTN's published numbers — and it instead stays `PENDING` for well over three seconds,
reaching a terminal state only by callback, roughly three minutes later (see *MTN's published
test MSISDNs, and what they actually answer* below for the two numbers easy to confuse with
it). A test that submits and asserts immediately, with no window for the operator to answer,
fails for reasons that have nothing to do with the code under test — `MtnSandboxIT` itself
polls rather than assumes.

## Status and error mapping

`MtnStatusMap` (package-private, `provider-mtn`) is the single place every MTN status and
error code becomes a `PaymentState`. The table below is a copy for reading, not a second
source of truth: `MtnStatusMappingDocTest`, beside `MtnStatusMap`, reads this file and
asserts the two agree in both directions — every code in the map has a row here, every row
here names a real code, and the state in the row is the state the map actually returns.
Change one without the other and the build fails.

**The table is closed, and anything absent is `UNKNOWN`.** A code MTN has not documented, a
typo, or a new one added to their API tomorrow is not a failure this gateway can assert —
only `UNKNOWN`. This is not merely intended: the conformance kit holds it as a rule every
adapter must pass (issue #80, `ProviderAdapterConformanceTest.an_unrecognised_answer_is_unknown`),
so an adapter that mapped an unrecognised code to `FAILED` would fail its own kit, not just
disagree with a comment.

**Both `status` and `reason` are read, and the more conclusive one wins.**
`MtnStatusMap.stateFor` does not pick one field over the other: it reads both, and whichever
names a *conclusive* verdict — `SUCCEEDED`, `FAILED` or `EXPIRED` — is the answer. `PENDING`
is deliberately not conclusive for this purpose: it is a real answer, but never a final one,
so a lone `PENDING` with no `reason` at all is still `PENDING`, but a `PENDING` paired with
anything else — even something as inconclusive as `SERVICE_UNAVAILABLE` — is not trustworthy
enough to stand on its own, and the answer is `UNKNOWN`. When both fields name a conclusive
verdict and they disagree, the more specific one wins: `status: FAILED, reason: EXPIRED`
stays `EXPIRED`, because `EXPIRED` is a sharper answer than a bare `FAILED`. When both name a
conclusive verdict and they flatly contradict each other — one says the payment succeeded,
the other says it did not — neither wins: `UNKNOWN`, because a contradiction is not a verdict
this gateway can assert. A code neither field's table has an entry for makes the whole answer
`UNKNOWN` too, even when the other field names a conclusive verdict. `errorCode` is the last
resort, consulted only when `status` and `reason` are both absent.

Until issue #115, a query or callback reporting `status: FAILED, reason:
SERVICE_UNAVAILABLE` came out `UNKNOWN`, not `FAILED` — the least obvious behaviour in this
adapter, because the old rule read `reason` first and stopped there. `reason` alone
genuinely says nothing about where the money went, but a `status` MTN did supply is not
*nothing* just because `reason` had nothing to add to it; that pair is now `FAILED`.

| Code | Appears in | Nkap records | Documented by MTN |
| --- | --- | --- | --- |
| `SUCCESSFUL` | `status` | `SUCCEEDED` | yes |
| `PENDING` | `status` | `PENDING` | yes |
| `CREATED` | `status` | `PENDING` | no |
| `EXPIRED` | `status` | `EXPIRED` | yes |
| `FAILED` | `status` | `FAILED` | no |
| `PAYER_NOT_FOUND` | `reason` | `FAILED` | yes |
| `PAYEE_NOT_FOUND` | `reason` | `FAILED` | yes |
| `NOT_ENOUGH_FUNDS` | `reason` | `FAILED` | yes |
| `PAYER_LIMIT_REACHED` | `reason` | `FAILED` | yes |
| `APPROVAL_REJECTED` | `reason` | `FAILED` | yes |
| `INVALID_CURRENCY` | `reason` | `FAILED` | yes |
| `NOT_ALLOWED` | `reason` | `FAILED` | yes |
| `INVALID_CALLBACK_URL_HOST` | `reason` | `FAILED` | yes |
| `SERVICE_UNAVAILABLE` | `reason` | `UNKNOWN` | yes |
| `INTERNAL_PROCESSING_ERROR` | `reason` | `UNKNOWN` | yes |
| `RESOURCE_NOT_FOUND` | error body `code`¹ | `UNKNOWN` | yes |

¹ This column names where MTN's own vocabulary places the code, not where `stateFor` reads
it from for this row. `MtnCollectionsAdapter`/`MtnDisbursementsAdapter`'s query handling maps
any `404` to `UNKNOWN` unconditionally, without calling `stateFor` and without inspecting the
body's `code` at all. That is deliberate, not an unwired lookup: see *Three namespaces, one
flat map* below for why routing it through the table would be a regression, not a tidy-up.

**Three rows carry the weight, and a reader will not believe them until they are argued.**
`SERVICE_UNAVAILABLE` and `INTERNAL_PROCESSING_ERROR` are the operator saying *its own*
system broke — which says nothing about where the money went, so `UNKNOWN`, not `FAILED`.
`RESOURCE_NOT_FOUND` on a query means MTN has never seen the reference; since Nkap persists
it before calling, that is either "never arrived" or "not visible yet", and one response
cannot tell them apart — the reconciler does, after its window (see *Observed responses*
below for the fuller argument, and `MtnStatusMap`'s own javadoc for the same reasoning next
to the code it defends).

**`FAILED` is in the map and not documented by MTN — that asymmetry is deliberate, not an
omission.** ADR 0004 documents the fourteen codes marked "yes" above; a bare `FAILED` status
with no recognised `reason` is Nkap's own interpretation, not an MTN vocabulary entry — MTN's
own documentation never lists `FAILED` as a value of the `reason` field, only as the `status`
value that carries one of the other codes. Treating an unadorned `FAILED` as `FAILED` rather
than `UNKNOWN` is a narrow, deliberate exception to "anything absent is `UNKNOWN`": the
operator's `status` field itself is never absent or unrecognised here, only its `reason` is,
and a `status` of literally `FAILED` is already the verdict — there is nothing left for a
missing `reason` to cast doubt on.

**`CREATED` is in the map and not documented by MTN either — same asymmetry, a different
reason.** MTN's own *Pending* test MSISDN, `46733123454`, answers `status: CREATED` and had
not moved after forty seconds (see *MTN's published test MSISDNs* below); `CREATED` itself is
absent from ADR 0004's fourteen documented codes. The row rests on one observation, against
one MSISDN, in one sandbox run — enough by this project's own standard, since an observed
code is no longer license to fall back on issue #80's `UNKNOWN` rule, but the reason it is
recorded as a comment on `MtnStatusMap.TABLE` rather than folded silently into the table.
`PENDING`, not `UNKNOWN`, because both are non-terminal and both get reconciled either way —
the choice is about what a merchant is told ("the operator has it and has not acted"), not
about escalation or the ledger — and it costs nothing extra: mapping `CREATED` to `PENDING`
enrols it in `PENDING`'s own non-conclusiveness rule for free, so `status: CREATED` alone is
`PENDING` but `status: CREATED, reason: SERVICE_UNAVAILABLE` is `UNKNOWN`, exactly as it would
be for a literal `PENDING`. `MtnStatusMapTest` pins both cases deliberately.

**A deployment can now send `X-Callback-Url`, which makes this row reachable — and makes it
the other half of an operational commitment, not just MTN's vocabulary.**
`PaymentIntent.providerOptions().get("callbackUrl")` is filled from `nkap.public-base-url`
(`docs/configuration-reference.md`), a deployment-level setting composing
`<public-base-url>/callbacks/<providerId>/<reference>` for every real submission, to every
provider alike (issue #185; `<public-base-url>/callbacks/<providerId>` before it, issue
#116). Setting it means `providerCallbackHost` must already name that same host at API-user
creation (*`providerCallbackHost` is an allow-list, not a destination*, below) — a mismatch
between the two is exactly this row, a real payment failing on a configuration error rather
than an operator refusal. Confirmed end to end against the shorter, provider-only shape: see
*A callback reaches the gateway* under *Callbacks* below.

**Whether MTN's allow-list constrains only the host, or the host and something about the path
too, was run against the real sandbox on 2026-09-22, ~06:48 UTC, and confirmed: host only.**
Two `requesttopay` Collections submissions, straight in `curl`, bypassing the gateway,
identical except for the `X-Callback-Url` path — MSISDN `46733123453`, 100 EUR, the recorder
behind a Cloudflare quick tunnel, the API user pinned to that tunnel's host:

| | `X-Callback-Url` path | `requesttopay` | callback received | on path |
| --- | --- | --- | --- | --- |
| control | `/callbacks/mtn-cm` | `202` | `06:50:52Z` | `/callbacks/mtn-cm` |
| probe | `/callbacks/mtn-cm/0ce67dde-4ce1-4faa-8b0f-e096f8068599` | `202` | `06:50:21Z` | `/callbacks/mtn-cm/0ce67dde-4ce1-4faa-8b0f-e096f8068599` |

Both submissions were accepted, both callbacks were delivered, and each arrived on the exact
path submitted — the extra segment was neither rejected at submission nor trimmed on
delivery. Both callback bodies carried `externalId` and no `referenceId`, as the September
run above already found; both payments ended `FAILED`/`EXPIRED`, which has no bearing on
either question and is noted only so a reader does not wonder whether it did.

**The limits of this run, stated in the same breath rather than left for a reader to
assume past them:** one sample per shape, Collections only, sandbox only, and exactly one
extra path segment — a `ReferenceId`, nothing longer. Nothing here says anything about
`/disbursement/v1_0/transfer`, about a path carrying more than one extra segment, about a
query string, or about production MTN. Read this as "one extra segment survived once against
Collections sandbox," not as "MTN preserves any path."

### Three namespaces, one flat map

The table is one flat map, but its keys come from three different namespaces — the "Appears
in" column above: `status` values (`SUCCESSFUL`, `PENDING`, `FAILED`, `EXPIRED`), `reason`
values, and the `code` field of a non-200 error body. Flattening them into one
`Map<String, PaymentState>` works because the tokens happen to be disjoint across MTN's
documented vocabulary, and it is what makes `stateFor` a one-liner — but it is an assumption,
not a guarantee, and nothing checks that a future code MTN adds to one namespace does not
collide with an existing one in another.

**The error-body-`code` namespace is not wired into `stateFor` the way `status` and `reason`
are, and that is not a gap — it is a stronger rule than the table can express.** Every call
to `stateFor` in `MtnCollectionsAdapter` and `MtnDisbursementsAdapter` passes an empty string
for that argument, and the one place a real `code` would naturally reach it — the query
path's `404` branch — never calls `stateFor` at all. It returns `UNKNOWN` unconditionally,
on purpose: a `404` moments after submission is indistinguishable from "not visible yet",
and the adapter's own comment calls this "the least intuitive rule in the adapter" (see
*Observed responses* below). Routing that branch through `stateFor` would let a `404` whose
body happened to carry a recognised `FAILED` reason code — say, `NOT_ENOUGH_FUNDS` — come out
`FAILED` instead of `UNKNOWN`, which is exactly the softening that rule exists to prevent:
this project does not treat "the answer looks like a failure" as license to skip the
one-response-cannot-tell-them-apart argument. `RESOURCE_NOT_FOUND`'s row in the table above
is real, and records the mapping ADR 0004 documents for that code, and
`MtnStatusMapTest.the_table_covers_every_documented_code` asserts it via `MtnStatusMap.isKnown`
— but the row is not what runs for a `404`, and consulting it there would be a regression,
not a tidy-up. A *second* error-body code, one no unconditional branch already overrides,
would be the first to actually reach `stateFor` through this namespace — worth remembering
before assuming this one already proves the wiring works.

## Observed responses

| Call | Result |
| --- | --- |
| `POST /collection/v1_0/requesttopay` | `202`, empty body |
| same `X-Reference-Id` again | `409` `{"code":"RESOURCE_ALREADY_EXIST"}` — a previous attempt reached MTN; not an error |
| `GET /collection/v1_0/requesttopay/{ref}` | `200` with the payload above |
| `GET` on a reference never submitted | `404` `{"code":"RESOURCE_NOT_FOUND"}` |
| `GET /collection/v1_0/requesttopay/{ref}` for a submission to MSISDN `46733123450` | `status: FAILED, reason: INTERNAL_PROCESSING_ERROR` — on every query, over 36 hours |
| `POST /v1_0/apiuser/{id}/apikey` with no `Content-Length` | `411 Length Required` — needs an explicit `Content-Length: 0`; `curl -X POST` with no data does not send one |

Every row above was actually seen against the sandbox. **A caution worth stating plainly: an
observation of Nkap's own logs is an observation of Nkap, not of the operator** — this table
only ever holds what MTN itself said, never what Nkap made of it afterwards. What a `400` on
submission means is
not: no real MTN account has produced one yet, so it stays here as **assumed**, from ADR
0004's documented vocabulary and `MtnCollectionsAdapter.submit`'s own handling, not from
observation — the same distinction the *Still unknown* section keeps elsewhere. `submit`
treats any `400` as an outright refusal: no payment exists under this reference, the body's
`code` is meant to be one of the `FAILED` reason codes from the mapping above, and the
gateway records `CREATED → FAILED` directly rather than calling `query()` at all. Whether a
real `400` body actually carries one of those codes, or something else entirely, is exactly
the kind of thing worth confirming against a real account and moving up out of *assumed* once
someone has.

**A query on a reference MTN never saw is `UNKNOWN`, not `FAILED` — and it stays `UNKNOWN`
forever if MTN genuinely never received it.** `MtnCollectionsAdapter`/
`MtnDisbursementsAdapter` map this `404` the same way for both products: not as a verdict,
because a `404` moments after submission is indistinguishable from "the request has not
propagated to whichever node answers reads yet" (see the code's own comment — "the least
intuitive rule in the adapter"). There is no separate signal for "never existed" versus "not
visible yet", so this project's rule — a timeout is never a failure — is applied here too.
This matters for issue #84's refunds: a refund payment stranded in `CREATED` (the reservation
committed, but the process was killed before the transfer was ever submitted) is swept to
`UNKNOWN` after a grace period and queried like anything else. If MTN truly never received
it, every query comes back `RESOURCE_NOT_FOUND` → `UNKNOWN`, forever — it does **not**
self-heal to `FAILED`. It escalates once its window is spent, the same as any other stuck
payment, and a human resolves it by hand once they have confirmed with MTN that the transfer
never happened.

### MTN's published test MSISDNs, and what they actually answer

MTN's developer documentation publishes a table of test MSISDNs said to produce determined
outcomes — Failed, Rejected, Timeout, Success, Pending. This page did not reproduce it
verbatim, out of caution rather than confidence: the "Success" entry carries a different
leading pair of digits from the other four, and `MtnSandboxIT`'s own default MSISDN,
`46733123453`, appears in neither list. Both turned out to matter.

Every published number has now been exercised directly against MTN, alongside
`MtnSandboxIT`'s undocumented default. All six, in one table — MTN's own label is given only
to show that its table was directionally right, not as a source for anything else here:

| MSISDN | MTN's label | Status query answered |
| --- | --- | --- |
| `56733123453` | *Success* | `SUCCESSFUL`, `financialTransactionId` present, no `reason`, under 10s |
| `46733123451` | *Rejected* | `FAILED` / `APPROVAL_REJECTED`, under 10s |
| `46733123452` | *Timeout* | `FAILED` / `EXPIRED`, under 10s |
| `46733123450` | *Failed* | `FAILED` / `INTERNAL_PROCESSING_ERROR`, under 10s |
| `46733123454` | *Pending* | `CREATED`, still `CREATED` at 40 seconds |
| `46733123453` | *(not published — `MtnSandboxIT`'s default)* | `PENDING` at 10s and 40s, then `FAILED`/`EXPIRED` by callback |

What this gateway makes of each answer is not repeated here — see *Status and error mapping*
above for the mapping, and `MtnStatusMappingDocTest` for the test that keeps it honest against
the code.

**Every timing above is a single sample, not a measurement.** Each row is one run against
that MSISDN — two runs for `46733123450` and `46733123451` — and "under 10s" means only that
the first status query, made at the ten-second mark, already carried that answer. Nothing
here was timed, and none of it is a latency characteristic.

**The leading-pair discrepancy this page declined to resolve was real, and both numbers
exist.** `56733123453` — the published *Success* entry, the digit this page would not guess
was a typo — answers `SUCCESSFUL`. `46733123453` is a different, undocumented number: it is
`MtnSandboxIT`'s own default, it expires rather than succeeds, and it is not MTN's *Success*
case. The caution was right, and the question it left open is now closed by observation, not
by adopting MTN's table.

**A `SUCCESSFUL` carries `financialTransactionId` and no `reason`** — the field-by-field
shape this page has been listing as unknown. `600198217` in this run: a short numeric id, not
a UUID.

**`CREATED` is a real MTN status, and issue #117 adds it to `MtnStatusMap`'s table.**
`46733123454` answers `status: CREATED` and had not moved after 40 seconds. Falling to
`UNKNOWN` was issue #80's rule working exactly as intended, not a defect — but the code is no
longer unrecognised now that it has been observed, and `PENDING` describes it precisely: the
operator has accepted the payment and has not yet acted on it. See *Status and error mapping*
above for the row and the full reasoning.

**A callback can carry a verdict the status endpoint was still withholding.** `46733123453`
answered `PENDING` twice, then announced `FAILED`/`EXPIRED` by callback before the status
endpoint caught up. For this MSISDN the callback was the first conclusive word — see
*Callbacks* below for what that callback looked like.

### `46733123450` is settled, and it is neither reading this page carried before

This page used to carry two open readings of `46733123450`: that the sandbox's status
endpoint is unreliable for it, or that the documented *Failed* verdict arrives only by
callback. **Neither is right.** The status endpoint answers, immediately and conclusively:

```
status  FAILED
reason  INTERNAL_PROCESSING_ERROR
```

MTN's published table is correct: `46733123450` *is* the *Failed* case, it says so in
`status`, on the first query, and the callback repeats the same pair.

What turned that verdict into thirty-six hours of `UNKNOWN`, six reconciler attempts and an
escalation was `MtnStatusMap.stateFor` reading only `reason`: `INTERNAL_PROCESSING_ERROR`
maps to `UNKNOWN`, and the terminal `status` was never consulted. That was right when `reason`
arrives *instead of* a status, because the operator saying its own system failed says nothing
about where the money went — but it was wrong here, because MTN had also supplied a real
verdict in `status`, and the old rule discarded it.

**The reading that discarded this verdict is corrected by issue #115.** `stateFor` now reads
both `status` and `reason` rather than picking one; see *Status and error mapping* above for
the current rule, and `MtnStatusMapTest` for the pair `status: FAILED, reason:
INTERNAL_PROCESSING_ERROR` returning `FAILED` from the map directly.

**Confirmed end to end, 2026-09-17.** One payment, submitted through the gateway —
`POST /payments`, not `curl` against MTN — to MSISDN `46733123450`, `100 EUR`, installation
`mtn-cm`. Its history, as `GET /payments/{reference}` returned it:

```json
{"from":"CREATED","to":"SUBMITTED","at":"2026-09-17T17:53:41.869732Z",
 "cause":"SUBMIT_RESPONSE","operatorCode":""}
{"from":"SUBMITTED","to":"FAILED","at":"2026-09-17T17:53:48.830327Z",
 "cause":"RECONCILER","operatorCode":"INTERNAL_PROCESSING_ERROR"}
```

Reference `76874635-e92a-41b4-b13a-f3b3522a3c1c`. Resolved on the reconciler's first pass —
where the old behaviour spent six attempts and then escalated. The attribution is the
evidence, not the state: `cause: RECONCILER` with `operatorCode: INTERNAL_PROCESSING_ERROR`
is the exact pair that used to be discarded, now named on the transition that settled it —
that is what makes this a confirmation of #115, not merely a payment that happened to fail.

The outbox produced an event for this terminal transition (`OutboxRelay` logged delivery
attempts) — for thirty-six hours the old behaviour produced none, because the payment never
went terminal. Delivery failed in this run because the registered endpoint belonged to the
demo stack and nothing was listening; that is a fact about the demo endpoint, not about the
fix.

This confirms the fix as it stands on `main`, run on a stack built from the working tree, not
a published image.

### What the reconciler did while `stateFor` discarded a conclusive verdict

A payment submitted for `46733123450` was accepted (`202`, `CREATED → SUBMITTED`). MTN
answered every subsequent status query with `status: FAILED, reason:
INTERNAL_PROCESSING_ERROR` — a terminal verdict, every time. `MtnStatusMap.stateFor`, as it
stood then, read `reason` first and returned `UNKNOWN` — not because the operator was
unclear, but because the rule never looked at `status` once `reason` matched. The reconciler
did exactly what `UNKNOWN` tells it to do: chase the payment. Observed in Nkap's own logs:

```
09:26:24  RECONCILER … not conclusive (INTERNAL_PROCESSING_ERROR), changing nothing
09:27:24  RECONCILER … not conclusive (INTERNAL_PROCESSING_ERROR), changing nothing
09:29:24  RECONCILER … not conclusive (INTERNAL_PROCESSING_ERROR), changing nothing
09:33:25  RECONCILER … not conclusive (INTERNAL_PROCESSING_ERROR), changing nothing
09:41:25  RECONCILER … not conclusive (INTERNAL_PROCESSING_ERROR), changing nothing
…
escalated to a human after 6 reconciler attempt(s); operator's last answer: INTERNAL_PROCESSING_ERROR
```

Six attempts on an exponential backoff — one minute, two, four, eight, and onward — then,
once the window was spent, an escalation. **This is not the founding rule working as
intended.** MTN did not time out and it did not answer inconclusively; it answered `FAILED`
on the first query and every one after. What happened is that `stateFor` threw the verdict
away before the reconciler ever saw it, so the reconciler chased an answer that had already
been given. **The map-level defect is corrected by issue #115** — see *Status and error
mapping* above, and *`46733123450` is settled, and it is neither reading this page carried
before* for the end-to-end confirmation: the reconciler's first pass now resolves it, not a
sixth attempt and an escalation.

What the run does show correctly, and worth keeping:

- **The reconciler's schedule survives a process restart.** The stack was down for
  roughly thirty-six hours between the fifth attempt and the sixth. The attempt count and
  the retry window are columns on the payment, not state held in memory, so the sixth
  attempt escalated correctly rather than starting the count over.
- **A discarded verdict leaves no trace on the payment.** `updatedAt` stayed at the
  millisecond of submission throughout, across all six attempts — not because MTN's answer
  was unclear, but because `stateFor` returned `UNKNOWN` every time and `UNKNOWN` writes
  nothing. A payment actively being chased is, through `GET /payments/{reference}`,
  indistinguishable from one nobody has looked at since it was created. Issue #113 tracks
  that gap, though its own worked example — this payment — is one that should never have
  needed chasing at all.

### What the simulator answers with

The simulator errors in this same shape everywhere, so an adapter driven against it can
always read a `code` — an empty error body would be a fiction no real operator produces
(issue #26). Only the two rows above are observed; the rest it chooses:

| Simulator case | Response |
| --- | --- |
| reused `X-Reference-Id` | `409` `RESOURCE_ALREADY_EXIST` — observed |
| `GET` on an unknown reference | `404` `RESOURCE_NOT_FOUND` — observed |
| missing or malformed `X-Reference-Id` | `400` `INVALID_REFERENCE_ID` — **not an MTN code**, see *Still unknown* |
| scenario `onSubmit.outcome: CONFLICT` | `409` `RESOURCE_ALREADY_EXIST` |
| scenario `onSubmit.outcome: BAD_REQUEST` | `400` `NOT_ALLOWED` — documented vocabulary (ADR 0004), chosen as a default |
| scenario `onSubmit.outcome: SERVER_ERROR` | `500` `INTERNAL_PROCESSING_ERROR` — documented vocabulary, chosen as a default |

A scenario that needs a particular code declares one rather than relying on those defaults:
`"onSubmit":{"outcome":"BAD_REQUEST","code":"INVALID_CURRENCY"}`. The field is optional and
the status still comes from the outcome. `ACCEPT` and `NO_RESPONSE` are untouched by this —
a 202 really is empty, and a non-answer really is nothing at all.

The `/_nkap/` control plane deliberately keeps its own error shape: it does not imitate MTN,
and a contributor who mistyped a scenario is better served by a 400 naming the field.

**The simulator never sends `financialTransactionId`, on any status, scripted or not** —
`RequestToPayController.status` (and its disbursement equivalent) answers with only
`{status, reason}`. Real MTN sends it once a payment settles (see *Quirks that cost time*
above). The consequence a reader hits directly: `GET /payments/{reference}` and every webhook
for a payment settled against the simulator carry `providerTransactionId: ""`, even for
`SUCCEEDED` — not a Nkap bug, and not something the simulator was asked to model here.

### Provenance: telling a simulated settlement from a real one

That blank `providerTransactionId` is not the tell it looks like — it is also what a real
operator returns for a payment that failed, so a signal indistinguishable from a legitimate
empty value is not a signal (issue #122). `provider_id` doesn't help either: it is derived
from the installation's country (#82's routing), so `mtn-cm` is written whether that
installation's `base-url` points at `sandbox.momodeveloper.mtn.com` or at
`http://simulator:8081` standing in for it. Two payments settled by different programs, same
MSISDN, same `mtn-cm`, were byte-identical in the database on everything provenance-shaped.

`payment.provider_base_url` (migration V9) records the installation's own base URL at the
moment a payment is created — self-describing, and unlike a deployment-level label such as
`nkap.environment: production`, it cannot lie about itself. Set once, from configuration,
never from a caller, and never again afterward: a database trigger refuses any `UPDATE` that
touches the column once a row exists. It is not returned by `GET /payments/{reference}` —
information about which endpoint this deployment talks to is about the deployment, not the
payment, the same reasoning that keeps the management port off the public API.

Every payment recorded before this migration carries `NULL`, and nothing backfills one: the
database this was found on already held simulator and real-operator payments side by side
with no way to tell them apart after the fact, and writing a plausible guess onto either kind
now would be the exact false statement this issue exists to remove — undetectable once
written. `NULL` means "predates provenance tracking," which is true, not a guess dressed up
as an answer.

## Callbacks

One run against the real sandbox, 2026-09-16, from a `cloudflared` quick tunnel in front of a
catch-all recorder, using a second API user created for the occasion and pinned to the
tunnel's hostname. Five submissions, made **directly to MTN in `curl`, bypassing the
gateway** — nothing in this section was exercised through Nkap itself; where it says
something about the gateway's own behaviour, that is inference from code the adapter's
source can be checked against, not observation of Nkap in motion.

### `providerCallbackHost` is an allow-list, not a destination

Each submission carried, or omitted, an explicit `X-Callback-Url` pointed at the tunnel;
everything else about the submission was identical:

| `externalId` suffix | MSISDN | `X-Callback-Url` sent | Callback received |
| --- | --- | --- | --- |
| `T215608Z` | `46733123453` | no | **none** |
| `T215701Z` | `46733123453` | yes | yes |
| `T220019Z` | `46733123451` | yes | **yes, twice** |
| `T220110Z` | `46733123451` | no | **none** |
| `T220218Z` | `46733123450` | yes | yes |

Three submissions carried the header; all three produced a callback. Two omitted it; neither
did. **Registering `providerCallbackHost` on the API user is not enough on its own — it only
constrains where a supplied `X-Callback-Url` is allowed to point.** Omitting the header
produces no callback at all: not a rejection, not a delayed delivery, nothing.

### What a real MTN callback looks like

The first one this project has ever received. Headers, verbatim, with the tunnel's own
(`Cf-*`, `X-Forwarded-*`, `Cdn-Loop`) removed as artefacts of Cloudflare rather than MTN:

```
POST /callbacks/mtn-cm
Host: <the host registered as providerCallbackHost>
User-Agent: LWAC Http Client 1.0
Content-Type: application/json; charset=utf-8
Content-Length: 212
Accept-Encoding: gzip
Connection: keep-alive
```

Body:

```json
{"externalId":"…","amount":"100","currency":"EUR",
 "payer":{"partyIdType":"MSISDN","partyId":"46733123451"},
 "payeeNote":"…","status":"FAILED","reason":"APPROVAL_REJECTED"}
```

Three facts settle a design question this project had to answer without evidence until now:

- **There is no signature and no credential of any kind.** No `Authorization`, no HMAC
  header, nothing MTN-specific — anyone who learns the URL can post to it.
  `docs/positioning.md`'s "the callback endpoint stays unauthenticated, on purpose" argument
  is now backed by an observation, not only a design intent.
- **The body carries no `referenceId`, only `externalId`.** Observed directly — but this
  callback came from a `curl` submission whose `externalId` the test script made up itself,
  never through the gateway, so this run alone said nothing about what
  `MtnCollectionsAdapter.parseCallback` does with a body shaped like this. The reading was:
  `parseCallback` reads `referenceId` first and falls back to `externalId`
  (`firstNonBlank(referenceId, externalId)`), and `submit` sets outgoing `externalId` to
  Nkap's own reference, so this shape should round-trip. *A callback reaches the gateway*
  below confirms the round-trip itself: a payment submitted through `POST /payments` settled
  by `CALLBACK`, which cannot happen unless `parseCallback` resolved that callback back to
  the payment's own reference. It does **not** confirm which field carried the match —
  whether MTN echoed a `referenceId` this time, or the fallback to `externalId` is what
  actually ran — because the callback's body was never logged (issue #129) and so was never
  recorded. See *Still unknown*.
- **MTN retries a delivery the receiver already answered `200` for.** See below.

### Callbacks are retried

The `46733123451` callback above was delivered **twice**, at `22:00:51` and `22:03:50` UTC —
the same body, three minutes apart — with no acknowledgement problem at the recorder, which
answered `200` both times. A receiver that treats a callback as a one-shot event is wrong
about this operator; `SettlementService`'s confirm-by-query design already tolerates a
repeat, since a duplicate is exactly what it is built to absorb.

One further delivery attempt was cut short before it reached the recorder, which was
single-threaded and still busy handling the first. So **one repeat is a floor, not a
count**: at least one attempt was lost to the recorder's own limitation, not to MTN, and
nothing here establishes MTN's retry ceiling or schedule.

### A callback reaches the gateway

2026-09-17, a `cloudflared` tunnel in front of the gateway itself — not a recorder this time —
with a second MTN API user created for the occasion, `providerCallbackHost` pinned to the
tunnel's hostname, and `nkap.public-base-url` set to it (issue #116). One payment, submitted
through `POST /payments` to MSISDN `46733123451`, `100 EUR`, installation `mtn-cm`.

The payment's history, as `GET /payments/{reference}` returned it:

```json
{"from":"CREATED","to":"SUBMITTED","cause":"SUBMIT_RESPONSE","operatorCode":""}
{"from":"SUBMITTED","to":"FAILED","cause":"CALLBACK","operatorCode":"APPROVAL_REJECTED"}
```

Reference `cae20264-012b-49c7-b12a-3dff5fa00e1e`. Terminal within fifteen seconds of
submission — one run, not a latency characteristic, the same caveat *Every timing above is a
single sample* makes for the six-MSISDN table.

**The attribution is the finding, and one detail makes it unambiguous.** The reconciler for
this run was configured with a two-minute interval, so it had not run even once in those
fifteen seconds. `cause: CALLBACK` is the only way this payment could have settled: a callback
reached `CallbackController`, `parseCallback` resolved it to this reference,
`SettlementService` confirmed it by querying MTN, and only then was the transition written.
This is the first callback any deployment of this gateway has received — the receiving half
has been complete and unreachable since before 1.0.0.

**`docker compose logs gateway | grep -i callback` returns nothing.** The callback arrived,
was confirmed by a query to MTN, and settled a payment, and the gateway logged not one word
of any of it (issue #129). The confirmation above is entirely from the payment's own history,
not from logs — a reader reproducing this should not go looking for a log line that is not
there, and neither the callback's body nor which field it carried the reference in was ever
recorded anywhere.

This ran against the fix as it stands on `main`, on a stack built from the working tree, not
a published image. The tunnel's hostname is gone — quick tunnels are ephemeral — so the API
user created for it can never receive anything again; this specific run cannot simply be
repeated by following this page.

## Disbursements

Not observed against a real MTN — mapped from the documented shape and driven through the
simulator (issue #62). Treated as **structurally identical to Collections** until a real
call proves otherwise:

- A **separate product**: its own subscription key, API user and key, and token endpoint
  `POST /disbursement/token/`. `provider-mtn` has a whole `MtnDisbursementsAdapter` with its
  own `MtnProfile` and token cache; `MtnAdapter` is one adapter over both. In the gateway
  the credentials are each installation's own `nkap.provider.mtn.installations[n].disbursement.*`.
- `POST /disbursement/v1_0/transfer` — `202` with an empty body, `X-Reference-Id` the
  idempotency key, exactly like `requesttopay`. The body names the counterparty **`payee`**
  where a collection says `payer`. A reused reference is handled the same way collections
  narrowed it to (issue #28, applied here by issue #171): only a `409` whose body's `code` is
  `RESOURCE_ALREADY_EXIST` is acknowledged as already-submitted; every other `409`, and any
  `409` whose body cannot be read at all, is `ProviderUnavailableException` -- `UNKNOWN` and
  the reconciler, never an outright refusal, since the transfer may well have reached MTN.
- `GET /disbursement/v1_0/transfer/{ref}` — assumed to return the same
  `{status, reason, financialTransactionId}` shape; `404` `RESOURCE_NOT_FOUND` for an
  unknown reference, handled as `UNKNOWN` the same way.
- The same status/error codes and the same conservatism: an unrecognised code is `UNKNOWN`.
  A transfer refused for lack of funds is an ordinary `FAILED` with `NOT_ENOUGH_FUNDS` — the
  gateway does not predict it or hold a reserve (see
  [ADR 0007](../adr/0007-what-a-settled-disbursement-posts.md)).
- The ledger effect is the mirror of a collection (ADR 0007); the fee rule is unchanged
  (ADR 0006).

## Account balance and account-holder validation

Not observed against a real MTN — mapped from the documented shape, driven through the
simulator (issue #72). Available under both products, at their own base paths, the same
way a balance and a status query are:

- `GET /collection/v1_0/account/balance` and `GET /disbursement/v1_0/account/balance` —
  assumed to return `{"availableBalance": "<decimal>", "currency": "<code>"}`, the decimal a
  major-unit string the same shape `requesttopay`'s own `amount` field uses, just the other
  direction. The adapter converts it to an exact count of minor units or refuses — see
  `MtnCollectionsAdapter.minorUnitsFromMtnAmount`; it never rounds. A response naming a
  currency other than the one asked for is refused, not coerced.
- `GET /collection/v1_0/accountholder/msisdn/{msisdn}/active` and the `/disbursement/v1_0/`
  equivalent — assumed to return `{"result": <true|false>}`. Whether MTN actually sends a
  JSON boolean or a `"true"`/`"false"` string is unconfirmed, so the adapter reads either;
  anything else is `UNKNOWN`, the same conservatism `MtnStatusMap` applies to a payment
  status. Whether the account-holder answer genuinely differs by product, or one product's
  answer would do for both, is also unconfirmed — each product asks its own path because
  each product is a separate set of credentials, not because the two are known to disagree.
- Both are `GET`s that call the operator on every request. Neither has a reference or a
  timeline, so the simulator answers them from declared configuration (`AccountBehaviour`),
  not a `Scenario` — see ADR 0002's scope.

## Still unknown

Left open deliberately rather than guessed. Each is worth a pull request adding a line here.

- **Whether a real MTN answers `404` when a collections reference is queried on the
  disbursements path (or the mirror), and what code it carries if so.** Issue #69 gave the
  simulator that behaviour — Collections and Disbursements are documented as separate
  products (*Disbursements*, above), so treating one product's reference as unknown to the
  other is the conservative choice, not a guess dressed up as one — but it was modelled, not
  observed: no real account has ever been asked. Whether the operator agrees, and whether it
  answers `RESOURCE_NOT_FOUND` or something else, is unconfirmed.
- **Whether a real MTN transfer answers `409 RESOURCE_ALREADY_EXIST` for a reused reference
  the same way a real MTN collection does.** Issue #171 narrowed `MtnDisbursementsAdapter`'s
  409 handling to match the collections rule issue #28 observed against the sandbox — the
  conservative and almost certainly correct reading, since both products document the same
  requesttopay-shaped behaviour — but no real disbursements account has been asked, so the
  narrowing rests on an assumption, not an observation of this product. If a real transfer
  turned out to answer some other code for a reused reference, that code would fall through
  to `ProviderUnavailableException` exactly like any other unrecognised `409` — `UNKNOWN` and
  the reconciler, not a wrong "already submitted" — so the cost of the assumption being wrong
  is a slower resolution, not an incorrect one.
- **Which field a callback for a gateway-submitted payment actually carries — `referenceId`
  or only `externalId` — and therefore whether `parseCallback`'s fallback to `externalId` has
  ever been exercised against a real operator.** *A callback reaches the gateway* confirms the
  round-trip resolves, but not by which branch: the callback's body was never logged
  (issue #129), so nothing recorded which one MTN actually sent.
- **Whether a callback is ever the *only* notification**, or whether the status endpoint
  always catches up. `46733123453` announced its outcome by callback while the status
  endpoint still said `PENDING`; whether that endpoint would have reported `EXPIRED` later
  was not checked.
- **How many times MTN retries a callback, and on what schedule.** One delivery was
  repeated once after three minutes, and one further attempt was lost to the recorder rather
  than refused — so one repeat is a floor. Nothing establishes the ceiling, the interval, or
  what a non-2xx answer would change.
- **Whether `46733123454` ever leaves `CREATED`.** It had not after 40 seconds and was not
  watched longer.
- **What a real `400` on submission actually contains.** Assumed from ADR 0004's vocabulary
  and `MtnCollectionsAdapter.submit`'s own handling (*Observed responses* above); every
  submission in this run and the one before it was accepted with `202`, so no real account
  has produced a `400` yet.
- **What MTN answers to a missing or malformed `X-Reference-Id`.** The simulator answers
  `400` with `INVALID_REFERENCE_ID`, which is *its own* code: no observation records MTN's,
  and inventing one that looked documented would be worse than an obviously local name. An
  unrecognised code maps to `UNKNOWN` anyway, so nothing depends on the guess.
- **What a `FAILED` status actually contains, field by field, from the status query
  itself.** A callback carrying `status`, `reason`, `externalId`, `amount`, `currency`,
  `payer` and `payeeNote` was observed (see *Callbacks* above); whether a query response has
  the same shape, or also carries `financialTransactionId`, was not checked directly.
- Whether production returns codes absent from the documentation.
- **Whether a real Disbursements `transfer` and its status differ from what the *Disbursements*
  section above assumes** — a different response field, a code Collections does not use, a
  callback shaped differently. Mapped from documentation and the simulator only; a real call
  is the thing to check it against, and this line moves up into that section when one is made.
- **The exact shape of a real Account Balance response.** Field name, decimal format,
  whether a currency mismatch can even occur or is only a defensive check against a fact
  that never happens. Mapped from documentation only — see *Account balance and
  account-holder validation* above.
- **The exact shape of a real Account Holder response**, including whether `result` is a
  JSON boolean or a string, and any code or field this adapter would currently map to
  `UNKNOWN` for lack of a recognised shape.
- **Whether Collections and Disbursements genuinely answer an account-holder check the
  same way**, or whether one product's endpoint is the one that actually matters and the
  other is untested surface. Both are called today because both are configured products;
  neither has been proven against a real MTN account.
- **How an operator statement is obtained** — a portal download, a report API, an emailed
  file, an SFTP drop — and on what cadence. Nothing fetches one today; statement
  reconciliation is a host-side command (`--nkap.statement.import=<path>`) run against a
  file already on the machine, not a network endpoint.
- **What a statement line contains, field by field.** Statement reconciliation was built
  against an *invented* provider-neutral model (`server`'s `statement` package): per line,
  an operator transaction id, a gross amount in minor units, a fee (may be absent), a
  timestamp, and an outcome of `SETTLED` or `FAILED`. The reconciler matches on the
  transaction id — the same value a settled `ProviderStatus` carries as
  `financialTransactionId` — and compares the gross amount; a fee, when present, posts
  `DR fees:mtn:<CCY> / CR provider:mtn:float:<CCY>` per [ADR 0006](../adr/0006-what-a-settled-collection-posts.md).
  The CSV layout the placeholder parser reads (`CsvStatementParser`) is equally ours:
  `operator_transaction_id,amount_minor,fee_minor,currency,occurred_at,status`. When a real
  MTN statement is seen, this file records what it holds and the parser is the only code
  that changes — reconciliation, persistence and the report all work in the neutral model.
