package com.corebank.transaction;

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
@Table(name = "transactions")
public class BankTransaction {

    public enum Kind { TRANSFER, DEPOSIT, WITHDRAWAL }

    public enum Status { PENDING, COMPLETED, FAILED }

    @Id
    private UUID id;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Kind type;
    @Column(name = "from_account")
    private String fromAccount;
    @Column(name = "to_account")
    private String toAccount;
    @Column(name = "from_customer_id")
    private UUID fromCustomerId;
    @Column(name = "to_customer_id")
    private UUID toCustomerId;
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;
    private String description;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.PENDING;
    @Column(name = "failure_code")
    private String failureCode;
    @Column(name = "failure_message")
    private String failureMessage;
    private String category;
    @Column(name = "customer_id", nullable = false)
    private UUID customerId;
    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;
    @Column(name = "correlation_id")
    private String correlationId;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected BankTransaction() {
    }

    public BankTransaction(UUID id, Kind type, String fromAccount, String toAccount, BigDecimal amount,
                           String description, UUID customerId, String idempotencyKey, String correlationId) {
        this.id = id;
        this.type = type;
        this.fromAccount = fromAccount;
        this.toAccount = toAccount;
        this.amount = amount;
        this.description = description;
        this.customerId = customerId;
        this.idempotencyKey = idempotencyKey;
        this.correlationId = correlationId;
    }

    public UUID getId() {
        return id;
    }

    public Kind getType() {
        return type;
    }

    public String getFromAccount() {
        return fromAccount;
    }

    public String getToAccount() {
        return toAccount;
    }

    public UUID getFromCustomerId() {
        return fromCustomerId;
    }

    public UUID getToCustomerId() {
        return toCustomerId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getDescription() {
        return description;
    }

    public Status getStatus() {
        return status;
    }

    public String getFailureCode() {
        return failureCode;
    }

    public String getFailureMessage() {
        return failureMessage;
    }

    public String getCategory() {
        return category;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
