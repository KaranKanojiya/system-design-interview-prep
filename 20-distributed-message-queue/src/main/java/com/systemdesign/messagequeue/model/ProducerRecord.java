package com.systemdesign.messagequeue.model;

import java.util.HashMap;
import java.util.Map;

/**
 * Record sent by a producer to the message queue.
 * The producer specifies the topic and payload; key and partition are optional.
 * If key is set but partition is null, the partitioner hashes the key.
 * If both are null, round-robin assignment is used.
 */
public class ProducerRecord {

    // --- routing ---
    private final String topic;                // destination topic (required)
    private final Integer partition;           // explicit partition (nullable — null = auto-assign)

    // --- payload ---
    private final String key;                  // partition key (nullable)
    private final String value;                // message payload (required)
    private final Map<String, String> headers; // user-defined headers

    // --- constructors (overloaded for convenience) ---

    /** Minimal — topic + value only (key=null, partition=auto). */
    public ProducerRecord(String topic, String value) {
        this(topic, null, value, null);
    }

    /** With key — partition derived from key hash. */
    public ProducerRecord(String topic, String key, String value) {
        this(topic, key, value, null);
    }

    /** Full — explicit key and partition. */
    public ProducerRecord(String topic, String key, String value, Integer partition) {
        this.topic = topic;
        this.key = key;
        this.value = value;
        this.partition = partition;
        this.headers = new HashMap<>();
    }

    // --- getters ---

    public String getTopic() { return topic; }
    public Integer getPartition() { return partition; }
    public String getKey() { return key; }
    public String getValue() { return value; }
    public Map<String, String> getHeaders() { return headers; }

    // --- header convenience ---

    public ProducerRecord withHeader(String key, String value) {
        this.headers.put(key, value);
        return this;
    }

    @Override
    public String toString() {
        return "ProducerRecord{topic='" + topic + "', key='" + key
                + "', partition=" + partition + "}";
    }
}
