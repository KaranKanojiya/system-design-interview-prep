package com.systemdesign.messagequeue.repository;

import com.systemdesign.messagequeue.model.BrokerNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory implementation of BrokerRepository.
 * Uses ConcurrentHashMap for thread-safe access without external synchronization.
 */
public class InMemoryBrokerRepository implements BrokerRepository {

    // --- storage: brokerId -> BrokerNode ---
    private final Map<String, BrokerNode> brokers = new ConcurrentHashMap<>();

    @Override
    public void save(BrokerNode broker) {
        brokers.put(broker.getBrokerId(), broker);
    }

    @Override
    public Optional<BrokerNode> findById(String brokerId) {
        return Optional.ofNullable(brokers.get(brokerId));
    }

    @Override
    public Optional<BrokerNode> findController() {
        // scan all brokers to find the one marked as controller
        return brokers.values().stream()
                .filter(BrokerNode::isController)
                .findFirst();
    }

    @Override
    public List<BrokerNode> findAll() {
        return new ArrayList<>(brokers.values());
    }

    @Override
    public List<BrokerNode> findAlive(Duration timeout) {
        // filter brokers whose last heartbeat is within the timeout
        return brokers.values().stream()
                .filter(broker -> broker.isAlive(timeout))
                .toList();
    }

    @Override
    public void deleteById(String brokerId) {
        brokers.remove(brokerId);
    }
}
