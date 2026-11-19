package com.ezeebit.wallet.common;

import org.springframework.http.HttpStatus;

import java.math.BigDecimal;

public class InsufficientFundsException extends ApiException {

    public InsufficientFundsException(CurrencyCode currency, BigDecimal available, BigDecimal requested) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_FUNDS",
                "Insufficient " + currency + " balance: available " + Amounts.format(available, currency)
                        + ", requested " + Amounts.format(requested, currency));
    }
}
