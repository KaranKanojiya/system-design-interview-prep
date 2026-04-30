package com.systemdesign.ecommerce.model;

import java.time.LocalDateTime;

/**
 * Payment — Record of a payment attempt for an order.
 *
 * Interview notes:
 * - Builder pattern because several fields are set asynchronously
 *   (transactionId comes from the payment gateway, processedAt is set
 *   when the gateway responds).
 * - One order may have multiple Payment records if the first attempt
 *   failed and the customer retried with a different method.
 */
public class Payment {

    private final String paymentId;
    private final String orderId;
    private final double amount;
    private final PaymentMethod method;
    private PaymentStatus status;
    private String transactionId;
    private LocalDateTime processedAt;

    private Payment(Builder builder) {
        this.paymentId = builder.paymentId;
        this.orderId = builder.orderId;
        this.amount = builder.amount;
        this.method = builder.method;
        this.status = builder.status;
        this.transactionId = builder.transactionId;
        this.processedAt = builder.processedAt;
    }

    // ── Mutations ────────────────────────────────────────────────────────

    public void setStatus(PaymentStatus status) {
        this.status = status;
    }

    public void setTransactionId(String transactionId) {
        this.transactionId = transactionId;
    }

    public void setProcessedAt(LocalDateTime processedAt) {
        this.processedAt = processedAt;
    }

    // ── Getters ──────────────────────────────────────────────────────────

    public String getPaymentId()         { return paymentId; }
    public String getOrderId()           { return orderId; }
    public double getAmount()            { return amount; }
    public PaymentMethod getMethod()     { return method; }
    public PaymentStatus getStatus()     { return status; }
    public String getTransactionId()     { return transactionId; }
    public LocalDateTime getProcessedAt(){ return processedAt; }

    @Override
    public String toString() {
        return String.format("Payment{id='%s', orderId='%s', amount=$%.2f, method=%s, status=%s, txn='%s'}",
                paymentId, orderId, amount, method, status, transactionId);
    }

    // ── Builder ──────────────────────────────────────────────────────────

    public static class Builder {
        private String paymentId;
        private String orderId;
        private double amount;
        private PaymentMethod method;
        private PaymentStatus status = PaymentStatus.PENDING;
        private String transactionId;
        private LocalDateTime processedAt;

        public Builder paymentId(String id)        { this.paymentId = id; return this; }
        public Builder orderId(String id)          { this.orderId = id; return this; }
        public Builder amount(double amt)          { this.amount = amt; return this; }
        public Builder method(PaymentMethod m)     { this.method = m; return this; }
        public Builder status(PaymentStatus s)     { this.status = s; return this; }
        public Builder transactionId(String txn)   { this.transactionId = txn; return this; }
        public Builder processedAt(LocalDateTime t){ this.processedAt = t; return this; }

        public Payment build() {
            return new Payment(this);
        }
    }
}
