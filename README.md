# Merchant Wallet Service

A multi-currency merchant wallet for the Ezeebit backend take-home. It covers three of the brief's tasks:

| Task | What it does |
|---|---|
| **1. Hold a merchant's money across currencies** | Double-entry ledger, idempotent deposits, balances (including historical balances), per-account statements and a reconciliation check |
| **2. Convert one currency into another** | Firm, short-lived FX quotes. Each quote can be executed once, atomically, with rate sanity checks, a spread, and a last-look check before execution |
| **3. Withdraw funds** | Funds are held when the withdrawal is requested. A transactional outbox submits the payout to an async rail, and callbacks are idempotent: the hold is either settled or released |

Design, trade-offs and how Tasks 4–6 fit in are in **[SOLUTION.md](SOLUTION.md)**. AI usage notes are in **[AI_USAGE.md](AI_USAGE.md)**.

Stack: Java 17, Spring Boot 3.3, MySQL 8 (H2 in MySQL mode for tests), Flyway, JdbcTemplate for the ledger, and Spring Data JPA for everything else.

## Prerequisites

- JDK 17 or newer
- Maven 3.9+
- Docker (optional): needed to run against MySQL and for the real-MySQL concurrency tests

## Run it

### Option A: against MySQL (production-like)

```bash
docker compose up -d          # MySQL 8.4 on localhost:3306 (db/user/password: wallet)
mvn spring-boot:run
```

### Option B: no Docker (in-memory H2)

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=h2
```

Flyway creates the schema on startup either way. The service listens on `http://localhost:8080`.

The exchange-rate feed and payout rail are **simulated** (`SimulatedRateProvider`, `SimulatedPayoutRail`), so the whole flow works locally. The simulated rail calls back about 2 seconds after submission and deliberately delivers every callback **twice**. The destination string controls what it does:

| destination contains | behaviour |
|---|---|
| `REJECT` | rejected at submission → funds released |
| `FLAKY` | first submission times out, the retry succeeds (same reference) |
| `FAIL` | accepted, then fails asynchronously → funds released |
| anything else | accepted, then succeeds asynchronously |

## Try it

`scripts/demo.sh` runs the full story with `curl` and `jq`: create a merchant, deposit, convert, withdraw, then a failing withdrawal, duplicate requests, and the statement plus reconciliation. To run the steps by hand:

```bash
# merchant with ZAR + USDT + USDC accounts
curl -s -XPOST localhost:8080/api/v1/merchants -H 'Content-Type: application/json' \
  -d '{"name":"Demo Store","country":"ZA","localCurrency":"ZAR"}'

# deposit (idempotent on source + externalReference)
curl -s -XPOST localhost:8080/api/v1/merchants/1/deposits -H 'Content-Type: application/json' \
  -d '{"source":"ONCHAIN","externalReference":"0xabc:0","currency":"USDT","amount":"500"}'

# quote, then execute the quote
curl -s -XPOST localhost:8080/api/v1/merchants/1/fx/quotes -H 'Content-Type: application/json' \
  -d '{"fromCurrency":"USDT","toCurrency":"ZAR","amount":"100"}'
curl -s -XPOST localhost:8080/api/v1/merchants/1/fx/conversions -H 'Content-Type: application/json' \
  -d '{"quoteId":"<quoteId>"}'

# withdraw (Idempotency-Key required)
curl -s -XPOST localhost:8080/api/v1/merchants/1/withdrawals -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: payout-001' \
  -d '{"currency":"ZAR","amount":"500.00","destinationType":"BANK_ACCOUNT","destination":"FNB 62000000001"}'

curl -s localhost:8080/api/v1/merchants/1/balances
curl -s localhost:8080/api/v1/merchants/1/accounts/ZAR/statement
curl -s localhost:8080/api/v1/admin/ledger/reconciliation
```

## API

| Method & path | Purpose |
|---|---|
| `POST /api/v1/merchants` | Register a merchant; opens local-fiat, USDT and USDC accounts |
| `POST /api/v1/merchants/{id}/accounts` | Open another currency |
| `POST /api/v1/merchants/{id}/deposits` | *Internal.* Record money that arrived. 201 when new, 200 when replayed |
| `GET  /api/v1/merchants/{id}/balances[?asOf=ISO-instant]` | Available / held / total per currency, now or at a past instant |
| `GET  /api/v1/merchants/{id}/accounts/{ccy}/statement[?purpose=&afterId=&limit=]` | Every posting with running balance (keyset pagination) |
| `POST /api/v1/merchants/{id}/fx/quotes` | Firm quote, valid for 30s |
| `GET  /api/v1/merchants/{id}/fx/quotes/{quoteId}` | Quote status (OPEN / EXECUTED / EXPIRED) |
| `POST /api/v1/merchants/{id}/fx/conversions` | Execute a quote. 201 new, 200 replay |
| `GET  /api/v1/merchants/{id}/fx/conversions/{conversionId}` | Conversion details |
| `POST /api/v1/merchants/{id}/withdrawals` + `Idempotency-Key` | Request a payout. 202 new, 200 replay |
| `GET  /api/v1/merchants/{id}/withdrawals/{withdrawalId}` | Withdrawal status |
| `POST /api/v1/payout-callbacks` | *Webhook* from the payout rail |
| `GET  /api/v1/admin/ledger/reconciliation` | Proves cached balances = postings, journals balance, system nets to zero |

Errors are RFC 7807 `application/problem+json` with a stable `code`, for example `INSUFFICIENT_FUNDS`, `QUOTE_EXPIRED`, `IDEMPOTENCY_KEY_REUSED` or `RATE_UNAVAILABLE`. Amounts are sent and returned as **strings** at the currency's precision (ZAR/NGN/KES 2dp, USDT/USDC 6dp). An amount with more decimals than its currency allows is rejected rather than rounded.

## Tests

```bash
mvn test
```

- `LedgerAndDepositTest`: per-currency balances, duplicate deposits, precision rules, large amounts, statements, historical balances, concurrent duplicate notifications.
- `ConversionTest`: bid/ask pricing and rounding, executing a quote twice, expiry, insufficient funds, last look, rate timeout, untrustworthy rates (zero, stale, wrong pair, 10× jump), concurrent execution of one quote.
- `WithdrawalTest`: hold → submit → settle, failure releases funds, idempotency-key replay and misuse, duplicate and contradictory callbacks, ambiguous submission failure retried with the same reference, rejection at submission, callback arriving before our own submission acknowledgement.
- `H2ConcurrencyTest` / `MySqlConcurrencyTest`: 20 parallel withdrawals against a balance that covers 10 (exactly 10 succeed), and 10 parallel retries with one key (exactly one withdrawal). The MySQL variant uses Testcontainers against real InnoDB locking and **skips itself when Docker is unavailable**.
- `FxPricingTest`: pure unit tests of pricing direction and rounding.
