package com.ezeebit.wallet.ledger;

import com.ezeebit.wallet.common.CurrencyCode;

/** Logical address of a ledger account. Each account holds exactly one currency. */
public record AccountRef(String ownerKey, Long merchantId, CurrencyCode currency, AccountPurpose purpose) {

    public static final String PLATFORM_OWNER = "platform";

    public static AccountRef merchant(long merchantId, CurrencyCode currency, AccountPurpose purpose) {
        if (purpose.isPlatformAccount()) {
            throw new IllegalArgumentException(purpose + " is a platform account purpose");
        }
        return new AccountRef("merchant:" + merchantId, merchantId, currency, purpose);
    }

    public static AccountRef platform(CurrencyCode currency, AccountPurpose purpose) {
        if (!purpose.isPlatformAccount()) {
            throw new IllegalArgumentException(purpose + " is a merchant account purpose");
        }
        return new AccountRef(PLATFORM_OWNER, null, currency, purpose);
    }
}
