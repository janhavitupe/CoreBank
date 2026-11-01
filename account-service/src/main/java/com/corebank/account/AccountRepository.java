package com.corebank.account;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    Optional<Account> findByAccountNumber(String accountNumber);

    List<Account> findByCustomerIdOrderByCreatedAt(UUID customerId);

    @Query("""
            select a from Account a
            where a.status = com.corebank.account.Account.Status.ACTIVE
              and a.balance < :threshold
              and (a.lowBalanceAlertedOn is null or a.lowBalanceAlertedOn < :today)
              and exists (select 1 from LedgerEntry e where e.accountId = a.id)
            """)
    List<Account> findNeedingLowBalanceAlert(BigDecimal threshold, LocalDate today);
}
