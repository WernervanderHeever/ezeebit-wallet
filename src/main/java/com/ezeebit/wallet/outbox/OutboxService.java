package com.ezeebit.wallet.outbox;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

@Service
public class OutboxService {

    private final OutboxRepository repository;
    private final Clock clock;

    public OutboxService(OutboxRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * Records a side effect to perform after commit. MANDATORY: it must share the transaction of
     * the state change that requires it, so either both are stored or neither is.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(String messageType, String aggregateId, String payload) {
        repository.save(new OutboxMessage(messageType, aggregateId, payload, clock.instant()));
    }
}
