package com.ezeebit.wallet.fx;

import com.ezeebit.wallet.common.CurrencyCode;
import com.ezeebit.wallet.common.WalletProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/** Stand-in for the real rate feed: configurable latency and a small random walk on every call. */
@Component
public class SimulatedRateProvider implements RateProvider {

    private final Map<String, BigDecimal> prices = new ConcurrentHashMap<>();
    private final long latencyMs;
    private final int jitterBps;
    private final Clock clock;

    public SimulatedRateProvider(WalletProperties properties, Clock clock) {
        WalletProperties.SimulatedRates cfg = properties.simulatedRates();
        if (cfg != null && cfg.prices() != null) {
            cfg.prices().forEach((pair, price) -> prices.put(pair.toLowerCase(Locale.ROOT), price));
        }
        this.latencyMs = cfg == null ? 0 : cfg.latencyMs();
        this.jitterBps = cfg == null ? 0 : cfg.jitterBps();
        this.clock = clock;
    }

    @Override
    public RateSnapshot fetch(CurrencyCode stablecoin, CurrencyCode fiat) {
        if (latencyMs > 0) {
            try {
                Thread.sleep(latencyMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted", e);
            }
        }
        String key = (stablecoin.name() + "-" + fiat.name()).toLowerCase(Locale.ROOT);
        BigDecimal price = prices.computeIfPresent(key, (k, p) -> {
            int moveBps = jitterBps == 0 ? 0 : ThreadLocalRandom.current().nextInt(-jitterBps, jitterBps + 1);
            return p.multiply(BigDecimal.ONE.add(BigDecimal.valueOf(moveBps, 4))).setScale(6, RoundingMode.HALF_EVEN);
        });
        if (price == null) {
            throw new IllegalStateException("No simulated price for " + key);
        }
        return new RateSnapshot(stablecoin, fiat, price, clock.instant(), "simulated");
    }
}
