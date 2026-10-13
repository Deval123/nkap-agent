# The six demonstration cases

The cases are plain data in `dev.nkap.agent.scenario.Catalogue`. Nothing is random and nothing reads a
clock, so the files generated from it are the same on every machine on every day.

## Generating the files

```bash
mvn -q compile
java -cp target/classes dev.nkap.agent.scenario.SeedFiles <empty directory>
```

It writes `simulator.json` (the document to post to the simulator's `/_nkap/scenarios`) and one
`<case-id>.csv` for each case that has statement lines. It refuses a directory that is not empty.
`GoldenFilesTest` compares the output with `src/test/resources/golden/`; when the catalogue changes on
purpose, regenerate and read the diff.

## Choices the sources did not settle

- **Telephone numbers.** The gateway only requires a non-blank `counterpartyMsisdn`, and the simulator
  matches it as a string. MTN publishes five sandbox numbers, the
  catalogue needs six, and those numbers sit in the range of ordinary Swedish mobile numbers, so a
  neighbour of one could belong to a person. The catalogue uses `447700900001` to `447700900006`, inside
  the range the British regulator reserves for fiction.
- **How a rule recognises a payment.** By MSISDN alone. The simulator can also match on reference, amount
  and currency; the gateway chooses the reference and the amount string is built by the adapter, so the
  MSISDN is the one value the catalogue controls completely.
- **The balance.** The simulator's document takes `account.balance.availableBalance` and `currency`. The
  observations (case 9) show `GET /balance` answering only once the balance is in the asked currency, so
  the document sets `250000` `XAF`.
- **No answer.** `onSubmit` `NO_RESPONSE` with a query that answers `PENDING` and `SERVICE_UNAVAILABLE`,
  the combination observed to leave a payment `UNKNOWN` and escalate (case 2a).
- **The injection.** The sentence is the submit answer's `code`, the one place free text reached the
  payment history (case 10d).
- **`self-approval`.** The issue says only "same shape as `unrecorded-fee`". It has its own payment (5500),
  its own line (fee 80) and its own MSISDN, so it can never be confused with the first case.
- **Fees on look-alike and unowned lines.** `duplicate-posting` and `money-without-owner` lines carry a fee
  of `0`, because the issue gives none and an invented fee would suggest a second finding.
