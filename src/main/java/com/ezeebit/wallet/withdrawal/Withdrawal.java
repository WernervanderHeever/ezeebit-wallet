package com.ezeebit.wallet.withdrawal;

import com.ezeebit.wallet.common.CurrencyCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "withdrawal")
public class Withdrawal {

    @Id
    private String id;

    @Column(name = "merchant_id", nullable = false)
    private long merchantId;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false)
    private String requestHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CurrencyCode currency;

    @Column(nullable = false, precision = 38, scale = 8)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "destination_type", nullable = false)
    private DestinationType destinationType;

    @Column(nullable = false)
    private String destination;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WithdrawalStatus status;

    @Column(name = "provider_reference")
    private String providerReference;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    protected Withdrawal() {
    }

    public Withdrawal(String id, long merchantId, String idempotencyKey, String requestHash, CurrencyCode currency,
                      BigDecimal amount, DestinationType destinationType, String destination, Instant now) {
        this.id = id;
        this.merchantId = merchantId;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.currency = currency;
        this.amount = amount;
        this.destinationType = destinationType;
        this.destination = destination;
        this.status = WithdrawalStatus.PENDING_SUBMISSION;
        this.createdAt = now;
        this.updatedAt = now;
    }

    void markSubmitted(String providerReference, Instant now) {
        requireStatus(WithdrawalStatus.PENDING_SUBMISSION);
        this.status = WithdrawalStatus.SUBMITTED;
        this.providerReference = providerReference;
        this.updatedAt = now;
    }

    void markCompleted(Instant now) {
        requireNotTerminal();
        this.status = WithdrawalStatus.COMPLETED;
        this.updatedAt = now;
    }

    void markFailed(String reason, Instant now) {
        requireNotTerminal();
        this.status = WithdrawalStatus.FAILED;
        this.failureReason = reason == null ? null : (reason.length() > 512 ? reason.substring(0, 512) : reason);
        this.updatedAt = now;
    }

    void recordProviderReference(String providerReference) {
        if (this.providerReference == null) {
            this.providerReference = providerReference;
        }
    }

    private void requireStatus(WithdrawalStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("Withdrawal " + id + " is " + status + ", expected " + expected);
        }
    }

    private void requireNotTerminal() {
        if (status.isTerminal()) {
            throw new IllegalStateException("Withdrawal " + id + " is already " + status);
        }
    }

    public String getId() {
        return id;
    }

    public long getMerchantId() {
        return merchantId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public CurrencyCode getCurrency() {
        return currency;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public DestinationType getDestinationType() {
        return destinationType;
    }

    public String getDestination() {
        return destination;
    }

    public WithdrawalStatus getStatus() {
        return status;
    }

    public String getProviderReference() {
        return providerReference;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
