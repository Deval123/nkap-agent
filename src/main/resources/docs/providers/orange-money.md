# Orange Money — read, not implemented

This page exists because of one roadmap line: *Orange Money's API documentation **read** — not
implemented — and `provider-api` reviewed against it. An adapter contract validated against a
single operator is probably wrong in a way only the second one reveals, and the first outside
contributor should not have to discover it in their first pull request.*

There is no Orange Money adapter in this repository and 1.0.0 does not plan one. What follows
is what the review found, so that whoever writes that adapter — most likely someone who is not
us — starts from what we learned rather than from nothing.

## Where this came from, and why most of it is *assumed*

`docs/providers/mtn.md` separates what was **observed** against a real sandbox from what was
**assumed** from documentation, because a guess recorded as a fact is the failure that page
exists to prevent. By that standard, the API shape below is **assumed**, and more weakly
sourced than anything in `mtn.md` — with one exception, marked the way `mtn.md` marks its own:
a fact about *access* to Orange's API, checked against Orange's own pages rather than inferred.

**Observed, read on 2026-09-18 on Orange's own developer pages — the product overview
(`om-webpay`) and its FAQ (`om-webpay/faq`), each fragment below checked verbatim against the
page it is attributed to, not taken from a summary of either.** Access to Orange's
authoritative API reference is not a developer account — it is Orange Money **merchant
status**:

- The overview: "officially registered retailers (Orange Money merchants — fully KYA
  compliant)", supplying "Trade and Personal Property Credit Register or other documents as
  per local legislation". The FAQ, in its own words: merchants must have an Orange Money
  account and must be KYA compliant.
- Testing exists, but the overview frames it as available to "merchants or their integrators
  prior to going live" — after merchant status is granted, not before it.
- There is no self-service enrolment: the overview directs a reader to "contact your local
  Orange operator to know more about the registration process and compliance rules"; the FAQ
  says the same, in different words, at more than one question.
- **The two pages name different countries, and disagree — not a detail to flatten into one
  list.** The overview: Mali, Cameroon, Côte d'Ivoire, Senegal, Madagascar, Botswana, Guinea
  Conakry, Guinea Bissau, Sierra Leone, RD Congo, Central African Republic. The FAQ: Mali,
  Cameroon, Senegal, Madagascar, Botswana, Guinea Conakry, Sierra Leone, Côte d'Ivoire, Guinea
  Bissau, **Liberia** — present on the FAQ's list and absent from the overview's; RD Congo and
  Central African Republic run the other way, on the overview's list and absent from the
  FAQ's. An operator whose own pages do not agree on where its product exists is a fact about
  the reliability of everything else stated here, not only about country coverage.

Everything else on this page is **assumed**:

- What was read is the public product portal — which describes the payer's journey — plus two
  third-party clients that really do call the API: a Java client and a PHP one. Field names
  below come from those clients, not from Orange.

Nothing here has been executed against Orange, in sandbox or otherwise. Treat every field name
as a plausible reading, and every status value as a list that may be incomplete. The *Still
unknown* section at the bottom is not a formality on this page; it is most of it.

## The shape, as far as it can be read

A merchant collection appears to be three calls:

| Call | Purpose | Fields seen in third-party clients |
| --- | --- | --- |
| `POST oauth/v2/token` | client-credentials token | `grant_type=client_credentials`, HTTP basic |
| `POST .../webpayment` | start a payment | `merchant_key`, `currency`, `order_id`, `amount`, `return_url`, `cancel_url`, `notif_url`, `lang` |
| `POST .../transactionstatus` | ask what became of it | `order_id`, `amount`, `pay_token` |

The start call answers with a payment token (`pay_token`), a **payment URL**, and a
notification token. Status values reported by one client: `INITIATED` (awaiting the payer),
`PENDING`, `EXPIRED`, `SUCCESS`, `FAILED`.

Newer Orange offers in some countries (Sonatel's QR-code / deeplink merchant payment) differ in
presentation but not in the shape that matters here: the payer is sent somewhere.

## What this means for `provider-api`

### 1. The contract cannot return what Orange gives the merchant

MTN's `requesttopay` is a **push**: the payer's handset prompts them, and the merchant gets
only an acknowledgement. Orange's documented flow is a **redirect**: the response carries a URL
(or a QR code, or a deeplink) and the payment does not happen until the payer is sent there.

`SubmitResult.Acknowledged` carries `(state, providerReference, rawResponse)`. There is no
field for something the merchant must show or send the payer — not in `SubmitResult`, not in
`ProviderStatus`, not in the HTTP API's `PaymentResponse`. An adapter for a redirect-style
operator cannot hand back the one thing without which no payment occurs.

This is not a missing field so much as a missing decision: whether Nkap carries redirect-style
operators at all. Either answer is defensible; the absence of an answer is what would cost the
first Orange contributor their first afternoon.

### 2. ADR 0008's own rule, broken twice

> If a method would have to look something up — read gateway state, consult a payment it cannot
> see — to do its job, the contract is missing an argument.

**`query`.** `transactionstatus` appears to need `order_id`, `amount` *and* `pay_token`.
`query(ReferenceId, Capability.Operation)` supplies the first only. `pay_token` is exactly what
`SubmitResult.Acknowledged.providerReference` is for and the gateway already persists it — but
`query` is never handed it. MTN never needed this, because for MTN the reference Nkap chose *is*
the key the operator is asked about. That is a property of MTN, not of operators.

**`parseCallback`.** `notif_url` and its notification token appear to be supplied **per
payment**, so verifying a notification means knowing that payment's token —
`parseCallback(RawCallback)` receives only the raw callback. This one is harder than an extra
argument: the gateway cannot know which payment a callback belongs to until something has
parsed it. It is recorded here as an open design question, not as a fix waiting to be applied.

### 3. Two things that hold, and were not guaranteed to

**The state machine absorbs Orange's statuses without change.** `INITIATED` is a `SUBMITTED`,
`PENDING` is `PENDING`, `EXPIRED` already exists, `SUCCESS` and `FAILED` map directly. A second
operator's vocabulary fitting a state machine designed against the first is worth noticing.

**A collection-only operator needs no contract change.** Orange's web payment offers neither
disbursement nor refund; an adapter would declare `COLLECT` and not `DISBURSE`. Because
`capabilities()` is a set and callers go through `operations()` (#70), nothing in the gateway or
the conformance kit assumes both exist.

## What this means for the project

This repository's premise is that the adapter for a second operator is written by someone who
is not us — the conformance kit, `docs/providers/`'s whole existence, and this page's own
opening sentence all assume that. MTN could be first because its sandbox is open to anyone with
an email address. An operator whose API cannot be seen without a registered company and a
compliance file is one that an outside contributor cannot approach, however good the contract
turns out to be.

That is a criterion for choosing a second operator this project had not written down:
**observable without a company.** It does not disqualify Orange, and this page does not say
that it does — it changes what the reader of this page has to weigh, which is exactly what the
page is for.

## Still unknown

Everything above, strictly speaking. The questions worth answering first, once someone holds
Orange Money merchant status:

- **Does `transactionstatus` really require `pay_token` and `amount`, or does `order_id` alone
  suffice?** This single answer decides how much of finding 2 survives.
- The complete status vocabulary, and what an unrecognised or absent status looks like.
- Whether there is any non-redirect collection flow — a push to the payer's handset — in any
  country, which would change finding 1 entirely.
- What a timeout on `webpayment` leaves behind: whether re-sending the same `order_id` is safe
  and idempotent, which is the equivalent of MTN's `409 RESOURCE_ALREADY_EXIST` and the single
  most important thing to know before writing an adapter.
- Whether the notification carries enough to identify the payment before its token is checked.
- Refunds and disbursements: whether any Orange product offers them, under what contract.

## Sources

Read on 2026-09-14. None of these is Orange's authoritative API reference.

- Orange Developer, *Orange Money Web Payment / M Payment (1.0)* — product overview:
  <https://developer.orange.com/apis/om-webpay>
- Orange Sonatel, *QR Code OM* — merchant collection offer:
  <https://developer.orange-sonatel.com/offers/paiement-qr-code>
- `om4j`, a third-party Java client: <https://github.com/pathus90/om4j>
- `Ibracilinks/OrangeMoney`, a third-party PHP client:
  <https://github.com/Ibracilinks/OrangeMoney/blob/master/src/Api.php>

Read on 2026-09-18, for the access-gate fact above — the same product's pages, read again for
what they say about reaching the API rather than about the payer's journey through it. Still
not Orange's authoritative API reference.

- Orange Developer, *Orange Money Web Payment (OM WebPay)* — API overview, merchant access
  requirements: <https://developer.orange.com/apis/om-webpay>
- Orange Developer, *Orange Money Web Payment (OM WebPay)* — FAQ, registration and compliance:
  <https://developer.orange.com/apis/om-webpay/faq>
