package com.systemdesign.payment.strategy.processor;

import com.systemdesign.payment.model.Payment;
import com.systemdesign.payment.model.Refund;
import com.systemdesign.payment.model.Transaction;

import java.util.Random;
import java.util.UUID;

/**
 * UPIProcessor — Simulates India's Unified Payments Interface.
 *
 * UPI CHARACTERISTICS:
 *   - 98% success rate (higher than credit cards — fewer intermediaries)
 *   - Instant settlement — no separate auth/capture phases
 *   - Uses VPA (Virtual Payment Address) like "user@upi" instead of card numbers
 *   - Settlement is real-time (money moves immediately between banks)
 *   - Very popular in India: ~10 billion transactions/month
 *
 * WHY no auth/capture split?
 *   UPI debits the customer's bank account instantly.  There's no concept
 *   of "holding" funds.  The payment is either done or it's not.
 *   This means PROCESSING → CAPTURED directly (skipping AUTHORIZED).
 *
 * VPA VALIDATION:
 *   In a real system, we'd validate the VPA format (user@bank) and check
 *   if the VPA is registered.  Here we simulate it with a simple check.
 */
public class UPIProcessor implements PaymentProcessor {

    private final Random random = new Random();

    @Override
    public Transaction processPayment(Payment payment) {
        // UPI is faster than cards — typically 50-100ms
        simulateLatency(80);

        String processorTxnId = "TXN-UPI-" + UUID.randomUUID().toString().substring(0, 8);

        // Simulate VPA validation
        // In production: call NPCI's API to validate VPA exists and is active
        System.out.println("    [UPIProcessor] Validating VPA for customer: "
                           + payment.getCustomerId());

        // 98% success rate
        int roll = random.nextInt(100);

        if (roll < 98) {
            // SUCCESS — instant settlement, no auth/capture split
            return new Transaction(
                payment.getPaymentId(),
                getName(),
                processorTxnId,
                payment.getAmount(),
                "APPROVED",
                "00",
                "UPI transaction successful — instant settlement"
            );
        } else {
            // FAILURE (2%) — could be invalid VPA, bank server down, daily limit exceeded
            String[] failureReasons = {
                "Invalid VPA or bank server unavailable",
                "Daily transaction limit exceeded"
            };
            return new Transaction(
                payment.getPaymentId(),
                getName(),
                processorTxnId,
                payment.getAmount(),
                "DECLINED",
                "U30",   // UPI-specific response code
                failureReasons[random.nextInt(failureReasons.length)]
            );
        }
    }

    @Override
    public Transaction processRefund(Refund refund, Payment payment) {
        simulateLatency(60);

        String processorTxnId = "TXN-UPI-REF-" + UUID.randomUUID().toString().substring(0, 8);

        // UPI refunds are also instant
        return new Transaction(
            payment.getPaymentId(),
            getName(),
            processorTxnId,
            refund.getAmount(),
            "APPROVED",
            "00",
            "UPI refund processed — instant credit to customer"
        );
    }

    @Override
    public String getName() {
        return "UPIProcessor";
    }

    private void simulateLatency(int baseMs) {
        try {
            int jitter = random.nextInt(Math.max(1, baseMs / 5)) - (baseMs / 10);
            Thread.sleep(Math.max(10, baseMs + jitter));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
