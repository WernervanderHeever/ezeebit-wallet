package com.ezeebit.wallet.outbox;

/**
 * Performs the side effect for one message type. Handlers must be idempotent: a message can be
 * delivered more than once (e.g. the worker crashes after the side effect but before marking DONE).
 *
 * <p>Throw {@link PermanentOutboxFailure} for failures that retrying cannot fix; any other
 * exception is retried with exponential backoff up to the configured maximum.
 */
public interface OutboxHandler {

    String messageType();

    void handle(OutboxMessage message);
}
