package com.systemdesign.payment.model;

/**
 * LedgerEntryType — Debit or Credit in double-entry bookkeeping.
 *
 * In double-entry accounting every transaction creates exactly TWO entries:
 *   DEBIT  — money leaves an account (positive amount by convention)
 *   CREDIT — money enters an account (negative amount by convention)
 *
 * The sum of all DEBIT amounts + all CREDIT amounts across ALL accounts
 * must always equal zero.  This is the fundamental invariant that
 * ReconciliationService verifies.
 */
public enum LedgerEntryType {
    DEBIT,
    CREDIT
}
