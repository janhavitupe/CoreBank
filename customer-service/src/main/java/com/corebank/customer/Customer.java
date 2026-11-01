package com.corebank.customer;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "customers")
public class Customer {

    public enum KycStatus { PENDING, VERIFIED, REJECTED }

    @Id
    private UUID id;
    @Column(nullable = false)
    private String name;
    @Column(nullable = false, unique = true)
    private String email;
    private String phone;
    @Enumerated(EnumType.STRING)
    @Column(name = "kyc_status", nullable = false)
    private KycStatus kycStatus = KycStatus.PENDING;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected Customer() {
    }

    public Customer(String name, String email, String phone) {
        this.id = UUID.randomUUID();
        this.name = name;
        this.email = email;
        this.phone = phone;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getEmail() {
        return email;
    }

    public String getPhone() {
        return phone;
    }

    public KycStatus getKycStatus() {
        return kycStatus;
    }

    public void setKycStatus(KycStatus kycStatus) {
        this.kycStatus = kycStatus;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
