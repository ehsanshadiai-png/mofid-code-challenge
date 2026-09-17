package com.example.mofid.balance.service;

import com.example.mofid.balance.AbstractIntegrationTest;
import com.example.mofid.balance.exception.AccountNotFoundException;
import com.example.mofid.balance.exception.InsufficientFundsException;
import com.example.mofid.balance.exception.TransactionIdConflictException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotencyTest extends AbstractIntegrationTest {

    @Test
    void repeatedCreditIsAppliedOnce() {
        openAccount("A", 1_000);

        balanceService.credit("A", 100, "TX-1");
        balanceService.credit("A", 100, "TX-1");
        balanceService.credit("A", 100, "TX-1");

        assertThat(balanceService.getBalance("A")).isEqualTo(1_100);
        assertThat(countTransactions("TX-1")).isEqualTo(1);
        assertThat(countLedgerEntries("TX-1")).isEqualTo(1);
    }

    @Test
    void repeatedDebitIsAppliedOnce() {
        openAccount("A", 1_000);

        balanceService.debit("A", 100, "TX-1");
        balanceService.debit("A", 100, "TX-1");
        balanceService.debit("A", 100, "TX-1");

        assertThat(balanceService.getBalance("A")).isEqualTo(900);
        assertThat(countLedgerEntries("TX-1")).isEqualTo(1);
    }

    @Test
    void repeatedTransferIsAppliedOnce() {
        openAccount("A", 1_000);
        openAccount("B", 500);

        balanceService.transfer("A", "B", 300, "TX-1");
        balanceService.transfer("A", "B", 300, "TX-1");
        balanceService.transfer("A", "B", 300, "TX-1");

        assertThat(balanceService.getBalance("A")).isEqualTo(700);
        assertThat(balanceService.getBalance("B")).isEqualTo(800);
        assertThat(countLedgerEntries("TX-1")).isEqualTo(2);
    }

    /**
     * The outcome of a transactionId is final: a debit rejected for insufficient funds stays rejected
     * on retry, even if the account has been topped up since. The client must use a new transactionId.
     */
    @Test
    void rejectedDebitStaysRejectedOnRetry() {
        openAccount("A", 100);

        assertThatThrownBy(() -> balanceService.debit("A", 500, "TX-1"))
                .isInstanceOf(InsufficientFundsException.class);
        balanceService.credit("A", 1_000, "TX-2");

        assertThatThrownBy(() -> balanceService.debit("A", 500, "TX-1"))
                .isInstanceOf(InsufficientFundsException.class)
                .hasMessageContaining("previously rejected");
        assertThat(balanceService.getBalance("A")).isEqualTo(1_100);

        balanceService.debit("A", 500, "TX-3");
        assertThat(balanceService.getBalance("A")).isEqualTo(600);
    }

    @Test
    void rejectedTransferStaysRejectedOnRetry() {
        openAccount("A", 100);
        openAccount("B", 0);

        assertThatThrownBy(() -> balanceService.transfer("A", "B", 500, "TX-1"))
                .isInstanceOf(InsufficientFundsException.class);
        balanceService.credit("A", 1_000, "TX-2");

        assertThatThrownBy(() -> balanceService.transfer("A", "B", 500, "TX-1"))
                .isInstanceOf(InsufficientFundsException.class);
        assertThat(balanceService.getBalance("A")).isEqualTo(1_100);
        assertThat(balanceService.getBalance("B")).isZero();
    }

    @Test
    void reusingTransactionIdForDifferentOperationIsRejected() {
        openAccount("A", 1_000);
        openAccount("B", 1_000);
        balanceService.credit("A", 100, "TX-1");

        assertThatThrownBy(() -> balanceService.credit("A", 999, "TX-1"))
                .as("different amount").isInstanceOf(TransactionIdConflictException.class);
        assertThatThrownBy(() -> balanceService.debit("A", 100, "TX-1"))
                .as("different type").isInstanceOf(TransactionIdConflictException.class);
        assertThatThrownBy(() -> balanceService.credit("B", 100, "TX-1"))
                .as("different account").isInstanceOf(TransactionIdConflictException.class);
        assertThatThrownBy(() -> balanceService.transfer("B", "A", 100, "TX-1"))
                .as("different operation touching the same account").isInstanceOf(TransactionIdConflictException.class);

        assertThat(balanceService.getBalance("A")).isEqualTo(1_100);
        assertThat(balanceService.getBalance("B")).isEqualTo(1_000);
    }

    /** Requests that fail validation or reference unknown accounts never consume their transactionId. */
    @Test
    void failedRequestDoesNotConsumeTransactionId() {
        assertThatThrownBy(() -> balanceService.credit("A", 100, "TX-1"))
                .isInstanceOf(AccountNotFoundException.class);

        openAccount("A", 0);
        balanceService.credit("A", 100, "TX-1");

        assertThat(balanceService.getBalance("A")).isEqualTo(100);
    }
}
