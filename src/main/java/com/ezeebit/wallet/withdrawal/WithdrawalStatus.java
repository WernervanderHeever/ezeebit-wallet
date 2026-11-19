package com.ezeebit.wallet.withdrawal;

/**
 * PENDING_SUBMISSION -> SUBMITTED -> COMPLETED
 *                    \            \-> FAILED
 *                     \-> FAILED (rejected at submission) / COMPLETED (callback arrived before our ack)
 */
public enum WithdrawalStatus {
    /** Funds held; payout instruction queued in the outbox, not yet acknowledged by the rail. */
    PENDING_SUBMISSION,
    /** Rail accepted the instruction; waiting for its asynchronous result. Funds still held. */
    SUBMITTED,
    /** Rail confirmed the payout. Held funds have left the platform. */
    COMPLETED,
    /** Rail rejected or failed the payout. Held funds returned to the available balance. */
    FAILED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED;
    }
}
