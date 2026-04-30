package com.systemdesign.payment.repository;

import com.systemdesign.payment.model.Payment;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * InMemoryPaymentRepository — ConcurrentHashMap-backed payment storage.
 *
 * WHY ConcurrentHashMap?
 *   Multiple threads may read/write payments concurrently.  ConcurrentHashMap
 *   provides thread-safe reads without locking the entire map, and
 *   segment-level locking for writes — good enough for this demo.
 *
 *   In production you'd use PostgreSQL with row-level locking.
 */
public class InMemoryPaymentRepository implements PaymentRepository {

    private final Map<String, Payment> store = new ConcurrentHashMap<>();

    @Override
    public void save(Payment payment) {
        store.put(payment.getPaymentId(), payment);
    }

    @Override
    public Optional<Payment> findById(String paymentId) {
        return Optional.ofNullable(store.get(paymentId));
    }

    @Override
    public List<Payment> findByMerchantId(String merchantId) {
        List<Payment> result = new ArrayList<>();
        for (Payment p : store.values()) {
            if (p.getMerchantId().equals(merchantId)) {
                result.add(p);
            }
        }
        return result;
    }

    @Override
    public List<Payment> findAll() {
        return new ArrayList<>(store.values());
    }
}
