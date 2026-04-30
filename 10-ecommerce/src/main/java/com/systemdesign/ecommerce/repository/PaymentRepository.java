package com.systemdesign.ecommerce.repository;

import com.systemdesign.ecommerce.model.Payment;

import java.util.Optional;

/**
 * PaymentRepository — Data access abstraction for payment records.
 *
 * Interview notes:
 * - findByOrderId supports the idempotency check in PaymentService:
 *   if a payment for this orderId already exists, don't charge again.
 */
public interface PaymentRepository {

    void save(Payment payment);

    Optional<Payment> findById(String paymentId);

    Optional<Payment> findByOrderId(String orderId);
}
