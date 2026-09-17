package com.example.mofid.balance.exception;

/** The request is malformed; it is rejected before any database work and is never recorded. */
public class InvalidRequestException extends BalanceException {

    public InvalidRequestException(String message) {
        super(message);
    }
}
