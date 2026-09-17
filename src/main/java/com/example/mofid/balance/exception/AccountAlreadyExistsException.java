package com.example.mofid.balance.exception;

public class AccountAlreadyExistsException extends BalanceException {

    public AccountAlreadyExistsException(String accountId) {
        super("Account '" + accountId + "' already exists");
    }
}
