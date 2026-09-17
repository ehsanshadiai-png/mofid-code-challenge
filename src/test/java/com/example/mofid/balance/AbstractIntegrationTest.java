package com.example.mofid.balance;

import com.example.mofid.balance.service.AccountService;
import com.example.mofid.balance.service.BalanceService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs against the real Spring context and H2 database; no mocks, since locking is the thing under test.
 * All integration tests share this exact configuration so they share one context (and one schema init).
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class AbstractIntegrationTest {

    @Autowired
    protected BalanceService balanceService;

    @Autowired
    protected AccountService accountService;

    @Autowired
    protected JdbcClient jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.sql("DELETE FROM ledger_entry").update();
        jdbc.sql("DELETE FROM balance_transaction").update();
        jdbc.sql("DELETE FROM account").update();
    }

    protected void openAccount(String accountId, long openingBalance) {
        accountService.openAccount(accountId, openingBalance);
    }

    protected long ledgerSum(String accountId) {
        return jdbc.sql("SELECT COALESCE(SUM(amount), 0) FROM ledger_entry WHERE account_id = ?")
                .param(accountId).query(Long.class).single();
    }

    protected long countTransactions(String transactionId) {
        return jdbc.sql("SELECT COUNT(*) FROM balance_transaction WHERE id = ?")
                .param(transactionId).query(Long.class).single();
    }

    protected long countLedgerEntries(String transactionId) {
        return jdbc.sql("SELECT COUNT(*) FROM ledger_entry WHERE transaction_id = ?")
                .param(transactionId).query(Long.class).single();
    }

    /** Invariant: every balance change is backed by exactly one ledger entry. */
    protected void assertBalanceMatchesLedger(String accountId, long openingBalance) {
        assertThat(balanceService.getBalance(accountId))
                .as("balance of %s must equal opening balance + sum of its ledger entries", accountId)
                .isEqualTo(openingBalance + ledgerSum(accountId));
    }
}
