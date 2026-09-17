package com.example.mofid.balance.exception;

/** Base type for all expected, business-level failures of the balance service. */
public abstract class BalanceException extends RuntimeException {

    protected BalanceException(String message) {
        super(message);
    }
}
