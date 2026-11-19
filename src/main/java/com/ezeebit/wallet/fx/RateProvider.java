package com.ezeebit.wallet.fx;

import com.ezeebit.wallet.common.CurrencyCode;

/**
 * The existing exchange-rate service (assumed). May be slow, may fail, and its price keeps moving.
 * Never called directly by business code; always through {@link RateService}, which adds a timeout
 * and sanity checks.
 */
public interface RateProvider {

    RateSnapshot fetch(CurrencyCode stablecoin, CurrencyCode fiat);
}
