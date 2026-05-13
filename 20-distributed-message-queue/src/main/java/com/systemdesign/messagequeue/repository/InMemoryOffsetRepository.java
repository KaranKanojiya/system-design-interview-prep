package com.systemdesign.messagequeue.repository;

import com.systemdesign.messagequeue.model.Offset;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory implementation of OffsetRepository.
 * Uses ConcurrentHashMap for thread-safe access without external synchronization.
 *
 * Key format: "groupId-topic-partition" — uniquely identifies a consumer group's
 * position within a specific partition.
 */
public class InMemoryOffsetRepository implements OffsetRepository {

    // --- storage: "groupId-topic-partition" -> Offset ---
    private final Map<String, Offset> offsets = new ConcurrentHashMap<>();

    @Override
    public void save(Offset offset) {
        String key = buildKey(offset.getGroupId(), offset.getTopicName(), offset.getPartitionId());
        offsets.put(key, offset);
    }

    @Override
    public Optional<Offset> findByGroupTopicPartition(String groupId, String topic, int partition) {
        String key = buildKey(groupId, topic, partition);
        return Optional.ofNullable(offsets.get(key));
    }

    @Override
    public List<Offset> findByGroup(String groupId) {
        // filter all offsets belonging to the given group
        return offsets.values().stream()
                .filter(offset -> offset.getGroupId().equals(groupId))
                .toList();
    }

    @Override
    public List<Offset> findAll() {
        return new ArrayList<>(offsets.values());
    }

    // ===================== Internal =====================

    /** Builds the composite key: "groupId-topic-partition". */
    private String buildKey(String groupId, String topic, int partition) {
        return groupId + "-" + topic + "-" + partition;
    }
}
