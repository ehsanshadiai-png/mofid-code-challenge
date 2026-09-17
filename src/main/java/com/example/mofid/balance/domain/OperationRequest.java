package com.example.mofid.balance.domain;

/**
 * A normalized balance operation. Used both to execute it and to recognise a retry of it:
 * a transactionId that arrives again with a different request is a client error, not a retry.
 */
public record OperationRequest(
        String transactionId,
        TransactionType type,
        String sourceAccountId,
        String destinationAccountId,
        long amount) {

    public static OperationRequest credit(String accountId, long amount, String transactionId) {
        return new OperationRequest(transactionId, TransactionType.CREDIT, null, accountId, amount);
    }

    public static OperationRequest debit(String accountId, long amount, String transactionId) {
        return new OperationRequest(transactionId, TransactionType.DEBIT, accountId, null, amount);
    }

    public static OperationRequest transfer(String sourceAccountId, String destinationAccountId,
                                            long amount, String transactionId) {
        return new OperationRequest(transactionId, TransactionType.TRANSFER,
                sourceAccountId, destinationAccountId, amount);
    }
}
