package com.ezeebit.wallet.common;

import java.util.Locale;

/**
 * Supported assets. Each has a fixed number of decimal places; amounts with more precision
 * than the asset supports are rejected rather than silently rounded.
 */
public enum CurrencyCode {
    ZAR(Kind.FIAT, 2),
    NGN(Kind.FIAT, 2),
    KES(Kind.FIAT, 2),
    USDT(Kind.STABLECOIN, 6),
    USDC(Kind.STABLECOIN, 6);

    public enum Kind { FIAT, STABLECOIN }

    private final Kind kind;
    private final int scale;

    CurrencyCode(Kind kind, int scale) {
        this.kind = kind;
        this.scale = scale;
    }

    public Kind kind() {
        return kind;
    }

    public int scale() {
        return scale;
    }

    public boolean isFiat() {
        return kind == Kind.FIAT;
    }

    public boolean isStablecoin() {
        return kind == Kind.STABLECOIN;
    }

    public static CurrencyCode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw ApiException.badRequest("CURRENCY_REQUIRED", "currency is required");
        }
        try {
            return CurrencyCode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("UNSUPPORTED_CURRENCY", "Unsupported currency: " + raw);
        }
    }
}
