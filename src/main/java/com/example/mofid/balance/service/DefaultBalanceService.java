package com.example.mofid.balance.service;

import com.example.mofid.balance.domain.OperationRequest;
import com.example.mofid.balance.exception.InvalidAmountException;
import com.example.mofid.balance.exception.InvalidRequestException;
import com.example.mofid.balance.exception.SameAccountTransferException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Validates requests and runs them through {@link TransactionalBalanceOperations}.
 * <p>
 * This class is intentionally <em>not</em> transactional: when two concurrent requests carry the same
 * transactionId but lock different accounts (i.e. they are different operations), they are not
 * serialized by row locks and the loser fails on the primary key. That failure rolls back the whole
 * loser transaction; only then, in a new transaction, can we read the winner and answer consistently.
 * <p>
 * {@link Propagation#NEVER} enforces that rule: calling this service inside a caller's transaction fails
 * fast with {@code IllegalTransactionStateException}. With the default REQUIRED, the operation would
 * silently join the caller's transaction instead: row locks would be held until the caller commits, and
 * after a primary key failure the lookup of the winner's outcome would run inside the same rollback-only transaction.
 */
@Slf4j
@Service
@Transactional(propagation = Propagation.NEVER)
@RequiredArgsConstructor
public class DefaultBalanceService implements BalanceService {

    static final int MAX_ACCOUNT_ID_LENGTH = 64;
    static final int MAX_TRANSACTION_ID_LENGTH = 128;

    private final TransactionalBalanceOperations operations;

    @Override
    public void credit(String accountId, long amount, String transactionId) {
        requireAccountId(accountId, "accountId");
        execute(OperationRequest.credit(accountId, amount, transactionId));
    }

    @Override
    public void debit(String accountId, long amount, String transactionId) {
        requireAccountId(accountId, "accountId");
        execute(OperationRequest.debit(accountId, amount, transactionId));
    }

    /**
     * A transfer to the same account is rejected as an invalid request: it would have no net effect,
     * almost certainly indicates a client bug, and recording it would pollute the ledger.
     */
    @Override
    public void transfer(String sourceAccountId, String destinationAccountId, long amount, String transactionId) {
        requireAccountId(sourceAccountId, "sourceAccountId");
        requireAccountId(destinationAccountId, "destinationAccountId");
        if (sourceAccountId.equals(destinationAccountId)) {
            throw new SameAccountTransferException(sourceAccountId);
        }
        execute(OperationRequest.transfer(sourceAccountId, destinationAccountId, amount, transactionId));
    }

    @Override
    public long getBalance(String accountId) {
        requireAccountId(accountId, "accountId");
        return operations.getBalance(accountId);
    }

    private void execute(OperationRequest request) {
        if (request.amount() <= 0) {
            throw new InvalidAmountException(request.amount());
        }
        requireTransactionId(request.transactionId());

        try {
            operations.execute(request);
        } catch (DataIntegrityViolationException e) {
            // Lost the race on the transactionId primary key to a concurrent transaction.
            // Our transaction has rolled back; return the outcome the winner committed.
            log.debug("Integrity violation for transaction {}, reading committed outcome", request.transactionId(), e);
            if (!operations.returnCommittedOutcome(request)) {
                throw e;
            }
        }
    }

    private static void requireAccountId(String accountId, String field) {
        if (accountId == null || accountId.isBlank()) {
            throw new InvalidRequestException(field + " must not be blank");
        }
        if (accountId.length() > MAX_ACCOUNT_ID_LENGTH) {
            throw new InvalidRequestException(field + " must be at most " + MAX_ACCOUNT_ID_LENGTH + " characters");
        }
    }

    private static void requireTransactionId(String transactionId) {
        if (transactionId == null || transactionId.isBlank()) {
            throw new InvalidRequestException("transactionId must not be blank");
        }
        if (transactionId.length() > MAX_TRANSACTION_ID_LENGTH) {
            throw new InvalidRequestException(
                    "transactionId must be at most " + MAX_TRANSACTION_ID_LENGTH + " characters");
        }
    }
}
