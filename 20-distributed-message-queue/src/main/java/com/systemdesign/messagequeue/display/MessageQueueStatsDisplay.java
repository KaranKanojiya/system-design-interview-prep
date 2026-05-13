package com.systemdesign.messagequeue.display;

// Wiring: MessageQueueStatsDisplay is a console output helper that prints formatted tables
// for topics, partitions, consumer groups, brokers, message logs, and summary statistics.

import com.systemdesign.messagequeue.engine.CommitLog;
import com.systemdesign.messagequeue.engine.ConsumerGroupCoordinator;
import com.systemdesign.messagequeue.engine.PartitionManager;
import com.systemdesign.messagequeue.model.BrokerNode;
import com.systemdesign.messagequeue.model.ConsumerGroup;
import com.systemdesign.messagequeue.model.Message;
import com.systemdesign.messagequeue.model.PartitionAssignment;
import com.systemdesign.messagequeue.model.Topic;
import com.systemdesign.messagequeue.service.BrokerService;
import com.systemdesign.messagequeue.service.ConsumerService;
import com.systemdesign.messagequeue.service.MessageQueueService;
import com.systemdesign.messagequeue.service.MetricsService;
import com.systemdesign.messagequeue.service.ProducerService;
import com.systemdesign.messagequeue.service.TopicService;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Console display helper for Distributed Message Queue statistics and state.
 *
 * Prints formatted tables for:
 *   - Topics (name, partitions, replication, retention)
 *   - Partition details (id, messages, earliest/latest offset)
 *   - Consumer groups (groupId, members, assigned partitions, lag)
 *   - Broker cluster (id, host, controller, partitions led)
 *   - Message log (recent messages in a partition)
 *   - Summary statistics
 */
public class MessageQueueStatsDisplay {

    // wiring: injected via constructor from AppConfig composition root
    private final MessageQueueService mqService;
    private final TopicService topicService;
    private final ProducerService producerService;
    private final ConsumerService consumerService;
    private final BrokerService brokerService;
    private final MetricsService metricsService;
    private final PartitionManager partitionManager;
    private final ConsumerGroupCoordinator coordinator;

    public MessageQueueStatsDisplay(MessageQueueService mqService,
                                    TopicService topicService,
                                    ProducerService producerService,
                                    ConsumerService consumerService,
                                    BrokerService brokerService,
                                    MetricsService metricsService,
                                    PartitionManager partitionManager,
                                    ConsumerGroupCoordinator coordinator) {
        this.mqService = mqService;
        this.topicService = topicService;
        this.producerService = producerService;
        this.consumerService = consumerService;
        this.brokerService = brokerService;
        this.metricsService = metricsService;
        this.partitionManager = partitionManager;
        this.coordinator = coordinator;
    }

    // ── Topics ──────────────────────────────────────────────────────────

    /**
     * Prints all topics as a formatted table.
     * Columns: Name | Partitions | Replication | Retention
     */
    public void printTopics() {
        printSeparator("TOPICS");
        List<Topic> topics = topicService.getAllTopics();

        if (topics.isEmpty()) {
            System.out.println("  (no topics created)");
            return;
        }

        System.out.printf("  %-25s %-12s %-14s %-20s%n",
                "NAME", "PARTITIONS", "REPLICATION", "RETENTION");
        System.out.printf("  %-25s %-12s %-14s %-20s%n",
                "-".repeat(25), "-".repeat(12), "-".repeat(14), "-".repeat(20));

        for (Topic topic : topics) {
            Duration retention = topic.getRetentionDuration();
            String retentionStr = formatDuration(retention);

            System.out.printf("  %-25s %-12d %-14d %-20s%n",
                    truncate(topic.getName(), 25),
                    topic.getPartitionCount(),
                    topic.getReplicationFactor(),
                    retentionStr);
        }
    }

    // ── Partition Details ───────────────────────────────────────────────

    /**
     * Prints partition details for a specific topic.
     * Columns: Partition ID | Messages | Earliest Offset | Latest Offset
     */
    public void printPartitionDetails(String topicName) {
        printSeparator("PARTITIONS: " + topicName);
        List<CommitLog> partitions = partitionManager.getPartitionsForTopic(topicName);

        if (partitions.isEmpty()) {
            System.out.println("  (no partitions for topic " + topicName + ")");
            return;
        }

        System.out.printf("  %-14s %-12s %-18s %-18s%n",
                "PARTITION ID", "MESSAGES", "EARLIEST OFFSET", "LATEST OFFSET");
        System.out.printf("  %-14s %-12s %-18s %-18s%n",
                "-".repeat(14), "-".repeat(12), "-".repeat(18), "-".repeat(18));

        for (CommitLog log : partitions) {
            System.out.printf("  %-14d %-12d %-18d %-18d%n",
                    log.getPartitionId(),
                    log.size(),
                    log.getEarliestOffset(),
                    log.getLatestOffset());
        }
    }

    // ── Consumer Groups ─────────────────────────────────────────────────

    /**
     * Prints all consumer groups as a formatted table.
     * Columns: Group ID | Members | Assigned Partitions | Lag
     */
    public void printConsumerGroups() {
        printSeparator("CONSUMER GROUPS");
        List<ConsumerGroup> groups = coordinator.getAllGroups();

        if (groups.isEmpty()) {
            System.out.println("  (no consumer groups registered)");
            return;
        }

        System.out.printf("  %-20s %-10s %-22s %-10s%n",
                "GROUP ID", "MEMBERS", "ASSIGNED PARTITIONS", "LAG");
        System.out.printf("  %-20s %-10s %-22s %-10s%n",
                "-".repeat(20), "-".repeat(10), "-".repeat(22), "-".repeat(10));

        for (ConsumerGroup group : groups) {
            int totalAssigned = group.getAssignments().values().stream()
                    .mapToInt(List::size)
                    .sum();

            // wiring: compute lag as sum of (latestOffset - committedOffset) across all assigned partitions
            long totalLag = computeGroupLag(group);

            System.out.printf("  %-20s %-10d %-22d %-10d%n",
                    truncate(group.getGroupId(), 20),
                    group.getMemberCount(),
                    totalAssigned,
                    totalLag);
        }
    }

    // ── Broker Cluster ──────────────────────────────────────────────────

    /**
     * Prints all brokers in the cluster as a formatted table.
     * Columns: Broker ID | Host | Controller | Partitions Led
     */
    public void printBrokerCluster() {
        printSeparator("BROKER CLUSTER");
        List<BrokerNode> brokers = brokerService.getAllBrokers();

        if (brokers.isEmpty()) {
            System.out.println("  (no brokers registered)");
            return;
        }

        System.out.printf("  %-10s %-25s %-12s %-16s%n",
                "ID", "HOST", "CONTROLLER", "PARTITIONS LED");
        System.out.printf("  %-10s %-25s %-12s %-16s%n",
                "-".repeat(10), "-".repeat(25), "-".repeat(12), "-".repeat(16));

        for (BrokerNode broker : brokers) {
            System.out.printf("  %-10s %-25s %-12s %-16d%n",
                    truncateId(broker.getBrokerId()),
                    broker.getHost() + ":" + broker.getPort(),
                    broker.isController() ? "YES" : "no",
                    broker.getPartitionLeadership().size());
        }
    }

    // ── Message Log ─────────────────────────────────────────────────────

    /**
     * Prints recent messages from a specific partition's commit log.
     * Shows the last N messages with offset, key, value preview, and timestamp.
     */
    public void printMessageLog(String topicName, int partition, int maxMessages) {
        printSeparator("MESSAGE LOG: " + topicName + "-" + partition);

        var commitLog = partitionManager.getPartition(topicName, partition);
        if (commitLog.isEmpty()) {
            System.out.println("  (partition " + topicName + "-" + partition + " not found)");
            return;
        }

        CommitLog log = commitLog.get();
        // wiring: read from the latest offset minus maxMessages, clamped to earliest
        long fromOffset = Math.max(log.getEarliestOffset(), log.getLatestOffset() - maxMessages);
        List<Message> messages = log.read(fromOffset, maxMessages);

        if (messages.isEmpty()) {
            System.out.println("  (no messages in partition)");
            return;
        }

        System.out.printf("  %-8s %-10s %-30s %-25s%n",
                "OFFSET", "KEY", "VALUE", "TIMESTAMP");
        System.out.printf("  %-8s %-10s %-30s %-25s%n",
                "-".repeat(8), "-".repeat(10), "-".repeat(30), "-".repeat(25));

        for (Message msg : messages) {
            System.out.printf("  %-8d %-10s %-30s %-25s%n",
                    msg.getOffset(),
                    truncate(msg.getKey() != null ? msg.getKey() : "(null)", 10),
                    truncate(msg.getValue(), 30),
                    msg.getTimestamp().toString());
        }
    }

    // ── Summary Stats ───────────────────────────────────────────────────

    /**
     * Prints a final summary of message queue statistics.
     */
    public void printStats() {
        printSeparator("MESSAGE QUEUE SUMMARY");

        List<Topic> topics = topicService.getAllTopics();
        Map<String, CommitLog> allPartitions = partitionManager.getAllPartitions();
        List<ConsumerGroup> groups = coordinator.getAllGroups();
        List<BrokerNode> brokers = brokerService.getAllBrokers();

        long totalMessages = allPartitions.values().stream()
                .mapToLong(CommitLog::size)
                .sum();

        long totalControllers = brokers.stream()
                .filter(BrokerNode::isController)
                .count();

        System.out.printf("  Topics:              %d%n", topics.size());
        System.out.printf("  Partitions:          %d%n", allPartitions.size());
        System.out.printf("  Total messages:      %d%n", totalMessages);
        System.out.printf("  Consumer groups:     %d%n", groups.size());
        System.out.printf("  Brokers:             %d (%d controller)%n", brokers.size(), totalControllers);
        System.out.printf("  Metrics dashboard:   %s%n", metricsService.getAllMetrics().toString());
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    /**
     * Prints a section separator with a title.
     */
    public void printSeparator(String title) {
        System.out.println();
        System.out.println("=".repeat(80));
        System.out.println("  " + title);
        System.out.println("=".repeat(80));
    }

    /** Truncates an ID to 8 characters for compact display. */
    private String truncateId(String id) {
        if (id == null) return "null";
        return id.length() > 8 ? id.substring(0, 8) : id;
    }

    /** Truncates a string to the given max length, appending ".." if truncated. */
    private String truncate(String value, int maxLength) {
        if (value == null) return "null";
        if (value.length() <= maxLength) return value;
        return value.substring(0, maxLength - 2) + "..";
    }

    /** Formats a Duration as a human-readable string (e.g., "7d 0h"). */
    private String formatDuration(Duration duration) {
        long days = duration.toDays();
        long hours = duration.toHoursPart();
        if (days > 0) {
            return days + "d " + hours + "h";
        }
        long minutes = duration.toMinutesPart();
        return hours + "h " + minutes + "m";
    }

    /**
     * Computes the total lag for a consumer group across all assigned partitions.
     * Lag = sum of (latestOffset - committedOffset) for each partition.
     */
    private long computeGroupLag(ConsumerGroup group) {
        long totalLag = 0;
        for (List<PartitionAssignment> assignments : group.getAssignments().values()) {
            for (PartitionAssignment assignment : assignments) {
                var commitLog = partitionManager.getPartition(
                        assignment.getTopicName(), assignment.getPartitionId());
                if (commitLog.isPresent()) {
                    // wiring: lag = latest offset in partition (approximation without offset tracking)
                    totalLag += commitLog.get().getLatestOffset();
                }
            }
        }
        return totalLag;
    }
}
