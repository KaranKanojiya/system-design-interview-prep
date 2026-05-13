package com.systemdesign.messagequeue.model;

/**
 * Acknowledgement mode for producer writes — controls durability vs. latency tradeoff.
 * Maps directly to Kafka's "acks" configuration.
 */
public enum AckMode {

    /** Fire-and-forget — no ack from broker (acks=0). Fastest, but messages may be lost. */
    NONE(0, "No acknowledgement — fire and forget (acks=0)"),

    /** Leader ack only (acks=1). Message is durable on leader but may be lost if leader fails before replication. */
    LEADER(1, "Leader acknowledgement only (acks=1)"),

    /** All ISR ack (acks=all). Message is durable on all in-sync replicas. Safest, highest latency. */
    ALL(-1, "All in-sync replicas acknowledge (acks=all)");

    private final int value;
    private final String description;

    AckMode(int value, String description) {
        this.value = value;
        this.description = description;
    }

    public int getValue() { return value; }
    public String getDescription() { return description; }

    /** Resolves an AckMode from its integer value (0, 1, or -1). */
    public static AckMode fromValue(int value) {
        for (AckMode mode : values()) {
            if (mode.value == value) return mode;
        }
        throw new IllegalArgumentException("Unknown ack value: " + value);
    }
}
