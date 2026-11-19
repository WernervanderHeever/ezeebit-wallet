package com.ezeebit.wallet;

import com.ezeebit.wallet.common.ApiException;
import com.ezeebit.wallet.deposit.DepositService;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Task 1: holding balances across currencies, deposits, and the audit trail. */
class LedgerAndDepositTest extends IntegrationTestBase {

    @Autowired
    DepositService deposits;

    @Test
    void merchantHoldsSeparateBalancesPerCurrency() throws Exception {
        long m = createMerchant("ZAR");
        deposit(m, "ZAR", "1000.50");
        deposit(m, "USDT", "250.123456");

        assertThat(available(m, "ZAR")).isEqualTo("1000.50");
        assertThat(available(m, "USDT")).isEqualTo("250.123456");
        assertThat(available(m, "USDC")).isEqualTo("0.000000");
        assertLedgerHealthy();
    }

    @Test
    void depositToCurrencyNotYetHeldOpensTheAccount() throws Exception {
        long m = createMerchant("ZAR");
        deposit(m, "NGN", "5000");
        assertThat(available(m, "NGN")).isEqualTo("5000.00");
    }

    @Test
    void duplicateDepositNotificationIsCountedOnce() throws Exception {
        long m = createMerchant("ZAR");
        Map<String, Object> body = Map.of("source", "BANK_TRANSFER", "externalReference", "stmt-line-42",
                "currency", "ZAR", "amount", "100.00");

        JsonNode first = readJson(postJson("/api/v1/merchants/" + m + "/deposits", body).andExpect(status().isCreated()));
        JsonNode second = readJson(postJson("/api/v1/merchants/" + m + "/deposits", body).andExpect(status().isOk()));

        assertThat(second.get("id").asText()).isEqualTo(first.get("id").asText());
        assertThat(available(m, "ZAR")).isEqualTo("100.00");
    }

    @Test
    void reusingADepositReferenceWithDifferentDetailsIsRejected() throws Exception {
        long m = createMerchant("ZAR");
        postJson("/api/v1/merchants/" + m + "/deposits", Map.of("source", "BANK_TRANSFER",
                "externalReference", "stmt-7", "currency", "ZAR", "amount", "100.00")).andExpect(status().isCreated());

        postJson("/api/v1/merchants/" + m + "/deposits", Map.of("source", "BANK_TRANSFER",
                "externalReference", "stmt-7", "currency", "ZAR", "amount", "999.00"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(available(m, "ZAR")).isEqualTo("100.00");
    }

    @Test
    void amountsAreValidatedNeverSilentlyRounded() throws Exception {
        long m = createMerchant("ZAR");
        String path = "/api/v1/merchants/" + m + "/deposits";
        postJson(path, Map.of("source", "BANK", "externalReference", "a", "currency", "ZAR", "amount", "10.001"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("AMOUNT_PRECISION"));
        postJson(path, Map.of("source", "BANK", "externalReference", "b", "currency", "USDT", "amount", "1.1234567"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("AMOUNT_PRECISION"));
        postJson(path, Map.of("source", "BANK", "externalReference", "c", "currency", "ZAR", "amount", "-5"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("AMOUNT_NOT_POSITIVE"));
        postJson(path, Map.of("source", "BANK", "externalReference", "d", "currency", "XYZ", "amount", "5"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("UNSUPPORTED_CURRENCY"));
        assertThat(available(m, "ZAR")).isEqualTo("0.00");
    }

    @Test
    void largeAmountsStayExact() throws Exception {
        long m = createMerchant("ZAR");
        deposit(m, "ZAR", "999999999999.99");
        deposit(m, "ZAR", "0.01");
        deposit(m, "USDT", "123456789012.123456");
        assertThat(available(m, "ZAR")).isEqualTo("1000000000000.00");
        assertThat(available(m, "USDT")).isEqualTo("123456789012.123456");
        assertLedgerHealthy();
    }

    @Test
    void statementExplainsHowTheBalanceWasReached() throws Exception {
        long m = createMerchant("ZAR");
        deposit(m, "ZAR", "100.00");
        deposit(m, "ZAR", "50.25");

        mvc.perform(get("/api/v1/merchants/" + m + "/accounts/ZAR/statement"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentBalance").value("150.25"))
                .andExpect(jsonPath("$.lines.length()").value(2))
                .andExpect(jsonPath("$.lines[0].entryType").value("DEPOSIT"))
                .andExpect(jsonPath("$.lines[0].amount").value("100.00"))
                .andExpect(jsonPath("$.lines[0].balanceAfter").value("100.00"))
                .andExpect(jsonPath("$.lines[1].amount").value("50.25"))
                .andExpect(jsonPath("$.lines[1].balanceAfter").value("150.25"));
    }

    @Test
    void balancesCanBeReconstructedAtAPastMoment() throws Exception {
        long m = createMerchant("ZAR");
        deposit(m, "ZAR", "100.00");
        clock.advance(Duration.ofHours(1));
        Instant between = clock.instant();
        clock.advance(Duration.ofHours(1));
        deposit(m, "ZAR", "40.00");

        mvc.perform(get("/api/v1/merchants/" + m + "/balances").param("asOf", between.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.currency=='ZAR')].available").value("100.00"));
        assertThat(available(m, "ZAR")).isEqualTo("140.00");
    }

    @Test
    void concurrentDuplicateNotificationsCreditOnce() throws Exception {
        long m = createMerchant("ZAR");
        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        Set<String> ids = ConcurrentHashMap.newKeySet();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    ids.add(deposits.record(m, "CHAIN", "0xabc:0", "USDT", new BigDecimal("75")).deposit().getId());
                } catch (ApiException e) {
                    assertThat(e.code()).isEqualTo("CONCURRENT_REQUEST"); // client would retry
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();

        assertThat(ids).hasSize(1);
        assertThat(available(m, "USDT")).isEqualTo("75.000000");
        assertLedgerHealthy();
    }
}
