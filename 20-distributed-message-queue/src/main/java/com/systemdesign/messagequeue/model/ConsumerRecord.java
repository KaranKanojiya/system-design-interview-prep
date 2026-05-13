package com.systemdesign.messagequeue.model;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;

/**
 * Record received by a consumer — immutable snapshot of a message at a specific offset.
 * Includes all routing metadata so the consumer can commit offsets and track provenance.
 */
public class ConsumerRecord {

    // --- routing ---
    private final String topic;                // source topic
    private final int partition;               // source partition
    private final long offset;                 // log offset within the partition

    // --- payload ---
    private final String key;                  // partition key (may be null)
    private final String value;                // message payload
    private final Map<String, String> headers; // user-defined headers

    // --- metadata ---
    private final Instant timestamp;           // original message timestamp

    public ConsumerRecord(String topic, int partition, long offset,
                          String key, String value,
                          Map<String, String> headers, Instant timestamp) {
        this.topic = topic;
        this.partition = partition;
        this.offset = offset;
        this.key = key;
        this.value = value;
        this.headers = Collections.unmodifiableMap(headers); // immutable
        this.timestamp = timestamp;
    }

    // --- getters (no setters — immutable) ---

    public String getTopic() { return topic; }
    public int getPartition() { return partition; }
    public long getOffset() { return offset; }
    public String getKey() { return key; }
    public String getValue() { return value; }
    public Map<String, String> getHeaders() { return headers; }
    public Instant getTimestamp() { return timestamp; }

    @Override
    public String toString() {
        return "ConsumerRecord{topic='" + topic + "', partition=" + partition
                + ", offset=" + offset + ", key='" + key + "'}";
    }
}
