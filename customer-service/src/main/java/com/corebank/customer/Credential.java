package com.corebank.customer;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "credentials")
public class Credential {

    @Id
    @Column(name = "customer_id")
    private UUID customerId;
    @Column(name = "password_hash", nullable = false)
    private String passwordHash;
    @Column(nullable = false)
    private String role;

    protected Credential() {
    }

    public Credential(UUID customerId, String passwordHash, String role) {
        this.customerId = customerId;
        this.passwordHash = passwordHash;
        this.role = role;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getRole() {
        return role;
    }
}
