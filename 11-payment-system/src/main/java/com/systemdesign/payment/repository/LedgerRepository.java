package com.systemdesign.payment.repository;

import com.systemdesign.payment.model.LedgerEntry;

import java.util.List;

/**
 * LedgerRepository — Data access interface for LedgerEntry entities.
 *
 * Note: LedgerEntries are APPEND-ONLY.  There is no update or delete.
 * To "undo" an entry, you add a new reversing entry.  This is the
 * fundamental principle of double-entry bookkeeping and provides
 * a complete audit trail.
 */
public interface LedgerRepository {
    void save(LedgerEntry entry);
    List<LedgerEntry> findByAccountId(String accountId);
    List<LedgerEntry> findByTransactionId(String transactionId);
    List<LedgerEntry> findAll();
}
