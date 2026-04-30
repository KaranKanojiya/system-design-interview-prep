package com.systemdesign.trading.service;

import com.systemdesign.trading.exception.InsufficientMarginException;
import com.systemdesign.trading.model.Account;
import com.systemdesign.trading.model.Trade;
import com.systemdesign.trading.repository.AccountRepository;

/**
 * AccountService manages user trading accounts (funds, margin).
 *
 * WHY synchronized at the Account level (not service level):
 * - Service-level synchronization would serialize ALL account operations across ALL users.
 * - Account-level synchronization (see Account.java) allows concurrent operations on
 *   different users' accounts. Only operations on the SAME account are serialized.
 * - This mirrors database row-level locking (SELECT ... FOR UPDATE on the account row).
 *
 * CALL CHAIN:
 * TradingService.placeOrder() → AccountService.blockMargin() → Account.blockMargin()
 * Trade settles → AccountService.settleTradePayment() →
 *   buyer: Account.debit() (pays for shares)
 *   seller: Account.credit() (receives payment)
 */
public class AccountService {

    private final AccountRepository accountRepository;

    public AccountService(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    /**
     * Get a user's account. Throws if account doesn't exist.
     */
    public Account getAccount(String userId) {
        Account account = accountRepository.findByUserId(userId);
        if (account == null) {
            throw new IllegalArgumentException("Account not found for user: " + userId);
        }
        return account;
    }

    /**
     * Block margin for a new order.
     *
     * WHY block, not debit:
     * - The order might not fill (limit order far from market).
     * - Blocked margin is "reserved" but not spent.
     * - When order fills → debit (actually spend the money).
     * - When order cancelled → releaseMargin (give back the reservation).
     */
    public void blockMargin(String userId, double amount) {
        Account account = getAccount(userId);
        try {
            account.blockMargin(amount);
        } catch (IllegalArgumentException e) {
            throw new InsufficientMarginException(account.getAvailableMargin(), amount);
        }
    }

    /**
     * Release margin when an order is cancelled or expires.
     */
    public void releaseMargin(String userId, double amount) {
        Account account = getAccount(userId);
        account.releaseMargin(amount);
    }

    /**
     * Settle a trade: transfer funds from buyer to seller.
     *
     * SETTLEMENT FLOW:
     * 1. Buyer's account: debit trade value (reduces balance, releases margin).
     * 2. Seller's account: credit trade value (increases balance).
     *
     * WHY debit also releases margin:
     * - When the order was placed, we blocked margin = price * qty.
     * - When the trade settles, we debit the actual amount.
     * - The debit method in Account also reduces marginUsed.
     */
    public void settleTradePayment(Trade trade) {
        double tradeValue = trade.getPrice() * trade.getQuantity();

        // Buyer pays
        Account buyerAccount = getAccount(trade.getBuyerUserId());
        buyerAccount.debit(tradeValue);

        // Seller receives
        Account sellerAccount = getAccount(trade.getSellerUserId());
        sellerAccount.credit(tradeValue);
    }

    /**
     * Save/update an account.
     */
    public void saveAccount(Account account) {
        accountRepository.save(account);
    }
}
