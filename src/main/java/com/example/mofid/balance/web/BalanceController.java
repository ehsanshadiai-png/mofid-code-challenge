package com.example.mofid.balance.web;

import com.example.mofid.balance.domain.Account;
import com.example.mofid.balance.service.AccountService;
import com.example.mofid.balance.service.BalanceService;
import com.example.mofid.balance.web.ApiDtos.AmountRequest;
import com.example.mofid.balance.web.ApiDtos.BalanceResponse;
import com.example.mofid.balance.web.ApiDtos.OpenAccountRequest;
import com.example.mofid.balance.web.ApiDtos.TransferRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thin HTTP adapter over {@link BalanceService}. Mutations return the balance read after the operation
 * committed; under concurrency it may already include later operations.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
class BalanceController {

    private final BalanceService balanceService;
    private final AccountService accountService;

    @PostMapping("/accounts")
    @ResponseStatus(HttpStatus.CREATED)
    BalanceResponse openAccount(@Valid @RequestBody OpenAccountRequest request) {
        Account account = accountService.openAccount(request.accountId(), request.openingBalance());
        return new BalanceResponse(account.getId(), account.getBalance());
    }

    @GetMapping("/accounts/{accountId}/balance")
    BalanceResponse getBalance(@PathVariable String accountId) {
        return new BalanceResponse(accountId, balanceService.getBalance(accountId));
    }

    @PostMapping("/accounts/{accountId}/credit")
    BalanceResponse credit(@PathVariable String accountId, @Valid @RequestBody AmountRequest request) {
        balanceService.credit(accountId, request.amount(), request.transactionId());
        return getBalance(accountId);
    }

    @PostMapping("/accounts/{accountId}/debit")
    BalanceResponse debit(@PathVariable String accountId, @Valid @RequestBody AmountRequest request) {
        balanceService.debit(accountId, request.amount(), request.transactionId());
        return getBalance(accountId);
    }

    @PostMapping("/transfers")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void transfer(@Valid @RequestBody TransferRequest request) {
        balanceService.transfer(request.sourceAccountId(), request.destinationAccountId(),
                request.amount(), request.transactionId());
    }
}
