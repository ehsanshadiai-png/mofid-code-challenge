package com.example.mofid.balance.service;

import com.example.mofid.balance.domain.Account;
import com.example.mofid.balance.exception.AccountAlreadyExistsException;
import com.example.mofid.balance.exception.InvalidRequestException;
import com.example.mofid.balance.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Account provisioning. Kept outside {@link BalanceService}, which only moves money. */
@Service
@RequiredArgsConstructor
public class AccountService {

    private final AccountRepository accountRepository;

    @Transactional
    public Account openAccount(String accountId, long openingBalance) {
        if (accountId == null || accountId.isBlank() || accountId.length() > DefaultBalanceService.MAX_ACCOUNT_ID_LENGTH) {
            throw new InvalidRequestException(
                    "accountId must be non-blank and at most " + DefaultBalanceService.MAX_ACCOUNT_ID_LENGTH + " characters");
        }
        if (openingBalance < 0) {
            throw new InvalidRequestException("openingBalance must not be negative");
        }
        try {
            // Plain INSERT (Account is Persistable): a duplicate id fails on the primary key, even under a race.
            return accountRepository.saveAndFlush(new Account(accountId, openingBalance));
        } catch (DataIntegrityViolationException e) {
            throw new AccountAlreadyExistsException(accountId);
        }
    }
}
