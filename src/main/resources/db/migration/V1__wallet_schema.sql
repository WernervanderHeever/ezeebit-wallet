-- Merchant wallet schema.
-- Written to run unchanged on MySQL 8 (production target) and H2 in MySQL mode (tests).
-- Money is stored as DECIMAL(38,8): exact, 30 integer digits, enough decimals for every
-- supported asset (fiat = 2dp, USDT/USDC = 6dp). Never FLOAT/DOUBLE.

CREATE TABLE merchant (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    name           VARCHAR(200) NOT NULL,
    country        CHAR(2)      NOT NULL,
    local_currency VARCHAR(10)  NOT NULL,
    created_at     DATETIME(6)  NOT NULL
);

-- ---------------------------------------------------------------------------
-- Double-entry ledger
-- ---------------------------------------------------------------------------
-- One row per (owner, currency, purpose). An account holds exactly ONE currency,
-- which is how two currencies can never be silently mixed.
-- balance is a cached projection of SUM(ledger_posting.amount); it is updated in the
-- same DB transaction as the postings and can be re-verified at any time
-- (see GET /api/v1/admin/ledger/reconciliation).
CREATE TABLE ledger_account (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    owner_key      VARCHAR(64)    NOT NULL,   -- 'merchant:<id>' or 'platform'
    merchant_id    BIGINT         NULL,
    currency       VARCHAR(10)    NOT NULL,
    purpose        VARCHAR(32)    NOT NULL,   -- AVAILABLE, WITHDRAWAL_HOLD, DEPOSIT_CLEARING, ...
    balance        DECIMAL(38, 8) NOT NULL DEFAULT 0,
    allow_negative BOOLEAN        NOT NULL,
    created_at     DATETIME(6)    NOT NULL,
    updated_at     DATETIME(6)    NOT NULL,
    CONSTRAINT uq_ledger_account UNIQUE (owner_key, currency, purpose),
    -- Defence in depth: even a buggy code path cannot drive a merchant balance negative.
    CONSTRAINT chk_ledger_account_non_negative CHECK (allow_negative = TRUE OR balance >= 0)
);
CREATE INDEX ix_ledger_account_merchant ON ledger_account (merchant_id);

-- A journal entry is one business event (a deposit, a conversion, a withdrawal hold...).
-- The unique key means the same business event can never be posted twice, no matter
-- what the calling code does.
CREATE TABLE journal_entry (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    entry_type     VARCHAR(32)  NOT NULL,
    reference_type VARCHAR(32)  NOT NULL,
    reference_id   VARCHAR(64)  NOT NULL,
    description    VARCHAR(255) NULL,
    created_at     DATETIME(6)  NOT NULL,
    CONSTRAINT uq_journal_entry_business_event UNIQUE (entry_type, reference_type, reference_id)
);

-- Append-only. Postings in one journal entry sum to zero per currency.
-- balance_after gives a running balance, so any balance can be explained line by line.
CREATE TABLE ledger_posting (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    journal_entry_id BIGINT         NOT NULL,
    account_id       BIGINT         NOT NULL,
    currency         VARCHAR(10)    NOT NULL,
    amount           DECIMAL(38, 8) NOT NULL,
    balance_after    DECIMAL(38, 8) NOT NULL,
    created_at       DATETIME(6)    NOT NULL,
    CONSTRAINT fk_posting_journal FOREIGN KEY (journal_entry_id) REFERENCES journal_entry (id),
    CONSTRAINT fk_posting_account FOREIGN KEY (account_id) REFERENCES ledger_account (id)
);
CREATE INDEX ix_posting_account ON ledger_posting (account_id, id);
CREATE INDEX ix_posting_journal ON ledger_posting (journal_entry_id);

-- ---------------------------------------------------------------------------
-- Task 1: deposits
-- ---------------------------------------------------------------------------
CREATE TABLE deposit (
    id                 CHAR(36)       NOT NULL PRIMARY KEY,
    merchant_id        BIGINT         NOT NULL,
    source             VARCHAR(32)    NOT NULL,
    external_reference VARCHAR(128)   NOT NULL,
    currency           VARCHAR(10)    NOT NULL,
    amount             DECIMAL(38, 8) NOT NULL,
    request_hash       CHAR(64)       NOT NULL,
    journal_entry_id   BIGINT         NOT NULL,
    created_at         DATETIME(6)    NOT NULL,
    version            BIGINT         NOT NULL,
    -- the upstream system's reference is the idempotency key for deposits
    CONSTRAINT uq_deposit_external UNIQUE (source, external_reference)
);
CREATE INDEX ix_deposit_merchant ON deposit (merchant_id, created_at);

-- ---------------------------------------------------------------------------
-- Task 2: FX quotes and conversions
-- ---------------------------------------------------------------------------
CREATE TABLE fx_quote (
    id             CHAR(36)       NOT NULL PRIMARY KEY,
    merchant_id    BIGINT         NOT NULL,
    from_currency  VARCHAR(10)    NOT NULL,
    to_currency    VARCHAR(10)    NOT NULL,
    from_amount    DECIMAL(38, 8) NOT NULL,
    to_amount      DECIMAL(38, 8) NOT NULL,
    mid_price      DECIMAL(38, 12) NOT NULL,  -- fiat per 1 stablecoin, from the rate service
    client_price   DECIMAL(38, 12) NOT NULL,  -- fiat per 1 stablecoin, after spread
    spread_bps     INT            NOT NULL,
    rate_source    VARCHAR(64)    NOT NULL,
    rate_as_of     DATETIME(6)    NOT NULL,
    expires_at     DATETIME(6)    NOT NULL,
    status         VARCHAR(16)    NOT NULL,   -- OPEN, EXECUTED
    created_at     DATETIME(6)    NOT NULL,
    version        BIGINT         NOT NULL
);
CREATE INDEX ix_fx_quote_merchant ON fx_quote (merchant_id, created_at);

CREATE TABLE fx_conversion (
    id               CHAR(36)        NOT NULL PRIMARY KEY,
    quote_id         CHAR(36)        NOT NULL,
    merchant_id      BIGINT          NOT NULL,
    from_currency    VARCHAR(10)     NOT NULL,
    to_currency      VARCHAR(10)     NOT NULL,
    from_amount      DECIMAL(38, 8)  NOT NULL,
    to_amount        DECIMAL(38, 8)  NOT NULL,
    client_price     DECIMAL(38, 12) NOT NULL,
    journal_entry_id BIGINT          NOT NULL,
    created_at       DATETIME(6)     NOT NULL,
    version          BIGINT          NOT NULL,
    -- a quote can be executed at most once; the quote id doubles as the idempotency key
    CONSTRAINT uq_fx_conversion_quote UNIQUE (quote_id)
);
CREATE INDEX ix_fx_conversion_merchant ON fx_conversion (merchant_id, created_at);

-- ---------------------------------------------------------------------------
-- Task 3: withdrawals
-- ---------------------------------------------------------------------------
CREATE TABLE withdrawal (
    id                 CHAR(36)       NOT NULL PRIMARY KEY,
    merchant_id        BIGINT         NOT NULL,
    idempotency_key    VARCHAR(128)   NOT NULL,
    request_hash       CHAR(64)       NOT NULL,
    currency           VARCHAR(10)    NOT NULL,
    amount             DECIMAL(38, 8) NOT NULL,
    destination_type   VARCHAR(32)    NOT NULL,
    destination        VARCHAR(256)   NOT NULL,
    status             VARCHAR(32)    NOT NULL,
    provider_reference VARCHAR(128)   NULL,
    failure_reason     VARCHAR(512)   NULL,
    created_at         DATETIME(6)    NOT NULL,
    updated_at         DATETIME(6)    NOT NULL,
    version            BIGINT         NOT NULL,
    CONSTRAINT uq_withdrawal_idempotency UNIQUE (merchant_id, idempotency_key)
);
CREATE INDEX ix_withdrawal_status ON withdrawal (status, updated_at);

-- Every callback we receive from the payout rail, including duplicates and conflicts.
CREATE TABLE payout_callback (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    withdrawal_id      CHAR(36)     NOT NULL,
    provider_event_id  VARCHAR(128) NULL,
    provider_reference VARCHAR(128) NULL,
    reported_status    VARCHAR(16)  NOT NULL,
    reason             VARCHAR(512) NULL,
    outcome            VARCHAR(16)  NOT NULL,   -- APPLIED, DUPLICATE, CONFLICT
    received_at        DATETIME(6)  NOT NULL
);
CREATE INDEX ix_payout_callback_withdrawal ON payout_callback (withdrawal_id);

-- ---------------------------------------------------------------------------
-- Transactional outbox: side effects (payout submission, hedging) are recorded in the
-- same transaction as the state change and dispatched afterwards, with retries.
-- ---------------------------------------------------------------------------
CREATE TABLE outbox_message (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    message_type    VARCHAR(64)  NOT NULL,
    aggregate_id    VARCHAR(64)  NOT NULL,
    payload         VARCHAR(2000) NULL,
    status          VARCHAR(16)  NOT NULL,   -- PENDING, IN_PROGRESS, DONE, DEAD
    attempts        INT          NOT NULL,
    next_attempt_at DATETIME(6)  NOT NULL,
    locked_until    DATETIME(6)  NULL,
    last_error      VARCHAR(1000) NULL,
    created_at      DATETIME(6)  NOT NULL,
    updated_at      DATETIME(6)  NOT NULL
);
CREATE INDEX ix_outbox_due ON outbox_message (status, next_attempt_at);
