package com.ezeebit.wallet.deposit;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DepositRepository extends JpaRepository<Deposit, String> {

    Optional<Deposit> findBySourceAndExternalReference(String source, String externalReference);
}
