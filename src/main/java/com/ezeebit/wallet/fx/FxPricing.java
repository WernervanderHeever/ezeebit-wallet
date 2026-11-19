package com.ezeebit.wallet.fx;

import com.ezeebit.wallet.common.Amounts;
import com.ezeebit.wallet.common.ApiException;
import com.ezeebit.wallet.common.CurrencyCode;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Pure pricing rules. All prices are "fiat per 1 stablecoin". Every rounding decision goes in the
 * platform's favour by at most one minor unit, so the platform never pays out more than it priced.
 */
public final class FxPricing {

    private static final BigDecimal BPS = BigDecimal.valueOf(10_000);
    private static final int PRICE_SCALE = 12;

    private FxPricing() {
    }

    public record Priced(BigDecimal clientPrice, BigDecimal toAmount) {
    }

    /** Only fiat <-> stablecoin conversions are supported (see SOLUTION.md for the reasoning). */
    public static void requireSupportedPair(CurrencyCode from, CurrencyCode to) {
        if (from == to || from.kind() == to.kind()) {
            throw ApiException.unprocessable("UNSUPPORTED_PAIR",
                    "Only fiat <-> stablecoin conversions are supported, got " + from + " -> " + to);
        }
    }

    public static CurrencyCode stablecoinOf(CurrencyCode from, CurrencyCode to) {
        return from.isStablecoin() ? from : to;
    }

    public static CurrencyCode fiatOf(CurrencyCode from, CurrencyCode to) {
        return from.isFiat() ? from : to;
    }

    /**
     * @param fromAmount amount the merchant sells, already validated for {@code from}'s precision
     * @param midPrice   market price, fiat per stablecoin
     */
    public static Priced price(CurrencyCode from, CurrencyCode to, BigDecimal fromAmount, BigDecimal midPrice, int spreadBps) {
        requireSupportedPair(from, to);
        BigDecimal spread = BigDecimal.valueOf(spreadBps).divide(BPS, PRICE_SCALE, RoundingMode.UNNECESSARY);
        BigDecimal clientPrice;
        BigDecimal toAmount;
        if (from.isStablecoin()) {
            // Merchant sells stablecoin: we buy it below mid (bid).
            clientPrice = midPrice.multiply(BigDecimal.ONE.subtract(spread)).setScale(PRICE_SCALE, RoundingMode.DOWN);
            toAmount = Amounts.roundDown(fromAmount.multiply(clientPrice), to);
        } else {
            // Merchant buys stablecoin with fiat: we sell it above mid (ask).
            clientPrice = midPrice.multiply(BigDecimal.ONE.add(spread)).setScale(PRICE_SCALE, RoundingMode.UP);
            toAmount = Amounts.roundDown(fromAmount.divide(clientPrice, MathContext.DECIMAL128), to);
        }
        if (toAmount.signum() <= 0) {
            throw ApiException.unprocessable("AMOUNT_TOO_SMALL", "Amount is too small to convert");
        }
        return new Priced(clientPrice, toAmount);
    }

    /**
     * How far the market has moved against the platform since the quote, in basis points of the
     * quoted mid. Negative means it moved in the platform's favour.
     */
    public static long adverseMoveBps(CurrencyCode from, BigDecimal quotedMid, BigDecimal currentMid) {
        // Merchant sold stablecoin -> platform is now long stablecoin -> a falling price hurts.
        // Merchant bought stablecoin -> platform is short stablecoin -> a rising price hurts.
        BigDecimal move = from.isStablecoin() ? quotedMid.subtract(currentMid) : currentMid.subtract(quotedMid);
        return move.multiply(BPS).divide(quotedMid, 0, RoundingMode.CEILING).longValueExact();
    }
}
