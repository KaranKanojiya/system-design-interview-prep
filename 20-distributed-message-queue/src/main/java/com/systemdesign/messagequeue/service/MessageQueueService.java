package com.systemdesign.messagequeue.service;

import com.systemdesign.messagequeue.model.AckMode;
import com.systemdesign.messagequeue.model.BrokerNode;
import com.systemdesign.messagequeue.model.ConsumerRecord;
import com.systemdesign.messagequeue.model.ProducerRecord;
import com.systemdesign.messagequeue.model.QueueMetrics;
import com.systemdesign.messagequeue.model.Topic;

import java.util.List;
import java.util.Map;

/**
 * Facade Pattern (GoF) — single entry point for the distributed message queue.
 *
 * Orchestrates all subsystems (topic management, produce, consume, broker cluster,
 * retention, and metrics) behind a unified API. Clients interact with this service
 * instead of wiring individual services directly.
 *
 * Flow (produce):
 *  1. Delegate to ProducerService.send()
 *  2. Record metrics via MetricsService
 *  3. Return the assigned offset
 *
 * Flow (consume):
 *  1. Delegate to ConsumerService.poll()
 *  2. Record metrics via MetricsService
 *  3. Return the consumer records
 */
public class MessageQueueService {

    // --- dependencies (constructor-injected, Facade wiring) ---
    private final TopicService topicService;          // topic lifecycle
    private final ProducerService producerService;    // message production
    private final ConsumerService consumerService;    // message consumption
    private final BrokerService brokerService;        // broker cluster management
    private final RetentionService retentionService;  // retention and compaction
    private final MetricsService metricsService;      // metrics tracking

    // Facade Pattern — single entry point for the message queue
    public MessageQueueService(TopicService topicService,
                               ProducerService producerService,
                               ConsumerService consumerService,
                               BrokerService brokerService,
                               RetentionService retentionService,
                               MetricsService metricsService) {
        this.topicService = topicService;
        this.producerService = producerService;
        this.consumerService = consumerService;
        this.brokerService = brokerService;
        this.retentionService = retentionService;
        this.metricsService = metricsService;
    }

    // ===================== Topic Management =====================

    /**
     * Creates a new topic with the specified number of partitions and replication factor.
     *
     * @param name              unique topic name
     * @param partitions        number of partitions
     * @param replicationFactor number of replicas per partition
     * @return the created Topic
     */
    public Topic createTopic(String name, int partitions, int replicationFactor) {
        return topicService.createTopic(name, partitions, replicationFactor);
    }

    // ===================== Produce =====================

    /**
     * Produces a message to the queue and records metrics.
     *
     * @param record  the producer record
     * @param ackMode the acknowledgment mode
     * @return the assigned offset
     */
    public long produce(ProducerRecord record, AckMode ackMode) {
        long offset = producerService.send(record, ackMode);

        // record produce metrics (build a lightweight message for metrics tracking)
        com.systemdesign.messagequeue.model.Message metricsMsg =
                new com.systemdesign.messagequeue.model.Message.Builder(
                        record.getTopic(), record.getValue())
                        .key(record.getKey())
                        .build();
        metricsService.recordProduce(record.getTopic(), metricsMsg);

        return offset;
    }

    // ===================== Consume =====================

    /**
     * Consumes messages from a specific partition and records metrics.
     *
     * @param groupId     the consumer group ID
     * @param topic       the topic to read from
     * @param partition   the partition index
     * @param maxMessages maximum number of messages to return
     * @return list of ConsumerRecords
     */
    public List<ConsumerRecord> consume(String groupId, String topic,
                                        int partition, int maxMessages) {
        List<ConsumerRecord> records = consumerService.poll(groupId, topic, partition, maxMessages);

        // record consume metrics for each returned record
        for (ConsumerRecord cr : records) {
            com.systemdesign.messagequeue.model.Message metricsMsg =
                    new com.systemdesign.messagequeue.model.Message.Builder(
                            cr.getTopic(), cr.getValue())
                            .key(cr.getKey())
                            .build();
            metricsService.recordConsume(topic, metricsMsg);
        }

        return records;
    }

    // ===================== Offset Commit =====================

    /**
     * Commits the consumer's offset for a specific partition.
     *
     * @param groupId   the consumer group ID
     * @param topic     the topic name
     * @param partition the partition index
     * @param offset    the offset to commit
     */
    public void commit(String groupId, String topic, int partition, long offset) {
        consumerService.commit(groupId, topic, partition, offset);
    }

    // ===================== Subscription =====================

    /**
     * Subscribes a consumer to a topic within a consumer group.
     *
     * @param groupId        the consumer group ID
     * @param consumerId     the consumer instance ID
     * @param topic          the topic to subscribe to
     * @param partitionCount total partitions for the topic
     */
    public void subscribe(String groupId, String consumerId, String topic, int partitionCount) {
        consumerService.subscribe(groupId, consumerId, topic, partitionCount);
    }

    // ===================== Retention =====================

    /**
     * Runs retention cleanup on a topic.
     *
     * @param topic       the topic to clean
     * @param retentionMs retention window in milliseconds
     * @return number of messages removed
     */
    public int runRetention(String topic, long retentionMs) {
        return retentionService.runCleanup(topic, retentionMs);
    }

    // ===================== System Overview =====================

    /**
     * Returns a formatted overview of the entire message queue system.
     * Includes broker count, topic count, and per-topic metrics.
     *
     * @return formatted string with system statistics
     */
    public String getSystemOverview() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Distributed Message Queue — System Overview ===\n");

        // broker stats
        List<BrokerNode> allBrokers = brokerService.getAllBrokers();
        List<BrokerNode> aliveBrokers = brokerService.getAliveBrokers();
        sb.append("Brokers: ").append(aliveBrokers.size())
                .append(" alive / ").append(allBrokers.size()).append(" total\n");

        // topic stats
        List<Topic> topics = topicService.getAllTopics();
        sb.append("Topics: ").append(topics.size()).append("\n");

        for (Topic topic : topics) {
            sb.append("  - ").append(topic.getName())
                    .append(" (").append(topic.getPartitionCount()).append(" partitions")
                    .append(", replication=").append(topic.getReplicationFactor()).append(")\n");

            // per-topic metrics
            metricsService.getMetrics(topic.getName()).ifPresent(m -> {
                sb.append("      in=").append(m.getMessagesIn())
                        .append(", out=").append(m.getMessagesOut())
                        .append(", lag=").append(m.getLag())
                        .append(", rate=").append(String.format("%.2f", m.getProduceRate()))
                        .append(" msg/s\n");
            });

            // storage stats
            Map<Integer, Long> storageStats = retentionService.getStorageStats(topic.getName());
            long totalMessages = storageStats.values().stream().mapToLong(Long::longValue).sum();
            sb.append("      storage: ").append(totalMessages)
                    .append(" messages across ").append(storageStats.size()).append(" partitions\n");
        }

        return sb.toString();
    }
}
