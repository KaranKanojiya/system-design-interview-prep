package com.systemdesign.messagequeue.engine;

import com.systemdesign.messagequeue.model.Message;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Append-only log for a single partition — THE core data structure of the message queue.
 *
 * Every partition is backed by one CommitLog. Producers append messages at the tail;
 * consumers read from any offset forward. Offsets are monotonically increasing longs
 * assigned at write time — this guarantees strict ordering within a partition.
 *
 * Flow (write path):
 *  1. Producer sends message to broker
 *  2. Broker routes to the correct partition
 *  3. CommitLog.append() assigns the next offset and appends
 *
 * Flow (read path):
 *  1. Consumer calls read(fromOffset, maxMessages)
 *  2. CommitLog returns a batch of messages starting at fromOffset
 */
public class CommitLog {

    // --- identity ---
    private final String topicName;       // owning topic
    private final int partitionId;        // partition index within the topic

    // --- storage ---
    private final List<Message> log;      // ordered list of messages (append-only)

    // --- offset tracking ---
    private final AtomicLong currentOffset;  // next offset to assign (starts at 0)

    public CommitLog(String topicName, int partitionId) {
        this.topicName = topicName;
        this.partitionId = partitionId;
        this.log = new ArrayList<>();
        this.currentOffset = new AtomicLong(0);
    }

    // ===================== Write Path =====================

    /**
     * Appends a message to the log and assigns it the next monotonic offset.
     * This is the single write entry point — all messages flow through here.
     *
     * @param message the message to append
     * @return the assigned offset
     */
    public synchronized long append(Message message) {
        // 1. assign the next sequential offset
        long assignedOffset = currentOffset.getAndIncrement();
        message.setOffset(assignedOffset);

        // 2. append to the log
        log.add(message);

        return assignedOffset;
    }

    // ===================== Read Path =====================

    /**
     * Reads up to maxMessages starting from the given offset.
     * Bounds-checked: returns an empty list if fromOffset is beyond the log.
     *
     * @param fromOffset   the offset to start reading from
     * @param maxMessages  maximum number of messages to return
     * @return list of messages (may be smaller than maxMessages)
     */
    public synchronized List<Message> read(long fromOffset, int maxMessages) {
        // bounds check — nothing to read if offset is past the end
        if (fromOffset < 0 || fromOffset >= log.size() || maxMessages <= 0) {
            return Collections.emptyList();
        }

        // calculate the slice end
        int start = (int) fromOffset;
        int end = Math.min(start + maxMessages, log.size());

        return new ArrayList<>(log.subList(start, end));
    }

    // ===================== Offset Queries =====================

    /** Returns the current high watermark — the next offset that will be assigned. */
    public long getLatestOffset() {
        return currentOffset.get();
    }

    /** Returns the earliest available offset (0 or first message's offset after retention cleanup). */
    public synchronized long getEarliestOffset() {
        if (log.isEmpty()) {
            return 0;
        }
        return log.getFirst().getOffset();
    }

    // ===================== Maintenance =====================

    /** Returns the total number of messages currently in the log. */
    public synchronized int size() {
        return log.size();
    }

    /**
     * Removes all messages with offset strictly less than the given offset.
     * Used for retention cleanup — old messages are truncated from the head.
     *
     * @param offset messages with offset < this value will be removed
     */
    public synchronized void truncateBefore(long offset) {
        log.removeIf(msg -> msg.getOffset() < offset);
    }

    /** Returns a read-only copy of all messages in the log. */
    public synchronized List<Message> getMessages() {
        return Collections.unmodifiableList(new ArrayList<>(log));
    }

    // --- getters ---

    public String getTopicName() { return topicName; }
    public int getPartitionId() { return partitionId; }

    @Override
    public String toString() {
        return "CommitLog{topic='" + topicName + "', partition=" + partitionId
                + ", size=" + log.size() + ", latestOffset=" + currentOffset.get() + "}";
    }
}
