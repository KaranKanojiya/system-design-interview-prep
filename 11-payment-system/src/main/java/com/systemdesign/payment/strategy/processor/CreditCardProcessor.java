package com.systemdesign.payment.strategy.processor;

import com.systemdesign.payment.model.Payment;
import com.systemdesign.payment.model.Refund;
import com.systemdesign.payment.model.Transaction;

import java.util.Random;
import java.util.UUID;

/**
 * CreditCardProcessor — Simulates a credit card payment gateway (like Stripe or Adyen).
 *
 * SIMULATED BEHAVIOR:
 *   - 95% success rate (realistic for credit cards)
 *   - ~200ms latency (simulates network round-trip to card network)
 *   - Two-phase: authorize (hold funds) → capture (move funds)
 *   - Decline reasons broken down:
 *       3% insufficient funds (code "51") — customer's card is maxed out
 *       1% fraud suspected (code "59")   — issuer's fraud system flagged it
 *       1% network error (code "91")     — communication failure with issuer
 *
 * WHY simulate failures?
 *   In interviews, showing you understand failure modes is more impressive
 *   than showing a happy path.  Real systems see ~5% decline rates on cards.
 *
 * GENERATED TRANSACTION IDs:
 *   Format: TXN-CC-{uuid} — the "CC" prefix lets you identify the processor
 *   in logs and during reconciliation.
 */
public class CreditCardProcessor implements PaymentProcessor {

    private final Random random = new Random();

    @Override
    public Transaction processPayment(Payment payment) {
        // Simulate network latency (~200ms to card network)
        simulateLatency(200);

        String processorTxnId = "TXN-CC-" + UUID.randomUUID().toString().substring(0, 8);

        // Determine outcome — 95% success, 5% failure with specific reasons
        int roll = random.nextInt(100);

        if (roll < 95) {
            // SUCCESS — authorized and captured
            // In a real two-phase system, authorize() and capture() are separate API calls.
            // Here we simulate both in one step for simplicity.
            return new Transaction(
                payment.getPaymentId(),
                getName(),
                processorTxnId,
                payment.getAmount(),
                "APPROVED",
                "00",                          // ISO 8583 approval code
                "Transaction approved"
            );
        } else if (roll < 98) {
            // INSUFFICIENT FUNDS (3%) — customer's credit limit exceeded
            return new Transaction(
                payment.getPaymentId(),
                getName(),
                processorTxnId,
                payment.getAmount(),
                "DECLINED",
                "51",                          // ISO 8583 code for insufficient funds
                "Insufficient funds on card"
            );
        } else if (roll < 99) {
            // FRAUD SUSPECTED (1%) — issuing bank's fraud system flagged this
            return new Transaction(
                payment.getPaymentId(),
                getName(),
                processorTxnId,
                payment.getAmount(),
                "DECLINED",
                "59",                          // ISO 8583 code for suspected fraud
                "Suspected fraud — issuer declined"
            );
        } else {
            // NETWORK ERROR (1%) — couldn't reach the card network
            return new Transaction(
                payment.getPaymentId(),
                getName(),
                processorTxnId,
                payment.getAmount(),
                "ERROR",
                "91",                          // ISO 8583 code for issuer unavailable
                "Network error — issuer unavailable"
            );
        }
    }

    @Override
    public Transaction processRefund(Refund refund, Payment payment) {
        // Refunds almost always succeed (99%+), and they go through
        // the same card network in reverse.
        simulateLatency(150);

        String processorTxnId = "TXN-CC-REF-" + UUID.randomUUID().toString().substring(0, 8);

        return new Transaction(
            payment.getPaymentId(),
            getName(),
            processorTxnId,
            refund.getAmount(),
            "APPROVED",
            "00",
            "Refund processed successfully"
        );
    }

    @Override
    public String getName() {
        return "CreditCardProcessor";
    }

    /**
     * Simulate network latency to a payment processor.
     * In production this would be actual HTTP call latency.
     */
    private void simulateLatency(int baseMs) {
        try {
            // Add some jitter: base ± 20%
            int jitter = random.nextInt(baseMs / 5) - (baseMs / 10);
            Thread.sleep(baseMs + jitter);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
