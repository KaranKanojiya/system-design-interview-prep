package com.systemdesign.payment.model;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * LedgerEntry — An IMMUTABLE record in the double-entry ledger.
 *
 * IMMUTABILITY IS CRITICAL:
 *   In real accounting systems, ledger entries are NEVER modified or deleted.
 *   To "undo" a charge, you create a NEW entry that reverses it.
 *   This gives you a complete audit trail — regulators and auditors love it.
 *
 * DOUBLE-ENTRY BOOKKEEPING:
 *   Every payment creates exactly TWO entries:
 *     1. DEBIT  the customer/platform account (money leaves)
 *     2. CREDIT the merchant account (money arrives)
 *
 *   The sum of ALL entries across ALL accounts must always equal zero.
 *   If it doesn't, something is badly wrong (the ReconciliationService
 *   checks this invariant).
 *
 * WHY positive for DEBIT and negative for CREDIT?
 *   Convention choice.  Some systems use unsigned amounts + a type field.
 *   We store the signed amount AND the type for clarity.
 *   DEBIT  → positive amount (money out of the source account)
 *   CREDIT → negative amount (money into the destination account)
 *   Sum of (debit.amount + credit.amount) = 0 for each payment.
 */
public class LedgerEntry {

    private final String entryId;
    private final String transactionId;
    private final String accountId;
    private final double amount;           // positive for DEBIT, negative for CREDIT
    private final LedgerEntryType type;
    private final Currency currency;
    private final String description;
    private final LocalDateTime createdAt;

    public LedgerEntry(String transactionId, String accountId, double amount,
                       LedgerEntryType type, Currency currency, String description) {
        this.entryId = "LED-" + UUID.randomUUID().toString().substring(0, 8);
        this.transactionId = transactionId;
        this.accountId = accountId;
        this.amount = amount;
        this.type = type;
        this.currency = currency;
        this.description = description;
        this.createdAt = LocalDateTime.now();
    }

    // ── Getters ONLY — no setters, this class is immutable ──

    public String getEntryId() { return entryId; }
    public String getTransactionId() { return transactionId; }
    public String getAccountId() { return accountId; }
    public double getAmount() { return amount; }
    public LedgerEntryType getType() { return type; }
    public Currency getCurrency() { return currency; }
    public String getDescription() { return description; }
    public LocalDateTime getCreatedAt() { return createdAt; }

    @Override
    public String toString() {
        return "LedgerEntry{" +
                "id='" + entryId + '\'' +
                ", txn='" + transactionId + '\'' +
                ", account='" + accountId + '\'' +
                ", type=" + type +
                ", amount=" + currency.format(Math.abs(amount)) +
                ", desc='" + description + '\'' +
                '}';
    }
}
