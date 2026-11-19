package com.ezeebit.wallet.withdrawal;

import com.ezeebit.wallet.common.Amounts;
import com.ezeebit.wallet.common.ApiException;
import com.ezeebit.wallet.common.CurrencyCode;
import com.ezeebit.wallet.common.RequestHash;
import com.ezeebit.wallet.ledger.AccountPurpose;
import com.ezeebit.wallet.ledger.AccountRef;
import com.ezeebit.wallet.ledger.JournalEntryType;
import com.ezeebit.wallet.ledger.JournalLine;
import com.ezeebit.wallet.ledger.JournalRequest;
import com.ezeebit.wallet.ledger.LedgerService;
import com.ezeebit.wallet.merchant.MerchantService;
import com.ezeebit.wallet.outbox.OutboxService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Task 3: paying money out of the platform.
 *
 * <p>The lifecycle is reserve -> submit -> settle-or-release:
 * <ol>
 *   <li><b>Request</b> (one DB transaction): claim the idempotency key, move the amount from the
 *       merchant's AVAILABLE account to its WITHDRAWAL_HOLD account (fails if they do not have it),
 *       and enqueue a SUBMIT_PAYOUT outbox message.</li>
 *   <li><b>Submit</b> (outbox, after commit, retried): call the rail with the withdrawal id as the
 *       rail-side idempotency reference.</li>
 *   <li><b>Callback</b> (one DB transaction): on success the held funds leave the platform; on failure
 *       they go back to AVAILABLE. Each of those ledger events can be posted at most once.</li>
 * </ol>
 * Because funds are reserved before the rail is ever called, concurrent withdrawals cannot overdraw,
 * and a failed payout always restores the balance.
 */
@Service
public class WithdrawalService {

    public static final String SUBMIT_MESSAGE = "SUBMIT_PAYOUT";
    private static final String REFERENCE_TYPE = "WITHDRAWAL";
    private static final Logger log = LoggerFactory.getLogger(WithdrawalService.class);

    public record WithdrawalResult(Withdrawal withdrawal, boolean replayed) {
    }

    public record CallbackCommand(String withdrawalId, String providerReference, String providerEventId,
                                  PayoutResult status, String reason) {
    }

    private final WithdrawalRepository withdrawals;
    private final PayoutCallbackRepository callbacks;
    private final LedgerService ledger;
    private final MerchantService merchants;
    private final OutboxService outbox;
    private final TransactionTemplate tx;
    private final Clock clock;

    public WithdrawalService(WithdrawalRepository withdrawals, PayoutCallbackRepository callbacks, LedgerService ledger,
                             MerchantService merchants, OutboxService outbox, TransactionTemplate tx, Clock clock) {
        this.withdrawals = withdrawals;
        this.callbacks = callbacks;
        this.ledger = ledger;
        this.merchants = merchants;
        this.outbox = outbox;
        this.tx = tx;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ request

    public WithdrawalResult request(long merchantId, String idempotencyKey, String currencyRaw, BigDecimal amountRaw,
                                    String destinationTypeRaw, String destinationRaw) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128) {
            throw ApiException.badRequest("IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key header (1-128 chars) is required");
        }
        merchants.require(merchantId);
        CurrencyCode currency = CurrencyCode.parse(currencyRaw);
        BigDecimal amount = Amounts.requireValidPositive(amountRaw, currency);
        DestinationType destinationType = DestinationType.parse(destinationTypeRaw);
        DestinationType.requireCompatible(currency, destinationType);
        String destination = destinationRaw.trim();
        String hash = RequestHash.of(merchantId, currency, amount.toPlainString(), destinationType, destination);

        var existing = withdrawals.findByMerchantIdAndIdempotencyKey(merchantId, idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get(), hash);
        }
        try {
            Withdrawal created = tx.execute(s -> reserve(merchantId, idempotencyKey, hash, currency, amount,
                    destinationType, destination));
            return new WithdrawalResult(created, false);
        } catch (DataAccessException race) {
            // Unique-key violation, lock timeout or deadlock with a concurrent identical request. Our
            // transaction rolled back, so nothing moved; return the winner's result if it has committed.
            log.warn("Concurrent/failed write for withdrawal key {}: {}", idempotencyKey, race.toString());
            return withdrawals.findByMerchantIdAndIdempotencyKey(merchantId, idempotencyKey)
                    .map(w -> replay(w, hash))
                    .orElseThrow(() -> ApiException.conflict("CONCURRENT_REQUEST",
                            "A request with this Idempotency-Key is in progress; retry"));
        }
    }

    private Withdrawal reserve(long merchantId, String idempotencyKey, String hash, CurrencyCode currency,
                               BigDecimal amount, DestinationType destinationType, String destination) {
        Instant now = clock.instant();
        // 1. Claim the idempotency key first: a duplicate fails here, before any balance is touched.
        Withdrawal w = withdrawals.saveAndFlush(new Withdrawal(UUID.randomUUID().toString(), merchantId,
                idempotencyKey, hash, currency, amount, destinationType, destination, now));
        // 2. Reserve the funds. Atomic conditional update: throws INSUFFICIENT_FUNDS (and rolls back
        //    the whole transaction) if the merchant does not have the amount available right now.
        ledger.post(new JournalRequest(JournalEntryType.WITHDRAWAL_HOLD, REFERENCE_TYPE, w.getId(),
                "Hold for withdrawal to " + destinationType,
                List.of(
                        JournalLine.debit(AccountRef.merchant(merchantId, currency, AccountPurpose.AVAILABLE), amount),
                        JournalLine.credit(AccountRef.merchant(merchantId, currency, AccountPurpose.WITHDRAWAL_HOLD), amount))));
        // 3. Schedule the payout. Committed atomically with the hold, delivered after commit.
        outbox.enqueue(SUBMIT_MESSAGE, w.getId(), null);
        return w;
    }

    private WithdrawalResult replay(Withdrawal w, String hash) {
        if (!w.getRequestHash().equals(hash)) {
            throw ApiException.unprocessable("IDEMPOTENCY_KEY_REUSED",
                    "This Idempotency-Key was already used for a different withdrawal");
        }
        return new WithdrawalResult(w, true);
    }

    public Withdrawal get(long merchantId, String withdrawalId) {
        return withdrawals.findById(withdrawalId)
                .filter(w -> w.getMerchantId() == merchantId)
                .orElseThrow(() -> ApiException.notFound("Withdrawal", withdrawalId));
    }

    public Withdrawal getById(String withdrawalId) {
        return withdrawals.findById(withdrawalId).orElseThrow(() -> ApiException.notFound("Withdrawal", withdrawalId));
    }

    // ------------------------------------------------------------------ submission results

    /** The rail accepted the instruction. No-op if a callback already resolved the withdrawal. */
    @Transactional
    public void markSubmitted(String withdrawalId, String providerReference) {
        Withdrawal w = lock(withdrawalId);
        if (w.getStatus() == WithdrawalStatus.PENDING_SUBMISSION) {
            w.markSubmitted(providerReference, clock.instant());
        } else {
            // The rail's callback beat our own ack; keep the terminal state, just remember the reference.
            w.recordProviderReference(providerReference);
        }
    }

    /** The rail definitively refused the instruction: nothing was paid, release the hold. */
    @Transactional
    public void markRejectedAtSubmission(String withdrawalId, String reason) {
        Withdrawal w = lock(withdrawalId);
        if (w.getStatus().isTerminal()) {
            return;
        }
        release(w, "Rejected by payout rail: " + reason);
    }

    // ------------------------------------------------------------------ callbacks

    /**
     * Applies an asynchronous result from the rail. Safe to call any number of times, in any order:
     * the first result wins, repeats are recorded as DUPLICATE, contradictions as CONFLICT (never
     * auto-reversed - a human must look at money that may already have left).
     */
    @Transactional
    public PayoutCallback.Outcome applyCallback(CallbackCommand cmd) {
        Objects.requireNonNull(cmd.status(), "status");
        Withdrawal w = lock(cmd.withdrawalId());
        Instant now = clock.instant();
        PayoutCallback.Outcome outcome;

        WithdrawalStatus target = cmd.status() == PayoutResult.SUCCEEDED ? WithdrawalStatus.COMPLETED : WithdrawalStatus.FAILED;
        boolean referenceMismatch = cmd.providerReference() != null && w.getProviderReference() != null
                && !cmd.providerReference().equals(w.getProviderReference());

        if (referenceMismatch) {
            outcome = PayoutCallback.Outcome.CONFLICT;
            log.error("ALERT payout callback for withdrawal {} has provider reference {} but we recorded {}",
                    w.getId(), cmd.providerReference(), w.getProviderReference());
        } else if (w.getStatus().isTerminal()) {
            outcome = w.getStatus() == target ? PayoutCallback.Outcome.DUPLICATE : PayoutCallback.Outcome.CONFLICT;
            if (outcome == PayoutCallback.Outcome.CONFLICT) {
                log.error("ALERT payout callback for withdrawal {} reports {} but it is already {}; manual review required",
                        w.getId(), cmd.status(), w.getStatus());
            }
        } else {
            if (cmd.providerReference() != null) {
                w.recordProviderReference(cmd.providerReference());
            }
            if (target == WithdrawalStatus.COMPLETED) {
                settle(w);
            } else {
                release(w, cmd.reason() == null ? "Payout failed" : cmd.reason());
            }
            outcome = PayoutCallback.Outcome.APPLIED;
        }
        callbacks.save(new PayoutCallback(w.getId(), cmd.providerEventId(), cmd.providerReference(),
                cmd.status(), cmd.reason(), outcome, now));
        return outcome;
    }

    /** Money has left: held funds move to the platform's payout clearing account. */
    private void settle(Withdrawal w) {
        BigDecimal amount = Amounts.normalise(w.getAmount(), w.getCurrency());
        ledger.post(new JournalRequest(JournalEntryType.WITHDRAWAL_SETTLED, REFERENCE_TYPE, w.getId(),
                "Payout completed, provider ref " + w.getProviderReference(),
                List.of(
                        JournalLine.debit(AccountRef.merchant(w.getMerchantId(), w.getCurrency(), AccountPurpose.WITHDRAWAL_HOLD), amount),
                        JournalLine.credit(AccountRef.platform(w.getCurrency(), AccountPurpose.PAYOUT_CLEARING), amount))));
        w.markCompleted(clock.instant());
    }

    /** Nothing was paid: held funds return to the merchant's available balance. */
    private void release(Withdrawal w, String reason) {
        BigDecimal amount = Amounts.normalise(w.getAmount(), w.getCurrency());
        ledger.post(new JournalRequest(JournalEntryType.WITHDRAWAL_RELEASED, REFERENCE_TYPE, w.getId(),
                "Payout failed, funds released: " + reason,
                List.of(
                        JournalLine.debit(AccountRef.merchant(w.getMerchantId(), w.getCurrency(), AccountPurpose.WITHDRAWAL_HOLD), amount),
                        JournalLine.credit(AccountRef.merchant(w.getMerchantId(), w.getCurrency(), AccountPurpose.AVAILABLE), amount))));
        w.markFailed(reason, clock.instant());
    }

    private Withdrawal lock(String withdrawalId) {
        return withdrawals.findForUpdate(withdrawalId)
                .orElseThrow(() -> ApiException.notFound("Withdrawal", withdrawalId));
    }
}
