# SOLUTION

## 1. Summary

I chose **Tasks 1, 2 and 3**: holding balances, converting between currencies, and withdrawing. Together they make a closed loop of money in, money changed, and money out. Each task stresses a different correctness problem:

| | Main risk | Main defence |
|---|---|---|
| Task 1 | A balance nobody can explain, or currencies mixed | An append-only double-entry ledger with one currency per account |
| Task 2 | Paying out at a stale or wrong price; selling more than the merchant holds | Firm single-use quotes, rate sanity checks, a last-look check before execution, and an atomic two-leg journal |
| Task 3 | Double payouts, overdrafts under concurrency, losing track of a failed payout | Funds held before the rail is called, idempotency at both our API and the rail, a transactional outbox, and an idempotent callback state machine |

Everything that changes money goes through **one function**, `LedgerService.post`. The three tasks are thin business workflows on top of it, and Tasks 4–6 slot into the same shape (§8).

## 2. Architecture

```
                 merchant dashboard (flaky mobile, retries)        upstream systems           payout rail (async)
                         |                                          (bank ingest, Task 4)          ^      |
                         v                                                |                      submit  callback
  +----------------------------------------------------------------------v----------------------|------v------+
  |  REST controllers  (merchant id in path; Idempotency-Key header / natural keys)                             |
  |----------------------------------------------------------------------------------------------------------- |
  |  DepositService        ConversionService           WithdrawalService            SubmitPayoutHandler        |
  |   (Task 1)             (Task 2)                    (Task 3)                      FxHedgeHandler            |
  |      |                  |    \__ RateService ------ timeout, validation --> RateProvider (existing)  ^    |
  |      |                  |                                                                             |    |
  |      +------------------+---------------+  same DB transaction  +--> OutboxService --> outbox_message |    |
  |                                         v                                             OutboxDispatcher ---+ |
  |                                   LedgerService.post  (the only writer of balances)                         |
  +-------------------------------------------------------------------------------------------------------------+
                                              |
                                           MySQL 8
   ledger_account (cached balance) | journal_entry (unique business event) | ledger_posting (append-only, running balance)
   deposit | fx_quote | fx_conversion | withdrawal | payout_callback | outbox_message
```

Packages: `ledger` (core), `deposit`, `fx`, `withdrawal`, `outbox`, `merchant`, `common`. It is a modular monolith. These modules could become separate services later, but money correctness is much simpler when the ledger write and the business-record write share one ACID transaction.

## 3. Task 1: the ledger

### Model

- **`ledger_account`**: one row per *(owner, currency, purpose)*. **An account holds exactly one currency**, so ZAR and USDT can never be summed by accident.
  - Merchant purposes: `AVAILABLE` (spendable) and `WITHDRAWAL_HOLD` (reserved for in-flight payouts).
  - Platform purposes, one per currency: `DEPOSIT_CLEARING`, `FX_LIQUIDITY` and `PAYOUT_CLEARING`. These are the "other side" of every merchant movement and may go negative.
- **`journal_entry`**: one row per business event (a deposit, a conversion, a withdrawal hold, settle or release). `UNIQUE(entry_type, reference_type, reference_id)` means **the same business event can be posted only once**, whatever the calling code does.
- **`ledger_posting`**: append-only signed amounts, each carrying `balance_after`.

### Invariants enforced in `LedgerService.post`

1. A journal's lines **sum to zero per currency**. Money is never created or destroyed, and value moves between currencies only through the platform's FX book (Task 2).
2. Each posting's currency matches its account's currency.
3. Merchant balances never go negative. This is enforced by an atomic conditional `UPDATE ... SET balance = balance + ? WHERE id = ? AND (allow_negative OR balance + ? >= 0)`, and backed by a DB `CHECK` constraint as a second line of defence.
4. Postings are never updated or deleted.

### Why a cached balance and postings

Reading the balance stays O(1) (`ledger_account.balance`), while the postings remain the source of truth. Both change in the same transaction. `GET /admin/ledger/reconciliation` proves three things at any time:

- each cached balance equals `SUM(postings)`;
- every journal balances per currency;
- the whole system nets to zero per currency.

In production this would run on a schedule and alert on any mismatch.

### "Explain how a balance reached its value"

- `GET .../accounts/{ccy}/statement` lists every posting with its running balance, entry type, business reference (deposit id, conversion id, withdrawal id) and a description.
- `GET .../balances?asOf=...` reconstructs balances at any past instant from `balance_after`.

### Amounts

- Stored as `DECIMAL(38,8)` and handled as `BigDecimal`. There is no floating point anywhere.
- Each currency has a fixed scale: fiat 2dp, USDT/USDC 6dp. An input with more precision is **rejected, not rounded**. There is also an upper bound of 1e15.
- The API sends and receives amounts as strings, so JavaScript clients cannot lose precision.
- Tests cover 13-digit balances exactly.

### Deposits

Deposits come from upstream systems, such as bank-statement ingestion or the Task 4 on-chain flow. They are idempotent on `(source, externalReference)`:

- a repeat with the same details returns the original deposit (HTTP 200) and moves nothing;
- the same reference with different details is rejected with `409 IDEMPOTENCY_KEY_REUSED` and should be investigated, not silently accepted.

A deposit in a currency the merchant has not opened opens the account automatically, because money that has actually arrived must always be creditable.

### Concurrency

- Balances change only through row-locking `UPDATE`s, applied in **ascending account-id order**, so two journals touching the same accounts cannot deadlock on each other.
- There is no read-modify-write in Java, so lost updates are impossible.
- Isolation is the InnoDB default. Correctness comes from locking writes and unique constraints, not from the isolation level.

## 4. Task 2: conversion

### Flow

1. **`POST /fx/quotes`**: fetch the market mid-rate through `RateService`, apply the spread against the merchant, and store a **firm quote valid for 30s**.
   - Prices are always quoted as *fiat per 1 stablecoin*.
   - A merchant selling USDT gets the **bid**: `mid × (1 − spread)`.
   - A merchant buying USDT pays the **ask**: `mid × (1 + spread)`.
   - The amount the merchant receives is **rounded down** to the target currency's precision, so the platform never pays more than it priced.
2. **`POST /fx/conversions {quoteId}`** executes the quote in **one transaction**:
   - lock the quote row (`SELECT ... FOR UPDATE`);
   - re-check that it is still OPEN and unexpired;
   - post one journal with two balanced legs: merchant `from` → platform FX `from`, and platform FX `to` → merchant `to`;
   - mark the quote EXECUTED, insert the conversion, and enqueue a hedge message.

### Which rate does the merchant get?

Exactly the quoted price and amount, or nothing. The merchant never receives a different number than the one they saw. If the quote cannot be honoured (expired, or the market moved through it), they get a clear error and request a new quote.

### Between quote and execution, and "the platform must not be on the wrong side"

- **Spread**: 50 bps by default. This is the platform's margin and buffer.
- **Short TTL**: 30s. This bounds how far the market can move against an honoured quote.
- **Last look**: before execution we fetch a fresh rate. If the market has moved against the platform by more than `max-adverse-move-bps` (default 50, the size of the spread), we refuse with `409 QUOTE_REPRICE_REQUIRED`. Moves in the platform's favour do not change the merchant's price.
  - *Trade-off:* last look is asymmetric and some merchants dislike it. The alternative is a shorter TTL and a wider spread. It is configurable.
  - If the rate service is down at execution time we **honour** the quote, because the TTL bounds the risk. Setting `honour-quote-when-rate-unavailable=false` refuses instead.
- **Hedging**: every executed conversion is written to the outbox (`FX_HEDGE`) in the same transaction. A conversion can therefore never go un-hedged because of a crash. The `FX_LIQUIDITY` balances per currency are the platform's open position at any moment.

### "A merchant must never convert more than they hold"

The debit leg runs through the same conditional `UPDATE`. If the merchant does not hold the amount at the instant of execution, which can change between quote and execution, the transaction fails with `422 INSUFFICIENT_FUNDS`. Nothing is written and the quote stays OPEN.

### Idempotency

The quote id *is* the idempotency key: `UNIQUE(fx_conversion.quote_id)` plus the row lock. A retried execution returns the original conversion (HTTP 200). Eight concurrent executions of one quote produce exactly one conversion (tested).

### Unreliable rate service (`RateService`)

- **Hard timeout** (2s), run on a dedicated small thread pool. A slow feed cannot exhaust the request threads.
- A rate is **rejected (503)** if it:
  - has a null or non-positive price;
  - is for the wrong pair;
  - is older than 10s, or timestamped in the future;
  - is more than 3% away from the last rate accepted for that pair within the last 60s (the "fat-finger" check).
  
  Rejections are logged as `ALERT`.
- *Trade-off of the jump check:* a genuine >3% move blocks quoting for at most the 60s window, after which the new level is accepted. A stronger version would cross-check a second independent feed.
- We never fall back to a cached rate for **pricing**. Refusing to quote is cheaper than mis-pricing.

### Supported pairs

Only fiat ↔ stablecoin (for example USDT↔ZAR, ZAR↔USDT, USDC↔NGN). Stablecoin↔stablecoin and fiat↔fiat are rejected with `422 UNSUPPORTED_PAIR`. *Assumption:* those are separate products with separate liquidity, and they could be added as two legs through USD.

## 5. Task 3: withdrawals

### Lifecycle

```
request ─► PENDING_SUBMISSION ─(rail accepts)─► SUBMITTED ─(callback OK)──► COMPLETED   hold → PAYOUT_CLEARING
              │       funds HELD                    └──(callback FAIL)─► FAILED      hold → AVAILABLE
              └─(rail rejects definitively)─────────────────────────────► FAILED      hold → AVAILABLE
```

1. **Request**: one transaction.
   1. Insert the withdrawal. `UNIQUE(merchant_id, idempotency_key)` claims the key *before* any balance is touched.
   2. Move the amount `AVAILABLE → WITHDRAWAL_HOLD`. This is the conditional update, so it fails atomically if the funds are not there.
   3. Enqueue `SUBMIT_PAYOUT` in the outbox.
   
   The response is `202 Accepted`.
2. **Submit** (`OutboxDispatcher` → `SubmitPayoutHandler`), after commit and **outside any DB transaction**, so a slow rail never holds a balance lock. The withdrawal id is sent as the **rail's idempotency reference**.
   - Timeout or 5xx: the outcome is ambiguous, so we retry with exponential backoff and the same reference.
   - Definitive rejection: release the hold.
   - Accepted: mark SUBMITTED.
3. **Callback** (`POST /payout-callbacks`): one transaction that locks the withdrawal row.
   - The first terminal result wins and posts *settle* or *release*.
   - A repeat of the same result is recorded as `DUPLICATE`.
   - A contradictory result is recorded as `CONFLICT` and raises an `ALERT`. It is **never auto-reversed**, because money may already have left.
   - The endpoint always returns 200 for a known withdrawal, so the rail stops retrying.
   - Every callback is stored in `payout_callback` for audit.

### Requirements mapping

| Requirement | How |
|---|---|
| Never withdraw money they don't have | The hold is taken atomically at request time. Concurrent requests serialise on the account row. Test: 20 parallel × 10 against 100 → exactly 10 accepted. |
| Same withdrawal never paid twice | `Idempotency-Key` unique per merchant. The same key with a different body gets `422 IDEMPOTENCY_KEY_REUSED`. A concurrent in-flight duplicate gets `409 CONCURRENT_REQUEST`, which is safe to retry. The rail de-duplicates on our withdrawal id. Settle and release journals are unique per withdrawal. |
| Balance always right, even when a payout fails | Funds stay in `WITHDRAWAL_HOLD`, still the merchant's and visible as `held`, until a *definitive* result. A failure moves them back to `AVAILABLE`. |
| Rail calls back before we've recorded its acknowledgement | Callbacks are accepted from `PENDING_SUBMISSION`. The late acknowledgement then does not overwrite the terminal state. (Tested.) |
| Crash between commit and calling the rail | The outbox row was committed with the hold, so the dispatcher picks it up. A lease reclaims messages from dead workers. |
| Rail unreachable for a long time | The message goes `DEAD` after N attempts with an `ALERT`, and the funds stay **held**. We never release on an ambiguous failure, because the rail may have paid. An operator, or a status-query job (§10), resolves it. |

## 6. Failure and duplicate handling at a glance

| Situation | Behaviour |
|---|---|
| Dashboard resends a withdrawal | Same `Idempotency-Key` → original returned (200) |
| Two identical requests at the same instant | One wins; the other gets the original or `409 CONCURRENT_REQUEST`. Nothing is doubled. |
| Duplicate deposit notification | Same `(source, externalReference)` → original returned, credited once |
| Rate feed slow / down / nonsense | 503 within 2s; no quote issued |
| Market moves between quote and execution | Within tolerance: executed at the quoted price. Beyond it: `409 QUOTE_REPRICE_REQUIRED` |
| Quote executed twice / concurrently | One conversion |
| Payout rail timeout | Retried with the same reference; funds stay held |
| Duplicate / contradictory payout callback | `DUPLICATE` (no-op) / `CONFLICT` (no-op + alert) |
| Any unexpected exception | The transaction rolls back; the client gets `500 INTERNAL_ERROR`, "no changes were applied" |

## 7. Assumptions and ambiguities I resolved

- **Authentication and tenancy:** the merchant id in the path is trusted, as the brief allows. Every lookup is still scoped by merchant id, so one merchant cannot read or execute another merchant's quote or withdrawal.
- **Deposits are an internal API.** They are called by systems that observe money arriving, not by the merchant.
- **The payout rail is idempotent on our reference.** This is standard for payout APIs, and it is what makes retrying an ambiguous submission safe. If a rail could not do this, I would query its status before every retry.
- **Callbacks identify the withdrawal by our id.** In production they would also be HMAC-verified.
- **Quotes are "sell an exact amount".** "Receive an exact amount" is a small extension of `FxPricing`.
- **One local fiat per merchant at registration**, plus USDT and USDC. More currencies can be opened.
- **A rejected request is not stored against its idempotency key.** If a withdrawal fails with `INSUFFICIENT_FUNDS`, retrying the same key later re-evaluates it. Stripe stores failures as well; I chose not to so a merchant can top up and retry. Either way is defensible.
- **Fees** are not modelled. A fee would be one more line in the same journal, crediting a platform `FEES` account.

## 8. How Tasks 4–6 fit the same design

- **Task 4, incoming crypto payment.** Add an `incoming_payment` record keyed by `(chain, tx_hash, log_index)` (unique), with states `SEEN → CONFIRMING → FINAL` driven by the confirmation-service notifications. Duplicate notifications become no-ops on that key, and confirmations only ever move forward.
  - When the confirmation count reaches the per-chain threshold, call `DepositService.record(source="ONCHAIN", externalReference="chain:tx:log")` in the same transaction. Its unique key is the second guarantee that a payment is never counted twice.
  - If "pending" funds should be visible, post to a merchant `PENDING_INCOMING` account first and move them to `AVAILABLE` at finality. That reuses the hold pattern from Task 3. A re-org before finality releases the pending posting.
- **Task 5, auto-settle.** Add a `settlement_rule` table (merchant, stablecoin, target fiat, percent). When a Task 4 deposit is finalised, the same transaction enqueues an `AUTO_CONVERT` outbox message.
  - The handler creates a quote and executes it through `ConversionService`. The idempotency reference is the deposit id, so it converts once.
  - If pricing is unavailable, the outbox retries. The merchant sees the stablecoin credit immediately and a linked conversion when it completes. Both appear on the statement, so the outcome is easy to explain.
  - The amount is computed with the same rounding rules, and the remainder stays in the stablecoin.
- **Task 6, payout routing.** Replace the single `PayoutRail` with a `PayoutRouter` that chooses a partner by country, currency, amount limits and health (a circuit breaker per partner), and records `partner` and `attempt` on the withdrawal.
  - The hold, outbox and callback machinery are unchanged.
  - Fail over to another partner **only on a definitive rejection or a submission we can prove did not happen**, never on a timeout. A timeout stays held until that partner's status is known.
  - Funds stay in `WITHDRAWAL_HOLD` throughout, so the merchant's money is always accounted for.

## 9. Trade-offs I made

- **JdbcTemplate for the ledger, JPA for the rest.** The ledger's correctness depends on exact SQL: conditional updates, lock order, and no ORM caching of balances. I wanted those statements visible and reviewable. JPA is used for plain records.
- **Pessimistic row locks rather than optimistic versioning on balances.** Payday bursts create hot accounts, and optimistic retries thrash under contention, while row locks serialise cheaply. Lock hold time is kept to a few statements, and network calls never happen inside a transaction.
- **Hot-account limit.** Every write to one merchant's currency account serialises on one row, which is fine for thousands of writes per second per merchant. If that ever binds, the platform clearing accounts could be sharded into N sub-accounts (merchant accounts are naturally partitioned).
- **Outbox polling rather than a message broker.** Polling is simple and correct, and the claim works across multiple instances without `SKIP LOCKED`. With Kafka available, the outbox would feed it via CDC (Debezium) instead.
- **H2 for the default test run.** The suite runs anywhere with only a JDK. The concurrency tests also run against real MySQL through Testcontainers when Docker is present, because InnoDB's locking is what production relies on.

## 10. With more time

- A **stuck-withdrawal sweeper**: query the rail's status for `SUBMITTED` items older than X minutes, and for `DEAD` submissions.
- A **daily external reconciliation**: ledger `PAYOUT_CLEARING` and `DEPOSIT_CLEARING` against bank and rail statements and chain balances.
- **HMAC verification** on callbacks, plus the rail's own event-id de-duplication table.
- **Per-merchant limits and velocity checks** on withdrawals, and beneficiary allow-listing.
- **DB-level immutability** for postings: triggers or a role without UPDATE/DELETE. **Partition** postings by month.
- **Metrics** (quote reject rate, last-look refusals, outbox lag, DEAD count) in Grafana, with alerts on reconciliation mismatches.
- **Exact-receive quotes**, fees, and a second rate source for cross-checking.
- **Property-based tests** that generate random interleavings of deposits, conversions and withdrawals and assert reconciliation after each step.

## 11. Not finished or not verified

- The code was written and reviewed, but I **compiled the pure pricing and amount logic separately** with `javac`. Run `mvn verify` locally (see README) for the full build and test suite. *(Update this line after your own run.)*
