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

/** A firm, short-lived, single-use price offered to one merchant for one amount. */
@Entity
@Table(name = "fx_quote")
public class FxQuote {

    public enum Status { OPEN, EXECUTED }

    @Id
    private String id;

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

    @Column(name = "mid_price", nullable = false, precision = 38, scale = 12)
    private BigDecimal midPrice;

    @Column(name = "client_price", nullable = false, precision = 38, scale = 12)
    private BigDecimal clientPrice;

    @Column(name = "spread_bps", nullable = false)
    private int spreadBps;

    @Column(name = "rate_source", nullable = false)
    private String rateSource;

    @Column(name = "rate_as_of", nullable = false)
    private Instant rateAsOf;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Version
    private Long version;

    protected FxQuote() {
    }

    public FxQuote(String id, long merchantId, CurrencyCode fromCurrency, CurrencyCode toCurrency,
                   BigDecimal fromAmount, BigDecimal toAmount, BigDecimal midPrice, BigDecimal clientPrice,
                   int spreadBps, String rateSource, Instant rateAsOf, Instant expiresAt, Instant createdAt) {
        this.id = id;
        this.merchantId = merchantId;
        this.fromCurrency = fromCurrency;
        this.toCurrency = toCurrency;
        this.fromAmount = fromAmount;
        this.toAmount = toAmount;
        this.midPrice = midPrice;
        this.clientPrice = clientPrice;
        this.spreadBps = spreadBps;
        this.rateSource = rateSource;
        this.rateAsOf = rateAsOf;
        this.expiresAt = expiresAt;
        this.status = Status.OPEN;
        this.createdAt = createdAt;
    }

    public boolean isExpiredAt(Instant now) {
        return !now.isBefore(expiresAt);
    }

    void markExecuted() {
        if (status != Status.OPEN) {
            throw new IllegalStateException("Quote " + id + " is " + status);
        }
        status = Status.EXECUTED;
    }

    public String getId() {
        return id;
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

    public BigDecimal getMidPrice() {
        return midPrice;
    }

    public BigDecimal getClientPrice() {
        return clientPrice;
    }

    public int getSpreadBps() {
        return spreadBps;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Status getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
