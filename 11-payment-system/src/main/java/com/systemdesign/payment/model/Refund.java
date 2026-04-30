package com.systemdesign.payment.model;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Refund — A request to return money to the customer.
 *
 * Supports both FULL and PARTIAL refunds:
 *   - Full refund:   amount == original payment amount
 *   - Partial refund: amount < original payment amount (e.g. $30 of $100)
 *
 * WHY a separate entity instead of a flag on Payment?
 *   A single payment can have multiple partial refunds.
 *   Each refund has its own lifecycle (PENDING → PROCESSING → COMPLETED/FAILED),
 *   its own processor transaction, and its own ledger entries.
 *
 * Uses Builder pattern for the same reasons as Payment.
 */
public class Refund {

    private final String refundId;
    private final String paymentId;
    private final double amount;
    private final String reason;
    private RefundStatus status;
    private final LocalDateTime createdAt;
    private LocalDateTime processedAt;

    private Refund(Builder builder) {
        this.refundId = builder.refundId;
        this.paymentId = builder.paymentId;
        this.amount = builder.amount;
        this.reason = builder.reason;
        this.status = builder.status;
        this.createdAt = builder.createdAt;
        this.processedAt = builder.processedAt;
    }

    // ── State transitions ──

    public void startProcessing() {
        if (status != RefundStatus.PENDING) {
            throw new IllegalStateException("Cannot process refund in state: " + status);
        }
        this.status = RefundStatus.PROCESSING;
    }

    public void complete() {
        if (status != RefundStatus.PROCESSING) {
            throw new IllegalStateException("Cannot complete refund in state: " + status);
        }
        this.status = RefundStatus.COMPLETED;
        this.processedAt = LocalDateTime.now();
    }

    public void fail() {
        if (status != RefundStatus.PROCESSING) {
            throw new IllegalStateException("Cannot fail refund in state: " + status);
        }
        this.status = RefundStatus.FAILED;
        this.processedAt = LocalDateTime.now();
    }

    // ── Getters ──
    public String getRefundId() { return refundId; }
    public String getPaymentId() { return paymentId; }
    public double getAmount() { return amount; }
    public String getReason() { return reason; }
    public RefundStatus getStatus() { return status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getProcessedAt() { return processedAt; }

    @Override
    public String toString() {
        return "Refund{" +
                "id='" + refundId + '\'' +
                ", paymentId='" + paymentId + '\'' +
                ", amount=" + amount +
                ", reason='" + reason + '\'' +
                ", status=" + status +
                '}';
    }

    // ── Builder ──
    public static class Builder {
        private String refundId = "REF-" + UUID.randomUUID().toString().substring(0, 8);
        private String paymentId;
        private double amount;
        private String reason = "";
        private RefundStatus status = RefundStatus.PENDING;
        private LocalDateTime createdAt = LocalDateTime.now();
        private LocalDateTime processedAt;

        public Builder refundId(String refundId) { this.refundId = refundId; return this; }
        public Builder paymentId(String paymentId) { this.paymentId = paymentId; return this; }
        public Builder amount(double amount) { this.amount = amount; return this; }
        public Builder reason(String reason) { this.reason = reason; return this; }
        public Builder status(RefundStatus status) { this.status = status; return this; }
        public Builder createdAt(LocalDateTime createdAt) { this.createdAt = createdAt; return this; }
        public Builder processedAt(LocalDateTime processedAt) { this.processedAt = processedAt; return this; }

        public Refund build() {
            if (paymentId == null || paymentId.isBlank()) {
                throw new IllegalArgumentException("paymentId is required for refund");
            }
            if (amount <= 0) {
                throw new IllegalArgumentException("Refund amount must be positive, got: " + amount);
            }
            return new Refund(this);
        }
    }
}
