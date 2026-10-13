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
