package com.systemdesign.trading.repository;

import com.systemdesign.trading.model.Account;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * InMemoryAccountRepository stores user trading accounts.
 *
 * WHY ConcurrentHashMap:
 * - Account objects are accessed from multiple threads (matching engines, risk checks).
 * - The map itself needs to be thread-safe for concurrent reads/writes.
 * - Individual Account operations are synchronized at the Account level (see Account.java).
 */
public class InMemoryAccountRepository implements AccountRepository {

    private final Map<String, Account> accounts = new ConcurrentHashMap<>();

    @Override
    public void save(Account account) {
        accounts.put(account.getUserId(), account);
    }

    @Override
    public Account findByUserId(String userId) {
        return accounts.get(userId);
    }

    @Override
    public List<Account> findAll() {
        return new ArrayList<>(accounts.values());
    }
}
