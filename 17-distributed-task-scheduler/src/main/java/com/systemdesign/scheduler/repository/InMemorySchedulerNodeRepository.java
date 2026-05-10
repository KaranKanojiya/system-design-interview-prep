package com.systemdesign.scheduler.repository;

import com.systemdesign.scheduler.model.SchedulerNode;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

// Wiring: In-memory node registry. findAlive() compares each node's lastHeartbeat
// against (now - timeout) to filter out stale nodes — critical for leader re-election.
public class InMemorySchedulerNodeRepository implements SchedulerNodeRepository {

    private final Map<String, SchedulerNode> store = new ConcurrentHashMap<>();

    @Override
    public void save(SchedulerNode node) {
        store.put(node.getNodeId(), node);
    }

    @Override
    public Optional<SchedulerNode> findById(String nodeId) {
        return Optional.ofNullable(store.get(nodeId));
    }

    @Override
    public Optional<SchedulerNode> findLeader() {
        return store.values().stream()
                .filter(SchedulerNode::isLeader)
                .findFirst();
    }

    @Override
    public List<SchedulerNode> findAll() {
        return List.copyOf(store.values());
    }

    @Override
    public List<SchedulerNode> findAlive(Duration timeout) {
        Instant cutoff = Instant.now().minus(timeout);
        return store.values().stream()
                .filter(node -> node.getLastHeartbeat().isAfter(cutoff))
                .toList();
    }

    @Override
    public void deleteById(String nodeId) {
        store.remove(nodeId);
    }
}
