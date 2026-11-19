package com.ezeebit.wallet;

import com.ezeebit.wallet.common.ApiException;
import com.ezeebit.wallet.common.CurrencyCode;
import com.ezeebit.wallet.fx.FxPricing;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Pure unit tests for pricing and rounding direction. */
class FxPricingTest {

    private static final BigDecimal MID = new BigDecimal("18.25");

    @Test
    void sellingStablecoinUsesBidAndRoundsDown() {
        FxPricing.Priced p = FxPricing.price(CurrencyCode.USDT, CurrencyCode.ZAR, new BigDecimal("100.000000"), MID, 50);
        assertThat(p.clientPrice()).isEqualByComparingTo("18.15875");
        assertThat(p.toAmount()).isEqualByComparingTo("1815.87"); // not .88
    }

    @Test
    void buyingStablecoinUsesAskAndRoundsDown() {
        FxPricing.Priced p = FxPricing.price(CurrencyCode.ZAR, CurrencyCode.USDT, new BigDecimal("1000.00"), MID, 50);
        assertThat(p.clientPrice()).isEqualByComparingTo("18.34125");
        assertThat(p.toAmount()).isEqualByComparingTo("54.521910");
    }

    @Test
    void platformNeverPaysMoreThanMidValue() {
        for (String amount : new String[]{"0.01", "1.00", "7.77", "123456.78"}) {
            BigDecimal fiat = new BigDecimal(amount);
            FxPricing.Priced p = FxPricing.price(CurrencyCode.ZAR, CurrencyCode.USDT, fiat, MID, 50);
            assertThat(p.toAmount().multiply(MID)).isLessThanOrEqualTo(fiat);
        }
    }

    @Test
    void tooSmallToConvertIsRejected() {
        assertThatThrownBy(() -> FxPricing.price(CurrencyCode.USDT, CurrencyCode.ZAR, new BigDecimal("0.000001"), MID, 50))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("too small");
    }

    @Test
    void adverseMoveDependsOnDirection() {
        // merchant sold USDT: we are long USDT, a falling price hurts
        assertThat(FxPricing.adverseMoveBps(CurrencyCode.USDT, MID, new BigDecimal("18.10"))).isEqualTo(83);
        assertThat(FxPricing.adverseMoveBps(CurrencyCode.USDT, MID, new BigDecimal("18.40"))).isNegative();
        // merchant bought USDT: we are short USDT, a rising price hurts
        assertThat(FxPricing.adverseMoveBps(CurrencyCode.ZAR, MID, new BigDecimal("18.40"))).isEqualTo(83);
    }

    @Test
    void sameKindPairsAreRejected() {
        assertThatThrownBy(() -> FxPricing.requireSupportedPair(CurrencyCode.ZAR, CurrencyCode.NGN))
                .isInstanceOf(ApiException.class);
    }
}
