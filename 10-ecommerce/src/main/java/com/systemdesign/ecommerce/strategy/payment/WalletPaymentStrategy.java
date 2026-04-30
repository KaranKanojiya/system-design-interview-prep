package com.systemdesign.ecommerce.strategy.payment;

import com.systemdesign.ecommerce.model.Payment;
import com.systemdesign.ecommerce.model.PaymentMethod;
import com.systemdesign.ecommerce.model.PaymentStatus;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * WalletPaymentStrategy — Simulates pre-funded wallet payment (e.g., Amazon Pay).
 *
 * Interview notes:
 * - 99% success rate because wallets are pre-funded — the money is already
 *   in the system, so there's no external bank authorization to fail.
 * - Faster than credit card (no network hop to card issuer).
 * - The 1% failure simulates edge cases like account locked, fraud flag, etc.
 */
public class WalletPaymentStrategy implements PaymentStrategy {

    private static final double SUCCESS_RATE = 0.99;

    @Override
    public Payment processPayment(double amount, String orderId) {
        String paymentId = "PAY-WAL-" + UUID.randomUUID().toString().substring(0, 8);

        boolean success = Math.random() < SUCCESS_RATE;

        return new Payment.Builder()
                .paymentId(paymentId)
                .orderId(orderId)
                .amount(amount)
                .method(PaymentMethod.WALLET)
                .status(success ? PaymentStatus.COMPLETED : PaymentStatus.FAILED)
                .transactionId(success ? "WAL-" + UUID.randomUUID().toString().substring(0, 8) : null)
                .processedAt(LocalDateTime.now())
                .build();
    }
}
