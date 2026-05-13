package com.systemdesign.messagequeue.repository;

import com.systemdesign.messagequeue.model.ConsumerGroup;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory implementation of ConsumerGroupRepository.
 * Uses ConcurrentHashMap for thread-safe access without external synchronization.
 */
public class InMemoryConsumerGroupRepository implements ConsumerGroupRepository {

    // --- storage: groupId -> ConsumerGroup ---
    private final Map<String, ConsumerGroup> groups = new ConcurrentHashMap<>();

    @Override
    public void save(ConsumerGroup group) {
        groups.put(group.getGroupId(), group);
    }

    @Override
    public Optional<ConsumerGroup> findById(String groupId) {
        return Optional.ofNullable(groups.get(groupId));
    }

    @Override
    public List<ConsumerGroup> findAll() {
        return new ArrayList<>(groups.values());
    }

    @Override
    public void deleteById(String groupId) {
        groups.remove(groupId);
    }
}
