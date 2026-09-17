package com.example.mofid.balance.repository;

import com.example.mofid.balance.domain.Account;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, String> {

    /**
     * {@code SELECT ... FOR UPDATE}: takes an exclusive row lock held until commit/rollback.
     * Other writers of the same row wait; plain readers are not blocked (MVCC).
     * <p>
     * {@link Propagation#MANDATORY}: a lock is only useful while the caller's transaction holds it, so
     * calling this outside a transaction is a bug and fails with {@code IllegalTransactionStateException}.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id = :id")
    Optional<Account> findByIdForUpdate(@Param("id") String id);
}
