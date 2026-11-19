package com.ezeebit.wallet.common;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;

@ConfigurationProperties(prefix = "wallet")
public record WalletProperties(Fx fx, Outbox outbox, PayoutRail payoutRail, SimulatedRates simulatedRates) {

    public record Fx(int spreadBps,
                     Duration quoteTtl,
                     Duration rateTimeout,
                     Duration maxRateAge,
                     int maxRateJumpBps,
                     Duration rateJumpWindow,
                     int maxAdverseMoveBps,
                     boolean honourQuoteWhenRateUnavailable) {
    }

    public record Outbox(boolean enabled, long pollIntervalMs, int batchSize, int maxAttempts, Duration lease) {
    }

    public record PayoutRail(String mode, Duration callbackDelay, boolean duplicateCallbacks) {
    }

    public record SimulatedRates(long latencyMs, int jitterBps, Map<String, BigDecimal> prices) {
    }
}
