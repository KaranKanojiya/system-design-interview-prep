package com.systemdesign.scheduler.repository;

import com.systemdesign.scheduler.model.SchedulerNode;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

// Wiring: Storage abstraction for SchedulerNode (cluster membership and leader election).
// findLeader() is called during leader election; findAlive() is used by the
// health-check service to detect nodes that have stopped sending heartbeats.
public interface SchedulerNodeRepository {

    void save(SchedulerNode node);

    Optional<SchedulerNode> findById(String nodeId);

    // Returns the node that currently holds the leader lease
    Optional<SchedulerNode> findLeader();

    List<SchedulerNode> findAll();

    // Returns nodes whose lastHeartbeat is within the given timeout window
    List<SchedulerNode> findAlive(Duration timeout);

    void deleteById(String nodeId);
}
