package com.ezeebit.wallet.withdrawal;

import com.ezeebit.wallet.outbox.OutboxHandler;
import com.ezeebit.wallet.outbox.OutboxMessage;
import com.ezeebit.wallet.outbox.PermanentOutboxFailure;
import org.springframework.stereotype.Component;

/**
 * Sends a held withdrawal to the payout rail. Runs outside any DB transaction.
 *
 * <p>Retries are safe because the withdrawal id is the rail's idempotency reference. If the rail
 * stays unreachable past the retry budget the message goes DEAD and the funds stay HELD - we never
 * release funds on an <em>ambiguous</em> failure, because the rail may in fact have paid out.
 */
@Component
public class SubmitPayoutHandler implements OutboxHandler {

    private final WithdrawalRepository withdrawals;
    private final WithdrawalService service;
    private final PayoutRail rail;

    public SubmitPayoutHandler(WithdrawalRepository withdrawals, WithdrawalService service, PayoutRail rail) {
        this.withdrawals = withdrawals;
        this.service = service;
        this.rail = rail;
    }

    @Override
    public String messageType() {
        return WithdrawalService.SUBMIT_MESSAGE;
    }

    @Override
    public void handle(OutboxMessage message) {
        Withdrawal w = withdrawals.findById(message.getAggregateId())
                .orElseThrow(() -> new PermanentOutboxFailure("Unknown withdrawal " + message.getAggregateId()));
        if (w.getStatus() != WithdrawalStatus.PENDING_SUBMISSION) {
            return; // already acknowledged, or already resolved by a callback
        }
        PayoutRail.SubmitResult result = rail.submit(new PayoutRail.PayoutInstruction(
                w.getId(), w.getCurrency(), w.getAmount(), w.getDestinationType(), w.getDestination()));
        if (result.accepted()) {
            service.markSubmitted(w.getId(), result.providerReference());
        } else {
            service.markRejectedAtSubmission(w.getId(), result.rejectionReason());
        }
    }
}
