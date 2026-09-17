package com.example.mofid.balance.service;

import com.example.mofid.balance.AbstractIntegrationTest;
import com.example.mofid.balance.repository.AccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Guards the transaction-boundary rules that the concurrency design depends on. */
class TransactionPropagationTest extends AbstractIntegrationTest {

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private AccountRepository accountRepository;

    @Test
    void balanceServiceRefusesToJoinCallerTransaction() {
        openAccount("A", 1_000);

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(
                status -> balanceService.credit("A", 100, "TX-1")))
                .isInstanceOf(IllegalTransactionStateException.class);

        assertThat(balanceService.getBalance("A")).isEqualTo(1_000);
        assertThat(countTransactions("TX-1")).isZero();
    }

    @Test
    void balanceServiceWorksWithoutCallerTransaction() {
        openAccount("A", 1_000);

        balanceService.credit("A", 100, "TX-1");

        assertThat(balanceService.getBalance("A")).isEqualTo(1_100);
    }

    @Test
    void lockQueryRequiresTransaction() {
        openAccount("A", 1_000);

        assertThatThrownBy(() -> accountRepository.findByIdForUpdate("A"))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void lockQueryWorksInsideTransaction() {
        openAccount("A", 1_000);

        Long balance = transactionTemplate.execute(
                status -> accountRepository.findByIdForUpdate("A").orElseThrow().getBalance());

        assertThat(balance).isEqualTo(1_000);
    }
}
