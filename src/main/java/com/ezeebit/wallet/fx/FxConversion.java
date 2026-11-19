package com.ezeebit.wallet.fx;

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
@Table(name = "fx_conversion")
public class FxConversion {

    @Id
    private String id;

    @Column(name = "quote_id", nullable = false)
    private String quoteId;

    @Column(name = "merchant_id", nullable = false)
    private long merchantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_currency", nullable = false)
    private CurrencyCode fromCurrency;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_currency", nullable = false)
    private CurrencyCode toCurrency;

    @Column(name = "from_amount", nullable = false, precision = 38, scale = 8)
    private BigDecimal fromAmount;

    @Column(name = "to_amount", nullable = false, precision = 38, scale = 8)
    private BigDecimal toAmount;

    @Column(name = "client_price", nullable = false, precision = 38, scale = 12)
    private BigDecimal clientPrice;

    @Column(name = "journal_entry_id", nullable = false)
    private long journalEntryId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Version
    private Long version;

    protected FxConversion() {
    }

    public FxConversion(String id, FxQuote quote, long journalEntryId, Instant createdAt) {
        this.id = id;
        this.quoteId = quote.getId();
        this.merchantId = quote.getMerchantId();
        this.fromCurrency = quote.getFromCurrency();
        this.toCurrency = quote.getToCurrency();
        this.fromAmount = quote.getFromAmount();
        this.toAmount = quote.getToAmount();
        this.clientPrice = quote.getClientPrice();
        this.journalEntryId = journalEntryId;
        this.createdAt = createdAt;
    }

    public String getId() {
        return id;
    }

    public String getQuoteId() {
        return quoteId;
    }

    public long getMerchantId() {
        return merchantId;
    }

    public CurrencyCode getFromCurrency() {
        return fromCurrency;
    }

    public CurrencyCode getToCurrency() {
        return toCurrency;
    }

    public BigDecimal getFromAmount() {
        return fromAmount;
    }

    public BigDecimal getToAmount() {
        return toAmount;
    }

    public BigDecimal getClientPrice() {
        return clientPrice;
    }

    public long getJournalEntryId() {
        return journalEntryId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
