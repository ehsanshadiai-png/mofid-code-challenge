package com.example.mofid.balance.service;

/**
 * Account balance operations. All amounts are positive values in minor currency units.
 * <p>
 * Every mutating operation is idempotent per {@code transactionId}: repeating a call with the same
 * arguments (sequentially or concurrently) applies its effect at most once and reproduces the
 * original outcome. Reusing a {@code transactionId} for a different operation is rejected.
 */
public interface BalanceService {

    void credit(String accountId, long amount, String transactionId);

    void debit(String accountId, long amount, String transactionId);

    void transfer(String sourceAccountId, String destinationAccountId, long amount, String transactionId);

    long getBalance(String accountId);
}
