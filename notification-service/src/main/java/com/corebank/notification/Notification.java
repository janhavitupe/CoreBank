package com.corebank.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notifications")
public class Notification {

    @Id
    private UUID id;
    @Column(name = "customer_id", nullable = false)
    private UUID customerId;
    @Column(nullable = false)
    private String channel;
    @Column(nullable = false)
    private String message;
    @Column(nullable = false)
    private String status;
    @Column(name = "event_id", nullable = false)
    private UUID eventId;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected Notification() {
    }

    public Notification(UUID customerId, String channel, String message, String status, UUID eventId) {
        this.id = UUID.randomUUID();
        this.customerId = customerId;
        this.channel = channel;
        this.message = message;
        this.status = status;
        this.eventId = eventId;
    }

    public UUID getId() {
        return id;
    }

    public String getChannel() {
        return channel;
    }

    public String getMessage() {
        return message;
    }

    public String getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
