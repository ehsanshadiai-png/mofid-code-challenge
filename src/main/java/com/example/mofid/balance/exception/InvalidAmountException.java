package com.example.mofid.balance.exception;

public class InvalidAmountException extends InvalidRequestException {

    public InvalidAmountException(long amount) {
        super("Amount must be greater than zero but was " + amount);
    }
}
