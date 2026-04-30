package com.systemdesign.payment.model;

/**
 * RefundStatus — Lifecycle of a refund request.
 *
 * PENDING    — refund created, not yet sent to processor
 * PROCESSING — sent to processor, awaiting confirmation
 * COMPLETED  — processor confirmed, money returned to customer
 * FAILED     — processor rejected the refund (e.g. original txn too old)
 */
public enum RefundStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED
}
