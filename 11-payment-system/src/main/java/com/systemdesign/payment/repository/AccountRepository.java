package com.systemdesign.payment.repository;

import com.systemdesign.payment.model.Account;

import java.util.List;
import java.util.Optional;

/**
 * AccountRepository — Data access interface for Account entities.
 */
public interface AccountRepository {
    void save(Account account);
    Optional<Account> findById(String accountId);
    List<Account> findAll();
}
