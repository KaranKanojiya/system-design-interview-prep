package com.systemdesign.messagequeue.model;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Core message entity — the fundamental unit of data in the message queue.
 * Analogous to a Kafka ProducerRecord after it has been assigned an offset by the broker.
 */
public class Message {

    // --- identity ---
    private final String id;               // unique message ID (UUID)
    private final String key;              // partition key (nullable — null means round-robin)
    private final String value;            // payload
    private final Map<String, String> headers;  // user-defined metadata

    // --- routing ---
    private final String topic;            // destination topic
    private int partition;                 // assigned partition index
    private long offset;                   // log offset — set to -1 until broker assigns it

    // --- metadata ---
    private final Instant timestamp;       // creation time
    private final String producerId;       // ID of the producing client

    // ---- private constructor — use Builder ----
    private Message(Builder builder) {
        this.id = builder.id;
        this.key = builder.key;
        this.value = builder.value;
        this.headers = builder.headers;
        this.topic = builder.topic;
        this.partition = builder.partition;
        this.offset = builder.offset;
        this.timestamp = builder.timestamp;
        this.producerId = builder.producerId;
    }

    // --- getters ---

    public String getId() { return id; }
    public String getKey() { return key; }
    public String getValue() { return value; }
    public Map<String, String> getHeaders() { return headers; }
    public String getTopic() { return topic; }
    public int getPartition() { return partition; }
    public long getOffset() { return offset; }
    public Instant getTimestamp() { return timestamp; }
    public String getProducerId() { return producerId; }

    // --- mutators (broker-side) ---

    /** Called by the broker when appending to the partition log. */
    public void setOffset(long offset) { this.offset = offset; }

    /** Called by the broker/partitioner when assigning a partition. */
    public void setPartition(int partition) { this.partition = partition; }

    /** Returns the size of the payload in bytes. */
    public int getSize() {
        return value.length();
    }

    @Override
    public String toString() {
        return "Message{id='" + id + "', topic='" + topic + "', partition=" + partition
                + ", offset=" + offset + ", key='" + key + "'}";
    }

    // ===================== Builder =====================

    /**
     * Builder for Message — public constructor requires (topic, value) at minimum.
     */
    public static class Builder {
        // required
        private final String topic;
        private final String value;

        // optional with defaults
        private String id = UUID.randomUUID().toString();
        private String key = null;
        private Map<String, String> headers = new HashMap<>();
        private int partition = -1;
        private long offset = -1;
        private Instant timestamp = Instant.now();
        private String producerId = "unknown";

        public Builder(String topic, String value) {
            this.topic = topic;
            this.value = value;
        }

        public Builder id(String id) { this.id = id; return this; }
        public Builder key(String key) { this.key = key; return this; }
        public Builder headers(Map<String, String> headers) { this.headers = new HashMap<>(headers); return this; }
        public Builder header(String k, String v) { this.headers.put(k, v); return this; }
        public Builder partition(int partition) { this.partition = partition; return this; }
        public Builder offset(long offset) { this.offset = offset; return this; }
        public Builder timestamp(Instant timestamp) { this.timestamp = timestamp; return this; }
        public Builder producerId(String producerId) { this.producerId = producerId; return this; }

        public Message build() {
            return new Message(this);
        }
    }
}
