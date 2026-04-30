package com.systemdesign.payment.repository;

import com.systemdesign.payment.model.LedgerEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * InMemoryLedgerRepository — ConcurrentHashMap-backed ledger storage.
 *
 * Uses CopyOnWriteArrayList for the per-account entry lists because
 * reads (balance checks, audits) are far more frequent than writes
 * (new entries only on payment/refund).
 *
 * APPEND-ONLY: There is no update() or delete() method.
 * Ledger entries are immutable once written — audit trail integrity.
 */
public class InMemoryLedgerRepository implements LedgerRepository {

    // accountId → list of ledger entries for that account
    private final Map<String, List<LedgerEntry>> byAccount = new ConcurrentHashMap<>();
    // transactionId → list of ledger entries for that transaction
    private final Map<String, List<LedgerEntry>> byTransaction = new ConcurrentHashMap<>();
    // all entries (for global queries like sum-to-zero verification)
    private final List<LedgerEntry> allEntries = new CopyOnWriteArrayList<>();

    @Override
    public void save(LedgerEntry entry) {
        allEntries.add(entry);
        byAccount.computeIfAbsent(entry.getAccountId(), k -> new CopyOnWriteArrayList<>())
                 .add(entry);
        byTransaction.computeIfAbsent(entry.getTransactionId(), k -> new CopyOnWriteArrayList<>())
                     .add(entry);
    }

    @Override
    public List<LedgerEntry> findByAccountId(String accountId) {
        return new ArrayList<>(byAccount.getOrDefault(accountId, List.of()));
    }

    @Override
    public List<LedgerEntry> findByTransactionId(String transactionId) {
        return new ArrayList<>(byTransaction.getOrDefault(transactionId, List.of()));
    }

    @Override
    public List<LedgerEntry> findAll() {
        return new ArrayList<>(allEntries);
    }
}
