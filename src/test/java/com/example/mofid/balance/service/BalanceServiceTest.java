package com.example.mofid.balance.service;

import com.example.mofid.balance.AbstractIntegrationTest;
import com.example.mofid.balance.exception.AccountNotFoundException;
import com.example.mofid.balance.exception.InsufficientFundsException;
import com.example.mofid.balance.exception.InvalidAmountException;
import com.example.mofid.balance.exception.InvalidRequestException;
import com.example.mofid.balance.exception.SameAccountTransferException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BalanceServiceTest extends AbstractIntegrationTest {

    @Nested
    class Credit {

        @Test
        void increasesBalance() {
            openAccount("A", 1_000);

            balanceService.credit("A", 500, "TX-1");

            assertThat(balanceService.getBalance("A")).isEqualTo(1_500);
            assertBalanceMatchesLedger("A", 1_000);
        }

        @Test
        void failsForUnknownAccountAndRecordsNothing() {
            assertThatThrownBy(() -> balanceService.credit("missing", 500, "TX-1"))
                    .isInstanceOf(AccountNotFoundException.class);
            assertThat(countTransactions("TX-1")).isZero();
        }

        @Test
        void rollsBackOnBalanceOverflow() {
            openAccount("A", Long.MAX_VALUE - 10);

            assertThatThrownBy(() -> balanceService.credit("A", 100, "TX-1"))
                    .isInstanceOf(ArithmeticException.class);

            assertThat(balanceService.getBalance("A")).isEqualTo(Long.MAX_VALUE - 10);
            assertThat(countTransactions("TX-1")).isZero();
        }
    }

    @Nested
    class Debit {

        @Test
        void decreasesBalance() {
            openAccount("A", 1_000);

            balanceService.debit("A", 700, "TX-1");

            assertThat(balanceService.getBalance("A")).isEqualTo(300);
            assertBalanceMatchesLedger("A", 1_000);
        }

        @Test
        void canDebitEntireBalance() {
            openAccount("A", 1_000);

            balanceService.debit("A", 1_000, "TX-1");

            assertThat(balanceService.getBalance("A")).isZero();
        }

        @Test
        void failsWhenFundsAreInsufficientAndLeavesBalanceUnchanged() {
            openAccount("A", 1_000);

            assertThatThrownBy(() -> balanceService.debit("A", 1_200, "TX-1"))
                    .isInstanceOf(InsufficientFundsException.class);

            assertThat(balanceService.getBalance("A")).isEqualTo(1_000);
            assertThat(countLedgerEntries("TX-1")).isZero();
        }

        @Test
        void failsForUnknownAccount() {
            assertThatThrownBy(() -> balanceService.debit("missing", 1, "TX-1"))
                    .isInstanceOf(AccountNotFoundException.class);
        }
    }

    @Nested
    class Transfer {

        @Test
        void movesAmountBetweenAccounts() {
            openAccount("A", 1_000);
            openAccount("B", 500);

            balanceService.transfer("A", "B", 300, "TX-1");

            assertThat(balanceService.getBalance("A")).isEqualTo(700);
            assertThat(balanceService.getBalance("B")).isEqualTo(800);
            assertBalanceMatchesLedger("A", 1_000);
            assertBalanceMatchesLedger("B", 500);
        }

        @Test
        void failsWhenSourceHasInsufficientFundsAndChangesNeitherAccount() {
            openAccount("A", 100);
            openAccount("B", 500);

            assertThatThrownBy(() -> balanceService.transfer("A", "B", 300, "TX-1"))
                    .isInstanceOf(InsufficientFundsException.class);

            assertThat(balanceService.getBalance("A")).isEqualTo(100);
            assertThat(balanceService.getBalance("B")).isEqualTo(500);
            assertThat(countLedgerEntries("TX-1")).isZero();
        }

        @Test
        void failsForUnknownSourceOrDestination() {
            openAccount("A", 1_000);

            assertThatThrownBy(() -> balanceService.transfer("A", "missing", 300, "TX-1"))
                    .isInstanceOf(AccountNotFoundException.class);
            assertThatThrownBy(() -> balanceService.transfer("missing", "A", 300, "TX-2"))
                    .isInstanceOf(AccountNotFoundException.class);

            assertThat(balanceService.getBalance("A")).isEqualTo(1_000);
        }

        @Test
        void rejectsSameAccountTransferWithoutRecordingIt() {
            openAccount("A", 1_000);

            assertThatThrownBy(() -> balanceService.transfer("A", "A", 100, "TX-100"))
                    .isInstanceOf(SameAccountTransferException.class);

            assertThat(balanceService.getBalance("A")).isEqualTo(1_000);
            assertThat(countTransactions("TX-100")).isZero();
        }

        /**
         * Forces a failure <em>after</em> the source has been debited in memory: crediting the destination
         * overflows. The whole database transaction must roll back, so there is no half-applied transfer.
         */
        @Test
        void failureAfterDebitingSourceRollsBackTheWholeTransfer() {
            openAccount("A", 1_000);
            openAccount("B", Long.MAX_VALUE - 10);

            assertThatThrownBy(() -> balanceService.transfer("A", "B", 300, "TX-1"))
                    .isInstanceOf(ArithmeticException.class);

            assertThat(balanceService.getBalance("A")).isEqualTo(1_000);
            assertThat(balanceService.getBalance("B")).isEqualTo(Long.MAX_VALUE - 10);
            assertThat(countTransactions("TX-1")).isZero();
            assertThat(countLedgerEntries("TX-1")).isZero();
        }
    }

    @Nested
    class Validation {

        @ParameterizedTest
        @ValueSource(longs = {0, -1, Long.MIN_VALUE})
        void rejectsNonPositiveAmounts(long amount) {
            openAccount("A", 1_000);
            openAccount("B", 1_000);

            assertThatThrownBy(() -> balanceService.credit("A", amount, "TX-1")).isInstanceOf(InvalidAmountException.class);
            assertThatThrownBy(() -> balanceService.debit("A", amount, "TX-2")).isInstanceOf(InvalidAmountException.class);
            assertThatThrownBy(() -> balanceService.transfer("A", "B", amount, "TX-3")).isInstanceOf(InvalidAmountException.class);

            assertThat(balanceService.getBalance("A")).isEqualTo(1_000);
            assertThat(balanceService.getBalance("B")).isEqualTo(1_000);
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = "   ")
        void rejectsBlankIdentifiers(String blank) {
            openAccount("A", 1_000);

            assertThatThrownBy(() -> balanceService.credit("A", 1, blank)).isInstanceOf(InvalidRequestException.class);
            assertThatThrownBy(() -> balanceService.credit(blank, 1, "TX-1")).isInstanceOf(InvalidRequestException.class);
            assertThatThrownBy(() -> balanceService.transfer("A", blank, 1, "TX-1")).isInstanceOf(InvalidRequestException.class);
            assertThatThrownBy(() -> balanceService.getBalance(blank)).isInstanceOf(InvalidRequestException.class);
        }

        @Test
        void getBalanceFailsForUnknownAccount() {
            assertThatThrownBy(() -> balanceService.getBalance("missing"))
                    .isInstanceOf(AccountNotFoundException.class);
        }
    }

    @Nested
    class SchemaConstraints {

        /** Defence in depth: even a write that bypasses the service cannot store a negative balance. */
        @Test
        void databaseRejectsNegativeBalance() {
            openAccount("A", 10);

            assertThatThrownBy(() -> jdbc.sql("UPDATE account SET balance = -1 WHERE id = 'A'").update())
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }
}
