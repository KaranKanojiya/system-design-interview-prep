package com.systemdesign.ecommerce.repository;

import com.systemdesign.ecommerce.model.Payment;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * InMemoryPaymentRepository — ConcurrentHashMap-backed payment store.
 * Supports lookup by paymentId and by orderId (for idempotency).
 */
public class InMemoryPaymentRepository implements PaymentRepository {

    private final Map<String, Payment> storeById = new ConcurrentHashMap<>();
    private final Map<String, Payment> storeByOrderId = new ConcurrentHashMap<>();

    @Override
    public void save(Payment payment) {
        storeById.put(payment.getPaymentId(), payment);
        storeByOrderId.put(payment.getOrderId(), payment);
    }

    @Override
    public Optional<Payment> findById(String paymentId) {
        return Optional.ofNullable(storeById.get(paymentId));
    }

    @Override
    public Optional<Payment> findByOrderId(String orderId) {
        return Optional.ofNullable(storeByOrderId.get(orderId));
    }
}
