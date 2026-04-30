package com.systemdesign.payment;

import com.systemdesign.payment.config.AppConfig;
import com.systemdesign.payment.controller.PaymentController;
import com.systemdesign.payment.exception.FraudDetectedException;
import com.systemdesign.payment.model.*;
import com.systemdesign.payment.service.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * PaymentSystemApp — Main demo application for the Payment System.
 *
 * Demonstrates all key features of a production payment system:
 *   - Payment processing lifecycle
 *   - Idempotency (duplicate prevention)
 *   - Multiple payment methods with different characteristics
 *   - Fraud detection (rule-based + ML)
 *   - Full and partial refunds
 *   - Double-entry ledger bookkeeping
 *   - Webhook delivery with exponential backoff retry
 *   - Reconciliation (internal vs external record matching)
 *   - Multi-currency support
 *   - Payment state machine transitions
 *
 * ARCHITECTURE:
 *   AppConfig (factory) wires all dependencies.
 *   PaymentController is the entry point for all operations.
 *   Services contain the business logic.
 *   Repositories provide data access.
 *   Strategies provide pluggable algorithms (processors, fraud checks).
 */
public class PaymentSystemApp {

    private static final String SEPARATOR = "=".repeat(70);

    public static void main(String[] args) {
        System.out.println(SEPARATOR);
        System.out.println("  PAYMENT SYSTEM — System Design Interview Demo");
        System.out.println("  Like Stripe / UPI / PayPal");
        System.out.println(SEPARATOR);

        // ── Initialize the system ──
        // AppConfig is the ONLY place with "new ConcreteClass()" — pure DI
        AppConfig config = new AppConfig();

        PaymentController controller = config.getPaymentController();
        PaymentService paymentService = config.getPaymentService();
        RefundService refundService = config.getRefundService();
        LedgerService ledgerService = config.getLedgerService();
        WebhookService webhookService = config.getWebhookService();
        CurrencyService currencyService = config.getCurrencyService();
        ReconciliationService reconciliationService = config.getReconciliationService();

        // ══════════════════════════════════════════════════════════════
        //  DEMO 1: Successful Payment Flow
        //  Shows the full lifecycle: initiate → process → ledger → webhook
        // ══════════════════════════════════════════════════════════════
        System.out.println("\n" + SEPARATOR);
        System.out.println("  DEMO 1: Successful Payment Flow");
        System.out.println(SEPARATOR);

        Payment payment1 = controller.handleProcessPayment(
            "MERCHANT-001",
            99.99,
            Currency.USD,
            PaymentMethod.CREDIT_CARD,
            "idem-" + UUID.randomUUID().toString().substring(0, 8),
            "CUSTOMER-001"
        );

        if (payment1 != null) {
            System.out.println("\n  Result: " + payment1);
            System.out.println("  Status: " + payment1.getStatus());
        }

        // ══════════════════════════════════════════════════════════════
        //  DEMO 2: Idempotency — Duplicate Prevention
        //  Same idempotency key sent twice → second call returns cached result.
        //  This prevents the customer from being charged twice on retry.
        // ══════════════════════════════════════════════════════════════
        System.out.println("\n" + SEPARATOR);
        System.out.println("  DEMO 2: Idempotency — Duplicate Prevention");
        System.out.println(SEPARATOR);

        String sharedIdempotencyKey = "idem-duplicate-test-001";

        System.out.println("\n  First request with key: " + sharedIdempotencyKey);
        Payment firstPayment = controller.handleProcessPayment(
            "MERCHANT-001", 150.00, Currency.USD, PaymentMethod.CREDIT_CARD,
            sharedIdempotencyKey, "CUSTOMER-001"
        );

        System.out.println("\n  Second request with SAME key (simulating retry):");
        Payment duplicatePayment = controller.handleProcessPayment(
            "MERCHANT-001", 150.00, Currency.USD, PaymentMethod.CREDIT_CARD,
            sharedIdempotencyKey, "CUSTOMER-001"
        );

        if (firstPayment != null && duplicatePayment != null) {
            System.out.println("\n  First payment ID:  " + firstPayment.getPaymentId());
            System.out.println("  Second payment ID: " + duplicatePayment.getPaymentId());
            System.out.println("  Same payment? " + firstPayment.getPaymentId().equals(duplicatePayment.getPaymentId()));
            System.out.println("  Customer was NOT charged twice!");
        }

        // ══════════════════════════════════════════════════════════════
        //  DEMO 3: Payment Method Comparison
        //  Credit Card vs UPI vs Wallet — different success rates and latency
        // ══════════════════════════════════════════════════════════════
        System.out.println("\n" + SEPARATOR);
        System.out.println("  DEMO 3: Payment Method Comparison");
        System.out.println(SEPARATOR);

        PaymentMethod[] methods = { PaymentMethod.CREDIT_CARD, PaymentMethod.UPI, PaymentMethod.WALLET };
        for (PaymentMethod method : methods) {
            System.out.println("\n  --- " + method + " ---");
            long start = System.currentTimeMillis();
            Payment p = controller.handleProcessPayment(
                "MERCHANT-002", 49.99, Currency.USD, method,
                "idem-" + method + "-" + UUID.randomUUID().toString().substring(0, 8),
                "CUSTOMER-001"
            );
            long elapsed = System.currentTimeMillis() - start;
            if (p != null) {
                System.out.println("  Status: " + p.getStatus() + " | Latency: " + elapsed + "ms");
            }
        }

        // ══════════════════════════════════════════════════════════════
        //  DEMO 4: Fraud Detection
        //  Rule-based + ML fraud check blocks a suspicious payment.
        //  Amount > $10,000 triggers the rule-based check.
        // ══════════════════════════════════════════════════════════════
        System.out.println("\n" + SEPARATOR);
        System.out.println("  DEMO 4: Fraud Detection");
        System.out.println(SEPARATOR);

        System.out.println("\n  Attempting suspicious payment: $15,000 (exceeds $10K threshold)");
        Payment fraudPayment = controller.handleProcessPayment(
            "MERCHANT-001", 15_000.00, Currency.USD, PaymentMethod.CREDIT_CARD,
            "idem-fraud-" + UUID.randomUUID().toString().substring(0, 8),
            "CUSTOMER-002"
        );
        System.out.println("  Result: " + (fraudPayment == null ? "BLOCKED by fraud detection" : fraudPayment));

        // ══════════════════════════════════════════════════════════════
        //  DEMO 5: Full Refund Processing
        //  Refund a captured payment — ledger entries are reversed.
        // ══════════════════════════════════════════════════════════════
        System.out.println("\n" + SEPARATOR);
        System.out.println("  DEMO 5: Full Refund Processing");
        System.out.println(SEPARATOR);

        // First, make a payment to refund
        Payment toRefund = controller.handleProcessPayment(
            "MERCHANT-001", 200.00, Currency.USD, PaymentMethod.WALLET,
            "idem-refund-full-" + UUID.randomUUID().toString().substring(0, 8),
            "CUSTOMER-001"
        );

        if (toRefund != null && (toRefund.getStatus() == PaymentStatus.CAPTURED
                                 || toRefund.getStatus() == PaymentStatus.SETTLED)) {
            System.out.println("\n  Now refunding the full amount...");
            Refund fullRefund = controller.handleRefund(
                toRefund.getPaymentId(), 200.00, "Customer requested full refund"
            );
            if (fullRefund != null) {
                System.out.println("  Refund status: " + fullRefund.getStatus());
            }
        }

        // ══════════════════════════════════════════════════════════════
        //  DEMO 6: Partial Refund
        //  Refund $30 of a $100 payment — merchant keeps $70.
        // ══════════════════════════════════════════════════════════════
        System.out.println("\n" + SEPARATOR);
        System.out.println("  DEMO 6: Partial Refund ($30 of $100)");
        System.out.println(SEPARATOR);

        Payment partialRefundPayment = controller.handleProcessPayment(
            "MERCHANT-002", 100.00, Currency.USD, PaymentMethod.WALLET,
            "idem-refund-partial-" + UUID.randomUUID().toString().substring(0, 8),
            "CUSTOMER-002"
        );

        if (partialRefundPayment != null
            && (partialRefundPayment.getStatus() == PaymentStatus.CAPTURED
                || partialRefundPayment.getStatus() == PaymentStatus.SETTLED)) {
            System.out.println("\n  Refunding $30 of $100 payment...");
            Refund partialRefund = controller.handleRefund(
                partialRefundPayment.getPaymentId(), 30.00, "Partial refund — item returned"
            );
            if (partialRefund != null) {
                System.out.println("  Partial refund status: " + partialRefund.getStatus());
                System.out.println("  Refunded: $30.00 | Merchant keeps: $70.00");
            }
        }

        // ══════════════════════════════════════════════════════════════
        //  DEMO 7: Double-Entry Ledger Verification
        //  Show all entries and verify they sum to zero.
        // ══════════════════════════════════════════════════════════════
        System.out.println("\n" + SEPARATOR);
        System.out.println("  DEMO 7: Double-Entry Ledger Verification");
        System.out.println(SEPARATOR);

        System.out.println("\n  All ledger entries:");
        List<LedgerEntry> allEntries = ledgerService.getAllEntries();
        for (LedgerEntry entry : allEntries) {
            System.out.println("    " + entry);
        }

        System.out.println("\n  Verifying ledger balance (sum of all entries should be 0):");
        double sum = ledgerService.verifyBalance();
        System.out.printf("  Total sum: %.4f — %s%n", sum,
            Math.abs(sum) < 0.01 ? "BALANCED" : "IMBALANCED!");

        // ══════════════════════════════════════════════════════════════
        //  DEMO 8: Webhook Delivery with Retry
        //  First attempt fails → retry succeeds with exponential backoff.
        // ══════════════════════════════════════════════════════════════
        System.out.println("\n" + SEPARATOR);
        System.out.println("  DEMO 8: Webhook Delivery with Retry");
        System.out.println(SEPARATOR);

        // Enable first-attempt failure simulation
        webhookService.setSimulateFirstAttemptFailure(true);

        Payment webhookPayment = controller.handleProcessPayment(
            "MERCHANT-003", 75.00, Currency.USD, PaymentMethod.UPI,
            "idem-webhook-" + UUID.randomUUID().toString().substring(0, 8),
            "CUSTOMER-001"
        );

        // Now retry failed webhooks
        if (webhookPayment != null) {
            System.out.println("\n  Retrying failed webhooks...");
            webhookService.setSimulateFirstAttemptFailure(false); // Allow success on retry
            controller.handleRetryWebhooks();
        }

        // ══════════════════════════════════════════════════════════════
        //  DEMO 9: Reconciliation
        //  Match internal transactions against external processor statements.
        //  Deliberately introduce discrepancies to show mismatch detection.
        // ══════════════════════════════════════════════════════════════
        System.out.println("\n" + SEPARATOR);
        System.out.println("  DEMO 9: Reconciliation (Internal vs External)");
        System.out.println(SEPARATOR);

        // Build internal transaction list from successful payments
        List<Transaction> internalTxns = new ArrayList<>();
        internalTxns.add(new Transaction("PAY-001", "CreditCardProcessor", "TXN-CC-match1",
            99.99, "APPROVED", "00", "OK"));
        internalTxns.add(new Transaction("PAY-002", "UPIProcessor", "TXN-UPI-match2",
            49.99, "APPROVED", "00", "OK"));
        internalTxns.add(new Transaction("PAY-003", "WalletProcessor", "TXN-WAL-missing-ext",
            75.00, "APPROVED", "00", "OK"));

        // Build external statements (processor's records)
        // Deliberately include:
        //   - A matched transaction (TXN-CC-match1)
        //   - An amount mismatch (TXN-UPI-match2 with different amount)
        //   - A transaction we don't have (TXN-CC-unknown)
        //   - Missing: TXN-WAL-missing-ext (we have it, they don't)
        List<ReconciliationService.ExternalStatement> externalStmts = new ArrayList<>();
        externalStmts.add(new ReconciliationService.ExternalStatement("TXN-CC-match1", 99.99, "SETTLED"));
        externalStmts.add(new ReconciliationService.ExternalStatement("TXN-UPI-match2", 49.50, "SETTLED")); // Amount mismatch!
        externalStmts.add(new ReconciliationService.ExternalStatement("TXN-CC-unknown", 120.00, "SETTLED")); // We don't have this!

        ReconciliationService.ReconciliationReport report =
            controller.handleReconciliation(internalTxns, externalStmts);

        System.out.println("\n  Reconciliation Results:");
        System.out.println("    Matched:          " + report.getMatched().size());
        for (String m : report.getMatched()) System.out.println("      " + m);

        System.out.println("    Amount Mismatches: " + report.getAmountMismatches().size());
        for (String m : report.getAmountMismatches()) System.out.println("      " + m);

        System.out.println("    Missing External:  " + report.getMissingExternal().size());
        for (String m : report.getMissingExternal()) System.out.println("      " + m);

        System.out.println("    Missing Internal:  " + report.getMissingInternal().size());
        for (String m : report.getMissingInternal()) System.out.println("      " + m);

        System.out.println("    Clean: " + report.isClean());

        // ══════════════════════════════════════════════════════════════
        //  DEMO 10: Multi-Currency Payment
        //  USD → INR conversion during payment.
        // ══════════════════════════════════════════════════════════════
        System.out.println("\n" + SEPARATOR);
        System.out.println("  DEMO 10: Multi-Currency Payment (USD → INR)");
        System.out.println(SEPARATOR);

        double usdAmount = 100.00;
        double inrAmount = currencyService.convert(usdAmount, Currency.USD, Currency.INR);
        System.out.println("  Original: " + Currency.USD.format(usdAmount));
        System.out.println("  Converted: " + Currency.INR.format(inrAmount));

        // Process payment in INR
        Payment inrPayment = controller.handleProcessPayment(
            "MERCHANT-003", inrAmount, Currency.INR, PaymentMethod.UPI,
            "idem-inr-" + UUID.randomUUID().toString().substring(0, 8),
            "CUSTOMER-001"
        );
        if (inrPayment != null) {
            System.out.println("  INR Payment: " + inrPayment.getCurrency().format(inrPayment.getAmount()));
        }

        // ══════════════════════════════════════════════════════════════
        //  DEMO 11: Payment State Machine
        //  Show all valid and invalid transitions.
        // ══════════════════════════════════════════════════════════════
        System.out.println("\n" + SEPARATOR);
        System.out.println("  DEMO 11: Payment State Machine Transitions");
        System.out.println(SEPARATOR);

        demonstrateStateMachine();

        // ══════════════════════════════════════════════════════════════
        //  FINAL: Display Statistics
        // ══════════════════════════════════════════════════════════════
        config.getStatsDisplay().displayStats();

        // ══════════════════════════════════════════════════════════════
        //  DESIGN SUMMARY
        // ══════════════════════════════════════════════════════════════
        printDesignSummary();
    }

    /**
     * Demonstrate valid and invalid state machine transitions.
     */
    private static void demonstrateStateMachine() {
        System.out.println("\n  Valid transitions (happy path):");
        System.out.println("    INITIATED → PROCESSING → AUTHORIZED → CAPTURED → SETTLED");
        System.out.println("    INITIATED → PROCESSING → CAPTURED (instant settlement: UPI/Wallet)");
        System.out.println("    CAPTURED/SETTLED → REFUNDED");
        System.out.println("    AUTHORIZED → CANCELLED");
        System.out.println("    Any non-terminal → FAILED");

        // Demonstrate a valid transition chain
        Payment demo = new Payment.Builder()
            .merchantId("DEMO-MERCHANT")
            .amount(50.00)
            .build();

        System.out.println("\n  Walking through the happy path:");
        System.out.println("    Current state: " + demo.getStatus());

        demo.startProcessing();
        System.out.println("    After startProcessing(): " + demo.getStatus());

        demo.authorize();
        System.out.println("    After authorize(): " + demo.getStatus());

        demo.capture();
        System.out.println("    After capture(): " + demo.getStatus());

        demo.settle();
        System.out.println("    After settle(): " + demo.getStatus());

        // Demonstrate invalid transitions
        System.out.println("\n  Invalid transitions (should throw IllegalStateException):");

        // Try to authorize a settled payment
        try {
            demo.authorize();
            System.out.println("    ERROR: should not reach here");
        } catch (IllegalStateException e) {
            System.out.println("    settle → authorize: BLOCKED — " + e.getMessage());
        }

        // Try to cancel a settled payment
        try {
            demo.cancel();
            System.out.println("    ERROR: should not reach here");
        } catch (IllegalStateException e) {
            System.out.println("    settle → cancel: BLOCKED — " + e.getMessage());
        }

        // Create a new payment and try double-capture
        Payment demo2 = new Payment.Builder()
            .merchantId("DEMO-MERCHANT")
            .amount(25.00)
            .build();
        demo2.startProcessing();
        demo2.capture();

        try {
            demo2.capture();
            System.out.println("    ERROR: should not reach here");
        } catch (IllegalStateException e) {
            System.out.println("    captured → capture (double): BLOCKED — " + e.getMessage());
        }

        // Try to fail a terminal-state payment
        Payment demo3 = new Payment.Builder()
            .merchantId("DEMO-MERCHANT")
            .amount(10.00)
            .build();
        demo3.startProcessing();
        demo3.capture();
        demo3.refund();

        try {
            demo3.fail();
            System.out.println("    ERROR: should not reach here");
        } catch (IllegalStateException e) {
            System.out.println("    refunded → fail: BLOCKED — " + e.getMessage());
        }
    }

    /**
     * Print a summary of the system design for interview discussion.
     */
    private static void printDesignSummary() {
        String separator = "=".repeat(70);
        System.out.println("\n" + separator);
        System.out.println("  DESIGN SUMMARY — Key Patterns & Decisions");
        System.out.println(separator);
        System.out.println();
        System.out.println("  1. STRATEGY PATTERN — Payment processors (CC, UPI, Wallet)");
        System.out.println("     Different processors, same interface. Easy to add new methods.");
        System.out.println();
        System.out.println("  2. STRATEGY PATTERN — Fraud detection (Rule-based, ML)");
        System.out.println("     Multiple checks run in parallel, fail if any flags.");
        System.out.println();
        System.out.println("  3. FACADE PATTERN — PaymentService orchestrates the entire flow.");
        System.out.println("     Single entry point, coordinates 7 steps internally.");
        System.out.println();
        System.out.println("  4. BUILDER PATTERN — Payment and Refund objects.");
        System.out.println("     Avoids telescoping constructors, clear construction.");
        System.out.println();
        System.out.println("  5. STATE MACHINE — Payment status transitions.");
        System.out.println("     Guarded transitions prevent double-charges.");
        System.out.println();
        System.out.println("  6. DOUBLE-ENTRY BOOKKEEPING — Ledger entries.");
        System.out.println("     Every payment creates 2 entries that sum to zero.");
        System.out.println("     Audit trail, error detection, regulatory compliance.");
        System.out.println();
        System.out.println("  7. IDEMPOTENCY — Prevents duplicate charges on retry.");
        System.out.println("     Synchronized check-and-store to handle race conditions.");
        System.out.println();
        System.out.println("  8. WEBHOOK DELIVERY — Exponential backoff retry.");
        System.out.println("     At-least-once delivery, HMAC-SHA256 signatures.");
        System.out.println();
        System.out.println("  9. RECONCILIATION — Match internal vs external records.");
        System.out.println("     Finds mismatches before they become financial problems.");
        System.out.println();
        System.out.println("  10. REPOSITORY PATTERN — Decouples storage from logic.");
        System.out.println("      Interface + InMemory impl; swap for Postgres in production.");
        System.out.println();
        System.out.println("  11. FACTORY / DI — AppConfig is the single wiring point.");
        System.out.println("      Only place with 'new ConcreteClass()'. Pure dependency injection.");
        System.out.println();
        System.out.println("  12. THREAD SAFETY — Synchronized Account.credit/debit,");
        System.out.println("      ConcurrentHashMap repositories, synchronized idempotency.");
        System.out.println();
        System.out.println(separator);
        System.out.println("  End of Payment System Demo");
        System.out.println(separator);
    }
}
