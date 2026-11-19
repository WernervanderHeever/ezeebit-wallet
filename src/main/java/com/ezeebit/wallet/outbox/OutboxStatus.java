package com.ezeebit.wallet.outbox;

public enum OutboxStatus {
    PENDING,
    IN_PROGRESS,
    DONE,
    /** Gave up after max attempts or a permanent failure; needs an operator. Never silently dropped. */
    DEAD
}
