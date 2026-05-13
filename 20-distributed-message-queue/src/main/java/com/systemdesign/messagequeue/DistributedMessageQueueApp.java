package com.systemdesign.messagequeue;

import com.systemdesign.messagequeue.config.AppConfig;
import com.systemdesign.messagequeue.display.MessageQueueStatsDisplay;
import com.systemdesign.messagequeue.engine.*;
import com.systemdesign.messagequeue.model.*;
import com.systemdesign.messagequeue.service.*;
import com.systemdesign.messagequeue.strategy.delivery.ExactlyOnceDeliveryStrategy;
import com.systemdesign.messagequeue.strategy.partitioning.RoundRobinPartitioningStrategy;
import com.systemdesign.messagequeue.strategy.storage.LogCompactionStrategy;

import java.util.*;

/**
 * Distributed Message Queue — System Design Demo
 *
 * Demonstrates: Append-only commit log, topic partitioning, consumer groups
 * with rebalancing, offset management, ack modes (acks=0/1/all), delivery
 * guarantees (at-least-once, exactly-once), log compaction, retention,
 * replication, broker cluster with controller election.
 *
 * 12 demos covering all major components.
 */
public class DistributedMessageQueueApp {

    private static final String SEPARATOR = "=".repeat(70);

    public static void main(String[] args) {
        System.out.println(SEPARATOR);
        System.out.println("   DISTRIBUTED MESSAGE QUEUE — System Design Demo");
        System.out.println("   Staff Engineer Interview Prep: Kafka/RabbitMQ Internals");
        System.out.println(SEPARATOR);
        System.out.println();

        AppConfig config = new AppConfig();
        setupCluster(config);

        demo1_ProduceAndConsume(config);
        demo2_Partitioning(config);
        demo3_ConsumerGroupRebalancing(config);
        demo4_OffsetManagement(config);
        demo5_AckModes(config);
        demo6_AtLeastOnceDelivery(config);
        demo7_ExactlyOnceDelivery(config);
        demo8_LogCompaction(config);
        demo9_TimeBasedRetention(config);
        demo10_BrokerCluster(config);
        demo11_Replication(config);
        demo12_FullPipelineOverview(config);

        printDesignSummary();
    }

    private static void setupCluster(AppConfig config) {
        BrokerService brokerService = config.getBrokerService();
        brokerService.registerBroker(new BrokerNode("broker-1", "kafka-1.cluster", 9092));
        brokerService.registerBroker(new BrokerNode("broker-2", "kafka-2.cluster", 9092));
        brokerService.registerBroker(new BrokerNode("broker-3", "kafka-3.cluster", 9092));
        brokerService.electController();

        System.out.println("[SETUP] 3-broker cluster ready");
        System.out.println();
    }

    // ─────────────────────────────────────────────────────────────────
    // Demo 1: Produce and Consume Messages
    // ─────────────────────────────────────────────────────────────────
    private static void demo1_ProduceAndConsume(AppConfig config) {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 1: Produce and Consume Messages");
        System.out.println(SEPARATOR);

        MessageQueueService mq = config.getMessageQueueService();

        // Create topic
        mq.createTopic("orders", 3, 2);

        // Produce messages
        long off1 = mq.produce(new ProducerRecord("orders", "order-123", "{\"item\":\"laptop\",\"qty\":1}"), AckMode.LEADER);
        long off2 = mq.produce(new ProducerRecord("orders", "order-456", "{\"item\":\"phone\",\"qty\":2}"), AckMode.LEADER);
        long off3 = mq.produce(new ProducerRecord("orders", "order-789", "{\"item\":\"tablet\",\"qty\":1}"), AckMode.LEADER);

        System.out.println("[DEMO] Produced 3 messages to 'orders' topic");
        System.out.println("  Offsets: " + off1 + ", " + off2 + ", " + off3);

        // Consume from partition 0
        List<ConsumerRecord> records = mq.consume("order-group", "orders", 0, 10);
        System.out.println("[DEMO] Consumed " + records.size() + " messages from partition 0");
        for (ConsumerRecord r : records) {
            System.out.println("  offset=" + r.getOffset() + " key=" + r.getKey()
                    + " value=" + r.getValue());
        }

        System.out.println();
        System.out.println("  KEY INSIGHT: Messages are appended to a commit log (append-only).");
        System.out.println("  Each message gets a monotonically increasing offset. Consumers");
        System.out.println("  read from an offset position — the log is NOT deleted on consumption");
        System.out.println("  (unlike traditional queues). This enables replay and multi-consumer.");
        System.out.println();
    }

    // ─────────────────────────────────────────────────────────────────
    // Demo 2: Key-Based Partitioning
    // ─────────────────────────────────────────────────────────────────
    private static void demo2_Partitioning(AppConfig config) {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 2: Key-Based Partitioning");
        System.out.println(SEPARATOR);

        MessageQueueService mq = config.getMessageQueueService();
        PartitionManager pm = config.getPartitionManager();

        mq.createTopic("user-events", 4, 2);

        // Same key always goes to same partition
        String[] keys = {"user-100", "user-200", "user-100", "user-300", "user-200", "user-100"};
        Map<String, Integer> keyToPartition = new LinkedHashMap<>();

        for (String key : keys) {
            int partition = Math.abs(key.hashCode()) % 4;
            keyToPartition.put(key, partition);
            mq.produce(new ProducerRecord("user-events", key, "{\"event\":\"click\"}"), AckMode.LEADER);
        }

        System.out.println("[DEMO] Key → Partition mapping (consistent hash):");
        keyToPartition.forEach((k, p) ->
                System.out.println("  " + k + " → partition " + p));

        System.out.println();
        System.out.println("[DEMO] Messages per partition:");
        for (int p = 0; p < 4; p++) {
            pm.getPartition("user-events", p).ifPresent(log ->
                    System.out.println("  partition " + log.getPartitionId() + ": " + log.size() + " messages"));
        }

        System.out.println();
        System.out.println("  KEY INSIGHT: Same key → same partition guarantees ordering per key.");
        System.out.println("  user-100's events are always in partition " + (Math.abs("user-100".hashCode()) % 4)
                + " in order.");
        System.out.println("  This is how Kafka guarantees per-user ordering without global order.");
        System.out.println("  Hash(key) % partitionCount. Null key → round-robin (no ordering).");
        System.out.println();
    }

    // ─────────────────────────────────────────────────────────────────
    // Demo 3: Consumer Group Rebalancing
    // ─────────────────────────────────────────────────────────────────
    private static void demo3_ConsumerGroupRebalancing(AppConfig config) {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 3: Consumer Group Rebalancing");
        System.out.println(SEPARATOR);

        ConsumerGroupCoordinator coordinator = config.getConsumerGroupCoordinator();
        ConsumerService consumerService = config.getConsumerService();

        // Create a 4-partition topic
        config.getMessageQueueService().createTopic("events", 4, 2);

        // Start with 2 consumers
        consumerService.subscribe("analytics-group", "consumer-A", "events", 4);
        consumerService.subscribe("analytics-group", "consumer-B", "events", 4);

        System.out.println("[DEMO] 2 consumers, 4 partitions → 2 partitions each:");
        printAssignments(coordinator, "analytics-group");

        // Add a 3rd consumer → rebalance
        consumerService.subscribe("analytics-group", "consumer-C", "events", 4);
        System.out.println("[DEMO] Added consumer-C → rebalance:");
        printAssignments(coordinator, "analytics-group");

        // Remove consumer-B → rebalance
        consumerService.unsubscribe("analytics-group", "consumer-B");
        System.out.println("[DEMO] Removed consumer-B → rebalance:");
        printAssignments(coordinator, "analytics-group");

        System.out.println();
        System.out.println("  KEY INSIGHT: Consumer group rebalancing redistributes partitions");
        System.out.println("  when members join/leave. Range assignment: sort consumers, divide");
        System.out.println("  partitions evenly. In Kafka: also Sticky and CooperativeSticky");
        System.out.println("  assignors to minimize partition movement during rebalance.");
        System.out.println();
    }

    // ─────────────────────────────────────────────────────────────────
    // Demo 4: Offset Management
    // ─────────────────────────────────────────────────────────────────
    private static void demo4_OffsetManagement(AppConfig config) {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 4: Offset Management (Commit & Lag)");
        System.out.println(SEPARATOR);

        MessageQueueService mq = config.getMessageQueueService();
        ConsumerService consumerService = config.getConsumerService();

        // Produce 10 messages
        for (int i = 0; i < 10; i++) {
            mq.produce(new ProducerRecord("orders", "key-" + i, "value-" + i), AckMode.LEADER);
        }

        // Consume 5, commit offset
        List<ConsumerRecord> batch1 = mq.consume("order-processors", "orders", 0, 5);
        System.out.println("[DEMO] Consumed " + batch1.size() + " messages");
        if (!batch1.isEmpty()) {
            long lastOffset = batch1.get(batch1.size() - 1).getOffset() + 1;
            mq.commit("order-processors", "orders", 0, lastOffset);
            System.out.println("[DEMO] Committed offset: " + lastOffset);
        }

        // Check lag
        long lag = consumerService.getLag("order-processors", "orders", 0);
        System.out.println("[DEMO] Consumer lag: " + lag + " messages behind");

        // Consume remaining
        List<ConsumerRecord> batch2 = mq.consume("order-processors", "orders", 0, 10);
        System.out.println("[DEMO] Second poll: " + batch2.size() + " messages (from committed offset)");

        System.out.println();
        System.out.println("  KEY INSIGHT: Offsets track consumer progress per group per partition.");
        System.out.println("  Committed offset = 'I have processed up to here.' Lag = latest offset");
        System.out.println("  - committed offset. High lag → consumer falling behind → scale up.");
        System.out.println("  Kafka stores offsets in __consumer_offsets internal topic.");
        System.out.println();
    }

    // ─────────────────────────────────────────────────────────────────
    // Demo 5: Ack Modes (acks=0, acks=1, acks=all)
    // ─────────────────────────────────────────────────────────────────
    private static void demo5_AckModes(AppConfig config) {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 5: Ack Modes (acks=0, acks=1, acks=all)");
        System.out.println(SEPARATOR);

        MessageQueueService mq = config.getMessageQueueService();

        System.out.println("[DEMO] acks=0 (fire-and-forget) — fastest, may lose messages:");
        mq.produce(new ProducerRecord("orders", "k1", "acks-0-msg"), AckMode.NONE);

        System.out.println();
        System.out.println("[DEMO] acks=1 (leader ack) — balanced speed/safety:");
        mq.produce(new ProducerRecord("orders", "k2", "acks-1-msg"), AckMode.LEADER);

        System.out.println();
        System.out.println("[DEMO] acks=all (all ISR ack) — safest, slowest:");
        mq.produce(new ProducerRecord("orders", "k3", "acks-all-msg"), AckMode.ALL);

        System.out.println();
        System.out.println("  ┌──────────┬────────────┬────────────┬─────────────────┐");
        System.out.println("  │ Ack Mode │ Durability │  Latency   │    Use Case      │");
        System.out.println("  ├──────────┼────────────┼────────────┼─────────────────┤");
        System.out.println("  │ acks=0   │ Lowest     │ Fastest    │ Metrics, logs    │");
        System.out.println("  │ acks=1   │ Medium     │ Medium     │ Most workloads   │");
        System.out.println("  │ acks=all │ Highest    │ Slowest    │ Financial data   │");
        System.out.println("  └──────────┴────────────┴────────────┴─────────────────┘");
        System.out.println();
        System.out.println("  KEY INSIGHT: acks=all + min.insync.replicas=2 guarantees no data loss");
        System.out.println("  if at least 2 replicas are alive. acks=0 is fire-and-forget (UDP-like).");
        System.out.println();
    }

    // ─────────────────────────────────────────────────────────────────
    // Demo 6: At-Least-Once Delivery
    // ─────────────────────────────────────────────────────────────────
    private static void demo6_AtLeastOnceDelivery(AppConfig config) {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 6: At-Least-Once Delivery (Default)");
        System.out.println(SEPARATOR);

        ConsumerService consumerService = config.getConsumerService();
        MessageQueueService mq = config.getMessageQueueService();

        // Produce and consume with at-least-once semantics
        for (int i = 0; i < 5; i++) {
            mq.produce(new ProducerRecord("orders", "order-" + i, "at-least-once-" + i), AckMode.LEADER);
        }

        List<ConsumerRecord> records = mq.consume("alo-group", "orders", 0, 5);
        System.out.println("[DEMO] Consumed " + records.size() + " messages with at-least-once");
        System.out.println("[DEMO] If consumer crashes before commit → messages will be redelivered");

        System.out.println();
        System.out.println("  KEY INSIGHT: At-least-once = consume, then commit offset.");
        System.out.println("  If crash between consume and commit, messages are re-read on restart.");
        System.out.println("  Consumer must be idempotent (handle duplicates gracefully).");
        System.out.println("  This is Kafka's default and the most common delivery guarantee.");
        System.out.println();
    }

    // ─────────────────────────────────────────────────────────────────
    // Demo 7: Exactly-Once Delivery (Idempotent Consumer)
    // ─────────────────────────────────────────────────────────────────
    private static void demo7_ExactlyOnceDelivery(AppConfig config) {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 7: Exactly-Once Delivery (Idempotent Consumer)");
        System.out.println(SEPARATOR);

        config.setDeliveryStrategy(new ExactlyOnceDeliveryStrategy());
        ConsumerService consumerService = config.getConsumerService();
        MessageQueueService mq = config.getMessageQueueService();

        // Create topic and produce messages
        mq_createIfNotExists(config, "payments", 2, 2);
        mq.produce(new ProducerRecord("payments", "pay-1", "{\"amount\":99.99}"), AckMode.ALL);
        mq.produce(new ProducerRecord("payments", "pay-2", "{\"amount\":49.99}"), AckMode.ALL);

        // Consume
        List<ConsumerRecord> first = mq.consume("payment-processors", "payments", 0, 10);
        System.out.println("[DEMO] First consumption: " + first.size() + " messages");

        // Simulate redelivery of same messages (duplicate)
        System.out.println("[DEMO] Simulating redelivery (consumer restart without commit)...");
        List<ConsumerRecord> second = mq.consume("payment-processors", "payments", 0, 10);
        System.out.println("[DEMO] Second consumption: " + second.size() + " messages");
        System.out.println("[DEMO] With exactly-once, idempotent consumer deduplicates by message ID");

        System.out.println();
        System.out.println("  KEY INSIGHT: Exactly-once = at-least-once + idempotent processing.");
        System.out.println("  Kafka EOS uses producer ID + sequence number for dedup on broker.");
        System.out.println("  Consumer side: dedup by message ID in a Set or database constraint.");
        System.out.println("  Transactions (read-process-write) span consume + produce + commit.");
        System.out.println();
    }

    // ─────────────────────────────────────────────────────────────────
    // Demo 8: Log Compaction
    // ─────────────────────────────────────────────────────────────────
    private static void demo8_LogCompaction(AppConfig config) {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 8: Log Compaction (Keep Latest Per Key)");
        System.out.println(SEPARATOR);

        config.setStorageStrategy(new LogCompactionStrategy());
        MessageQueueService mq = config.getMessageQueueService();
        RetentionService retentionService = config.getRetentionService();

        mq.createTopic("user-profiles", 1, 1);

        // Multiple updates for same users
        mq.produce(new ProducerRecord("user-profiles", "user-1", "{\"name\":\"Karan\",\"v\":1}"), AckMode.LEADER);
        mq.produce(new ProducerRecord("user-profiles", "user-2", "{\"name\":\"Alex\",\"v\":1}"), AckMode.LEADER);
        mq.produce(new ProducerRecord("user-profiles", "user-1", "{\"name\":\"Karan\",\"v\":2}"), AckMode.LEADER);
        mq.produce(new ProducerRecord("user-profiles", "user-1", "{\"name\":\"Karan\",\"v\":3}"), AckMode.LEADER);
        mq.produce(new ProducerRecord("user-profiles", "user-2", "{\"name\":\"Alex\",\"v\":2}"), AckMode.LEADER);

        PartitionManager pm = config.getPartitionManager();
        int beforeSize = pm.getPartition("user-profiles", 0).map(CommitLog::size).orElse(0);
        System.out.println("[DEMO] Before compaction: " + beforeSize + " messages");

        int removed = retentionService.runCompaction("user-profiles");
        int afterSize = pm.getPartition("user-profiles", 0).map(CommitLog::size).orElse(0);
        System.out.println("[DEMO] After compaction: " + afterSize + " messages (" + removed + " removed)");
        System.out.println("[DEMO] Only latest version of each key is retained");

        System.out.println();
        System.out.println("  KEY INSIGHT: Log compaction keeps the LATEST value for each key.");
        System.out.println("  Used for changelog topics (KTable in Kafka Streams), CDC streams,");
        System.out.println("  and materialized views. cleanup.policy=compact retains forever but");
        System.out.println("  removes superseded values. Tombstone (null value) = key deletion.");
        System.out.println();
    }

    // ─────────────────────────────────────────────────────────────────
    // Demo 9: Time-Based Retention
    // ─────────────────────────────────────────────────────────────────
    private static void demo9_TimeBasedRetention(AppConfig config) {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 9: Time-Based Retention");
        System.out.println(SEPARATOR);

        MessageQueueService mq = config.getMessageQueueService();
        RetentionService retentionService = config.getRetentionService();
        PartitionManager pm = config.getPartitionManager();

        int before = 0;
        for (int p = 0; p < 3; p++) {
            before += pm.getPartition("orders", p).map(CommitLog::size).orElse(0);
        }
        System.out.println("[DEMO] Messages in 'orders' topic: " + before);

        // Run retention with 7-day window (nothing should expire since messages are fresh)
        int removed = retentionService.runCleanup("orders", 7 * 24 * 60 * 60 * 1000L);
        System.out.println("[DEMO] Retention cleanup (7-day window): " + removed + " messages removed");
        System.out.println("[DEMO] All messages are fresh → 0 removed (as expected)");

        // Run with very short retention (1ms) to expire everything
        int removedAll = retentionService.runCleanup("orders", 1);
        System.out.println("[DEMO] Retention cleanup (1ms window): " + removedAll + " messages removed");

        System.out.println();
        System.out.println("  KEY INSIGHT: Time-based retention (retention.ms) deletes messages");
        System.out.println("  older than the configured window. Kafka default: 7 days.");
        System.out.println("  Size-based retention (retention.bytes) caps per-partition storage.");
        System.out.println("  Retention is per-partition, checked by a background cleaner thread.");
        System.out.println();
    }

    // ─────────────────────────────────────────────────────────────────
    // Demo 10: Broker Cluster & Controller Election
    // ─────────────────────────────────────────────────────────────────
    private static void demo10_BrokerCluster(AppConfig config) {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 10: Broker Cluster & Controller Election");
        System.out.println(SEPARATOR);

        BrokerService brokerService = config.getBrokerService();
        MessageQueueStatsDisplay display = config.getStatsDisplay();

        display.printBrokerCluster();

        // Simulate controller failure
        System.out.println("[DEMO] Simulating controller failure...");
        Optional<BrokerNode> controller = brokerService.getAllBrokers().stream()
                .filter(BrokerNode::isController).findFirst();
        controller.ifPresent(c -> {
            System.out.println("[DEMO] Controller " + c.getBrokerId() + " failed!");
            brokerService.handleBrokerFailure(c.getBrokerId());
        });

        System.out.println("[DEMO] After re-election:");
        display.printBrokerCluster();

        System.out.println();
        System.out.println("  KEY INSIGHT: The controller broker handles partition leader election,");
        System.out.println("  topic creation, and replica management. Kafka uses ZooKeeper for");
        System.out.println("  controller election (KRaft mode removes ZK dependency). When the");
        System.out.println("  controller fails, brokers race to claim the /controller znode.");
        System.out.println();
    }

    // ─────────────────────────────────────────────────────────────────
    // Demo 11: Replication (ISR, Ack Modes)
    // ─────────────────────────────────────────────────────────────────
    private static void demo11_Replication(AppConfig config) {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 11: Replication (ISR & Ack Modes)");
        System.out.println(SEPARATOR);

        ReplicationEngine replicationEngine = config.getReplicationEngine();
        TopicService topicService = config.getTopicService();

        mq_createIfNotExists(config, "replicated-topic", 2, 3);

        // Show partition info
        Partition partition = new Partition("replicated-topic", 0, "broker-1",
                List.of("broker-1", "broker-2", "broker-3"),
                List.of("broker-1", "broker-2", "broker-3"));

        System.out.println("[DEMO] Partition: replicated-topic-0");
        System.out.println("  Leader: " + partition.getLeaderId());
        System.out.println("  Replicas: " + partition.getReplicaIds());
        System.out.println("  ISR: " + partition.getInSyncReplicaIds());

        // Replicate with different ack modes
        Message msg = new Message.Builder("replicated-topic", "replicated-data").key("rk1").build();
        System.out.println();
        System.out.println("[DEMO] Replicating with acks=all:");
        boolean result = replicationEngine.replicate(msg, partition, AckMode.ALL);
        System.out.println("  Result: " + (result ? "SUCCESS" : "FAILED"));

        // Simulate ISR shrink
        partition.removeFromIsr("broker-3");
        System.out.println();
        System.out.println("[DEMO] After broker-3 falls out of ISR:");
        System.out.println("  ISR: " + partition.getInSyncReplicaIds());
        boolean result2 = replicationEngine.replicate(msg, partition, AckMode.ALL);
        System.out.println("  acks=all with 2/3 ISR: " + (result2 ? "SUCCESS" : "FAILED"));

        System.out.println();
        System.out.println("  KEY INSIGHT: ISR (In-Sync Replicas) = replicas that are caught up");
        System.out.println("  with the leader. acks=all waits for ALL ISR replicas. If ISR shrinks");
        System.out.println("  below min.insync.replicas, producer gets NotEnoughReplicasException.");
        System.out.println("  This prevents writing to an under-replicated partition.");
        System.out.println();
    }

    // ─────────────────────────────────────────────────────────────────
    // Demo 12: Full Pipeline Overview
    // ─────────────────────────────────────────────────────────────────
    private static void demo12_FullPipelineOverview(AppConfig config) {
        System.out.println(SEPARATOR);
        System.out.println("  DEMO 12: Full Pipeline Overview & Stats");
        System.out.println(SEPARATOR);

        MessageQueueStatsDisplay display = config.getStatsDisplay();
        MetricsService metricsService = config.getMetricsService();

        display.printTopics();
        metricsService.printDashboard();
        display.printStats();
    }

    // ─────────────────────────────────────────────────────────────────
    private static void printDesignSummary() {
        System.out.println(SEPARATOR);
        System.out.println("  DESIGN SUMMARY — Distributed Message Queue");
        System.out.println(SEPARATOR);
        System.out.println();
        System.out.println("  Core Data Structures:");
        System.out.println("    • Commit Log (append-only) — O(1) append, O(1) read by offset");
        System.out.println("    • Partitions — parallel processing units within a topic");
        System.out.println("    • Consumer Group — coordinated consumption with offset tracking");
        System.out.println("    • ISR (In-Sync Replicas) — durability guarantee set");
        System.out.println();
        System.out.println("  Key Algorithms:");
        System.out.println("    • Hash partitioning — key.hashCode() % partitionCount");
        System.out.println("    • Range assignment — distribute partitions evenly to consumers");
        System.out.println("    • Log compaction — keep latest value per key");
        System.out.println("    • Time-based retention — expire messages older than window");
        System.out.println();
        System.out.println("  Design Patterns (GoF):");
        System.out.println("    • Strategy — delivery, partitioning, storage/compaction");
        System.out.println("    • Builder — Message construction");
        System.out.println("    • Factory — AppConfig composition root");
        System.out.println("    • Repository — data access (Topic, ConsumerGroup, Broker, Offset)");
        System.out.println("    • Facade — MessageQueueService orchestrates all services");
        System.out.println("    • Observer — consumer group rebalancing on member change");
        System.out.println("    • State — consumer offset lifecycle");
        System.out.println("    • Singleton — AppConfig lazy initialization");
        System.out.println();
        System.out.println("  Staff-Level Topics:");
        System.out.println("    • Append-only commit log (Kafka's core abstraction)");
        System.out.println("    • Delivery guarantees (at-most/at-least/exactly-once)");
        System.out.println("    • Consumer group rebalancing (range/sticky assignor)");
        System.out.println("    • ISR and replication (acks=0/1/all)");
        System.out.println("    • Log compaction vs time-based retention");
        System.out.println("    • Controller election (ZK → KRaft migration)");
        System.out.println();
        System.out.println(SEPARATOR);
        System.out.println("  End of Distributed Message Queue Demo");
        System.out.println(SEPARATOR);
    }

    private static void printAssignments(ConsumerGroupCoordinator coordinator, String groupId) {
        coordinator.getGroup(groupId).ifPresent(group -> {
            group.getAssignments().forEach((consumerId, partitions) -> {
                System.out.print("  " + consumerId + " → partitions: ");
                partitions.forEach(p -> System.out.print(p.getPartitionId() + " "));
                System.out.println();
            });
        });
    }

    private static void mq_createIfNotExists(AppConfig config, String name, int partitions, int replication) {
        TopicService ts = config.getTopicService();
        if (ts.getTopic(name).isEmpty()) {
            config.getMessageQueueService().createTopic(name, partitions, replication);
        }
    }
}
