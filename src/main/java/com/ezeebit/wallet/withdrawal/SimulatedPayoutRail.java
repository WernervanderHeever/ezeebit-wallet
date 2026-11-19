package com.ezeebit.wallet.withdrawal;

import com.ezeebit.wallet.common.WalletProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Local stand-in for the payout rail so the whole flow can be exercised end to end.
 * Behaviour is driven by the destination string:
 * <ul>
 *   <li>contains "REJECT" - rejected synchronously at submission</li>
 *   <li>contains "FLAKY"  - first submission times out (ambiguous), retry succeeds</li>
 *   <li>contains "FAIL"   - accepted, then fails asynchronously</li>
 *   <li>anything else     - accepted, then succeeds asynchronously</li>
 * </ul>
 * Like a real at-least-once rail it may deliver each callback twice.
 */
@Component
@ConditionalOnProperty(prefix = "wallet.payout-rail", name = "mode", havingValue = "simulated", matchIfMissing = true)
public class SimulatedPayoutRail implements PayoutRail, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(SimulatedPayoutRail.class);

    private final WithdrawalService withdrawals;
    private final Duration callbackDelay;
    private final boolean duplicateCallbacks;
    private final Map<String, String> acceptedByReference = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> attemptsByReference = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "simulated-payout-rail");
        t.setDaemon(true);
        return t;
    });

    public SimulatedPayoutRail(@Lazy WithdrawalService withdrawals, WalletProperties properties) {
        this.withdrawals = withdrawals;
        this.callbackDelay = properties.payoutRail().callbackDelay();
        this.duplicateCallbacks = properties.payoutRail().duplicateCallbacks();
    }

    @Override
    public SubmitResult submit(PayoutInstruction instruction) {
        String destination = instruction.destination().toUpperCase(Locale.ROOT);
        int attempt = attemptsByReference.computeIfAbsent(instruction.reference(), k -> new AtomicInteger()).incrementAndGet();
        if (destination.contains("REJECT")) {
            return SubmitResult.rejected("Destination failed validation");
        }
        if (destination.contains("FLAKY") && attempt == 1) {
            throw new PayoutRailUnavailableException("Simulated timeout talking to payout rail");
        }
        // Idempotent on our reference: a resubmission returns the original acceptance, pays once.
        String existing = acceptedByReference.get(instruction.reference());
        if (existing != null) {
            return SubmitResult.accepted(existing);
        }
        String providerRef = "SIM-" + UUID.randomUUID();
        acceptedByReference.put(instruction.reference(), providerRef);
        PayoutResult result = destination.contains("FAIL") ? PayoutResult.FAILED : PayoutResult.SUCCEEDED;
        String eventId = UUID.randomUUID().toString();
        Runnable callback = () -> {
            try {
                withdrawals.applyCallback(new WithdrawalService.CallbackCommand(instruction.reference(), providerRef,
                        eventId, result, result == PayoutResult.FAILED ? "Beneficiary bank declined" : null));
            } catch (RuntimeException e) {
                log.warn("Simulated callback for {} failed: {}", instruction.reference(), e.toString());
            }
        };
        scheduler.schedule(callback, callbackDelay.toMillis(), TimeUnit.MILLISECONDS);
        if (duplicateCallbacks) {
            scheduler.schedule(callback, callbackDelay.toMillis() + 250, TimeUnit.MILLISECONDS);
        }
        return SubmitResult.accepted(providerRef);
    }

    @Override
    public void destroy() {
        scheduler.shutdownNow();
    }
}
