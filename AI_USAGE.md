# AI usage notes

> **Draft: edit before submitting.** Ezeebit wants to see *your* judgement. Replace the
> `TODO` markers with what you actually reviewed, changed or rejected. Do not submit them as-is.

## Tool used

Claude (Anthropic), used as a pair-programmer in one working session.

## What the AI produced

- An initial reading of the brief and a recommendation to pick Tasks 1–3 as one coherent money-in / change / money-out loop.
- The first draft of the design: a double-entry ledger, firm quotes with last look, held withdrawals with a transactional outbox, and idempotent callbacks.
- The first draft of all code, tests, the Flyway schema, README.md and SOLUTION.md.

## Decisions to own (review each one, keep or change it, and say why)

These are the places where a plausible-looking default would have been wrong for a money system. They are good to talk through in the walkthrough video.

| Topic | Draft's choice | Common naive alternative | Your verdict |
|---|---|---|---|
| Balance updates | Atomic conditional `UPDATE` with locks in account-id order | Read balance in Java, check, then save (lost updates under concurrency) | TODO |
| Withdrawal safety | Hold funds **before** calling the rail; release only on a *definitive* failure | Debit on success only (overdraft window), or release on timeout (double spend) | TODO |
| Side effects | Transactional outbox; rail called outside the DB transaction | Call the rail inside `@Transactional` (holds locks over a network call; lost on crash) | TODO |
| FX price | Quoted price is firm; last look refuses rather than re-pricing silently | Execute at whatever the current rate is | TODO |
| Rounding | Always toward the platform, by at most one minor unit | `HALF_UP` everywhere | TODO |
| Bad rates | Reject (503) on timeout, stale, wrong pair, non-positive or >3% jump | Fall back to a cached rate | TODO |
| Contradictory callback | Record `CONFLICT`, alert, never auto-reverse | Apply the latest status | TODO |
| Idempotency scope | Key unique per merchant + request fingerprint; mismatch → 422 | Global key, or replay regardless of body | TODO |
| Not storing rejected requests | A retry with the same key after `INSUFFICIENT_FUNDS` is re-evaluated | Store and replay the failure (Stripe's approach) | TODO |

## Where I changed or rejected AI output

- TODO: for example "Changed the quote TTL from X to Y because…", "Rejected the suggestion to…", "Rewrote … after a test failed on MySQL".

## How I verified it

- TODO: `mvn verify` results (H2), whether the MySQL Testcontainers run executed, and the `scripts/demo.sh` run.
- TODO: anything you tested by hand, such as killing the app between the withdrawal request and its submission and confirming the outbox resumed.
