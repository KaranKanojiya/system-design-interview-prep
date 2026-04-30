package com.systemdesign.ecommerce.strategy.payment;

import com.systemdesign.ecommerce.model.Payment;

/**
 * PaymentStrategy — Strategy pattern for payment processing.
 *
 * Interview notes:
 * - Each payment method (credit card, wallet, COD) has different
 *   success rates, latencies, and behaviors.
 * - The Strategy pattern lets the checkout flow call processPayment()
 *   without knowing which gateway is behind it.
 * - New methods (Apple Pay, crypto, BNPL) can be added by implementing
 *   this interface — no changes to existing code.
 */
public interface PaymentStrategy {

    /**
     * Processes a payment for the given amount and orderId.
     *
     * @return a Payment object. Check payment.getStatus() to determine
     *         if it COMPLETED or FAILED.
     */
    Payment processPayment(double amount, String orderId);
}
