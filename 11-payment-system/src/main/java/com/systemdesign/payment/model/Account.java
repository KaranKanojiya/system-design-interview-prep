package com.systemdesign.payment.model;

import com.systemdesign.payment.exception.InsufficientFundsException;

/**
 * Account — A financial account in the payment system.
 *
 * THREAD SAFETY:
 *   credit() and debit() are synchronized because multiple payment threads
 *   may try to update the same account concurrently.  Without synchronization,
 *   two concurrent debits could both read balance=100, both pass the
 *   "balance >= amount" check, and both debit — leaving the balance negative.
 *
 *   In a real system you'd use database-level locking (SELECT FOR UPDATE)
 *   or optimistic concurrency control (version column).  Here we use
 *   synchronized as the in-memory equivalent.
 *
 * WHY double for balance?
 *   In production you'd use BigDecimal to avoid floating-point errors.
 *   We use double for simplicity in this interview-prep demo, but the
 *   design patterns (double-entry, reconciliation) are the same.
 */
public class Account {

    private final String accountId;
    private final String name;
    private final AccountType accountType;
    private double balance;
    private final Currency currency;

    public Account(String accountId, String name, AccountType accountType,
                   double balance, Currency currency) {
        this.accountId = accountId;
        this.name = name;
        this.accountType = accountType;
        this.balance = balance;
        this.currency = currency;
    }

    /**
     * Credit — add money to this account.
     *
     * Synchronized to prevent lost updates when multiple threads
     * credit the same account concurrently.
     */
    public synchronized void credit(double amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("Credit amount must be positive, got: " + amount);
        }
        this.balance += amount;
    }

    /**
     * Debit — remove money from this account.
     *
     * Synchronized to prevent the "double-debit" race condition:
     *   Thread A reads balance=100, checks 100 >= 80 → true
     *   Thread B reads balance=100, checks 100 >= 80 → true
     *   Both debit → balance = -60  (WRONG!)
     *
     * With synchronized, Thread B waits for A to finish,
     * then sees balance=20, checks 20 >= 80 → false → throws.
     *
     * @throws InsufficientFundsException if balance < amount
     */
    public synchronized void debit(double amount) throws InsufficientFundsException {
        if (amount <= 0) {
            throw new IllegalArgumentException("Debit amount must be positive, got: " + amount);
        }
        if (balance < amount) {
            throw new InsufficientFundsException(
                "Account " + accountId + " has insufficient funds: balance="
                + currency.format(balance) + ", debit=" + currency.format(amount));
        }
        this.balance -= amount;
    }

    // ── Getters ──
    public String getAccountId() { return accountId; }
    public String getName() { return name; }
    public AccountType getAccountType() { return accountType; }
    public synchronized double getBalance() { return balance; }
    public Currency getCurrency() { return currency; }

    @Override
    public String toString() {
        return "Account{" +
                "id='" + accountId + '\'' +
                ", name='" + name + '\'' +
                ", type=" + accountType +
                ", balance=" + currency.format(balance) +
                '}';
    }
}
