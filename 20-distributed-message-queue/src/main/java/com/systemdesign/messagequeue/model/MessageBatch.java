package com.systemdesign.messagequeue.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Batch of messages for efficient network and disk I/O.
 * The broker and producer buffer messages into batches to amortize overhead.
 */
public class MessageBatch {

    // --- identity ---
    private final String batchId;          // unique batch ID (UUID)

    // --- routing ---
    private final String topicName;        // destination topic
    private final int partition;           // destination partition

    // --- payload ---
    private final List<Message> messages;  // ordered list of messages in this batch

    // --- metadata ---
    private final Instant createdAt;       // batch creation time

    public MessageBatch(String topicName, int partition) {
        this.batchId = UUID.randomUUID().toString();
        this.topicName = topicName;
        this.partition = partition;
        this.messages = new ArrayList<>();
        this.createdAt = Instant.now();
    }

    // --- getters ---

    public String getBatchId() { return batchId; }
    public String getTopicName() { return topicName; }
    public int getPartition() { return partition; }
    public List<Message> getMessages() { return messages; }
    public Instant getCreatedAt() { return createdAt; }

    // --- batch operations ---

    /** Adds a message to the batch. */
    public void add(Message message) {
        messages.add(message);
    }

    /** Returns the number of messages in the batch. */
    public int size() {
        return messages.size();
    }

    /** Returns the offset of the first message, or -1 if the batch is empty. */
    public long getFirstOffset() {
        return messages.isEmpty() ? -1 : messages.getFirst().getOffset();
    }

    /** Returns the offset of the last message, or -1 if the batch is empty. */
    public long getLastOffset() {
        return messages.isEmpty() ? -1 : messages.getLast().getOffset();
    }

    /** Returns the total size (in bytes) of all message payloads in this batch. */
    public int getSizeBytes() {
        return messages.stream()
                .mapToInt(Message::getSize)
                .sum();
    }

    @Override
    public String toString() {
        return "MessageBatch{id='" + batchId + "', topic='" + topicName
                + "', partition=" + partition + ", messages=" + messages.size()
                + ", bytes=" + getSizeBytes() + "}";
    }
}
