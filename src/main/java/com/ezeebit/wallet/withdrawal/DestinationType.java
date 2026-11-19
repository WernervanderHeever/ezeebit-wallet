package com.ezeebit.wallet.withdrawal;

import com.ezeebit.wallet.common.ApiException;
import com.ezeebit.wallet.common.CurrencyCode;

import java.util.Locale;

public enum DestinationType {
    BANK_ACCOUNT,
    BLOCKCHAIN_ADDRESS;

    public static DestinationType parse(String raw) {
        try {
            return DestinationType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            throw ApiException.badRequest("UNSUPPORTED_DESTINATION_TYPE", "Unsupported destinationType: " + raw);
        }
    }

    /** Fiat goes to a bank account, stablecoins go to a blockchain address. */
    public static void requireCompatible(CurrencyCode currency, DestinationType type) {
        DestinationType expected = currency.isFiat() ? BANK_ACCOUNT : BLOCKCHAIN_ADDRESS;
        if (type != expected) {
            throw ApiException.unprocessable("DESTINATION_MISMATCH",
                    currency + " can only be withdrawn to a " + expected);
        }
    }
}
