package com.ezeebit.wallet.fx;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface FxConversionRepository extends JpaRepository<FxConversion, String> {

    Optional<FxConversion> findByQuoteId(String quoteId);
}
