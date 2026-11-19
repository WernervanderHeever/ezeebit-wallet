package com.ezeebit.wallet.outbox;

import com.ezeebit.wallet.common.WalletProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Delivers outbox messages to their handlers. External calls happen OUTSIDE any database
 * transaction, so a slow partner never holds a lock on a merchant's balance.
 */
@Service
public class OutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(5);

    private final OutboxRepository repository;
    private final Map<String, OutboxHandler> handlers;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final WalletProperties.Outbox config;

    public OutboxDispatcher(OutboxRepository repository, List<OutboxHandler> handlers, TransactionTemplate tx,
                            Clock clock, WalletProperties properties) {
        this.repository = repository;
        this.handlers = handlers.stream().collect(Collectors.toMap(OutboxHandler::messageType, Function.identity()));
        this.tx = tx;
        this.clock = clock;
        this.config = properties.outbox();
    }

    /** Processes one batch of due messages. Returns how many this worker processed. */
    public int dispatchDue() {
        Instant now = clock.instant();
        List<Long> due = repository.findDueIds(now, OutboxStatus.PENDING, OutboxStatus.IN_PROGRESS,
                PageRequest.of(0, config.batchSize()));
        int processed = 0;
        for (Long id : due) {
            if (dispatchOne(id)) {
                processed++;
            }
        }
        return processed;
    }

    private boolean dispatchOne(long id) {
        Instant now = clock.instant();
        Integer claimed = tx.execute(s -> repository.claim(id, now, now.plus(config.lease()),
                OutboxStatus.PENDING, OutboxStatus.IN_PROGRESS));
        if (claimed == null || claimed == 0) {
            return false; // another worker has it
        }
        OutboxMessage message = repository.findById(id).orElseThrow();
        OutboxHandler handler = handlers.get(message.getMessageType());
        try {
            if (handler == null) {
                throw new PermanentOutboxFailure("No handler for message type " + message.getMessageType());
            }
            handler.handle(message);
            update(id, m -> m.markDone(clock.instant()));
        } catch (PermanentOutboxFailure e) {
            log.error("ALERT outbox message {} ({} {}) failed permanently: {}",
                    id, message.getMessageType(), message.getAggregateId(), e.getMessage());
            update(id, m -> m.markDead(e.getMessage(), clock.instant()));
        } catch (RuntimeException e) {
            if (message.getAttempts() >= config.maxAttempts()) {
                log.error("ALERT outbox message {} ({} {}) gave up after {} attempts: {}",
                        id, message.getMessageType(), message.getAggregateId(), message.getAttempts(), e.toString());
                update(id, m -> m.markDead(e.toString(), clock.instant()));
            } else {
                Instant retryAt = clock.instant().plus(backoff(message.getAttempts()));
                log.warn("Outbox message {} ({} {}) attempt {} failed, retrying at {}: {}",
                        id, message.getMessageType(), message.getAggregateId(), message.getAttempts(), retryAt, e.toString());
                update(id, m -> m.reschedule(retryAt, e.toString(), clock.instant()));
            }
        }
        return true;
    }

    static Duration backoff(int attempts) {
        long seconds = 1L << Math.min(Math.max(attempts - 1, 0), 16); // 1s, 2s, 4s, ...
        Duration d = Duration.ofSeconds(seconds);
        return d.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : d;
    }

    private void update(long id, java.util.function.Consumer<OutboxMessage> change) {
        tx.executeWithoutResult(s -> change.accept(repository.findById(id).orElseThrow()));
    }
}
