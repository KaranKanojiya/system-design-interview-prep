package com.systemdesign.ecommerce.strategy.payment;

import com.systemdesign.ecommerce.model.Payment;
import com.systemdesign.ecommerce.model.PaymentMethod;
import com.systemdesign.ecommerce.model.PaymentStatus;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * CODPaymentStrategy — Cash On Delivery.
 *
 * Interview notes:
 * - Always "succeeds" at order time because no charge is made upfront.
 * - PaymentStatus is PENDING (not COMPLETED) because actual payment
 *   collection happens when the delivery driver receives cash.
 * - Risk: customer refuses delivery → product returned, logistics cost wasted.
 *   Real systems mitigate with COD limits, verified addresses, or partial
 *   online prepayment.
 * - Common in India, Middle East, and Southeast Asia where card penetration
 *   is lower.
 */
public class CODPaymentStrategy implements PaymentStrategy {

    @Override
    public Payment processPayment(double amount, String orderId) {
        String paymentId = "PAY-COD-" + UUID.randomUUID().toString().substring(0, 8);

        // COD always succeeds at order time — actual collection at delivery
        return new Payment.Builder()
                .paymentId(paymentId)
                .orderId(orderId)
                .amount(amount)
                .method(PaymentMethod.COD)
                .status(PaymentStatus.PENDING)  // NOT completed — pending until delivery
                .transactionId("COD-" + UUID.randomUUID().toString().substring(0, 8))
                .processedAt(LocalDateTime.now())
                .build();
    }
}
