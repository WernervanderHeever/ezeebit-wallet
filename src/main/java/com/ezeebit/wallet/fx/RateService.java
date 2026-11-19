package com.ezeebit.wallet.fx;

import com.ezeebit.wallet.common.ApiException;
import com.ezeebit.wallet.common.CurrencyCode;
import com.ezeebit.wallet.common.WalletProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Defensive wrapper around the exchange-rate service. A rate is only used if it:
 * arrives within the timeout, is for the pair we asked for, is positive, is fresh, and is not an
 * implausible jump from the last rate we accepted. Anything else is rejected and the caller gets a
 * 503 - we never price a conversion off a rate we cannot trust.
 */
@Service
public class RateService implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(RateService.class);
    private static final Duration MAX_CLOCK_SKEW = Duration.ofSeconds(5);

    private final RateProvider provider;
    private final Clock clock;
    private final WalletProperties.Fx config;
    // Bulkhead: a slow rate feed can tie up at most these threads, never the request pool.
    private final ExecutorService executor = Executors.newFixedThreadPool(8, r -> {
        Thread t = new Thread(r, "rate-fetch");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, RateSnapshot> lastAccepted = new ConcurrentHashMap<>();

    public RateService(RateProvider provider, Clock clock, WalletProperties properties) {
        this.provider = provider;
        this.clock = clock;
        this.config = properties.fx();
    }

    public RateSnapshot currentRate(CurrencyCode stablecoin, CurrencyCode fiat) {
        CompletableFuture<RateSnapshot> future = CompletableFuture.supplyAsync(() -> provider.fetch(stablecoin, fiat), executor);
        RateSnapshot snapshot;
        try {
            snapshot = future.get(config.rateTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw ApiException.unavailable("RATE_UNAVAILABLE", "Exchange-rate service timed out; please retry");
        } catch (ExecutionException e) {
            log.warn("Exchange-rate service failed for {}/{}: {}", stablecoin, fiat, String.valueOf(e.getCause()));
            throw ApiException.unavailable("RATE_UNAVAILABLE", "Exchange-rate service is unavailable; please retry");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ApiException.unavailable("RATE_UNAVAILABLE", "Interrupted while fetching rate");
        }
        validate(stablecoin, fiat, snapshot);
        lastAccepted.put(key(stablecoin, fiat), snapshot);
        return snapshot;
    }

    private void validate(CurrencyCode stablecoin, CurrencyCode fiat, RateSnapshot s) {
        Instant now = clock.instant();
        if (s == null || s.price() == null || s.asOf() == null) {
            reject(stablecoin, fiat, "incomplete rate", s);
        }
        if (s.stablecoin() != stablecoin || s.fiat() != fiat) {
            reject(stablecoin, fiat, "rate is for the wrong pair " + s.stablecoin() + "/" + s.fiat(), s);
        }
        if (s.price().signum() <= 0) {
            reject(stablecoin, fiat, "non-positive price", s);
        }
        if (s.asOf().isBefore(now.minus(config.maxRateAge()))) {
            reject(stablecoin, fiat, "stale rate from " + s.asOf(), s);
        }
        if (s.asOf().isAfter(now.plus(MAX_CLOCK_SKEW))) {
            reject(stablecoin, fiat, "rate timestamp in the future " + s.asOf(), s);
        }
        RateSnapshot previous = lastAccepted.get(key(stablecoin, fiat));
        if (previous != null && previous.asOf().isAfter(now.minus(config.rateJumpWindow()))) {
            BigDecimal jumpBps = s.price().subtract(previous.price()).abs()
                    .multiply(BigDecimal.valueOf(10_000))
                    .divide(previous.price(), 2, RoundingMode.HALF_UP);
            if (jumpBps.compareTo(BigDecimal.valueOf(config.maxRateJumpBps())) > 0) {
                reject(stablecoin, fiat, "price jumped " + jumpBps + "bps from " + previous.price(), s);
            }
        }
    }

    private void reject(CurrencyCode stablecoin, CurrencyCode fiat, String reason, RateSnapshot s) {
        log.error("ALERT rejected exchange rate for {}/{}: {} ({})", stablecoin, fiat, reason, s);
        throw ApiException.unavailable("RATE_REJECTED", "Could not obtain a reliable exchange rate; please retry");
    }

    private static String key(CurrencyCode stablecoin, CurrencyCode fiat) {
        return stablecoin.name() + "/" + fiat.name();
    }

    /** Test hook: forget accepted history. */
    public void resetHistory() {
        lastAccepted.clear();
    }

    @Override
    public void destroy() {
        executor.shutdownNow();
    }
}
