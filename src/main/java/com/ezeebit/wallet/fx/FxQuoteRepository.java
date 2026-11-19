package com.ezeebit.wallet.fx;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface FxQuoteRepository extends JpaRepository<FxQuote, String> {

    /** SELECT ... FOR UPDATE: serialises concurrent attempts to execute the same quote. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select q from FxQuote q where q.id = :id")
    Optional<FxQuote> findForUpdate(@Param("id") String id);
}
