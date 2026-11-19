package com.ezeebit.wallet.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "outbox_message")
public class OutboxMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "message_type", nullable = false)
    private String messageType;

    @Column(name = "aggregate_id", nullable = false)
    private String aggregateId;

    @Column
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OutboxStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected OutboxMessage() {
    }

    public OutboxMessage(String messageType, String aggregateId, String payload, Instant now) {
        this.messageType = messageType;
        this.aggregateId = aggregateId;
        this.payload = payload;
        this.status = OutboxStatus.PENDING;
        this.attempts = 0;
        this.nextAttemptAt = now;
        this.createdAt = now;
        this.updatedAt = now;
    }

    void markDone(Instant now) {
        this.status = OutboxStatus.DONE;
        this.lockedUntil = null;
        this.lastError = null;
        this.updatedAt = now;
    }

    void reschedule(Instant nextAttemptAt, String error, Instant now) {
        this.status = OutboxStatus.PENDING;
        this.nextAttemptAt = nextAttemptAt;
        this.lockedUntil = null;
        this.lastError = truncate(error);
        this.updatedAt = now;
    }

    void markDead(String error, Instant now) {
        this.status = OutboxStatus.DEAD;
        this.lockedUntil = null;
        this.lastError = truncate(error);
        this.updatedAt = now;
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= 1000 ? s : s.substring(0, 1000);
    }

    public Long getId() {
        return id;
    }

    public String getMessageType() {
        return messageType;
    }

    public String getAggregateId() {
        return aggregateId;
    }

    public String getPayload() {
        return payload;
    }

    public OutboxStatus getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public String getLastError() {
        return lastError;
    }
}
