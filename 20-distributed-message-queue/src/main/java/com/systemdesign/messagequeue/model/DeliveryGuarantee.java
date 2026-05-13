package com.systemdesign.messagequeue.model;

/**
 * Delivery guarantee semantics — defines how the system handles message loss and duplication.
 */
public enum DeliveryGuarantee {

    /** Messages may be lost but never duplicated. Consumer commits offset before processing. */
    AT_MOST_ONCE("Messages delivered zero or one time — no duplicates, possible loss"),

    /** Messages are never lost but may be duplicated. Consumer commits offset after processing. */
    AT_LEAST_ONCE("Messages delivered one or more times — no loss, possible duplicates"),

    /** Messages are delivered exactly once. Requires idempotent producer + transactional consumer. */
    EXACTLY_ONCE("Messages delivered exactly one time — no loss, no duplicates");

    private final String description;

    DeliveryGuarantee(String description) {
        this.description = description;
    }

    public String getDescription() { return description; }
}
