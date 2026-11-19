package com.ezeebit.wallet.merchant;

import com.ezeebit.wallet.common.ApiException;
import com.ezeebit.wallet.common.CurrencyCode;
import com.ezeebit.wallet.ledger.LedgerService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Locale;

@Service
public class MerchantService {

    private final MerchantRepository merchants;
    private final LedgerService ledger;
    private final Clock clock;

    public MerchantService(MerchantRepository merchants, LedgerService ledger, Clock clock) {
        this.merchants = merchants;
        this.ledger = ledger;
        this.clock = clock;
    }

    /**
     * Registers a merchant and opens accounts for its local fiat currency and the supported
     * stablecoins. (KYC/onboarding is out of scope; in production this would be driven by onboarding.)
     */
    @Transactional
    public Merchant create(String name, String country, String localCurrencyRaw) {
        CurrencyCode localCurrency = CurrencyCode.parse(localCurrencyRaw);
        if (!localCurrency.isFiat()) {
            throw ApiException.badRequest("LOCAL_CURRENCY_NOT_FIAT", "localCurrency must be a fiat currency");
        }
        Merchant merchant = merchants.save(new Merchant(name, country.toUpperCase(Locale.ROOT), localCurrency, clock.instant()));
        ledger.openMerchantCurrency(merchant.getId(), localCurrency);
        ledger.openMerchantCurrency(merchant.getId(), CurrencyCode.USDT);
        ledger.openMerchantCurrency(merchant.getId(), CurrencyCode.USDC);
        return merchant;
    }

    @Transactional
    public void openCurrency(long merchantId, String currencyRaw) {
        require(merchantId);
        ledger.openMerchantCurrency(merchantId, CurrencyCode.parse(currencyRaw));
    }

    @Transactional(readOnly = true)
    public Merchant require(long merchantId) {
        return merchants.findById(merchantId).orElseThrow(() -> ApiException.notFound("Merchant", merchantId));
    }
}
