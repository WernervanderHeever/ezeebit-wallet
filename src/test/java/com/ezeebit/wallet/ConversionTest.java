package com.ezeebit.wallet;

import com.ezeebit.wallet.common.ApiException;
import com.ezeebit.wallet.common.CurrencyCode;
import com.ezeebit.wallet.fx.ConversionService;
import com.ezeebit.wallet.fx.FxQuote;
import com.ezeebit.wallet.fx.RateSnapshot;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Task 2: quotes and conversions. Mid USDT/ZAR = 18.25, spread 50 bps. */
class ConversionTest extends IntegrationTestBase {

    @Autowired
    ConversionService conversions;

    private String quote(long m, String from, String to, String amount) throws Exception {
        JsonNode q = readJson(postJson("/api/v1/merchants/" + m + "/fx/quotes",
                Map.of("fromCurrency", from, "toCurrency", to, "amount", amount)).andExpect(status().isCreated()));
        return q.get("quoteId").asText();
    }

    @Test
    void sellUsdtForZarAtTheQuotedBid() throws Exception {
        long m = createMerchant("ZAR");
        deposit(m, "USDT", "100");

        JsonNode q = readJson(postJson("/api/v1/merchants/" + m + "/fx/quotes",
                Map.of("fromCurrency", "USDT", "toCurrency", "ZAR", "amount", "100")).andExpect(status().isCreated()));
        // bid = 18.25 * (1 - 0.005) = 18.15875 ; 100 * 18.15875 = 1815.875 -> rounded DOWN to 1815.87
        assertThat(q.get("price").asText()).isEqualTo("18.15875");
        assertThat(q.get("toAmount").asText()).isEqualTo("1815.87");

        postJson("/api/v1/merchants/" + m + "/fx/conversions", Map.of("quoteId", q.get("quoteId").asText()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.toAmount").value("1815.87"));

        assertThat(available(m, "USDT")).isEqualTo("0.000000");
        assertThat(available(m, "ZAR")).isEqualTo("1815.87");
        assertLedgerHealthy();
    }

    @Test
    void buyUsdtWithZarAtTheQuotedAsk() throws Exception {
        long m = createMerchant("ZAR");
        deposit(m, "ZAR", "1000");
        String quoteId = quote(m, "ZAR", "USDT", "1000");
        // ask = 18.25 * 1.005 = 18.34125 ; 1000 / 18.34125 = 54.5219104... -> 54.521910
        postJson("/api/v1/merchants/" + m + "/fx/conversions", Map.of("quoteId", quoteId))
                .andExpect(status().isCreated());
        assertThat(available(m, "ZAR")).isEqualTo("0.00");
        assertThat(available(m, "USDT")).isEqualTo("54.521910");
        assertLedgerHealthy();
    }

    @Test
    void executingTheSameQuoteTwiceConvertsOnce() throws Exception {
        long m = createMerchant("ZAR");
        deposit(m, "USDT", "200");
        String quoteId = quote(m, "USDT", "ZAR", "100");

        JsonNode first = readJson(postJson("/api/v1/merchants/" + m + "/fx/conversions", Map.of("quoteId", quoteId))
                .andExpect(status().isCreated()));
        JsonNode second = readJson(postJson("/api/v1/merchants/" + m + "/fx/conversions", Map.of("quoteId", quoteId))
                .andExpect(status().isOk()));

        assertThat(second.get("conversionId").asText()).isEqualTo(first.get("conversionId").asText());
        assertThat(available(m, "USDT")).isEqualTo("100.000000");
        assertThat(available(m, "ZAR")).isEqualTo("1815.87");
    }

    @Test
    void expiredQuoteCannotBeExecuted() throws Exception {
        long m = createMerchant("ZAR");
        deposit(m, "USDT", "100");
        String quoteId = quote(m, "USDT", "ZAR", "100");
        clock.advance(Duration.ofSeconds(31));

        postJson("/api/v1/merchants/" + m + "/fx/conversions", Map.of("quoteId", quoteId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("QUOTE_EXPIRED"));
        assertThat(available(m, "USDT")).isEqualTo("100.000000");
    }

    @Test
    void cannotConvertMoreThanHeld() throws Exception {
        long m = createMerchant("ZAR");
        deposit(m, "USDT", "10");
        String quoteId = quote(m, "USDT", "ZAR", "50");

        postJson("/api/v1/merchants/" + m + "/fx/conversions", Map.of("quoteId", quoteId))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));
        assertThat(available(m, "USDT")).isEqualTo("10.000000");
        assertThat(available(m, "ZAR")).isEqualTo("0.00");
        assertThat(conversions.getQuote(m, quoteId).getStatus()).isEqualTo(FxQuote.Status.OPEN);
        assertLedgerHealthy();
    }

    @Test
    void lastLookRefusesAQuoteTheMarketHasMovedThrough() throws Exception {
        long m = createMerchant("ZAR");
        deposit(m, "USDT", "100");
        String quoteId = quote(m, "USDT", "ZAR", "100");
        market.put("USDT/ZAR", new BigDecimal("18.10")); // -82 bps: we would be buying above market

        postJson("/api/v1/merchants/" + m + "/fx/conversions", Map.of("quoteId", quoteId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("QUOTE_REPRICE_REQUIRED"));
        assertThat(available(m, "USDT")).isEqualTo("100.000000");
    }

    @Test
    void favourableOrSmallMovesStillExecute() throws Exception {
        long m = createMerchant("ZAR");
        deposit(m, "USDT", "100");
        String quoteId = quote(m, "USDT", "ZAR", "100");
        market.put("USDT/ZAR", new BigDecimal("18.30")); // moved in the platform's favour

        postJson("/api/v1/merchants/" + m + "/fx/conversions", Map.of("quoteId", quoteId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.toAmount").value("1815.87")); // merchant still gets exactly the quote
    }

    @Test
    void slowRateServiceTimesOut() throws Exception {
        long m = createMerchant("ZAR");
        when(rateProvider.fetch(any(), any())).thenAnswer(inv -> {
            Thread.sleep(2_000);
            return null;
        });
        postJson("/api/v1/merchants/" + m + "/fx/quotes", Map.of("fromCurrency", "USDT", "toCurrency", "ZAR", "amount", "10"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("RATE_UNAVAILABLE"));
    }

    @Test
    void untrustworthyRatesAreRejected() throws Exception {
        long m = createMerchant("ZAR");
        Map<String, Object> req = Map.of("fromCurrency", "USDT", "toCurrency", "ZAR", "amount", "10");
        String path = "/api/v1/merchants/" + m + "/fx/quotes";

        when(rateProvider.fetch(any(), any())).thenReturn(
                new RateSnapshot(CurrencyCode.USDT, CurrencyCode.ZAR, BigDecimal.ZERO, clock.instant(), "test"));
        postJson(path, req).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("RATE_REJECTED"));

        when(rateProvider.fetch(any(), any())).thenReturn(new RateSnapshot(CurrencyCode.USDT, CurrencyCode.ZAR,
                new BigDecimal("18.25"), clock.instant().minus(Duration.ofMinutes(5)), "test"));
        postJson(path, req).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("RATE_REJECTED"));

        when(rateProvider.fetch(any(), any())).thenReturn(
                new RateSnapshot(CurrencyCode.USDT, CurrencyCode.NGN, new BigDecimal("1550"), clock.instant(), "test"));
        postJson(path, req).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("RATE_REJECTED"));

        // a sane rate, then a 10x "fat finger" jump
        when(rateProvider.fetch(any(), any())).thenReturn(
                new RateSnapshot(CurrencyCode.USDT, CurrencyCode.ZAR, new BigDecimal("18.25"), clock.instant(), "test"));
        postJson(path, req).andExpect(status().isCreated());
        when(rateProvider.fetch(any(), any())).thenReturn(
                new RateSnapshot(CurrencyCode.USDT, CurrencyCode.ZAR, new BigDecimal("182.5"), clock.instant(), "test"));
        postJson(path, req).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("RATE_REJECTED"));
    }

    @Test
    void onlyFiatStablecoinPairsAreSupported() throws Exception {
        long m = createMerchant("ZAR");
        postJson("/api/v1/merchants/" + m + "/fx/quotes", Map.of("fromCurrency", "USDT", "toCurrency", "USDC", "amount", "10"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_PAIR"));
    }

    @Test
    void concurrentExecutionsOfOneQuoteConvertOnce() throws Exception {
        long m = createMerchant("ZAR");
        deposit(m, "USDT", "1000");
        String quoteId = quote(m, "USDT", "ZAR", "100");

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        Set<String> conversionIds = ConcurrentHashMap.newKeySet();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    conversionIds.add(conversions.execute(m, quoteId).conversion().getId());
                } catch (ApiException e) {
                    assertThat(e.code()).isEqualTo("CONCURRENT_REQUEST");
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();

        assertThat(conversionIds).hasSize(1);
        assertThat(available(m, "USDT")).isEqualTo("900.000000");
        assertThat(available(m, "ZAR")).isEqualTo("1815.87");
        assertLedgerHealthy();
    }
}
