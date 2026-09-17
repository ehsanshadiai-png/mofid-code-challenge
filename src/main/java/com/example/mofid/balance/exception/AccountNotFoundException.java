package com.example.mofid.balance.exception;

import lombok.Getter;

@Getter
public class AccountNotFoundException extends BalanceException {

    private final String accountId;

    public AccountNotFoundException(String accountId) {
        super("Account '" + accountId + "' does not exist");
        this.accountId = accountId;
    }
}
