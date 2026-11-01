package com.corebank.transaction;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface TransactionRepository extends JpaRepository<BankTransaction, UUID> {

    /** Returns 1 for the one caller that wins the PENDING to COMPLETED change, so only it writes the event. */
    @Modifying
    @Query("""
            update BankTransaction t
            set t.status = com.corebank.transaction.BankTransaction.Status.COMPLETED,
                t.fromCustomerId = :fromCustomer, t.toCustomerId = :toCustomer, t.updatedAt = :now
            where t.id = :id and t.status = com.corebank.transaction.BankTransaction.Status.PENDING
            """)
    int markCompleted(UUID id, UUID fromCustomer, UUID toCustomer, Instant now);

    @Modifying
    @Query("""
            update BankTransaction t
            set t.status = com.corebank.transaction.BankTransaction.Status.FAILED,
                t.failureCode = :code, t.failureMessage = :message, t.updatedAt = :now
            where t.id = :id and t.status = com.corebank.transaction.BankTransaction.Status.PENDING
            """)
    int markFailed(UUID id, String code, String message, Instant now);

    @Modifying
    @Query("update BankTransaction t set t.category = :category where t.id = :id")
    int setCategory(UUID id, String category);

    @Query("""
            select t from BankTransaction t
            where t.status = com.corebank.transaction.BankTransaction.Status.PENDING and t.createdAt < :before
            order by t.createdAt
            """)
    List<BankTransaction> findStalePending(Instant before, Pageable page);

    @Query("""
            select t from BankTransaction t
            where (t.fromAccount = :account or t.toAccount = :account)
              and t.status = com.corebank.transaction.BankTransaction.Status.COMPLETED
            order by t.createdAt desc
            """)
    List<BankTransaction> statement(String account, Pageable page);

    @Query("""
            select t from BankTransaction t
            where t.fromCustomerId = :customer
              and t.status = com.corebank.transaction.BankTransaction.Status.COMPLETED
              and t.createdAt >= :from and t.createdAt < :to
            order by t.createdAt desc
            """)
    List<BankTransaction> spending(UUID customer, Instant from, Instant to);
}
