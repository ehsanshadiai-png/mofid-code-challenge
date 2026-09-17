package com.example.mofid.balance.domain;

public enum TransactionStatus {
    /** The balance effect was applied. */
    COMPLETED,
    /**
     * A business rule (insufficient funds) refused the operation. The outcome is persisted so
     * a retry with the same transactionId gets the same answer instead of being re-evaluated.
     */
    REJECTED
}
