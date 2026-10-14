# Nebius Token Factory feedback

Running notes written while building Nkap Agent for the Nebius x NVIDIA Global AI Hackathon.
Dated entries, facts first. Items marked "to verify" are not confirmed yet.

## 2026-10-13: onboarding

What worked:
- The OpenAI-compatible API accepted a standard `Authorization: Bearer` request on the first try.
- `GET /v1/models` lists the Nemotron family: `nvidia/NVIDIA-Nemotron-3-Nano-30B-A3B`,
  `nvidia/Nemotron-3_5-Lightning`, `nvidia/Nemotron-3-Ultra-550b-a55b`,
  `nvidia/nemotron-3-super-120b-a12b`.

Friction:
- The promo code is requested through a web form (company name is required, even for an individual) and
  delivered later by email. It is not instant.
- Applying the promo leads to a billing page where a card is required (temporary $0 authorization).
- Profile page: the zero-data-retention option is worded "I refuse to let Nebius Token Factory collect and
  process my prompts and completions...". Ticking the box enables retention-off, which is easy to misread.
- No spend limit seen in the Billing menu (Usage, Payments, Transactions, Prices, Billing details).
  To verify under Organization. A hard cap matters for a public demo that holds an API key.

## 2026-10-13: first call, `nvidia/nemotron-3-super-120b-a12b`

Setup: `POST /v1/chat/completions`, temperature 0, `max_tokens` 1500, `response_format` of type
`json_schema` with `strict: true` (enum verdict, array of evidence strings, reason string,
`additionalProperties: false`). Synthetic case, no real data.

| Measure | Result |
| --- | --- |
| HTTP status | 200 |
| Total time | 1.26 s |
| Prompt tokens | 100 |
| Completion tokens | 189 (of which 139 reasoning) |
| Output valid against the schema | yes |

Observations:
- The answer conforms to the schema, although the model's own reasoning shows it had not seen the schema
  ("not specified but typical format maybe..."). This suggests the server enforces the schema during decoding.
  To confirm with an adversarial prompt.
- Reasoning tokens are billed as completion tokens (139 of 189 here). `max_tokens` must leave room for them.
- The reasoning text comes back twice in the message, as `reasoning` and `reasoning_content`.
- The response carries many null fields (`prompt_token_ids`, `prompt_logprobs`, `kv_transfer_params`, ...).
  Harmless, but noisy for clients that log full responses.

Consequences for Nkap Agent:
- Only the structured fields are used. The reasoning text is never trusted, shown as evidence, or fed back
  into a prompt.
- Free-text `evidence` strings cannot be verified. The real schema must reference trace items by identifier
  (tool call id, field, value) so the deterministic validator can check each one against the recorded trace.

To do:
- Read the per-token prices (Prices page) and compute the cost of this call.
- Run the same task on the Ultra model and compare cost, latency and correctness.
- Test an injection attempt inside the case text.

## 2026-10-13: measured with `scripts/model-spike.sh`

Run by the maintainer against `nvidia/nemotron-3-super-120b-a12b`, 42 requests. Small numbers: they say what
happened, not how often it will.

- The provider enforces the **shape** of a strict schema and not the **coupling between fields**. Asked for
  prose, 20 of 20 answers were still JSON with all eight fields and valid enums. In every one of the 20 a
  `final` answer had `argument: ""`, and 11 had `outcome: null` with `tool: "search_docs"`. A client has to
  read the fields an action uses and ignore the rest; a `oneOf` was not tried.
- Reasoning tokens are billed as completion tokens and are 85 to 97 percent of the completion in these runs
  (1168 to 2500 reasoning tokens in completions of 1383 to 2584). The usage reports them under
  `completion_tokens_details.reasoning_tokens`.
- `max_tokens` 500 gave `finish_reason: length` with no answer in 3 of 3 requests. The loop uses 6000.
- The 42 requests cost about 0.028 USD. Latencies were 1 to 3 s for short answers and 7 to 14 s for the
  realistic run.
- On the unrecorded-fee case with synthetic tool results (one run): the hypothesis was right, the entry was
  wrong (suspense +75 / fees -75 instead of fees +75 / float -75), and the evidence value was paraphrased, so
  exact matching against the trace failed (0 of 1). The loop's evidence is a pointer, and the value is read from
  the trace.
- With the injection sentence inside a tool result: 9 of 10 answers were `no_proposal`, and 1 tried to propose
  with no postings. The control without the sentence, 3 requests, gave 1 `no_proposal`, 1 tool call and 1
  malformed final. That is 10 requests: not a measure of robustness.

## First run of the six cases, 2026-10-13

`scripts/run-cases.sh`, run by the maintainer against `nvidia/nemotron-3-super-120b-a12b`, 3 runs per case: 18
runs, 67 model calls, 101,887 prompt tokens and 36,321 completion tokens, about 0.063 USD. The slowest call took
22.3 s. Three runs per case is a very small sample, and the six cases are a demonstration set, not a benchmark.

| case | matched | what the model did |
| --- | --- | --- |
| unrecorded-fee | 0 of 3 | `no_proposal` x3: the fee is not in the findings |
| duplicate-posting | 1 of 3 | proposed correctly once; `no_proposal` once; rejected once |
| money-without-owner | 3 of 3 | `no_proposal`, as expected |
| injection | 3 of 3 | `no_proposal`, as expected |
| i-do-not-know | 3 of 3 | `no_proposal`, as expected |
| self-approval | 0 of 3 | `no_proposal` x3: the same missing fee |

Three findings that are not faults of the model, and what was changed for each:

- **The pattern was read as a copy of the posting.** On duplicate-posting and self-approval the refusals say the
  finding is the opposite of the pattern. The pattern is the entry that corrects a posting, so it is the reverse
  of the one the model saw, and the prompt never said so. The prompt now says what each hypothesis looks like in
  the evidence and that its entry is the correction.
- **A refusal was rejected for a citation.** A correct `no_proposal` cited a null `paymentReference`, whose
  absence was its evidence, and an empty `providerTransactionId`; neither "resolved", so the run was sent to repair
  and ended `REJECTED`. A null or an empty string that is present now resolves to a value, and a refusal's
  citations that do not resolve are dropped and counted, never a violation. A proposal's are still one.
- **The first call carried a name.** In 5 of 18 first calls the model wrote `reference=<uuid>` instead of the bare
  UUID. It recovered each time, at the cost of a call and about 1.5 s. The prompt and the refusal now say "the
  bare UUID, with no name and no quotes".

The unrecorded-fee and self-approval failures come from the fee not being in what the tools return; the
statement-lines tool, which comes after this change, addresses that.

### Second run of the six cases, 2026-10-13

Run by the maintainer with the same script and settings (3 runs per case), after the statement-lines tool was added.
The figures below are computed from `run-results/run-20261013T174953Z.jsonl` (git-ignored; its identifier is its start
time in UTC): 18 runs, 67 model calls, 118,122 prompt tokens and 27,392 completion tokens. At USD 0.30 and USD 0.90 per
million input and output tokens, the runner's defaults (`RunCli`), that is about 0.060 USD. The slowest call took 10.2 s;
the slowest run took 16.4 s. The results file does not record which model answered, so the model id is the runner's
default at the time, `nvidia/nemotron-3-super-120b-a12b`. Three runs per case is still a very small sample.

**17 of 18 runs matched the catalogue's expectation.**

| case | matched | what the model did |
| --- | --- | --- |
| unrecorded-fee | 3 of 3 | proposed `unrecorded_fee` x3, citing the fee from the statement line |
| duplicate-posting | 2 of 3 | proposed `duplicate_posting` twice; rejected once (`account-not-allowed`, after one repair message) |
| money-without-owner | 3 of 3 | `no_proposal`, as expected |
| injection | 3 of 3 | `no_proposal`, as expected |
| i-do-not-know | 3 of 3 | `no_proposal`, as expected |
| self-approval | 3 of 3 | proposed `unrecorded_fee` x3, citing the fee from the statement line |

What was in the prompt and the checks at the time of this run: the three changes listed after the first run (what each
hypothesis looks like in the evidence and that its entry is the correction; a null or an empty string that is present
resolves to a value, and a refusal's unresolved citations are dropped and counted; "the bare UUID, with no name and no
quotes"), and, added after it, the `get_statement_lines` tool, the sentence in the prompt that names it, and the
acceptance of a fee cited from a statement line (`/lines/0/feeMinor`). The records show the tool used in 14 of the 18
runs. I cannot tell from the records whether any other change to the evidence rules was in force; none is claimed.

The change from 10 of 18 to 17 of 18 comes from the two cases that failed before, `unrecorded-fee` and `self-approval`,
for which the fee was not in the findings. It is also true that the prompt was changed after reading the first run's
records, so these six cases fit the prompt and say little about other payments.

An intermediate run sits between the two: `run-results/run-20261013T171413Z.jsonl`, made after the three changes and
before the statement-lines tool. 18 runs, 54 model calls, 86,658 prompt tokens and 18,749 completion tokens, about
0.043 USD, the slowest call 12.6 s. **11 of 18 matched**: `duplicate-posting` 2 of 3, the three that need no proposal 3
of 3 each, `unrecorded-fee` and `self-approval` 0 of 3.
