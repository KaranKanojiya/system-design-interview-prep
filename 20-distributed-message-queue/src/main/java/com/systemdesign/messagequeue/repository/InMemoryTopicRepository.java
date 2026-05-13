package com.systemdesign.messagequeue.repository;

import com.systemdesign.messagequeue.model.Topic;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory implementation of TopicRepository.
 * Uses ConcurrentHashMap for thread-safe access without external synchronization.
 */
public class InMemoryTopicRepository implements TopicRepository {

    // --- storage: topicName -> Topic ---
    private final Map<String, Topic> topics = new ConcurrentHashMap<>();

    @Override
    public void save(Topic topic) {
        topics.put(topic.getName(), topic);
    }

    @Override
    public Optional<Topic> findByName(String name) {
        return Optional.ofNullable(topics.get(name));
    }

    @Override
    public List<Topic> findAll() {
        return new ArrayList<>(topics.values());
    }

    @Override
    public void deleteByName(String name) {
        topics.remove(name);
    }

    @Override
    public boolean existsByName(String name) {
        return topics.containsKey(name);
    }
}
