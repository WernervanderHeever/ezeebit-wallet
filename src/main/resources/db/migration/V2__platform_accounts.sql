-- Platform-side ledger accounts, one per supported currency and purpose.
-- These are the "other side" of every merchant movement and are allowed to go negative:
--   DEPOSIT_CLEARING  money that has arrived from outside (goes negative as merchants are credited)
--   FX_LIQUIDITY      the platform's conversion book; its balances are the open FX position to hedge
--   PAYOUT_CLEARING   money that has left the platform to an external destination
INSERT INTO ledger_account (owner_key, merchant_id, currency, purpose, balance, allow_negative, created_at, updated_at) VALUES
('platform', NULL, 'ZAR',  'DEPOSIT_CLEARING', 0, TRUE, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
('platform', NULL, 'NGN',  'DEPOSIT_CLEARING', 0, TRUE, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
('platform', NULL, 'KES',  'DEPOSIT_CLEARING', 0, TRUE, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
('platform', NULL, 'USDT', 'DEPOSIT_CLEARING', 0, TRUE, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
('platform', NULL, 'USDC', 'DEPOSIT_CLEARING', 0, TRUE, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
('platform', NULL, 'ZAR',  'FX_LIQUIDITY',     0, TRUE, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
('platform', NULL, 'NGN',  'FX_LIQUIDITY',     0, TRUE, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
('platform', NULL, 'KES',  'FX_LIQUIDITY',     0, TRUE, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
('platform', NULL, 'USDT', 'FX_LIQUIDITY',     0, TRUE, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
('platform', NULL, 'USDC', 'FX_LIQUIDITY',     0, TRUE, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
('platform', NULL, 'ZAR',  'PAYOUT_CLEARING',  0, TRUE, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
('platform', NULL, 'NGN',  'PAYOUT_CLEARING',  0, TRUE, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
('platform', NULL, 'KES',  'PAYOUT_CLEARING',  0, TRUE, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
('platform', NULL, 'USDT', 'PAYOUT_CLEARING',  0, TRUE, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
('platform', NULL, 'USDC', 'PAYOUT_CLEARING',  0, TRUE, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6));
