package com.ezeebit.wallet.deposit;

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
@Table(name = "deposit")
public class Deposit {

    @Id
    private String id;

    @Column(name = "merchant_id", nullable = false)
    private long merchantId;

    @Column(nullable = false)
    private String source;

    @Column(name = "external_reference", nullable = false)
    private String externalReference;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CurrencyCode currency;

    @Column(nullable = false, precision = 38, scale = 8)
    private BigDecimal amount;

    @Column(name = "request_hash", nullable = false)
    private String requestHash;

    @Column(name = "journal_entry_id", nullable = false)
    private long journalEntryId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Version
    private Long version;

    protected Deposit() {
    }

    public Deposit(String id, long merchantId, String source, String externalReference, CurrencyCode currency,
                   BigDecimal amount, String requestHash, long journalEntryId, Instant createdAt) {
        this.id = id;
        this.merchantId = merchantId;
        this.source = source;
        this.externalReference = externalReference;
        this.currency = currency;
        this.amount = amount;
        this.requestHash = requestHash;
        this.journalEntryId = journalEntryId;
        this.createdAt = createdAt;
    }

    public String getId() {
        return id;
    }

    public long getMerchantId() {
        return merchantId;
    }

    public String getSource() {
        return source;
    }

    public String getExternalReference() {
        return externalReference;
    }

    public CurrencyCode getCurrency() {
        return currency;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public long getJournalEntryId() {
        return journalEntryId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
