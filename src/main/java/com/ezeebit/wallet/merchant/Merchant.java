package com.ezeebit.wallet.merchant;

import com.ezeebit.wallet.common.CurrencyCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "merchant")
public class Merchant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String country;

    @Enumerated(EnumType.STRING)
    @Column(name = "local_currency", nullable = false)
    private CurrencyCode localCurrency;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Merchant() {
    }

    public Merchant(String name, String country, CurrencyCode localCurrency, Instant createdAt) {
        this.name = name;
        this.country = country;
        this.localCurrency = localCurrency;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getCountry() {
        return country;
    }

    public CurrencyCode getLocalCurrency() {
        return localCurrency;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
