package com.example.mofid.balance.exception;

import lombok.Getter;

@Getter
public class InsufficientFundsException extends BalanceException {

    private final String accountId;
    private final long requestedAmount;

    public InsufficientFundsException(String accountId, long availableBalance, long requestedAmount) {
        super("Account '%s' has insufficient funds: balance %d, requested %d"
                .formatted(accountId, availableBalance, requestedAmount));
        this.accountId = accountId;
        this.requestedAmount = requestedAmount;
    }

    private InsufficientFundsException(String accountId, long requestedAmount, String message) {
        super(message);
        this.accountId = accountId;
        this.requestedAmount = requestedAmount;
    }

    /** Raised when a retry hits a transaction that was already rejected for insufficient funds. */
    public static InsufficientFundsException previouslyRejected(String transactionId, String accountId, long requestedAmount) {
        return new InsufficientFundsException(accountId, requestedAmount,
                "Transaction '%s' was previously rejected: account '%s' had insufficient funds for %d"
                        .formatted(transactionId, accountId, requestedAmount));
    }
}
