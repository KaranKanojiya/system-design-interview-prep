package com.systemdesign.messagequeue.strategy.storage;

import com.systemdesign.messagequeue.model.Message;

import java.time.Instant;
import java.util.List;

/**
 * Time-based retention — retains messages whose timestamp falls within
 * the configured retention window. Expired messages are discarded during compaction.
 * Analogous to Kafka's log.retention.ms setting.
 */
// wiring: compare message timestamp against (now - retentionMs); compact filters expired messages
public class TimeBasedRetentionStrategy implements StorageStrategy {

    @Override
    public boolean shouldRetain(Message message, long retentionMs) {
        long messageAgeMs = Instant.now().toEpochMilli() - message.getTimestamp().toEpochMilli();
        return messageAgeMs <= retentionMs;
    }

    @Override
    public List<Message> compact(List<Message> messages) {
        // Use a generous default retention of 7 days for compaction
        long defaultRetentionMs = 7L * 24 * 60 * 60 * 1000;
        return compact(messages, defaultRetentionMs);
    }

    /**
     * Compacts with a specific retention window.
     *
     * @param messages    the messages to evaluate
     * @param retentionMs retention window in milliseconds
     * @return list of messages that are still within the retention window
     */
    public List<Message> compact(List<Message> messages, long retentionMs) {
        int beforeCount = messages.size();

        List<Message> retained = messages.stream()
                .filter(m -> shouldRetain(m, retentionMs))
                .toList();

        int expired = beforeCount - retained.size();
        System.out.println("[RETENTION] Time-based compaction — before: " + beforeCount
                + ", retained: " + retained.size() + ", expired: " + expired);

        return retained;
    }

    @Override
    public String getStrategyName() {
        return "TimeBasedRetention";
    }
}
