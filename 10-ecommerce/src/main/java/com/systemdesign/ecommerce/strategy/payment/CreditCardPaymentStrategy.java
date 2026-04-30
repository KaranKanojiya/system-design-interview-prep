package com.systemdesign.ecommerce.strategy.payment;

import com.systemdesign.ecommerce.model.Payment;
import com.systemdesign.ecommerce.model.PaymentMethod;
import com.systemdesign.ecommerce.model.PaymentStatus;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * CreditCardPaymentStrategy — Simulates credit card payment processing.
 *
 * Interview notes:
 * - 95% success rate simulates real-world card declines (~3-5% of charges
 *   fail due to insufficient funds, expired cards, fraud checks, etc.).
 * - Generates a unique transactionId (UUID) that serves as the idempotency
 *   key for refunds.
 * - In production this would call Stripe/Braintree/Adyen via an HTTP client.
 */
public class CreditCardPaymentStrategy implements PaymentStrategy {

    private static final double SUCCESS_RATE = 0.95;

    @Override
    public Payment processPayment(double amount, String orderId) {
        String paymentId = "PAY-CC-" + UUID.randomUUID().toString().substring(0, 8);

        // Simulate gateway call with random success/failure
        boolean success = Math.random() < SUCCESS_RATE;

        return new Payment.Builder()
                .paymentId(paymentId)
                .orderId(orderId)
                .amount(amount)
                .method(PaymentMethod.CREDIT_CARD)
                .status(success ? PaymentStatus.COMPLETED : PaymentStatus.FAILED)
                .transactionId(success ? "TXN-" + UUID.randomUUID().toString().substring(0, 8) : null)
                .processedAt(LocalDateTime.now())
                .build();
    }
}
