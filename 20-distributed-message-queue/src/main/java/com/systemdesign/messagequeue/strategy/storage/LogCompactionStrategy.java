package com.systemdesign.messagequeue.strategy.storage;

import com.systemdesign.messagequeue.model.Message;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Kafka-style log compaction — retains only the latest message per key.
 * Messages with a null key are always retained (they cannot be compacted).
 * The "latest" message is determined by the highest offset value.
 */
// wiring: group by key, keep highest-offset entry per key; null-key messages always survive
public class LogCompactionStrategy implements StorageStrategy {

    @Override
    public boolean shouldRetain(Message message, long retentionMs) {
        // Log compaction is not time-based — always retain individual messages.
        // Actual compaction happens in compact() where duplicates per key are removed.
        return true;
    }

    @Override
    public List<Message> compact(List<Message> messages) {
        int beforeCount = messages.size();

        // 1) Collect all null-key messages (always retained)
        List<Message> nullKeyMessages = new ArrayList<>();
        // 2) Keep only the latest (highest offset) message per key
        Map<String, Message> latestByKey = new LinkedHashMap<>();

        for (Message message : messages) {
            if (message.getKey() == null) {
                nullKeyMessages.add(message);
            } else {
                Message existing = latestByKey.get(message.getKey());
                if (existing == null || message.getOffset() > existing.getOffset()) {
                    latestByKey.put(message.getKey(), message);
                }
            }
        }

        List<Message> compacted = new ArrayList<>(nullKeyMessages.size() + latestByKey.size());
        compacted.addAll(nullKeyMessages);
        compacted.addAll(latestByKey.values());

        System.out.println("[COMPACTION] Log compaction — before: " + beforeCount
                + ", after: " + compacted.size()
                + " (null-key: " + nullKeyMessages.size()
                + ", unique keys: " + latestByKey.size() + ")");

        return compacted;
    }

    @Override
    public String getStrategyName() {
        return "LogCompaction";
    }
}
