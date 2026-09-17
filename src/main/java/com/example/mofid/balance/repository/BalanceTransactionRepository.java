package com.example.mofid.balance.repository;

import com.example.mofid.balance.domain.BalanceTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BalanceTransactionRepository extends JpaRepository<BalanceTransaction, String> {
}
