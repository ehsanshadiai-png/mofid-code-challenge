package com.example.mofid.balance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.Objects;

/**
 * The record of a processed transactionId. Its primary key is the idempotency key.
 * <p>
 * Implements {@link Persistable} so that {@code save()} issues a plain INSERT: with an assigned id,
 * Spring Data would otherwise call {@code merge()}, which SELECTs first and could silently turn a
 * duplicate into an UPDATE instead of letting the primary key reject it.
 */
@Entity
@Table(name = "balance_transaction")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BalanceTransaction implements Persistable<String> {

    @Id
    private String id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private TransactionType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private TransactionStatus status;

    @Column(name = "source_account_id", updatable = false)
    private String sourceAccountId;

    @Column(name = "destination_account_id", updatable = false)
    private String destinationAccountId;

    @Column(nullable = false, updatable = false)
    private long amount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Transient
    private boolean isNew = true;

    public BalanceTransaction(OperationRequest request, TransactionStatus status) {
        this.id = request.transactionId();
        this.type = request.type();
        this.sourceAccountId = request.sourceAccountId();
        this.destinationAccountId = request.destinationAccountId();
        this.amount = request.amount();
        this.status = status;
        this.createdAt = Instant.now();
    }

    /** True if this stored transaction was created by exactly the same request (a genuine retry). */
    public boolean matches(OperationRequest request) {
        return type == request.type()
                && amount == request.amount()
                && Objects.equals(sourceAccountId, request.sourceAccountId())
                && Objects.equals(destinationAccountId, request.destinationAccountId());
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }
}
