package com.ezeebit.wallet.withdrawal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** Audit record of every callback received from the payout rail, whatever we did with it. */
@Entity
@Table(name = "payout_callback")
public class PayoutCallback {

    public enum Outcome {
        /** Moved the withdrawal to a terminal state and posted to the ledger. */
        APPLIED,
        /** Repeat of a result we already applied; nothing changed. */
        DUPLICATE,
        /** Contradicts what we already recorded; nothing changed, operator alerted. */
        CONFLICT
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "withdrawal_id", nullable = false)
    private String withdrawalId;

    @Column(name = "provider_event_id")
    private String providerEventId;

    @Column(name = "provider_reference")
    private String providerReference;

    @Enumerated(EnumType.STRING)
    @Column(name = "reported_status", nullable = false)
    private PayoutResult reportedStatus;

    @Column
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Outcome outcome;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    protected PayoutCallback() {
    }

    public PayoutCallback(String withdrawalId, String providerEventId, String providerReference,
                          PayoutResult reportedStatus, String reason, Outcome outcome, Instant receivedAt) {
        this.withdrawalId = withdrawalId;
        this.providerEventId = providerEventId;
        this.providerReference = providerReference;
        this.reportedStatus = reportedStatus;
        this.reason = reason == null ? null : (reason.length() > 512 ? reason.substring(0, 512) : reason);
        this.outcome = outcome;
        this.receivedAt = receivedAt;
    }

    public String getWithdrawalId() {
        return withdrawalId;
    }

    public Outcome getOutcome() {
        return outcome;
    }

    public PayoutResult getReportedStatus() {
        return reportedStatus;
    }
}
