package com.example.mofid.balance.web;

import jakarta.validation.constraints.NotNull;

/** Request/response bodies. Business validation lives in the service; here we only reject missing fields. */
final class ApiDtos {

    private ApiDtos() {
    }

    record OpenAccountRequest(String accountId, @NotNull Long openingBalance) {
    }

    record AmountRequest(@NotNull Long amount, String transactionId) {
    }

    record TransferRequest(String sourceAccountId, String destinationAccountId,
                           @NotNull Long amount, String transactionId) {
    }

    record BalanceResponse(String accountId, long balance) {
    }
}
