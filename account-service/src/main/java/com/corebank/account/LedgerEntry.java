package com.corebank.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ledger_entries")
public class LedgerEntry {

    public enum Type { DEBIT, CREDIT }

    @Id
    private UUID id;
    @Column(name = "transaction_id", nullable = false)
    private UUID transactionId;
    @Column(name = "account_id", nullable = false)
    private UUID accountId;
    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false)
    private Type entryType;
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;
    @Column(name = "balance_after", nullable = false, precision = 19, scale = 4)
    private BigDecimal balanceAfter;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected LedgerEntry() {
    }

    public LedgerEntry(UUID transactionId, UUID accountId, Type entryType, BigDecimal amount, BigDecimal balanceAfter) {
        this.id = UUID.randomUUID();
        this.transactionId = transactionId;
        this.accountId = accountId;
        this.entryType = entryType;
        this.amount = amount;
        this.balanceAfter = balanceAfter;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public Type getEntryType() {
        return entryType;
    }
}
