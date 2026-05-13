package com.systemdesign.messagequeue.engine;

import com.systemdesign.messagequeue.model.ConsumerGroup;
import com.systemdesign.messagequeue.model.ConsumerInstance;
import com.systemdesign.messagequeue.model.PartitionAssignment;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages consumer groups, membership, rebalancing, and offset tracking.
 *
 * This is the coordinator that handles:
 *  1. Group lifecycle — create, join, leave
 *  2. Rebalancing — re-distribute partitions when membership changes
 *  3. Offset management — commit and retrieve consumer offsets
 *
 * Rebalance strategy: Range assignment (Kafka default).
 * Partitions are divided evenly; remainder partitions go to the first consumers.
 *
 * Flow (consumer joins):
 *  1. Consumer calls joinGroup(groupId, consumerInstance)
 *  2. Coordinator adds member to the group
 *  3. Coordinator triggers rebalance — partitions are reassigned
 *  4. Each consumer receives its new partition assignment
 */
public class ConsumerGroupCoordinator {

    // --- group registry ---
    private final Map<String, ConsumerGroup> groups;              // groupId -> ConsumerGroup

    // --- committed offsets: key = "groupId-topic-partition" ---
    private final Map<String, Long> committedOffsets;

    public ConsumerGroupCoordinator() {
        this.groups = new ConcurrentHashMap<>();
        this.committedOffsets = new ConcurrentHashMap<>();
    }

    // ===================== Group Lifecycle =====================

    /**
     * Creates a new consumer group.
     *
     * @param groupId unique group identifier
     * @return the newly created ConsumerGroup
     */
    public ConsumerGroup createGroup(String groupId) {
        ConsumerGroup group = new ConsumerGroup(groupId);
        groups.put(groupId, group);
        return group;
    }

    /**
     * Adds a consumer to a group and triggers rebalance.
     *
     * @param groupId  the group to join
     * @param consumer the consumer instance joining
     */
    public void joinGroup(String groupId, ConsumerInstance consumer) {
        ConsumerGroup group = groups.get(groupId);
        if (group == null) {
            throw new IllegalStateException("Consumer group not found: " + groupId);
        }

        // 1. add member to the group
        group.addMember(consumer);
        System.out.println("[COORDINATOR] Consumer '" + consumer.getConsumerId()
                + "' joined group '" + groupId + "'");
    }

    /**
     * Removes a consumer from a group and triggers rebalance.
     *
     * @param groupId    the group to leave
     * @param consumerId the consumer leaving
     */
    public void leaveGroup(String groupId, String consumerId) {
        ConsumerGroup group = groups.get(groupId);
        if (group == null) {
            throw new IllegalStateException("Consumer group not found: " + groupId);
        }

        // 1. remove member
        group.removeMember(consumerId);
        System.out.println("[COORDINATOR] Consumer '" + consumerId
                + "' left group '" + groupId + "'");
    }

    // ===================== Rebalancing =====================

    /**
     * Range assignment: distribute partitions evenly across consumers.
     *
     * Algorithm:
     *  - Sort consumer IDs for deterministic assignment
     *  - Each consumer gets (partitionCount / consumerCount) partitions
     *  - First (partitionCount % consumerCount) consumers get one extra
     *
     * @param groupId        the group to rebalance
     * @param partitionCount total number of partitions to distribute
     */
    public void rebalance(String groupId, int partitionCount) {
        ConsumerGroup group = groups.get(groupId);
        if (group == null) {
            throw new IllegalStateException("Consumer group not found: " + groupId);
        }

        List<String> consumerIds = new ArrayList<>(group.getMembers().keySet());
        if (consumerIds.isEmpty()) {
            System.out.println("[REBALANCE] Group '" + groupId + "' has no members — skipping");
            return;
        }

        // sort for deterministic assignment
        consumerIds.sort(String::compareTo);

        int consumerCount = consumerIds.size();
        int partitionsPerConsumer = partitionCount / consumerCount;
        int remainder = partitionCount % consumerCount;

        System.out.println("[REBALANCE] Group '" + groupId + "': " + partitionCount
                + " partitions across " + consumerCount + " consumers");

        // assign partitions using range strategy
        int partitionIndex = 0;
        for (int i = 0; i < consumerCount; i++) {
            String consumerId = consumerIds.get(i);
            // first 'remainder' consumers get one extra partition
            int count = partitionsPerConsumer + (i < remainder ? 1 : 0);

            List<PartitionAssignment> assignments = new ArrayList<>();
            for (int j = 0; j < count; j++) {
                // use first subscribed topic (simplified — real Kafka iterates all subscribed topics)
                String topic = group.getSubscribedTopics().isEmpty()
                        ? "unknown" : group.getSubscribedTopics().iterator().next();
                assignments.add(new PartitionAssignment(topic, partitionIndex++, consumerId));
            }

            group.setAssignment(consumerId, assignments);
            System.out.println("[REBALANCE]   " + consumerId + " -> partitions "
                    + assignments.stream().map(a -> String.valueOf(a.getPartitionId())).toList());
        }
    }

    // ===================== Offset Management =====================

    /**
     * Commits the consumer's current offset for a specific partition.
     *
     * @param groupId   the consumer group
     * @param topic     the topic name
     * @param partition the partition index
     * @param offset    the offset to commit
     */
    public void commitOffset(String groupId, String topic, int partition, long offset) {
        String key = buildOffsetKey(groupId, topic, partition);
        committedOffsets.put(key, offset);
    }

    /**
     * Returns the last committed offset for a consumer group on a partition.
     *
     * @param groupId   the consumer group
     * @param topic     the topic name
     * @param partition the partition index
     * @return the committed offset, or 0 if none committed
     */
    public long getCommittedOffset(String groupId, String topic, int partition) {
        String key = buildOffsetKey(groupId, topic, partition);
        return committedOffsets.getOrDefault(key, 0L);
    }

    /**
     * Calculates consumer lag: how far behind the consumer is from the latest offset.
     *
     * @param groupId      the consumer group
     * @param topic        the topic name
     * @param partition    the partition index
     * @param latestOffset the current high watermark of the partition
     * @return the lag (latestOffset - committedOffset)
     */
    public long getLag(String groupId, String topic, int partition, long latestOffset) {
        long committed = getCommittedOffset(groupId, topic, partition);
        return latestOffset - committed;
    }

    // ===================== Lookup =====================

    /**
     * Retrieves a consumer group by ID.
     *
     * @param groupId the group identifier
     * @return the group if it exists
     */
    public Optional<ConsumerGroup> getGroup(String groupId) {
        return Optional.ofNullable(groups.get(groupId));
    }

    public List<ConsumerGroup> getAllGroups() {
        return new ArrayList<>(groups.values());
    }

    // ===================== Internal =====================

    /** Builds the composite key for offset storage: "groupId-topic-partition". */
    private String buildOffsetKey(String groupId, String topic, int partition) {
        return groupId + "-" + topic + "-" + partition;
    }

    @Override
    public String toString() {
        return "ConsumerGroupCoordinator{groups=" + groups.size()
                + ", trackedOffsets=" + committedOffsets.size() + "}";
    }
}
