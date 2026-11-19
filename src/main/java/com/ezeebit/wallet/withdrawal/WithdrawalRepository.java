package com.ezeebit.wallet.withdrawal;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface WithdrawalRepository extends JpaRepository<Withdrawal, String> {

    Optional<Withdrawal> findByMerchantIdAndIdempotencyKey(long merchantId, String idempotencyKey);

    /** SELECT ... FOR UPDATE: serialises callbacks / submission acks for one withdrawal. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from Withdrawal w where w.id = :id")
    Optional<Withdrawal> findForUpdate(@Param("id") String id);

    List<Withdrawal> findByMerchantId(long merchantId);
}
