#!/usr/bin/env bash
# End-to-end walkthrough against a running service. Requires curl and jq.
set -euo pipefail
BASE=${BASE:-http://localhost:8080/api/v1}
H='Content-Type: application/json'
say() { printf '\n\033[1m== %s\033[0m\n' "$*"; }

say "Create merchant (opens ZAR, USDT, USDC)"
M=$(curl -s -XPOST "$BASE/merchants" -H "$H" -d '{"name":"Demo Store","country":"ZA","localCurrency":"ZAR"}' | jq -r .id)
echo "merchant id: $M"

say "Deposit 500 USDT (on-chain) - sent twice, credited once"
for i in 1 2; do
  curl -s -o /dev/null -w "HTTP %{http_code}\n" -XPOST "$BASE/merchants/$M/deposits" -H "$H" \
    -d "{\"source\":\"ONCHAIN\",\"externalReference\":\"0xdemo-$M:0\",\"currency\":\"USDT\",\"amount\":\"500\"}"
done
curl -s "$BASE/merchants/$M/balances" | jq .

say "Quote: sell 100 USDT for ZAR"
Q=$(curl -s -XPOST "$BASE/merchants/$M/fx/quotes" -H "$H" -d '{"fromCurrency":"USDT","toCurrency":"ZAR","amount":"100"}')
echo "$Q" | jq .
QID=$(echo "$Q" | jq -r .quoteId)

say "Execute the quote twice (second is a replay)"
for i in 1 2; do
  curl -s -w "\nHTTP %{http_code}\n" -XPOST "$BASE/merchants/$M/fx/conversions" -H "$H" -d "{\"quoteId\":\"$QID\"}"
done
curl -s "$BASE/merchants/$M/balances" | jq .

say "Try to convert more than held"
Q2=$(curl -s -XPOST "$BASE/merchants/$M/fx/quotes" -H "$H" -d '{"fromCurrency":"USDT","toCurrency":"ZAR","amount":"10000"}' | jq -r .quoteId)
curl -s -XPOST "$BASE/merchants/$M/fx/conversions" -H "$H" -d "{\"quoteId\":\"$Q2\"}" | jq '{code, detail}'

say "Withdraw 1000 ZAR to a bank account (Idempotency-Key sent twice)"
for i in 1 2; do
  curl -s -w "\nHTTP %{http_code}\n" -XPOST "$BASE/merchants/$M/withdrawals" -H "$H" -H "Idempotency-Key: payout-$M-1" \
    -d '{"currency":"ZAR","amount":"1000.00","destinationType":"BANK_ACCOUNT","destination":"FNB 62000000001"}'
done
curl -s "$BASE/merchants/$M/balances" | jq .

say "Withdraw 200 ZAR to an account that will FAIL at the bank"
W2=$(curl -s -XPOST "$BASE/merchants/$M/withdrawals" -H "$H" -H "Idempotency-Key: payout-$M-2" \
  -d '{"currency":"ZAR","amount":"200.00","destinationType":"BANK_ACCOUNT","destination":"FAIL-ACCT-1"}' | jq -r .withdrawalId)

say "Waiting for the (simulated, duplicated) payout callbacks..."
sleep 5
curl -s "$BASE/merchants/$M/balances" | jq .
curl -s "$BASE/merchants/$M/withdrawals/$W2" | jq '{withdrawalId, status, failureReason}'

say "ZAR statement - every movement with its running balance"
curl -s "$BASE/merchants/$M/accounts/ZAR/statement" | jq '.lines[] | {entryType, amount, balanceAfter, description}'

say "Ledger reconciliation"
curl -s "$BASE/admin/ledger/reconciliation" | jq .
