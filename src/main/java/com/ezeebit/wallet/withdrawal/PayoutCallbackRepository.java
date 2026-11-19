package com.ezeebit.wallet.withdrawal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PayoutCallbackRepository extends JpaRepository<PayoutCallback, Long> {

    List<PayoutCallback> findByWithdrawalIdOrderByIdAsc(String withdrawalId);
}
