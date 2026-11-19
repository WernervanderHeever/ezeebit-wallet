package com.ezeebit.wallet.fx;

import com.ezeebit.wallet.common.CurrencyCode;

import java.math.BigDecimal;
import java.time.Instant;

/** Price of one unit of {@code stablecoin} expressed in {@code fiat}, as reported by the rate service. */
public record RateSnapshot(CurrencyCode stablecoin, CurrencyCode fiat, BigDecimal price, Instant asOf, String source) {
}
