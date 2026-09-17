package com.example.mofid.balance.service;

import com.example.mofid.balance.AbstractIntegrationTest;
import com.example.mofid.balance.ConcurrentRunner;
import com.example.mofid.balance.ConcurrentRunner.Outcome;
import com.example.mofid.balance.exception.InsufficientFundsException;
import com.example.mofid.balance.exception.TransactionIdConflictException;
import com.example.mofid.balance.repository.AccountRepository;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrency tests. Thread count (32) deliberately exceeds the connection pool (20), so requests also
 * contend for connections, and all tasks are released through a single start gate.
 */
class ConcurrencyTest extends AbstractIntegrationTest {

    private static final int THREADS = 32;
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    // ---------------------------------------------------------------- single account

    /** The spec's example: balance 1,000 and two concurrent debits of 700; exactly one may succeed. */
    @RepeatedTest(10)
    void onlyOneOfConcurrentCompetingDebitsSucceeds() {
        openAccount("A", 1_000);

        List<Runnable> tasks = IntStream.range(0, THREADS)
                .<Runnable>mapToObj(i -> () -> balanceService.debit("A", 700, "TX-" + i))
                .toList();
        List<Outcome> outcomes = ConcurrentRunner.run(THREADS, tasks, TIMEOUT);

        assertThat(outcomes).filteredOn(Outcome::succeeded).hasSize(1);
        assertThat(outcomes).filteredOn(o -> !o.succeeded())
                .allSatisfy(o -> assertThat(o.error()).isInstanceOf(InsufficientFundsException.class));
        assertThat(balanceService.getBalance("A")).isEqualTo(300);
        assertBalanceMatchesLedger("A", 1_000);
    }

    /**
     * 1,000 concurrent credits and debits of different sizes on one account. Any lost update
     * (two transactions reading the same balance and both writing) changes the final number.
     */
    @Test
    void mixedOperationsOnOneAccountProduceExactFinalBalance() {
        openAccount("A", 100_000);

        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            String n = String.valueOf(i);
            tasks.add(() -> balanceService.credit("A", 7, "CREDIT-" + n));
            tasks.add(() -> balanceService.debit("A", 3, "DEBIT-" + n));
        }
        Collections.shuffle(tasks, new Random(42));

        List<Outcome> outcomes = ConcurrentRunner.run(THREADS, tasks, TIMEOUT);

        assertThat(outcomes).allSatisfy(o -> assertThat(o.error()).isNull());
        assertThat(balanceService.getBalance("A")).isEqualTo(100_000 + 500 * 7 - 500 * 3);
        assertBalanceMatchesLedger("A", 100_000);
    }

    /** 1,000 concurrent debits of 150 against 100,000: exactly 666 fit, the balance never goes negative. */
    @Test
    void concurrentDebitsNeverOverdraw() {
        openAccount("A", 100_000);

        List<Runnable> tasks = IntStream.range(0, 1_000)
                .<Runnable>mapToObj(i -> () -> balanceService.debit("A", 150, "TX-" + i))
                .toList();
        List<Outcome> outcomes = ConcurrentRunner.run(THREADS, tasks, TIMEOUT);

        assertThat(outcomes).filteredOn(Outcome::succeeded).hasSize(666);
        assertThat(outcomes).filteredOn(o -> !o.succeeded())
                .hasSize(334)
                .allSatisfy(o -> assertThat(o.error()).isInstanceOf(InsufficientFundsException.class));
        assertThat(balanceService.getBalance("A")).isEqualTo(100);
        assertBalanceMatchesLedger("A", 100_000);
    }

    // ---------------------------------------------------------------- concurrent duplicates

    @RepeatedTest(5)
    void concurrentDuplicateCreditsAreAppliedOnce() {
        openAccount("A", 1_000);

        List<Outcome> outcomes = runCopies(() -> balanceService.credit("A", 500, "TX-100"));

        assertThat(outcomes).allSatisfy(o -> assertThat(o.error()).isNull());
        assertThat(balanceService.getBalance("A")).isEqualTo(1_500);
        assertThat(countLedgerEntries("TX-100")).isEqualTo(1);
    }

    @RepeatedTest(5)
    void concurrentDuplicateDebitsAreAppliedOnce() {
        openAccount("A", 1_000);

        List<Outcome> outcomes = runCopies(() -> balanceService.debit("A", 500, "TX-100"));

        assertThat(outcomes).allSatisfy(o -> assertThat(o.error()).isNull());
        assertThat(balanceService.getBalance("A")).isEqualTo(500);
        assertThat(countLedgerEntries("TX-100")).isEqualTo(1);
    }

    @RepeatedTest(5)
    void concurrentDuplicateTransfersAreAppliedOnce() {
        openAccount("A", 1_000);
        openAccount("B", 500);

        List<Outcome> outcomes = runCopies(() -> balanceService.transfer("A", "B", 300, "TX-100"));

        assertThat(outcomes).allSatisfy(o -> assertThat(o.error()).isNull());
        assertThat(balanceService.getBalance("A")).isEqualTo(700);
        assertThat(balanceService.getBalance("B")).isEqualTo(800);
        assertThat(countLedgerEntries("TX-100")).isEqualTo(2);
    }

    /** Every concurrent copy of a rejected debit must see the same rejection, never a success. */
    @RepeatedTest(5)
    void concurrentDuplicatesOfRejectedDebitAreAllRejected() {
        openAccount("A", 100);

        List<Outcome> outcomes = runCopies(() -> balanceService.debit("A", 500, "TX-100"));

        assertThat(outcomes).allSatisfy(o -> assertThat(o.error()).isInstanceOf(InsufficientFundsException.class));
        assertThat(balanceService.getBalance("A")).isEqualTo(100);
    }

    /**
     * Same transactionId, different accounts: these are not serialized by any row lock, so they really do
     * race to INSERT the transaction row. The primary key lets exactly one win; the rest get a conflict.
     */
    @RepeatedTest(10)
    void sameTransactionIdOnDifferentAccountsIsAppliedExactlyOnce() {
        List<String> accounts = IntStream.range(0, THREADS).mapToObj(i -> "ACC-" + i).toList();
        accounts.forEach(id -> openAccount(id, 1_000));

        List<Runnable> tasks = accounts.stream()
                .<Runnable>map(id -> () -> balanceService.credit(id, 100, "TX-SHARED"))
                .toList();
        List<Outcome> outcomes = ConcurrentRunner.run(THREADS, tasks, TIMEOUT);

        assertThat(outcomes).filteredOn(Outcome::succeeded).hasSize(1);
        assertThat(outcomes).filteredOn(o -> !o.succeeded())
                .allSatisfy(o -> assertThat(o.error()).isInstanceOf(TransactionIdConflictException.class));
        long totalBalance = accounts.stream().mapToLong(balanceService::getBalance).sum();
        assertThat(totalBalance).isEqualTo(THREADS * 1_000L + 100);
        assertThat(countLedgerEntries("TX-SHARED")).isEqualTo(1);
    }

    // ---------------------------------------------------------------- multiple accounts

    /**
     * 2,000 random transfers between 10 accounts in both directions (A→B and B→A overlap constantly,
     * the classic deadlock shape). Money is neither created nor destroyed, no balance goes negative,
     * every balance is backed by the ledger, and the run finishes (no deadlock).
     */
    @Test
    void randomTransfersAcrossAccountsConserveMoneyWithoutDeadlock() {
        int accountCount = 10;
        long opening = 10_000;
        List<String> accounts = IntStream.range(0, accountCount).mapToObj(i -> "ACC-" + i).toList();
        accounts.forEach(id -> openAccount(id, opening));

        Random random = new Random(7);
        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < 2_000; i++) {
            int from = random.nextInt(accountCount);
            int to = (from + 1 + random.nextInt(accountCount - 1)) % accountCount;
            long amount = 1 + random.nextInt(2_000);
            String txId = "TX-" + i;
            tasks.add(() -> balanceService.transfer(accounts.get(from), accounts.get(to), amount, txId));
        }

        List<Outcome> outcomes = ConcurrentRunner.run(THREADS, tasks, TIMEOUT);

        assertThat(outcomes).filteredOn(o -> !o.succeeded())
                .as("only business rejections are acceptable; no deadlock/lock-timeout errors")
                .allSatisfy(o -> assertThat(o.error()).isInstanceOf(InsufficientFundsException.class));
        assertThat(outcomes).filteredOn(Outcome::succeeded).isNotEmpty();

        long total = 0;
        for (String id : accounts) {
            long balance = balanceService.getBalance(id);
            assertThat(balance).isNotNegative();
            assertBalanceMatchesLedger(id, opening);
            total += balance;
        }
        assertThat(total).isEqualTo(accountCount * opening);
    }

    /** Head-on opposing transfers between the same pair: the textbook lock-ordering deadlock. */
    @RepeatedTest(5)
    void opposingTransfersBetweenTwoAccountsDoNotDeadlock() {
        openAccount("A", 100_000);
        openAccount("B", 100_000);

        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < 250; i++) {
            String n = String.valueOf(i);
            tasks.add(() -> balanceService.transfer("A", "B", 10, "AB-" + n));
            tasks.add(() -> balanceService.transfer("B", "A", 30, "BA-" + n));
        }

        List<Outcome> outcomes = ConcurrentRunner.run(THREADS, tasks, TIMEOUT);

        assertThat(outcomes).allSatisfy(o -> assertThat(o.error()).isNull());
        assertThat(balanceService.getBalance("A")).isEqualTo(100_000 - 250 * 10 + 250 * 30);
        assertThat(balanceService.getBalance("B")).isEqualTo(100_000 + 250 * 10 - 250 * 30);
    }

    /**
     * Holds the row lock on account A in an open transaction and checks that an operation on B completes
     * immediately, while an operation on A waits until the lock is released: locking is per row, not global.
     */
    @Test
    void lockOnOneAccountDoesNotBlockAnotherAccount() throws Exception {
        openAccount("A", 1_000);
        openAccount("B", 1_000);

        CountDownLatch lockAcquired = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> lockHolder = CompletableFuture.runAsync(() ->
                transactionTemplate.executeWithoutResult(status -> {
                    accountRepository.findByIdForUpdate("A").orElseThrow();
                    lockAcquired.countDown();
                    await(release);
                }));
        assertThat(lockAcquired.await(5, TimeUnit.SECONDS)).isTrue();

        try {
            CompletableFuture<Void> onB = CompletableFuture.runAsync(() -> balanceService.credit("B", 1, "TX-B"));
            onB.get(2, TimeUnit.SECONDS);

            CompletableFuture<Void> onA = CompletableFuture.runAsync(() -> balanceService.credit("A", 1, "TX-A"));
            Thread.sleep(500);
            assertThat(onA).as("operation on the locked account must wait").isNotDone();

            // Readers are not blocked by the writer's row lock (MVCC); they see the last committed balance.
            assertThat(balanceService.getBalance("A")).isEqualTo(1_000);

            release.countDown();
            onA.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            lockHolder.get(5, TimeUnit.SECONDS);
        }

        assertThat(balanceService.getBalance("A")).isEqualTo(1_001);
        assertThat(balanceService.getBalance("B")).isEqualTo(1_001);
    }

    private List<Outcome> runCopies(Runnable request) {
        return ConcurrentRunner.run(THREADS, Collections.nCopies(THREADS, request), TIMEOUT);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
