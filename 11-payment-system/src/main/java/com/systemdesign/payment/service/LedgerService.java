package com.systemdesign.payment.service;

import com.systemdesign.payment.model.*;
import com.systemdesign.payment.repository.AccountRepository;
import com.systemdesign.payment.repository.LedgerRepository;

import java.util.List;

/**
 * LedgerService — THE CRITICAL SERVICE: Double-Entry Bookkeeping.
 *
 * ═══════════════════════════════════════════════════════════
 *  DOUBLE-ENTRY BOOKKEEPING EXPLAINED
 * ═══════════════════════════════════════════════════════════
 *
 * Every financial transaction creates EXACTLY TWO ledger entries:
 *   - One DEBIT  (money leaves an account)
 *   - One CREDIT (money enters an account)
 *
 * The amounts are equal and opposite, so their sum is always zero.
 *
 * EXAMPLE — Customer pays $100 to Merchant:
 *   Entry 1: DEBIT  Customer Account  +$100  (money leaves customer)
 *   Entry 2: CREDIT Merchant Account  -$100  (money enters merchant)
 *   Sum: +100 + (-100) = 0  ✓
 *
 * EXAMPLE — Refund of $100:
 *   Entry 3: DEBIT  Merchant Account  +$100  (money leaves merchant)
 *   Entry 4: CREDIT Customer Account  -$100  (money returns to customer)
 *   Sum: +100 + (-100) = 0  ✓
 *
 * WHY DOUBLE-ENTRY?
 *   1. Audit trail — every dollar is accounted for, you can trace where it went
 *   2. Error detection — if the sum of all entries ≠ 0, something is wrong
 *   3. Regulatory compliance — financial regulations require it
 *   4. Reconciliation — match internal ledger against bank statements
 *
 * THE INVARIANT:
 *   sum(all ledger entries across all accounts) == 0
 *   This is verified by verifyBalance() and checked by ReconciliationService.
 *
 * CALL CHAIN:
 *   PaymentService.processPayment() → LedgerService.recordPayment(payment)
 *     → creates DEBIT entry for customer/platform account
 *     → creates CREDIT entry for merchant account
 *     → updates Account balances (synchronized)
 *
 * ═══════════════════════════════════════════════════════════
 */
public class LedgerService {

    private final LedgerRepository ledgerRepository;
    private final AccountRepository accountRepository;

    // The platform's own account — collects fees, holds float
    // In a real system this would be configurable, not hardcoded
    private static final String PLATFORM_ACCOUNT_ID = "ACC-PLATFORM";

    public LedgerService(LedgerRepository ledgerRepository, AccountRepository accountRepository) {
        this.ledgerRepository = ledgerRepository;
        this.accountRepository = accountRepository;
    }

    /**
     * Record ledger entries for a successful payment.
     *
     * Creates TWO entries (double-entry bookkeeping):
     *   1. DEBIT  the customer/platform account (money out)
     *   2. CREDIT the merchant account (money in)
     *
     * Also updates the Account balances atomically (synchronized on each account).
     *
     * @param payment the successfully processed payment
     */
    public void recordPayment(Payment payment) {
        // Determine the source account — use customer account if available, else platform
        String sourceAccountId = (payment.getCustomerId() != null && !payment.getCustomerId().isBlank())
                                 ? payment.getCustomerId()
                                 : PLATFORM_ACCOUNT_ID;

        // The destination is always the merchant
        String destAccountId = payment.getMerchantId();

        // ── Entry 1: DEBIT the source account (money leaves) ──
        // Positive amount by convention — represents money going OUT
        LedgerEntry debitEntry = new LedgerEntry(
            payment.getPaymentId(),   // transactionId — links ledger entries to the payment
            sourceAccountId,
            payment.getAmount(),       // positive for DEBIT
            LedgerEntryType.DEBIT,
            payment.getCurrency(),
            "Payment " + payment.getPaymentId() + " — debit from " + sourceAccountId
        );

        // ── Entry 2: CREDIT the merchant account (money arrives) ──
        // Negative amount by convention — represents money coming IN
        // WHY negative? So that sum(all entries) = amount + (-amount) = 0
        LedgerEntry creditEntry = new LedgerEntry(
            payment.getPaymentId(),
            destAccountId,
            -payment.getAmount(),      // negative for CREDIT
            LedgerEntryType.CREDIT,
            payment.getCurrency(),
            "Payment " + payment.getPaymentId() + " — credit to " + destAccountId
        );

        // Save both entries atomically
        ledgerRepository.save(debitEntry);
        ledgerRepository.save(creditEntry);

        // Update account balances
        // Account.debit() and .credit() are synchronized internally
        accountRepository.findById(destAccountId).ifPresent(account ->
            account.credit(payment.getAmount())
        );

        System.out.println("    [Ledger] Recorded double-entry for payment " + payment.getPaymentId());
        System.out.println("      DEBIT  " + sourceAccountId + " → " + payment.getCurrency().format(payment.getAmount()));
        System.out.println("      CREDIT " + destAccountId + "   → " + payment.getCurrency().format(payment.getAmount()));
    }

    /**
     * Record REVERSE ledger entries for a refund.
     *
     * Creates TWO entries that reverse the original payment:
     *   1. DEBIT  the merchant account (money leaves merchant)
     *   2. CREDIT the customer/platform account (money returns to customer)
     */
    public void recordRefund(Refund refund, Payment originalPayment) {
        String customerAccountId = (originalPayment.getCustomerId() != null
                                    && !originalPayment.getCustomerId().isBlank())
                                   ? originalPayment.getCustomerId()
                                   : PLATFORM_ACCOUNT_ID;

        String merchantAccountId = originalPayment.getMerchantId();

        // ── Reverse Entry 1: DEBIT merchant (money leaves merchant) ──
        LedgerEntry debitEntry = new LedgerEntry(
            refund.getRefundId(),
            merchantAccountId,
            refund.getAmount(),
            LedgerEntryType.DEBIT,
            originalPayment.getCurrency(),
            "Refund " + refund.getRefundId() + " — debit from merchant " + merchantAccountId
        );

        // ── Reverse Entry 2: CREDIT customer (money returns to customer) ──
        LedgerEntry creditEntry = new LedgerEntry(
            refund.getRefundId(),
            customerAccountId,
            -refund.getAmount(),
            LedgerEntryType.CREDIT,
            originalPayment.getCurrency(),
            "Refund " + refund.getRefundId() + " — credit to customer " + customerAccountId
        );

        ledgerRepository.save(debitEntry);
        ledgerRepository.save(creditEntry);

        // Update account balances
        accountRepository.findById(merchantAccountId).ifPresent(account -> {
            try {
                account.debit(refund.getAmount());
            } catch (Exception e) {
                System.out.println("    [Ledger] WARNING: Could not debit merchant account: " + e.getMessage());
            }
        });
        accountRepository.findById(customerAccountId).ifPresent(account ->
            account.credit(refund.getAmount())
        );

        System.out.println("    [Ledger] Recorded reverse entries for refund " + refund.getRefundId());
        System.out.println("      DEBIT  " + merchantAccountId + " → " + originalPayment.getCurrency().format(refund.getAmount()));
        System.out.println("      CREDIT " + customerAccountId + "   → " + originalPayment.getCurrency().format(refund.getAmount()));
    }

    /**
     * Get the computed balance for an account from ledger entries.
     *
     * This sums all ledger entries for the account.
     * DEBIT entries are positive (money out), CREDIT entries are negative (money in).
     * For a merchant receiving payments, the sum will be negative (net credit).
     * The absolute value of that sum is the merchant's balance.
     */
    public double getBalance(String accountId) {
        return ledgerRepository.findByAccountId(accountId).stream()
                .mapToDouble(LedgerEntry::getAmount)
                .sum();
    }

    /**
     * Get all ledger entries for an account.
     */
    public List<LedgerEntry> getLedgerEntries(String accountId) {
        return ledgerRepository.findByAccountId(accountId);
    }

    /**
     * VERIFY THE FUNDAMENTAL INVARIANT: sum of all ledger entries = 0.
     *
     * If this returns a non-zero value, we have a bug in our bookkeeping.
     * This is the most important check in any financial system.
     *
     * @return the sum of all ledger entries (should be 0.0 or very close to it)
     */
    public double verifyBalance() {
        double totalSum = ledgerRepository.findAll().stream()
                .mapToDouble(LedgerEntry::getAmount)
                .sum();

        // Due to floating-point arithmetic, the sum might be very close to zero
        // but not exactly zero.  In production with BigDecimal this would be exact.
        if (Math.abs(totalSum) > 0.01) {
            System.out.println("    [Ledger] WARNING: Ledger imbalance detected! Sum = " + totalSum);
        } else {
            System.out.println("    [Ledger] Ledger balanced: sum of all entries = "
                               + String.format("%.4f", totalSum));
        }
        return totalSum;
    }

    /**
     * Get all ledger entries in the system.
     */
    public List<LedgerEntry> getAllEntries() {
        return ledgerRepository.findAll();
    }
}
