package com.systemdesign.trading.repository;

import com.systemdesign.trading.model.Account;

import java.util.List;

/**
 * AccountRepository stores and retrieves user trading accounts.
 *
 * CALL CHAIN:
 * AccountService.getAccount() → AccountRepository.findByUserId() →
 * Account.blockMargin() / Account.debit() / Account.credit() →
 * changes persisted in-memory (reference semantics)
 */
public interface AccountRepository {

    void save(Account account);

    Account findByUserId(String userId);

    List<Account> findAll();
}
