package com.systemdesign.scheduler.service;

import com.systemdesign.scheduler.model.SchedulerNode;
import com.systemdesign.scheduler.repository.SchedulerNodeRepository;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

// Wiring: Leader election service using the Bully algorithm.
// The node with the highest priority (ties broken by nodeId) becomes leader.
// Delegates node persistence to SchedulerNodeRepository.
public class LeaderElectionService {

    private static final Duration ALIVE_TIMEOUT = Duration.ofSeconds(30);

    private final SchedulerNodeRepository nodeRepo;  // scheduler node storage
    private String currentLeaderId;                   // cached leader ID

    public LeaderElectionService(SchedulerNodeRepository nodeRepo) {
        this.nodeRepo = nodeRepo;
    }

    // 1. Registers a scheduler node in the cluster
    public void registerNode(SchedulerNode node) {
        nodeRepo.save(node);
        System.out.println("[LEADER ELECTION] Registered node: " + node);
    }

    // 2. Bully algorithm: each node challenges higher-priority nodes.
    //    The highest-priority alive node wins and becomes the leader.
    public SchedulerNode electLeader() {
        List<SchedulerNode> allNodes = nodeRepo.findAll();

        // Step 1: Find all alive nodes
        List<SchedulerNode> aliveNodes = allNodes.stream()
                .filter(node -> node.isAlive(ALIVE_TIMEOUT))
                .toList();

        System.out.println("[LEADER ELECTION] Starting bully algorithm election...");
        System.out.println("[LEADER ELECTION] Alive nodes: " + aliveNodes.size() + "/" + allNodes.size());

        if (aliveNodes.isEmpty()) {
            System.out.println("[LEADER ELECTION] No alive nodes found — election failed");
            currentLeaderId = null;
            return null;
        }

        // Step 2: Simulate bully algorithm — each node challenges higher-priority nodes
        for (SchedulerNode node : aliveNodes) {
            List<SchedulerNode> higherPriorityNodes = aliveNodes.stream()
                    .filter(other -> other.getPriority() > node.getPriority()
                            || (other.getPriority() == node.getPriority()
                                && other.getNodeId().compareTo(node.getNodeId()) > 0))
                    .toList();

            if (higherPriorityNodes.isEmpty()) {
                System.out.println("[LEADER ELECTION] Node " + node.getNodeId()
                        + " (priority=" + node.getPriority() + ") has no challengers");
            } else {
                System.out.println("[LEADER ELECTION] Node " + node.getNodeId()
                        + " (priority=" + node.getPriority() + ") challenged by "
                        + higherPriorityNodes.size() + " higher-priority nodes");
            }
        }

        // Step 3: Pick the winner — highest priority, then highest nodeId for ties
        SchedulerNode leader = aliveNodes.stream()
                .max(Comparator.comparingInt(SchedulerNode::getPriority)
                        .thenComparing(SchedulerNode::getNodeId))
                .orElseThrow();

        // Step 4: Update all nodes — only the winner is leader
        for (SchedulerNode node : allNodes) {
            node.setLeader(node.getNodeId().equals(leader.getNodeId()));
            nodeRepo.save(node);
        }

        currentLeaderId = leader.getNodeId();
        System.out.println("[LEADER ELECTION] Elected leader: " + leader);
        return leader;
    }

    // 3. Returns the current leader node if one exists and is alive
    public Optional<SchedulerNode> getCurrentLeader() {
        if (currentLeaderId == null) {
            return Optional.empty();
        }
        return nodeRepo.findById(currentLeaderId)
                .filter(node -> node.isAlive(ALIVE_TIMEOUT));
    }

    // 4. Handles a node failure — marks it dead and triggers re-election if it was the leader
    public void handleNodeFailure(String nodeId) {
        nodeRepo.findById(nodeId).ifPresent(node -> {
            node.setLeader(false);
            nodeRepo.save(node);
            System.out.println("[LEADER ELECTION] Node failed: " + nodeId);

            if (nodeId.equals(currentLeaderId)) {
                System.out.println("[LEADER ELECTION] Failed node was the leader — triggering re-election");
                electLeader();
            }
        });
    }

    // 5. Checks if a given node is currently the leader
    public boolean isLeader(String nodeId) {
        return nodeId != null && nodeId.equals(currentLeaderId);
    }

    // 6. Simulates heartbeat updates for all alive nodes
    public void simulateHeartbeats() {
        List<SchedulerNode> allNodes = nodeRepo.findAll();
        for (SchedulerNode node : allNodes) {
            if (node.isAlive(ALIVE_TIMEOUT)) {
                node.updateHeartbeat();
                nodeRepo.save(node);
            }
        }
        System.out.println("[LEADER ELECTION] Heartbeats updated for alive nodes");
    }
}
