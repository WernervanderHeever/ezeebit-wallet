package com.ezeebit.wallet.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Background trigger for the dispatcher. Disabled in tests, which drive the dispatcher directly. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "wallet.outbox", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPoller {

    private final OutboxDispatcher dispatcher;

    public OutboxPoller(OutboxDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Scheduled(fixedDelayString = "${wallet.outbox.poll-interval-ms:500}")
    public void poll() {
        dispatcher.dispatchDue();
    }
}
