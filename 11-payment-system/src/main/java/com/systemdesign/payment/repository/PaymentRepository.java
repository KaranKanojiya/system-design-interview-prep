package com.systemdesign.payment.repository;

import com.systemdesign.payment.model.Payment;

import java.util.List;
import java.util.Optional;

/**
 * PaymentRepository — Data access interface for Payment entities.
 *
 * WHY an interface?
 *   Decouples service logic from storage.  In production you'd have
 *   a PostgresPaymentRepository, here we use InMemoryPaymentRepository.
 *   Same pattern as Spring Data's CrudRepository, but without the framework.
 */
public interface PaymentRepository {
    void save(Payment payment);
    Optional<Payment> findById(String paymentId);
    List<Payment> findByMerchantId(String merchantId);
    List<Payment> findAll();
}
