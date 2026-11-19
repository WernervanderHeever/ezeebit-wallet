package com.ezeebit.wallet.common;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Validation and formatting rules for monetary amounts. */
public final class Amounts {

    /** Upper bound well inside DECIMAL(38,8); protects against absurd/overflowing input. */
    public static final BigDecimal MAX_AMOUNT = new BigDecimal("1000000000000000"); // 1e15

    private Amounts() {
    }

    /**
     * Validates a client-supplied amount: strictly positive, within bounds, and with no more
     * decimal places than the currency supports. Returns it normalised to the currency scale.
     */
    public static BigDecimal requireValidPositive(BigDecimal amount, CurrencyCode currency) {
        if (amount == null) {
            throw ApiException.badRequest("AMOUNT_REQUIRED", "amount is required");
        }
        if (amount.signum() <= 0) {
            throw ApiException.badRequest("AMOUNT_NOT_POSITIVE", "amount must be greater than zero");
        }
        if (amount.compareTo(MAX_AMOUNT) > 0) {
            throw ApiException.badRequest("AMOUNT_TOO_LARGE", "amount exceeds the maximum allowed");
        }
        if (amount.stripTrailingZeros().scale() > currency.scale()) {
            throw ApiException.badRequest("AMOUNT_PRECISION",
                    currency + " supports at most " + currency.scale() + " decimal places");
        }
        return normalise(amount, currency);
    }

    /** Sets the scale to the currency scale. Only valid for amounts already at or below that precision. */
    public static BigDecimal normalise(BigDecimal amount, CurrencyCode currency) {
        return amount.setScale(currency.scale(), RoundingMode.UNNECESSARY);
    }

    /** Rounds toward zero to the currency scale (used where the platform must never over-pay). */
    public static BigDecimal roundDown(BigDecimal amount, CurrencyCode currency) {
        return amount.setScale(currency.scale(), RoundingMode.DOWN);
    }

    /** Renders an amount as a plain string at the currency scale, e.g. "100.50" or "12.345678". */
    public static String format(BigDecimal amount, CurrencyCode currency) {
        return amount.setScale(currency.scale(), RoundingMode.UNNECESSARY).toPlainString();
    }
}
