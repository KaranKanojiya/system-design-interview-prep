package com.systemdesign.payment.model;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Payment — The central domain entity of the payment system.
 *
 * KEY DESIGN DECISIONS:
 *
 * 1. Builder Pattern — Payments have many fields; a builder avoids telescoping
 *    constructors and lets callers set only the fields they care about.
 *
 * 2. State Machine — The status field is guarded by transition methods
 *    (authorize, capture, settle, fail, refund, cancel).  Each method checks
 *    the CURRENT state before transitioning.  This prevents double-charges:
 *    you can't capture an already-captured payment.
 *
 *    Valid transitions:
 *      INITIATED  → PROCESSING  (processPayment begins)
 *      PROCESSING → AUTHORIZED  (processor approved, funds held)
 *      PROCESSING → CAPTURED    (instant-settlement methods like UPI)
 *      PROCESSING → FAILED      (processor declined)
 *      AUTHORIZED → CAPTURED    (merchant captures held funds)
 *      AUTHORIZED → CANCELLED   (merchant releases held funds)
 *      CAPTURED   → SETTLED     (settlement cycle completes)
 *      CAPTURED   → REFUNDED    (refund before settlement)
 *      SETTLED    → REFUNDED    (refund after settlement)
 *      Any non-terminal → FAILED (unexpected error)
 *
 * 3. Idempotency Key — Clients send this to prevent duplicate charges on retry.
 *    If we see the same key twice, we return the cached result.
 *
 * 4. Metadata Map — Extensible key-value pairs for merchant-specific data
 *    (order ID, customer email, etc.) without changing the schema.
 */
public class Payment {

    private final String paymentId;
    private final String merchantId;
    private final String customerId;
    private final double amount;
    private final Currency currency;
    private final PaymentMethod method;
    private PaymentStatus status;
    private final String idempotencyKey;
    private final String description;
    private String processorTransactionId;
    private final LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private final Map<String, String> metadata;

    // ──────────────────────────────────────────────
    //  Private constructor — use Builder
    // ──────────────────────────────────────────────
    private Payment(Builder builder) {
        this.paymentId = builder.paymentId;
        this.merchantId = builder.merchantId;
        this.customerId = builder.customerId;
        this.amount = builder.amount;
        this.currency = builder.currency;
        this.method = builder.method;
        this.status = builder.status;
        this.idempotencyKey = builder.idempotencyKey;
        this.description = builder.description;
        this.processorTransactionId = builder.processorTransactionId;
        this.createdAt = builder.createdAt;
        this.updatedAt = builder.updatedAt;
        this.metadata = builder.metadata;
    }

    // ──────────────────────────────────────────────
    //  STATE MACHINE — Guarded Transitions
    //
    //  Each method validates the current state before
    //  allowing the transition.  If the transition is
    //  invalid, an IllegalStateException is thrown.
    //  This is the CORE defense against double-charges.
    // ──────────────────────────────────────────────

    /**
     * INITIATED → PROCESSING
     * Called when we're about to send the payment to the processor.
     */
    public void startProcessing() {
        if (status != PaymentStatus.INITIATED) {
            throw new IllegalStateException(
                "Cannot start processing: payment is " + status + ", expected INITIATED");
        }
        this.status = PaymentStatus.PROCESSING;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * PROCESSING → AUTHORIZED
     * Called when the processor approves and holds funds.
     * Only applies to two-phase methods (credit/debit card).
     */
    public void authorize() {
        if (status != PaymentStatus.PROCESSING) {
            throw new IllegalStateException(
                "Cannot authorize: payment is " + status + ", expected PROCESSING");
        }
        this.status = PaymentStatus.AUTHORIZED;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * AUTHORIZED → CAPTURED  or  PROCESSING → CAPTURED
     * Called when funds are captured (two-phase) or instant settlement (UPI/wallet).
     *
     * WHY allow PROCESSING → CAPTURED?
     *   UPI and wallet payments don't have a separate auth step.
     *   The processor authorizes and captures in one shot.
     */
    public void capture() {
        if (status != PaymentStatus.AUTHORIZED && status != PaymentStatus.PROCESSING) {
            throw new IllegalStateException(
                "Cannot capture: payment is " + status + ", expected AUTHORIZED or PROCESSING");
        }
        this.status = PaymentStatus.CAPTURED;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * CAPTURED → SETTLED
     * Called when the settlement cycle completes and funds are in the merchant's bank.
     */
    public void settle() {
        if (status != PaymentStatus.CAPTURED) {
            throw new IllegalStateException(
                "Cannot settle: payment is " + status + ", expected CAPTURED");
        }
        this.status = PaymentStatus.SETTLED;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * Any non-terminal state → FAILED
     * Called when the processor declines or an unexpected error occurs.
     */
    public void fail() {
        if (status == PaymentStatus.SETTLED || status == PaymentStatus.REFUNDED
                || status == PaymentStatus.CANCELLED) {
            throw new IllegalStateException(
                "Cannot fail: payment is already in terminal state " + status);
        }
        this.status = PaymentStatus.FAILED;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * CAPTURED → REFUNDED  or  SETTLED → REFUNDED
     * Called when a refund is successfully processed.
     *
     * WHY only from CAPTURED or SETTLED?
     *   You can only refund money that was actually taken.
     *   AUTHORIZED payments should be cancelled instead (release the hold).
     */
    public void refund() {
        if (status != PaymentStatus.CAPTURED && status != PaymentStatus.SETTLED) {
            throw new IllegalStateException(
                "Cannot refund: payment is " + status + ", expected CAPTURED or SETTLED");
        }
        this.status = PaymentStatus.REFUNDED;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * AUTHORIZED → CANCELLED
     * Called when the merchant decides not to capture an authorized payment.
     * The hold on the customer's funds is released.
     */
    public void cancel() {
        if (status != PaymentStatus.AUTHORIZED && status != PaymentStatus.INITIATED
                && status != PaymentStatus.PROCESSING) {
            throw new IllegalStateException(
                "Cannot cancel: payment is " + status
                + ", expected INITIATED, PROCESSING, or AUTHORIZED");
        }
        this.status = PaymentStatus.CANCELLED;
        this.updatedAt = LocalDateTime.now();
    }

    // ──────────────────────────────────────────────
    //  Getters (no setters — mutations go through
    //  state machine methods above)
    // ──────────────────────────────────────────────

    public String getPaymentId() { return paymentId; }
    public String getMerchantId() { return merchantId; }
    public String getCustomerId() { return customerId; }
    public double getAmount() { return amount; }
    public Currency getCurrency() { return currency; }
    public PaymentMethod getMethod() { return method; }
    public PaymentStatus getStatus() { return status; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getDescription() { return description; }
    public String getProcessorTransactionId() { return processorTransactionId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public Map<String, String> getMetadata() { return metadata; }

    public void setProcessorTransactionId(String processorTransactionId) {
        this.processorTransactionId = processorTransactionId;
        this.updatedAt = LocalDateTime.now();
    }

    @Override
    public String toString() {
        return "Payment{" +
                "id='" + paymentId + '\'' +
                ", merchant='" + merchantId + '\'' +
                ", customer='" + customerId + '\'' +
                ", amount=" + currency.format(amount) +
                ", method=" + method +
                ", status=" + status +
                ", idempotencyKey='" + idempotencyKey + '\'' +
                '}';
    }

    // ──────────────────────────────────────────────
    //  BUILDER
    // ──────────────────────────────────────────────
    public static class Builder {
        private String paymentId = "PAY-" + UUID.randomUUID().toString().substring(0, 8);
        private String merchantId;
        private String customerId;
        private double amount;
        private Currency currency = Currency.USD;
        private PaymentMethod method = PaymentMethod.CREDIT_CARD;
        private PaymentStatus status = PaymentStatus.INITIATED;
        private String idempotencyKey;
        private String description = "";
        private String processorTransactionId;
        private LocalDateTime createdAt = LocalDateTime.now();
        private LocalDateTime updatedAt = LocalDateTime.now();
        private Map<String, String> metadata = new HashMap<>();

        public Builder paymentId(String paymentId) { this.paymentId = paymentId; return this; }
        public Builder merchantId(String merchantId) { this.merchantId = merchantId; return this; }
        public Builder customerId(String customerId) { this.customerId = customerId; return this; }
        public Builder amount(double amount) { this.amount = amount; return this; }
        public Builder currency(Currency currency) { this.currency = currency; return this; }
        public Builder method(PaymentMethod method) { this.method = method; return this; }
        public Builder status(PaymentStatus status) { this.status = status; return this; }
        public Builder idempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; return this; }
        public Builder description(String description) { this.description = description; return this; }
        public Builder processorTransactionId(String id) { this.processorTransactionId = id; return this; }
        public Builder createdAt(LocalDateTime createdAt) { this.createdAt = createdAt; return this; }
        public Builder updatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; return this; }
        public Builder metadata(Map<String, String> metadata) { this.metadata = new HashMap<>(metadata); return this; }
        public Builder addMetadata(String key, String value) { this.metadata.put(key, value); return this; }

        public Payment build() {
            if (merchantId == null || merchantId.isBlank()) {
                throw new IllegalArgumentException("merchantId is required");
            }
            if (amount <= 0) {
                throw new IllegalArgumentException("amount must be positive, got: " + amount);
            }
            return new Payment(this);
        }
    }
}
