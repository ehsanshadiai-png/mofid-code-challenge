package com.example.mofid.balance.exception;

import lombok.Getter;

/** The transactionId was already used by a different operation (different type, accounts or amount). */
@Getter
public class TransactionIdConflictException extends BalanceException {

    private final String transactionId;

    public TransactionIdConflictException(String transactionId) {
        super("Transaction id '" + transactionId + "' was already used for a different operation");
        this.transactionId = transactionId;
    }
}
