package com.example.mofid.balance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

/**
 * An account and its current balance. Not thread-safe by itself: mutations are only performed
 * on an instance loaded with a pessimistic row lock inside a database transaction.
 */
@Entity
@Table(name = "account")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Account implements Persistable<String> {

    @Id
    private String id;

    @Column(nullable = false)
    private long balance;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Transient
    private boolean isNew = true;

    public Account(String id, long openingBalance) {
        if (openingBalance < 0) {
            throw new IllegalArgumentException("Opening balance must not be negative");
        }
        this.id = id;
        this.balance = openingBalance;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public boolean canDebit(long amount) {
        return balance >= amount;
    }

    public void credit(long amount) {
        balance = Math.addExact(balance, amount);
        updatedAt = Instant.now();
    }

    /**
     * Callers must check {@link #canDebit(long)} first; reaching the guard is a programming error.
     * It deliberately throws an unchecked non-business exception so the surrounding transaction
     * always rolls back (the business "insufficient funds" exception does not roll back).
     */
    public void debit(long amount) {
        if (!canDebit(amount)) {
            throw new IllegalStateException("Debit of %d would make account '%s' negative (balance %d)"
                    .formatted(amount, id, balance));
        }
        balance -= amount;
        updatedAt = Instant.now();
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
