package com.systemdesign.payment.repository;

import com.systemdesign.payment.model.Account;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * InMemoryAccountRepository — ConcurrentHashMap-backed account storage.
 */
public class InMemoryAccountRepository implements AccountRepository {

    private final Map<String, Account> store = new ConcurrentHashMap<>();

    @Override
    public void save(Account account) {
        store.put(account.getAccountId(), account);
    }

    @Override
    public Optional<Account> findById(String accountId) {
        return Optional.ofNullable(store.get(accountId));
    }

    @Override
    public List<Account> findAll() {
        return new ArrayList<>(store.values());
    }
}
