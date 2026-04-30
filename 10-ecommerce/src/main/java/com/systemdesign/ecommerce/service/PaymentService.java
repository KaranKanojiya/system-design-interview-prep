package com.systemdesign.ecommerce.service;

import com.systemdesign.ecommerce.exception.PaymentFailedException;
import com.systemdesign.ecommerce.model.Order;
import com.systemdesign.ecommerce.model.Payment;
import com.systemdesign.ecommerce.model.PaymentStatus;
import com.systemdesign.ecommerce.repository.PaymentRepository;
import com.systemdesign.ecommerce.strategy.payment.PaymentStrategy;

import java.util.Optional;

/**
 * PaymentService — Processes and refunds payments.
 *
 * Interview notes:
 * - IDEMPOTENCY CHECK: before processing, we check if a payment for the
 *   same orderId already exists. If so, we return the existing payment
 *   rather than charging the customer twice. This is critical because
 *   network retries (timeout → retry) could otherwise result in double
 *   charges.
 * - In production, the idempotency key would be a client-generated UUID
 *   passed in the request header, stored in Redis with a TTL. Here we
 *   use orderId as the key for simplicity.
 *
 * Call chain: SagaOrchestrator → PaymentService → PaymentStrategy → PaymentRepository
 */
public class PaymentService {

    private final PaymentRepository paymentRepository;

    public PaymentService(PaymentRepository paymentRepository) {
        this.paymentRepository = paymentRepository;
    }

    /**
     * Processes payment for an order using the given strategy.
     *
     * Idempotency: if a payment for this orderId already exists and is
     * COMPLETED, return it. Don't charge again.
     */
    public Payment processPayment(Order order, PaymentStrategy paymentStrategy) {
        // ── Idempotency check ──
        // Prevents double-charging if the saga retries after a timeout.
        Optional<Payment> existing = paymentRepository.findByOrderId(order.getOrderId());
        if (existing.isPresent() && existing.get().getStatus() == PaymentStatus.COMPLETED) {
            System.out.println("    [Payment] Idempotency hit: payment already exists for order "
                    + order.getOrderId());
            return existing.get();
        }

        // Delegate to the strategy (credit card, wallet, COD, etc.)
        Payment payment = paymentStrategy.processPayment(
                order.getTotalAmount(), order.getOrderId());

        paymentRepository.save(payment);
        return payment;
    }

    /**
     * Refunds a payment by paymentId.
     * Marks the payment status as REFUNDED.
     */
    public void refundPayment(String paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentFailedException(
                        "Cannot refund: payment not found: " + paymentId));

        payment.setStatus(PaymentStatus.REFUNDED);
        paymentRepository.save(payment);
        System.out.println("    [Payment] Refunded payment " + paymentId);
    }

    /**
     * Looks up a payment by orderId. Used for status queries.
     */
    public Optional<Payment> getPaymentByOrderId(String orderId) {
        return paymentRepository.findByOrderId(orderId);
    }
}
