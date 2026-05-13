package com.systemdesign.messagequeue.service;

import com.systemdesign.messagequeue.engine.CommitLog;
import com.systemdesign.messagequeue.engine.ConsumerGroupCoordinator;
import com.systemdesign.messagequeue.engine.PartitionManager;
import com.systemdesign.messagequeue.model.ConsumerInstance;
import com.systemdesign.messagequeue.model.ConsumerRecord;
import com.systemdesign.messagequeue.model.Message;
import com.systemdesign.messagequeue.strategy.delivery.DeliveryStrategy;

import java.util.ArrayList;
import java.util.List;

/**
 * Message consumption — polls messages from partitions and manages consumer group offsets.
 *
 * Flow (poll):
 *  1. Get the committed offset for (groupId, topic, partition)
 *  2. Read messages from the CommitLog starting at that offset
 *  3. Convert each Message to a ConsumerRecord (immutable snapshot)
 *  4. Return the batch to the consumer
 *
 * Flow (commit):
 *  1. Consumer processes messages and calls commit()
 *  2. Coordinator persists the new offset for (groupId, topic, partition)
 */
public class ConsumerService {

    // --- dependencies (constructor-injected) ---
    private final PartitionManager partitionManager;          // partition commit log access
    private final ConsumerGroupCoordinator coordinator;       // group membership and offset tracking
    private final DeliveryStrategy deliveryStrategy;          // Strategy Pattern — delivery guarantee

    public ConsumerService(PartitionManager partitionManager,
                           ConsumerGroupCoordinator coordinator,
                           DeliveryStrategy deliveryStrategy) {
        this.partitionManager = partitionManager;
        this.coordinator = coordinator;
        this.deliveryStrategy = deliveryStrategy;
    }

    // ===================== Poll =====================

    /**
     * Polls messages from a specific partition, starting from the group's committed offset.
     *
     * @param groupId     the consumer group ID
     * @param topic       the topic to read from
     * @param partition   the partition index
     * @param maxMessages maximum number of messages to return
     * @return list of ConsumerRecords (may be empty if no new messages)
     * @throws IllegalStateException if the partition does not exist
     */
    public List<ConsumerRecord> poll(String groupId, String topic, int partition, int maxMessages) {
        // 1. get the committed offset for this group on this partition
        long committedOffset = coordinator.getCommittedOffset(groupId, topic, partition);

        // 2. get the CommitLog for the partition
        CommitLog commitLog = partitionManager.getPartition(topic, partition)
                .orElseThrow(() -> new IllegalStateException(
                        "Partition not found: " + topic + "-" + partition));

        // 3. read messages from the committed offset
        List<Message> messages = commitLog.read(committedOffset, maxMessages);

        // 4. convert each Message to an immutable ConsumerRecord
        List<ConsumerRecord> records = new ArrayList<>();
        for (Message msg : messages) {
            ConsumerRecord record = new ConsumerRecord(
                    msg.getTopic(),
                    msg.getPartition(),
                    msg.getOffset(),
                    msg.getKey(),
                    msg.getValue(),
                    msg.getHeaders(),
                    msg.getTimestamp()
            );
            records.add(record);
        }

        System.out.println("[CONSUMER] Poll groupId='" + groupId + "' topic='" + topic
                + "' partition=" + partition + " from offset=" + committedOffset
                + " — returned " + records.size() + " records"
                + " (delivery=" + deliveryStrategy.getStrategyName() + ")");

        return records;
    }

    // ===================== Offset Commit =====================

    /**
     * Commits the consumer's current offset for a specific partition.
     *
     * @param groupId   the consumer group ID
     * @param topic     the topic name
     * @param partition the partition index
     * @param offset    the offset to commit (typically lastProcessedOffset + 1)
     */
    public void commit(String groupId, String topic, int partition, long offset) {
        coordinator.commitOffset(groupId, topic, partition, offset);
        System.out.println("[CONSUMER] Committed offset=" + offset + " for groupId='" + groupId
                + "' topic='" + topic + "' partition=" + partition);
    }

    // ===================== Group Membership =====================

    /**
     * Subscribes a consumer to a topic within a consumer group.
     * Creates the group if it does not exist, joins the consumer, and triggers rebalance.
     *
     * @param groupId        the consumer group ID
     * @param consumerId     the consumer instance ID
     * @param topic          the topic to subscribe to
     * @param partitionCount total partitions for the topic (used during rebalance)
     */
    public void subscribe(String groupId, String consumerId, String topic, int partitionCount) {
        // 1. create the group if it does not exist
        if (coordinator.getGroup(groupId).isEmpty()) {
            coordinator.createGroup(groupId);
        }

        // 2. subscribe the group to the topic
        coordinator.getGroup(groupId).ifPresent(group -> group.subscribe(topic));

        // 3. create consumer instance and join the group
        ConsumerInstance consumer = new ConsumerInstance(consumerId, groupId, "localhost");
        coordinator.joinGroup(groupId, consumer);

        // 4. trigger rebalance to redistribute partitions
        coordinator.rebalance(groupId, partitionCount);

        System.out.println("[CONSUMER] Consumer '" + consumerId + "' subscribed to topic '"
                + topic + "' in group '" + groupId + "'");
    }

    /**
     * Unsubscribes a consumer from its group and triggers rebalance.
     *
     * @param groupId    the consumer group ID
     * @param consumerId the consumer instance ID to remove
     */
    public void unsubscribe(String groupId, String consumerId) {
        coordinator.leaveGroup(groupId, consumerId);

        // trigger rebalance if the group still has members
        coordinator.getGroup(groupId).ifPresent(group -> {
            if (group.getMemberCount() > 0) {
                // use the first subscribed topic's partition count for rebalance
                // (simplified — real Kafka would rebalance across all subscribed topics)
                int partitionCount = group.getSubscribedTopics().isEmpty()
                        ? 0 : group.getMemberCount();
                coordinator.rebalance(groupId, partitionCount);
            }
        });

        System.out.println("[CONSUMER] Consumer '" + consumerId + "' unsubscribed from group '"
                + groupId + "'");
    }

    // ===================== Lag =====================

    /**
     * Returns the consumer lag for a specific partition — how far behind the consumer is.
     *
     * @param groupId   the consumer group ID
     * @param topic     the topic name
     * @param partition the partition index
     * @return lag = latestOffset - committedOffset
     * @throws IllegalStateException if the partition does not exist
     */
    public long getLag(String groupId, String topic, int partition) {
        // 1. get the latest offset from the CommitLog
        CommitLog commitLog = partitionManager.getPartition(topic, partition)
                .orElseThrow(() -> new IllegalStateException(
                        "Partition not found: " + topic + "-" + partition));

        long latestOffset = commitLog.getLatestOffset();

        // 2. calculate lag via the coordinator
        return coordinator.getLag(groupId, topic, partition, latestOffset);
    }
}
