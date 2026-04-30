package com.systemdesign.ridesharing.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Payment — Tracks payment for a completed ride.
 *
 * In production Uber:
 *   Payment processing is async. After ride completion, a payment intent is
 *   created (PENDING), then submitted to the payment processor (Stripe, Braintree).
 *   If it fails, the system retries with exponential backoff. If it ultimately
 *   fails, the rider is notified and the payment is added to their balance.
 *
 * PaymentStatus lifecycle:
 *   PENDING -> COMPLETED  (happy path)
 *   PENDING -> FAILED     (card declined, insufficient funds)
 *   COMPLETED -> REFUNDED (ride dispute, cancellation refund)
 */
public class Payment {

    /**
     * PaymentStatus — tracks the lifecycle of a payment.
     */
    public enum PaymentStatus {
        PENDING,
        COMPLETED,
        FAILED,
        REFUNDED
    }

    private final String paymentId;
    private final String rideId;
    private final double amount;
    private final PaymentMethod method;
    private PaymentStatus status;
    private final Instant createdAt;

    public Payment(String rideId, double amount, PaymentMethod method) {
        this.paymentId = "PAY-" + UUID.randomUUID().toString().substring(0, 8);
        this.rideId = rideId;
        this.amount = amount;
        this.method = method;
        this.status = PaymentStatus.PENDING;
        this.createdAt = Instant.now();
    }

    public void markCompleted() {
        this.status = PaymentStatus.COMPLETED;
    }

    public void markFailed() {
        this.status = PaymentStatus.FAILED;
    }

    public void markRefunded() {
        this.status = PaymentStatus.REFUNDED;
    }

    // --- Getters ---

    public String getPaymentId() {
        return paymentId;
    }

    public String getRideId() {
        return rideId;
    }

    public double getAmount() {
        return amount;
    }

    public PaymentMethod getMethod() {
        return method;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public String toString() {
        return String.format("Payment{id='%s', rideId='%s', amount=$%.2f, method=%s, status=%s}",
                paymentId, rideId, amount, method, status);
    }
}
