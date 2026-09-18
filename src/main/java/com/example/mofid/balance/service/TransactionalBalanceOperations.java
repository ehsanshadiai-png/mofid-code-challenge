package com.example.mofid.balance.service;

import com.example.mofid.balance.domain.Account;
import com.example.mofid.balance.domain.BalanceTransaction;
import com.example.mofid.balance.domain.LedgerEntry;
import com.example.mofid.balance.domain.OperationRequest;
import com.example.mofid.balance.domain.TransactionStatus;
import com.example.mofid.balance.exception.AccountNotFoundException;
import com.example.mofid.balance.exception.InsufficientFundsException;
import com.example.mofid.balance.exception.TransactionIdConflictException;
import com.example.mofid.balance.repository.AccountRepository;
import com.example.mofid.balance.repository.BalanceTransactionRepository;
import com.example.mofid.balance.repository.LedgerEntryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The transactional unit of work behind {@link DefaultBalanceService}. Kept as a separate bean so the
 * facade can observe the outcome of a <em>committed or rolled-back</em> transaction (e.g. a primary
 * key violation) and react to it in a fresh transaction.
 *
 * <h2>Algorithm (one database transaction, READ COMMITTED)</h2>
 * <ol>
 *   <li>Lock every involved account row with {@code SELECT ... FOR UPDATE}, in ascending id order.</li>
 *   <li>Look up the transactionId. Duplicates of the same request touch the same accounts, so they
 *       queue on step 1; once one commits, the next one's statement sees the committed row
 *       (READ COMMITTED takes a new snapshot per statement) and returns the stored outcome
 *       without applying anything again.</li>
 *   <li>Apply the balance change, or record a REJECTED outcome if funds are insufficient.</li>
 *   <li>INSERT the transaction row (primary key = transactionId) and the ledger entries.</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
public class TransactionalBalanceOperations {

    private final AccountRepository accountRepository;
    private final BalanceTransactionRepository transactionRepository;
    private final LedgerEntryRepository ledgerEntryRepository;

    /**
     * {@code noRollbackFor}: an insufficient-funds rejection is a valid, final outcome of the
     * transaction and its REJECTED record must be committed. No balance has been touched at that point.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED, noRollbackFor = InsufficientFundsException.class)
    public void execute(OperationRequest request) {
        Map<String, Account> accounts = lockAccountsInIdOrder(request);

        Optional<BalanceTransaction> existing = transactionRepository.findById(request.transactionId());
        if (existing.isPresent()) {
            returnStoredOutcome(existing.get(), request);
            return;
        }

        switch (request.type()) {
            case CREDIT -> credit(request, accounts.get(request.destinationAccountId()));
            case DEBIT -> debit(request, accounts.get(request.sourceAccountId()));
            case TRANSFER -> transfer(request,
                    accounts.get(request.sourceAccountId()),
                    accounts.get(request.destinationAccountId()));
        }
    }

    /**
     * Returns the outcome of a transaction that another request has already committed. Used after this
     * request lost the race on the transactionId primary key.
     *
     * @return false if no committed transaction with that id is visible
     */
    @Transactional(isolation = Isolation.READ_COMMITTED, readOnly = true)
    public boolean returnCommittedOutcome(OperationRequest request) {
        Optional<BalanceTransaction> existing = transactionRepository.findById(request.transactionId());
        existing.ifPresent(transaction -> returnStoredOutcome(transaction, request));
        return existing.isPresent();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, readOnly = true)
    public long getBalance(String accountId) {
        return accountRepository.findById(accountId)
                .map(Account::getBalance)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
    }

    private void credit(OperationRequest request, Account destination) {
        destination.credit(request.amount());
        complete(request, new LedgerEntry(request.transactionId(), destination.getId(), request.amount()));
    }

    private void debit(OperationRequest request, Account source) {
        if (!source.canDebit(request.amount())) {
            throw reject(request, source);
        }
        source.debit(request.amount());
        complete(request, new LedgerEntry(request.transactionId(), source.getId(), -request.amount()));
    }

    /** Both rows are locked and both changes are flushed in the same database transaction: all or nothing. */
    private void transfer(OperationRequest request, Account source, Account destination) {
        if (!source.canDebit(request.amount())) {
            throw reject(request, source);
        }
        source.debit(request.amount());
        destination.credit(request.amount());
        complete(request,
                new LedgerEntry(request.transactionId(), source.getId(), -request.amount()),
                new LedgerEntry(request.transactionId(), destination.getId(), request.amount()));
    }

    private void complete(OperationRequest request, LedgerEntry... entries) {
        // saveAndFlush: a duplicate transactionId fails here, at the INSERT, rather than later at commit.
        // Not needed for correctness (the primary key rejects it either way), but the failure point is explicit.
        transactionRepository.saveAndFlush(new BalanceTransaction(request, TransactionStatus.COMPLETED));
        ledgerEntryRepository.saveAll(List.of(entries));
    }

    private InsufficientFundsException reject(OperationRequest request, Account source) {
        transactionRepository.saveAndFlush(new BalanceTransaction(request, TransactionStatus.REJECTED));
        return new InsufficientFundsException(source.getId(), source.getBalance(), request.amount());
    }

    /**
     * Locks are always acquired in ascending account id order, whatever the transfer direction.
     * Two transfers A→B and B→A therefore both lock A first, so a lock-order cycle (deadlock) cannot form.
     */
    private Map<String, Account> lockAccountsInIdOrder(OperationRequest request) {
        // The sort is what prevents deadlock; the map below is only a lookup of already-locked accounts.
        List<String> idsInLockOrder = Stream.of(request.sourceAccountId(), request.destinationAccountId())
                .filter(Objects::nonNull)
                .sorted()
                .toList();

        Map<String, Account> locked = new HashMap<>();
        for (String id : idsInLockOrder) {
            locked.put(id, accountRepository.findByIdForUpdate(id)
                    .orElseThrow(() -> new AccountNotFoundException(id)));
        }
        return locked;
    }

    private static void returnStoredOutcome(BalanceTransaction existing, OperationRequest request) {
        if (!existing.matches(request)) {
            throw new TransactionIdConflictException(request.transactionId());
        }
        if (existing.getStatus() == TransactionStatus.REJECTED) {
            throw InsufficientFundsException.previouslyRejected(
                    existing.getId(), existing.getSourceAccountId(), existing.getAmount());
        }
        // COMPLETED: the effect is already applied; returning normally is the idempotent response.
    }
}
