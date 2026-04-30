package com.systemdesign.trading.model;

/**
 * Account represents a user's trading account with funds and margin.
 *
 * WHY synchronized methods:
 * - Multiple threads (matching engines for different symbols) may simultaneously:
 *   (a) block margin for a new order
 *   (b) release margin for a cancelled order
 *   (c) debit/credit for a settled trade
 * - Without synchronization, balance could go negative due to race conditions.
 * - In production, this would be a database row with SELECT ... FOR UPDATE.
 *
 * MARGIN MODEL (simplified):
 * - balance: total funds in account (cash + blocked margin)
 * - marginUsed: funds blocked for open orders that haven't settled yet
 * - availableMargin = balance - marginUsed: what the user can spend on new orders
 *
 * EXAMPLE:
 *   User deposits 1,00,000. balance=1,00,000, marginUsed=0, available=1,00,000
 *   Places buy order for 100 shares @ 500. marginUsed += 50,000. available=50,000
 *   Order fills. marginUsed -= 50,000, balance -= 50,000. available=50,000
 *   Now balance=50,000, marginUsed=0, available=50,000 (and user has 100 shares)
 *
 * CALL CHAIN:
 * TradingService.placeOrder() → AccountService.blockMargin() → Account.blockMargin()
 * Trade settles → AccountService.settleTradePayment() → Account.debit()/credit()
 */
public class Account {

    private final String userId;
    private final String name;
    private double balance;         // Total funds in account
    private double marginUsed;      // Funds blocked for pending orders
    private double totalDeposited;  // Lifetime deposits (for audit)

    public Account(String userId, String name, double initialBalance) {
        this.userId = userId;
        this.name = name;
        this.balance = initialBalance;
        this.marginUsed = 0.0;
        this.totalDeposited = initialBalance;
    }

    // =====================================================================
    // SYNCHRONIZED OPERATIONS — Thread-safe fund management
    // =====================================================================

    /**
     * Available margin = what the user can spend on NEW orders.
     * WHY not just balance: some of the balance is already "promised" to open orders.
     */
    public synchronized double getAvailableMargin() {
        return balance - marginUsed;
    }

    /**
     * Block margin when a new order is accepted.
     * WHY block first, debit later: if the order never fills (limit order far from market),
     * we release the margin. Only when trade settles do we actually debit.
     *
     * @throws IllegalArgumentException if insufficient funds
     */
    public synchronized void blockMargin(double amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("Block amount must be positive: " + amount);
        }
        if (getAvailableMargin() < amount) {
            throw new IllegalArgumentException(
                    String.format("Insufficient margin. Available: %.2f, Required: %.2f",
                            getAvailableMargin(), amount));
        }
        this.marginUsed += amount;
    }

    /**
     * Release margin when an order is cancelled or expires without filling.
     * The funds were never actually spent — just "reserved".
     */
    public synchronized void releaseMargin(double amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("Release amount must be positive: " + amount);
        }
        this.marginUsed = Math.max(0, this.marginUsed - amount);
    }

    /**
     * Debit funds from account (buyer pays for shares).
     * Called during trade settlement, NOT during order placement.
     */
    public synchronized void debit(double amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("Debit amount must be positive: " + amount);
        }
        this.balance -= amount;
        // Also release the margin that was blocked for this order
        this.marginUsed = Math.max(0, this.marginUsed - amount);
    }

    /**
     * Credit funds to account (seller receives payment for shares).
     * Called during trade settlement.
     */
    public synchronized void credit(double amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("Credit amount must be positive: " + amount);
        }
        this.balance += amount;
    }

    /**
     * Deposit fresh funds into the account.
     */
    public synchronized void deposit(double amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("Deposit must be positive: " + amount);
        }
        this.balance += amount;
        this.totalDeposited += amount;
    }

    // =====================================================================
    // GETTERS
    // =====================================================================

    public String getUserId() { return userId; }
    public String getName() { return name; }
    public synchronized double getBalance() { return balance; }
    public synchronized double getMarginUsed() { return marginUsed; }
    public double getTotalDeposited() { return totalDeposited; }

    @Override
    public String toString() {
        return String.format("Account{user='%s', name='%s', balance=%.2f, marginUsed=%.2f, available=%.2f}",
                userId, name, balance, marginUsed, getAvailableMargin());
    }
}
