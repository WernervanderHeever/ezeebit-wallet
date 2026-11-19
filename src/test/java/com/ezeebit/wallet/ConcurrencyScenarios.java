package com.ezeebit.wallet;

import com.ezeebit.wallet.common.ApiException;
import com.ezeebit.wallet.withdrawal.WithdrawalRepository;
import com.ezeebit.wallet.withdrawal.WithdrawalService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Races that must never lose or duplicate money. Run against H2 by default and against real MySQL
 * when Docker is available (see MySqlConcurrencyTest).
 */
abstract class ConcurrencyScenarios extends IntegrationTestBase {

    @Autowired
    WithdrawalService withdrawals;
    @Autowired
    WithdrawalRepository withdrawalRepository;

    @Test
    void concurrentWithdrawalsNeverOverdraw() throws Exception {
        long m = createMerchant("ZAR");
        deposit(m, "ZAR", "100.00");

        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger insufficient = new AtomicInteger();
        runConcurrently(20, i -> () -> {
            for (int attempt = 0; attempt < 20; attempt++) { // a real client retries CONCURRENT_REQUEST with the same key
                try {
                    withdrawals.request(m, "burst-" + i, "ZAR", new BigDecimal("10.00"), "BANK_ACCOUNT", "FNB 1");
                    accepted.incrementAndGet();
                    return null;
                } catch (ApiException e) {
                    if ("INSUFFICIENT_FUNDS".equals(e.code())) {
                        insufficient.incrementAndGet();
                        return null;
                    }
                    if (!"CONCURRENT_REQUEST".equals(e.code())) {
                        throw e;
                    }
                    Thread.sleep(20);
                }
            }
            throw new AssertionError("request never settled");
        });

        assertThat(accepted.get()).isEqualTo(10);
        assertThat(insufficient.get()).isEqualTo(10);
        assertThat(available(m, "ZAR")).isEqualTo("0.00");
        assertThat(held(m, "ZAR")).isEqualTo("100.00");
        assertLedgerHealthy();
    }

    @Test
    void concurrentRetriesWithOneKeyCreateOneWithdrawal() throws Exception {
        long m = createMerchant("ZAR");
        deposit(m, "ZAR", "100.00");

        Set<String> ids = ConcurrentHashMap.newKeySet();
        runConcurrently(10, i -> () -> {
            try {
                ids.add(withdrawals.request(m, "same-key", "ZAR", new BigDecimal("30.00"), "BANK_ACCOUNT", "FNB 1")
                        .withdrawal().getId());
            } catch (ApiException e) {
                assertThat(e.code()).isEqualTo("CONCURRENT_REQUEST"); // safe for the client to retry
            }
            return null;
        });

        assertThat(ids).hasSize(1);
        assertThat(withdrawalRepository.findByMerchantId(m)).hasSize(1);
        assertThat(available(m, "ZAR")).isEqualTo("70.00");
        assertThat(held(m, "ZAR")).isEqualTo("30.00");
        assertLedgerHealthy();
    }

    interface TaskFactory {
        Callable<Void> create(int index);
    }

    protected void runConcurrently(int threads, TaskFactory factory) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Void>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Callable<Void> task = factory.create(i);
            futures.add(pool.submit(() -> {
                start.await();
                return task.call();
            }));
        }
        start.countDown();
        try {
            for (Future<Void> f : futures) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
