package com.corebank.account;

import com.corebank.common.web.ApiException;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "accounts")
public class Account {

    public enum Type { SAVINGS, CURRENT }

    public enum Status { ACTIVE, FROZEN, CLOSED }

    @Id
    private UUID id;
    @Column(name = "customer_id", nullable = false)
    private UUID customerId;
    @Column(name = "account_number", nullable = false, unique = true)
    private String accountNumber;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Type type;
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal balance = BigDecimal.ZERO;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.ACTIVE;
    @Version
    private long version;
    @JsonIgnore
    @Column(name = "low_balance_alerted_on")
    private LocalDate lowBalanceAlertedOn;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected Account() {
    }

    public Account(UUID customerId, String accountNumber, Type type) {
        this.id = UUID.randomUUID();
        this.customerId = customerId;
        this.accountNumber = accountNumber;
        this.type = type;
    }

    public void debit(BigDecimal amount) {
        requireActive();
        if (balance.compareTo(amount) < 0) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_FUNDS",
                    "Account " + accountNumber + " has insufficient funds");
        }
        balance = balance.subtract(amount);
    }

    public void credit(BigDecimal amount) {
        requireActive();
        balance = balance.add(amount);
    }

    public void freeze() {
        requireNotClosed();
        status = Status.FROZEN;
    }

    public void unfreeze() {
        requireNotClosed();
        status = Status.ACTIVE;
    }

    public void close() {
        requireActive();
        if (balance.signum() != 0) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "BALANCE_NOT_ZERO",
                    "Withdraw the remaining balance before closing the account");
        }
        status = Status.CLOSED;
    }

    private void requireActive() {
        requireNotClosed();
        if (status == Status.FROZEN) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "ACCOUNT_FROZEN",
                    "Account " + accountNumber + " is frozen");
        }
    }

    private void requireNotClosed() {
        if (status == Status.CLOSED) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "ACCOUNT_CLOSED",
                    "Account " + accountNumber + " is closed");
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public String getAccountNumber() {
        return accountNumber;
    }

    public Type getType() {
        return type;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public Status getStatus() {
        return status;
    }

    public long getVersion() {
        return version;
    }

    public void markLowBalanceAlerted(LocalDate date) {
        lowBalanceAlertedOn = date;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
