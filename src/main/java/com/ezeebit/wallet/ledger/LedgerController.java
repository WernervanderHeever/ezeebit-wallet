package com.ezeebit.wallet.ledger;

import com.ezeebit.wallet.common.CurrencyCode;
import com.ezeebit.wallet.merchant.MerchantService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
public class LedgerController {

    private final LedgerService ledger;
    private final MerchantService merchants;

    public LedgerController(LedgerService ledger, MerchantService merchants) {
        this.ledger = ledger;
        this.merchants = merchants;
    }

    /** Current balances, or balances as they were at {@code asOf}. */
    @GetMapping("/api/v1/merchants/{merchantId}/balances")
    public List<LedgerService.CurrencyBalance> balances(
            @PathVariable long merchantId,
            @RequestParam(required = false) Instant asOf) {
        merchants.require(merchantId);
        return ledger.merchantBalances(merchantId, asOf);
    }

    /** Every movement on one of the merchant's accounts, with the running balance after each. */
    @GetMapping("/api/v1/merchants/{merchantId}/accounts/{currency}/statement")
    public LedgerService.Statement statement(
            @PathVariable long merchantId,
            @PathVariable String currency,
            @RequestParam(defaultValue = "AVAILABLE") AccountPurpose purpose,
            @RequestParam(defaultValue = "0") long afterId,
            @RequestParam(defaultValue = "100") int limit) {
        merchants.require(merchantId);
        return ledger.statement(AccountRef.merchant(merchantId, CurrencyCode.parse(currency), purpose), afterId, limit);
    }

    /** Operational check: proves cached balances match postings and every journal balances. */
    @GetMapping("/api/v1/admin/ledger/reconciliation")
    public LedgerService.ReconciliationReport reconcile() {
        return ledger.reconcile();
    }
}
