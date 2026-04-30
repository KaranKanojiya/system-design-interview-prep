package com.systemdesign.payment.strategy.processor;

import com.systemdesign.payment.model.Payment;
import com.systemdesign.payment.model.Refund;
import com.systemdesign.payment.model.Transaction;

/**
 * PaymentProcessor — Strategy interface for processing payments through different providers.
 *
 * STRATEGY PATTERN:
 *   Each payment method (credit card, UPI, wallet) has a different processor
 *   with different APIs, latencies, success rates, and settlement behavior.
 *   The Strategy pattern lets PaymentService delegate to the right processor
 *   without knowing the implementation details.
 *
 * CALL CHAIN:
 *   PaymentService.processPayment()
 *     → selects PaymentProcessor based on payment.getMethod()
 *     → calls processor.processPayment(payment)
 *     → processor talks to external API (simulated here)
 *     → returns Transaction with processor's response
 *
 * In production, each implementation would make HTTP calls to the actual
 * processor's API (Stripe, Razorpay, PhonePe, etc.).
 */
public interface PaymentProcessor {

    /**
     * Process a payment through this processor.
     *
     * @param payment the payment to process (status should be PROCESSING)
     * @return Transaction with the processor's response (approved/declined)
     */
    Transaction processPayment(Payment payment);

    /**
     * Process a refund through this processor.
     *
     * @param refund  the refund request
     * @param payment the original payment being refunded
     * @return Transaction with the processor's refund response
     */
    Transaction processRefund(Refund refund, Payment payment);

    /**
     * @return human-readable name of this processor (e.g. "CreditCardProcessor")
     */
    String getName();
}
