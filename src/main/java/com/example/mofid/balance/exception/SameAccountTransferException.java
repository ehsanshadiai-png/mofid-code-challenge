package com.example.mofid.balance.exception;

public class SameAccountTransferException extends InvalidRequestException {

    public SameAccountTransferException(String accountId) {
        super("Source and destination account must differ, both were '" + accountId + "'");
    }
}
