# Low-Level Design: Distributed Message Queue

> Interview-prep reference for Senior Java Developer (7+ years).
> Focus: clean OOP, design patterns, concurrency awareness, extensibility.
> Modeled after Apache Kafka internals -- append-only commit log, topic partitioning,
> consumer groups with rebalancing, ISR replication, and delivery guarantees.

---

## Table of Contents

1. [Core Modules Overview](#1-core-modules-overview)
2. [Package Structure](#2-package-structure)
3. [Class Diagram](#3-class-diagram)
4. [Entity Design](#4-entity-design)
5. [Interface Contracts](#5-interface-contracts)
6. [Strategy Implementations](#6-strategy-implementations)
7. [Engine Design](#7-engine-design)
8. [Service Layer Design](#8-service-layer-design)
9. [Concurrency Considerations](#9-concurrency-considerations)
10. [SOLID Principles Mapping](#10-solid-principles-mapping)
11. [Sample Workflows](#11-sample-workflows)
12. [Design Patterns Used](#12-design-patterns-used)
13. [Extensibility Points](#13-extensibility-points)

---

## 1. Core Modules Overview

| Module         | Responsibility                                                                  |
|----------------|---------------------------------------------------------------------------------|
| **model**      | Domain entities, enums, and value objects (Message, Topic, Partition, etc.)      |
| **engine**     | Core infrastructure: commit log, partition management, replication, routing      |
| **repository** | Data access abstraction over in-memory storage (Topic, Broker, Offset, Group)   |
| **service**    | Business logic orchestration (produce, consume, retention, metrics, broker)      |
| **strategy**   | Pluggable algorithms for partitioning, delivery guarantees, and storage/cleanup  |
| **controller** | REST-like API layer; maps client requests to MessageQueueService facade          |
| **config**     | Composition root / factory; all concrete class instantiation and DI wiring      |
| **display**    | Console output helper; formatted tables for topics, partitions, brokers, stats  |
| **exception**  | Domain-specific exception hierarchy rooted at MessageQueueException             |

---

## 2. Package Structure

```
com.systemdesign.messagequeue
|
|-- model/                              -- 15 classes
|   |-- Message.java                    -- Core message entity (Builder pattern)
|   |-- Topic.java                      -- Named channel with partition/replication config
|   |-- Partition.java                  -- Unit of parallelism; tracks leader + ISR
|   |-- BrokerNode.java                -- Cluster node with heartbeat + controller flag
|   |-- ConsumerGroup.java             -- Coordinated consumer set for load-balanced reads
|   |-- ConsumerInstance.java           -- Single consumer within a group; heartbeat tracking
|   |-- ConsumerRecord.java            -- Immutable snapshot of a consumed message
|   |-- ProducerRecord.java            -- Record sent by producer (topic, key, value)
|   |-- MessageBatch.java              -- Batch of messages for efficient I/O
|   |-- Offset.java                    -- Committed offset for (group, topic, partition)
|   |-- PartitionAssignment.java       -- Maps partition to consumer during rebalance
|   |-- QueueMetrics.java              -- Throughput, byte rates, consumer lag counters
|   |-- RetentionPolicy.java           -- Retention config (time, size, compaction policy)
|   |-- AckMode.java                   -- Enum: NONE (acks=0), LEADER (acks=1), ALL (acks=all)
|   |-- DeliveryGuarantee.java         -- Enum: AT_MOST_ONCE, AT_LEAST_ONCE, EXACTLY_ONCE
|
|-- engine/                             -- 5 classes
|   |-- CommitLog.java                  -- Append-only log per partition (THE core data structure)
|   |-- PartitionManager.java          -- Registry of all CommitLogs across all topics
|   |-- ConsumerGroupCoordinator.java  -- Group lifecycle, rebalancing, offset management
|   |-- MessageRouter.java             -- Routes messages: explicit > key-hash > round-robin
|   |-- ReplicationEngine.java         -- ISR replication simulation with ack mode handling
|
|-- repository/                         -- 8 classes (4 interfaces + 4 implementations)
|   |-- TopicRepository.java           -- Interface: topic CRUD
|   |-- InMemoryTopicRepository.java   -- ConcurrentHashMap-backed implementation
|   |-- BrokerRepository.java          -- Interface: broker CRUD + liveness queries
|   |-- InMemoryBrokerRepository.java  -- ConcurrentHashMap-backed implementation
|   |-- ConsumerGroupRepository.java   -- Interface: consumer group CRUD
|   |-- InMemoryConsumerGroupRepository.java -- ConcurrentHashMap-backed implementation
|   |-- OffsetRepository.java          -- Interface: committed offset CRUD
|   |-- InMemoryOffsetRepository.java  -- ConcurrentHashMap-backed implementation
|
|-- service/                            -- 7 classes
|   |-- MessageQueueService.java       -- Facade (GoF): unified API for the entire system
|   |-- TopicService.java              -- Topic lifecycle: create, delete, query
|   |-- ProducerService.java           -- Produce path: route, build message, append, replicate
|   |-- ConsumerService.java           -- Consume path: poll, commit offset, subscribe/unsub
|   |-- BrokerService.java             -- Broker cluster: registration, controller election
|   |-- RetentionService.java          -- Time-based retention + log compaction
|   |-- MetricsService.java            -- Per-topic produce/consume counters and dashboards
|
|-- strategy/                           -- 9 classes (3 interfaces + 6 implementations)
|   |-- delivery/
|   |   |-- DeliveryStrategy.java       -- Interface: deliver message to consumer
|   |   |-- AtLeastOnceDeliveryStrategy.java  -- Retry loop with 5% simulated failure
|   |   |-- ExactlyOnceDeliveryStrategy.java  -- Idempotent consumer via dedup set
|   |-- partitioning/
|   |   |-- PartitioningStrategy.java   -- Interface: assign partition for a key
|   |   |-- HashPartitioningStrategy.java     -- key.hashCode() % partitionCount
|   |   |-- RoundRobinPartitioningStrategy.java -- AtomicInteger counter mod partitionCount
|   |-- storage/
|       |-- StorageStrategy.java        -- Interface: retention + compaction logic
|       |-- TimeBasedRetentionStrategy.java   -- Expire messages older than retention window
|       |-- LogCompactionStrategy.java        -- Keep only latest value per key
|
|-- controller/                         -- 1 class
|   |-- MessageQueueController.java     -- REST-like facade delegating to MessageQueueService
|
|-- config/                             -- 1 class
|   |-- AppConfig.java                  -- Factory / composition root; lazy initialization
|
|-- display/                            -- 1 class
|   |-- MessageQueueStatsDisplay.java   -- Formatted console output for all system state
|
|-- exception/                          -- 4 classes
|   |-- MessageQueueException.java      -- Base exception (RuntimeException)
|   |-- TopicNotFoundException.java     -- Topic does not exist in registry
|   |-- PartitionNotFoundException.java -- Partition does not exist for topic
|   |-- ConsumerGroupException.java     -- Consumer group coordination error
|
|-- DistributedMessageQueueApp.java     -- Main entry point with 12 demos
```

---

## 3. Class Diagram

### 3.1 Strategy Interfaces and Implementations

```
+-----------------------------------------------------------------------+
|                       <<interface>>                                    |
|                    PartitioningStrategy                                |
|-----------------------------------------------------------------------|
| + assignPartition(key: String, partitionCount: int): int              |
| + getStrategyName(): String                                           |
+-----------------------------------------------------------------------+
          ^                              ^
          | implements                   | implements
          |                              |
+--------------------------+  +-------------------------------+
| HashPartitioningStrategy |  | RoundRobinPartitioningStrategy|
|--------------------------|  |-------------------------------|
|                          |  | - counter: AtomicInteger      |
|--------------------------|  |-------------------------------|
| + assignPartition()      |  | + assignPartition()           |
| + getStrategyName()      |  | + getStrategyName()           |
+--------------------------+  +-------------------------------+

+-----------------------------------------------------------------------+
|                       <<interface>>                                    |
|                     DeliveryStrategy                                   |
|-----------------------------------------------------------------------|
| + deliver(message: Message, consumerId: String): boolean              |
| + getGuarantee(): DeliveryGuarantee                                   |
| + getStrategyName(): String                                           |
+-----------------------------------------------------------------------+
          ^                              ^
          | implements                   | implements
          |                              |
+------------------------------+  +-------------------------------+
| AtLeastOnceDeliveryStrategy  |  | ExactlyOnceDeliveryStrategy   |
|------------------------------|  |-------------------------------|
| - MAX_RETRIES: 3             |  | - deliveredIds: Set<String>   |
| - FAILURE_RATE: 0.05         |  |-------------------------------|
|------------------------------|  | + deliver()                   |
| + deliver()                  |  | + getGuarantee()              |
| + getGuarantee()             |  | + getStrategyName()           |
| + getStrategyName()          |  +-------------------------------+
+------------------------------+

+-----------------------------------------------------------------------+
|                       <<interface>>                                    |
|                      StorageStrategy                                   |
|-----------------------------------------------------------------------|
| + shouldRetain(message: Message, retentionMs: long): boolean          |
| + compact(messages: List<Message>): List<Message>                     |
| + getStrategyName(): String                                           |
+-----------------------------------------------------------------------+
          ^                              ^
          | implements                   | implements
          |                              |
+------------------------------+  +-------------------------------+
| TimeBasedRetentionStrategy   |  | LogCompactionStrategy         |
|------------------------------|  |-------------------------------|
|                              |  |                               |
|------------------------------|  |-------------------------------|
| + shouldRetain()             |  | + shouldRetain()              |
| + compact()                  |  | + compact()                   |
| + compact(msgs, retentionMs) |  | + getStrategyName()           |
| + getStrategyName()          |  +-------------------------------+
+------------------------------+
```

### 3.2 Repository Layer

```
+-----------------------------------------------------------------------+
|                     <<interface>>                                      |
|                     TopicRepository                                    |
|-----------------------------------------------------------------------|
| + save(topic: Topic): void                                            |
| + findByName(name: String): Optional<Topic>                           |
| + findAll(): List<Topic>                                              |
| + deleteByName(name: String): void                                    |
| + existsByName(name: String): boolean                                 |
+-----------------------------------------------------------------------+
                          ^
                          | implements
                          |
+-----------------------------------------------------------------------+
|                   InMemoryTopicRepository                              |
|-----------------------------------------------------------------------|
| - topics: ConcurrentHashMap<String, Topic>                            |
|-----------------------------------------------------------------------|
| + save(topic: Topic): void                                            |
| + findByName(name: String): Optional<Topic>                           |
| + findAll(): List<Topic>                                              |
| + deleteByName(name: String): void                                    |
| + existsByName(name: String): boolean                                 |
+-----------------------------------------------------------------------+

+-----------------------------------------------------------------------+
|                     <<interface>>                                      |
|                    BrokerRepository                                    |
|-----------------------------------------------------------------------|
| + save(broker: BrokerNode): void                                      |
| + findById(brokerId: String): Optional<BrokerNode>                    |
| + findController(): Optional<BrokerNode>                              |
| + findAll(): List<BrokerNode>                                         |
| + findAlive(timeout: Duration): List<BrokerNode>                      |
| + deleteById(brokerId: String): void                                  |
+-----------------------------------------------------------------------+
                          ^
                          | implements
                          |
+-----------------------------------------------------------------------+
|                 InMemoryBrokerRepository                               |
|-----------------------------------------------------------------------|
| - brokers: ConcurrentHashMap<String, BrokerNode>                      |
|-----------------------------------------------------------------------|
| (implements all BrokerRepository methods)                              |
| findAlive: filters by broker.isAlive(timeout) using heartbeat          |
| findController: scans for broker with isController==true               |
+-----------------------------------------------------------------------+

+-----------------------------------------------------------------------+
|                     <<interface>>                                      |
|                 ConsumerGroupRepository                                |
|-----------------------------------------------------------------------|
| + save(group: ConsumerGroup): void                                    |
| + findById(groupId: String): Optional<ConsumerGroup>                  |
| + findAll(): List<ConsumerGroup>                                      |
| + deleteById(groupId: String): void                                   |
+-----------------------------------------------------------------------+
                          ^
                          | implements
                          |
+-----------------------------------------------------------------------+
|             InMemoryConsumerGroupRepository                            |
|-----------------------------------------------------------------------|
| - groups: ConcurrentHashMap<String, ConsumerGroup>                    |
+-----------------------------------------------------------------------+

+-----------------------------------------------------------------------+
|                     <<interface>>                                      |
|                    OffsetRepository                                    |
|-----------------------------------------------------------------------|
| + save(offset: Offset): void                                          |
| + findByGroupTopicPartition(g: String, t: String, p: int): Opt<Off>  |
| + findByGroup(groupId: String): List<Offset>                          |
| + findAll(): List<Offset>                                             |
+-----------------------------------------------------------------------+
                          ^
                          | implements
                          |
+-----------------------------------------------------------------------+
|                InMemoryOffsetRepository                                |
|-----------------------------------------------------------------------|
| - offsets: ConcurrentHashMap<String, Offset>                          |
| key format: "groupId-topic-partition"                                  |
+-----------------------------------------------------------------------+
```

### 3.3 Engine Layer

```
+-----------------------------------------------------------------------+
|                        CommitLog                                       |
|-----------------------------------------------------------------------|
| - topicName: String                                                    |
| - partitionId: int                                                     |
| - log: List<Message>                  (append-only)                    |
| - currentOffset: AtomicLong           (monotonically increasing)       |
|-----------------------------------------------------------------------|
| + append(message: Message): long      (synchronized)                   |
| + read(fromOffset: long, max: int): List<Message>  (synchronized)     |
| + getLatestOffset(): long                                              |
| + getEarliestOffset(): long                                            |
| + truncateBefore(offset: long): void  (retention cleanup)              |
| + size(): int                                                          |
| + getMessages(): List<Message>        (read-only copy)                 |
+-----------------------------------------------------------------------+

+-----------------------------------------------------------------------+
|                      PartitionManager                                  |
|-----------------------------------------------------------------------|
| - commitLogs: ConcurrentHashMap<String, CommitLog>                    |
|   key format: "topicName-partitionId"                                  |
|-----------------------------------------------------------------------|
| + createPartition(topicName, partitionId): void                        |
| + getPartition(topicName, partitionId): Optional<CommitLog>            |
| + getPartitionsForTopic(topicName): List<CommitLog>                    |
| + getAllPartitions(): Map<String, CommitLog>                            |
| + deletePartition(topicName, partitionId): void                        |
+-----------------------------------------------------------------------+

+-----------------------------------------------------------------------+
|                  ConsumerGroupCoordinator                              |
|-----------------------------------------------------------------------|
| - groups: ConcurrentHashMap<String, ConsumerGroup>                    |
| - committedOffsets: ConcurrentHashMap<String, Long>                   |
|   key format: "groupId-topic-partition"                                |
|-----------------------------------------------------------------------|
| + createGroup(groupId): ConsumerGroup                                  |
| + joinGroup(groupId, consumer): void                                   |
| + leaveGroup(groupId, consumerId): void                                |
| + rebalance(groupId, partitionCount): void   (range assignment)        |
| + commitOffset(groupId, topic, partition, offset): void                |
| + getCommittedOffset(groupId, topic, partition): long                  |
| + getLag(groupId, topic, partition, latestOffset): long                 |
| + getGroup(groupId): Optional<ConsumerGroup>                           |
| + getAllGroups(): List<ConsumerGroup>                                   |
+-----------------------------------------------------------------------+

+-----------------------------------------------------------------------+
|                       MessageRouter                                    |
|-----------------------------------------------------------------------|
| - defaultPartitionCount: int                                           |
| - roundRobinCounter: AtomicInteger                                     |
|-----------------------------------------------------------------------|
| + routeToPartition(record: ProducerRecord, partitionCount: int): int  |
|   Priority: explicit partition > key hash > round-robin                |
+-----------------------------------------------------------------------+

+-----------------------------------------------------------------------+
|                     ReplicationEngine                                   |
|-----------------------------------------------------------------------|
| - replicationFactor: int                                               |
|-----------------------------------------------------------------------|
| + replicate(msg: Message, partition: Partition, ackMode: AckMode): bool|
| + getReplicaCount(partition: Partition): int                           |
| - replicateToAllIsr(msg, partition): bool  (ISR ack simulation)        |
+-----------------------------------------------------------------------+
```

### 3.4 Service Layer

```
+-----------------------------------------------------------------------+
|                    MessageQueueService  <<Facade>>                     |
|-----------------------------------------------------------------------|
| - topicService: TopicService                                           |
| - producerService: ProducerService                                     |
| - consumerService: ConsumerService                                     |
| - brokerService: BrokerService                                         |
| - retentionService: RetentionService                                   |
| - metricsService: MetricsService                                       |
|-----------------------------------------------------------------------|
| + createTopic(name, partitions, replicationFactor): Topic              |
| + produce(record: ProducerRecord, ackMode: AckMode): long             |
| + consume(groupId, topic, partition, maxMessages): List<ConsumerRecord>|
| + commit(groupId, topic, partition, offset): void                      |
| + subscribe(groupId, consumerId, topic, partitionCount): void          |
| + runRetention(topic, retentionMs): int                                |
| + getSystemOverview(): String                                          |
+-----------------------------------------------------------------------+
         |             |            |           |          |          |
         v             v            v           v          v          v
  TopicService  ProducerService  ConsumerService  BrokerService  RetentionService  MetricsService
```

### 3.5 Dependency Wiring Graph (AppConfig)

```
  Layer 1: Repositories
  +------------------+  +------------------+  +------------------+  +------------------+
  | TopicRepository  |  | BrokerRepository |  | ConsumerGroupRepo|  | OffsetRepository |
  +--------+---------+  +--------+---------+  +--------+---------+  +--------+---------+
           |                     |                     |                     |
  Layer 2: Engines
  +--------+---------+  +--------+---------+  +--------+---------+  +--------+---------+
  | PartitionManager |  | ReplicationEngine|  | ConsumerGroupCoord| | MessageRouter    |
  +--------+---------+  +--------+---------+  +--------+---------+  +--------+---------+
           |                     |                     |
  Layer 3: Strategies
  +--------+---------+  +--------+---------+  +--------+---------+
  | PartitioningStrat|  | DeliveryStrategy |  | StorageStrategy  |
  +--------+---------+  +--------+---------+  +--------+---------+
           |                     |                     |
  Layer 4: Services
  +--------+---------+  +--------+---------+  +--------+---------+
  | TopicService     |  | ProducerService  |  | ConsumerService  |
  +--------+---------+  +--------+---------+  +--------+---------+
  +--------+---------+  +--------+---------+  +--------+---------+
  | BrokerService    |  | RetentionService |  | MetricsService   |
  +--------+---------+  +--------+---------+  +--------+---------+
           |                     |                     |
  Layer 5: Facade
  +----------------------------------------------------------+
  |              MessageQueueService  (FACADE)                |
  +------------------------------+---------------------------+
                                 |
  Layer 6: Controller + Display
  +---------------------------+  +---------------------------+
  | MessageQueueController    |  | MessageQueueStatsDisplay  |
  +---------------------------+  +---------------------------+
```

---

## 4. Entity Design

### 4.1 Message (Builder Pattern)

The fundamental unit of data in the message queue. Constructed via `Message.Builder`.

```java
public class Message {
    // identity
    private final String id;                    // UUID
    private final String key;                   // partition key (nullable)
    private final String value;                 // payload
    private final Map<String, String> headers;  // user-defined metadata

    // routing
    private final String topic;                 // destination topic
    private int partition;                      // assigned partition index
    private long offset;                        // log offset (-1 until assigned)

    // metadata
    private final Instant timestamp;            // creation time
    private final String producerId;            // producing client ID
}
```

**Key decisions:**
- `id` is a UUID generated at build time -- globally unique
- `key` is nullable; null key triggers round-robin partitioning
- `offset` is -1 until the broker assigns it via `CommitLog.append()`
- `partition` is mutable (set by broker after routing)
- `headers` map enables custom metadata without schema changes

**Builder usage:**
```java
Message msg = new Message.Builder("orders", "{\"item\":\"laptop\"}")
    .key("order-123")
    .header("trace-id", "abc-xyz")
    .producerId("producer-1")
    .build();
```

### 4.2 Topic

A named channel that producers write to and consumers read from.

```java
public class Topic {
    private final String name;                  // unique topic name
    private final int partitionCount;           // number of partitions
    private final int replicationFactor;        // replicas per partition
    private long retentionMs;                   // retention window (default: 7 days)
    private final Instant createdAt;
    private final Map<String, String> config;   // topic-level config overrides
}
```

**Key decisions:**
- `partitionCount` is immutable after creation (Kafka allows expansion but not shrinking)
- `retentionMs` defaults to 604,800,000 (7 days), matching Kafka's `retention.ms`
- `config` map allows per-topic overrides without adding new fields

### 4.3 Partition

A single partition within a topic -- the unit of parallelism and ordering.

```java
public class Partition {
    private final String topicName;
    private final int partitionId;
    private String leaderId;                    // broker ID of leader
    private final List<String> replicaIds;      // all replica broker IDs
    private final List<String> inSyncReplicaIds; // ISR -- caught up replicas
}
```

**Key decisions:**
- `leaderId` is mutable (changes during leader election after failure)
- ISR is managed via `addToIsr()` / `removeFromIsr()` methods
- `getPartitionKey()` returns `"topicName-partitionId"` composite key

### 4.4 BrokerNode

A broker node in the distributed cluster.

```java
public class BrokerNode {
    private final String brokerId;
    private final String host;
    private final int port;
    private boolean isController;               // cluster controller flag
    private final Set<String> partitionLeadership; // "topic-partition" keys this broker leads
    private Instant lastHeartbeat;              // liveness tracking
}
```

**Key decisions:**
- `isController` flag identifies the controller broker (handles partition assignment, leader election)
- `isAlive(Duration timeout)` compares heartbeat against timeout for failure detection
- `partitionLeadership` tracks which topic-partitions this broker leads

### 4.5 ConsumerGroup

Coordinates a set of consumers for load-balanced consumption.

```java
public class ConsumerGroup {
    private final String groupId;
    private final Set<String> subscribedTopics;
    private final Map<String, ConsumerInstance> members;          // consumerId -> instance
    private final Map<String, List<PartitionAssignment>> assignments; // consumerId -> assigned partitions
    private final Instant createdAt;
}
```

**Key decisions:**
- Each partition is assigned to exactly one consumer within the group (Kafka-style)
- `addMember()` / `removeMember()` triggers rebalancing at the service layer
- `assignments` map is updated by `ConsumerGroupCoordinator.rebalance()`

### 4.6 ConsumerInstance

A single consumer within a consumer group.

```java
public class ConsumerInstance {
    private final String consumerId;
    private final String groupId;
    private final String host;
    private Instant lastHeartbeat;
    private List<PartitionAssignment> assignedPartitions;
}
```

### 4.7 ConsumerRecord (Immutable)

Immutable snapshot of a message delivered to a consumer.

```java
public class ConsumerRecord {
    private final String topic;
    private final int partition;
    private final long offset;
    private final String key;
    private final String value;
    private final Map<String, String> headers;  // Collections.unmodifiableMap
    private final Instant timestamp;
}
```

**Key decisions:**
- No setters -- fully immutable once created
- `headers` wrapped in `Collections.unmodifiableMap()` for defensive copying
- Contains all routing metadata so consumers can commit offsets

### 4.8 ProducerRecord

Record sent by a producer. Key and partition are optional.

```java
public class ProducerRecord {
    private final String topic;                 // required
    private final Integer partition;            // nullable (auto-assign if null)
    private final String key;                   // nullable (round-robin if null)
    private final String value;                 // required
    private final Map<String, String> headers;
}
```

**Partition resolution priority:**
1. Explicit partition (if set and >= 0)
2. Key-based hash (`Math.abs(key.hashCode()) % partitionCount`)
3. Round-robin (null key, no explicit partition)

### 4.9 Offset

Tracks committed offset for a `(groupId, topicName, partitionId)` tuple.

```java
public class Offset {
    private final String groupId;
    private final String topicName;
    private final int partitionId;
    private long committedOffset;       // -1 = no offset committed yet
    private Instant lastCommitTime;
}
```

### 4.10 MessageBatch

Batch of messages for efficient network and disk I/O.

```java
public class MessageBatch {
    private final String batchId;       // UUID
    private final String topicName;
    private final int partition;
    private final List<Message> messages;
    private final Instant createdAt;
}
```

### 4.11 QueueMetrics

Per-topic throughput, byte rates, and consumer lag counters.

```java
public class QueueMetrics {
    private long messagesIn;            // total produced
    private long messagesOut;           // total consumed
    private long bytesIn;
    private long bytesOut;
    private long lag;                   // messagesIn - messagesOut
    private Instant firstMessageTime;   // for rate calculation
    private Instant lastUpdateTime;
}
```

**Key methods:**
- `recordIn(Message)` -- increments messagesIn, bytesIn, recalculates lag
- `recordOut(Message)` -- increments messagesOut, bytesOut, recalculates lag
- `getProduceRate()` -- `messagesIn / elapsedSeconds` since first message

### 4.12 RetentionPolicy (Static Factory Methods)

```java
public class RetentionPolicy {
    private final long retentionMs;         // -1 = unlimited
    private final long retentionBytes;      // -1 = unlimited
    private final CleanupPolicy cleanupPolicy;

    // Static factories
    public static RetentionPolicy timeBased(long ms)  { ... }
    public static RetentionPolicy sizeBased(long bytes) { ... }
    public static RetentionPolicy compact()           { ... }
    public static RetentionPolicy of(long ms, long bytes, CleanupPolicy policy) { ... }
}
```

**CleanupPolicy enum:**
- `DELETE` -- delete old segments when retention exceeded
- `COMPACT` -- keep only latest value per key (log compaction)
- `COMPACT_DELETE` -- compact first, then delete if still exceeding retention

### 4.13 AckMode Enum

```java
public enum AckMode {
    NONE(0, "No acknowledgement -- fire and forget (acks=0)"),
    LEADER(1, "Leader acknowledgement only (acks=1)"),
    ALL(-1, "All in-sync replicas acknowledge (acks=all)");
}
```

| Mode   | Value | Durability | Latency | Use Case         |
|--------|-------|------------|---------|------------------|
| NONE   | 0     | Lowest     | Fastest | Metrics, logs    |
| LEADER | 1     | Medium     | Medium  | Most workloads   |
| ALL    | -1    | Highest    | Slowest | Financial data   |

### 4.14 DeliveryGuarantee Enum

```java
public enum DeliveryGuarantee {
    AT_MOST_ONCE("Messages delivered zero or one time"),
    AT_LEAST_ONCE("Messages delivered one or more times"),
    EXACTLY_ONCE("Messages delivered exactly one time");
}
```

| Guarantee      | Loss? | Duplicates? | How                              |
|----------------|-------|-------------|----------------------------------|
| AT_MOST_ONCE   | Yes   | No          | Commit offset before processing  |
| AT_LEAST_ONCE  | No    | Yes         | Commit offset after processing   |
| EXACTLY_ONCE   | No    | No          | Idempotent producer + txn commit |

---

## 5. Interface Contracts

### 5.1 PartitioningStrategy

```java
public interface PartitioningStrategy {
    int assignPartition(String key, int partitionCount);
    String getStrategyName();
}
```

**Contract:**
- Returns a value in `[0, partitionCount)` -- always valid partition index
- Must be deterministic for non-null keys (same key always maps to same partition)
- Null key behavior is implementation-specific (hash returns 0, round-robin rotates)

### 5.2 DeliveryStrategy

```java
public interface DeliveryStrategy {
    boolean deliver(Message message, String consumerId);
    DeliveryGuarantee getGuarantee();
    String getStrategyName();
}
```

**Contract:**
- `deliver()` returns true if the message was successfully delivered (acked)
- For AT_LEAST_ONCE: retries on failure, may cause duplicates
- For EXACTLY_ONCE: deduplicates by message ID, always returns true

### 5.3 StorageStrategy

```java
public interface StorageStrategy {
    boolean shouldRetain(Message message, long retentionMs);
    List<Message> compact(List<Message> messages);
    String getStrategyName();
}
```

**Contract:**
- `shouldRetain()` evaluates a single message against the retention window
- `compact()` processes an entire partition's messages and returns retained messages
- For time-based: retains if `(now - message.timestamp) <= retentionMs`
- For compaction: retains only the latest (highest offset) message per key

### 5.4 TopicRepository

```java
public interface TopicRepository {
    void save(Topic topic);
    Optional<Topic> findByName(String name);
    List<Topic> findAll();
    void deleteByName(String name);
    boolean existsByName(String name);
}
```

### 5.5 BrokerRepository

```java
public interface BrokerRepository {
    void save(BrokerNode broker);
    Optional<BrokerNode> findById(String brokerId);
    Optional<BrokerNode> findController();
    List<BrokerNode> findAll();
    List<BrokerNode> findAlive(Duration timeout);
    void deleteById(String brokerId);
}
```

**Unique method:** `findAlive(Duration timeout)` -- filters brokers whose last heartbeat is within the timeout window. Used by `BrokerService.electController()` to exclude dead brokers.

### 5.6 ConsumerGroupRepository

```java
public interface ConsumerGroupRepository {
    void save(ConsumerGroup group);
    Optional<ConsumerGroup> findById(String groupId);
    List<ConsumerGroup> findAll();
    void deleteById(String groupId);
}
```

### 5.7 OffsetRepository

```java
public interface OffsetRepository {
    void save(Offset offset);
    Optional<Offset> findByGroupTopicPartition(String groupId, String topic, int partition);
    List<Offset> findByGroup(String groupId);
    List<Offset> findAll();
}
```

**Key format:** `"groupId-topic-partition"` -- composite key uniquely identifies a consumer group's position within a specific partition.

---

## 6. Strategy Implementations

### 6.1 HashPartitioningStrategy

```java
public class HashPartitioningStrategy implements PartitioningStrategy {
    @Override
    public int assignPartition(String key, int partitionCount) {
        if (key == null) return 0;
        return Math.abs(key.hashCode()) % partitionCount;
    }
}
```

**Behavior:**
- Null key defaults to partition 0 (deterministic fallback)
- Same key always maps to same partition -- guarantees per-key ordering
- `Math.abs()` prevents negative hash codes from producing negative partition indices
- Analogous to Kafka's `DefaultPartitioner` using murmur2 hash

**Example:**
```
key="user-100", partitionCount=4 -> Math.abs("user-100".hashCode()) % 4 = 2
key="user-200", partitionCount=4 -> Math.abs("user-200".hashCode()) % 4 = 1
key=null,       partitionCount=4 -> 0 (fallback)
```

### 6.2 RoundRobinPartitioningStrategy

```java
public class RoundRobinPartitioningStrategy implements PartitioningStrategy {
    private final AtomicInteger counter = new AtomicInteger(0);

    @Override
    public int assignPartition(String key, int partitionCount) {
        return Math.abs(counter.getAndIncrement() % partitionCount);
    }
}
```

**Behavior:**
- Ignores the key entirely -- distributes evenly across all partitions
- Thread-safe via `AtomicInteger` -- no synchronization needed
- Ideal for maximizing throughput when per-key ordering is not required
- Does NOT guarantee per-key ordering

### 6.3 AtLeastOnceDeliveryStrategy

```java
public class AtLeastOnceDeliveryStrategy implements DeliveryStrategy {
    private static final int MAX_RETRIES = 3;
    private static final double FAILURE_RATE = 0.05;

    @Override
    public boolean deliver(Message message, String consumerId) {
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            boolean acked = ThreadLocalRandom.current().nextDouble() >= FAILURE_RATE;
            if (acked) return true;   // success
            // retry on failure
        }
        return false;  // exhausted retries
    }
}
```

**Behavior:**
- Simulates 5% ack failure rate per attempt
- Retries up to 3 times before giving up
- On crash between consume and commit, messages are re-read on restart (duplicates possible)
- Consumer must be idempotent to handle duplicates gracefully

### 6.4 ExactlyOnceDeliveryStrategy

```java
public class ExactlyOnceDeliveryStrategy implements DeliveryStrategy {
    private final Set<String> deliveredIds = ConcurrentHashMap.newKeySet();

    @Override
    public boolean deliver(Message message, String consumerId) {
        if (deliveredIds.contains(message.getId())) {
            return true;  // idempotent -- treat duplicate as success
        }
        deliveredIds.add(message.getId());
        return true;
    }
}
```

**Behavior:**
- Uses a `ConcurrentHashMap`-backed set for deduplication
- If a message ID has already been delivered, the duplicate is silently skipped
- Simulates the idempotent consumer pattern from Kafka's exactly-once semantics
- In production: dedup via database constraint or Kafka's producer ID + sequence number

### 6.5 TimeBasedRetentionStrategy

```java
public class TimeBasedRetentionStrategy implements StorageStrategy {
    @Override
    public boolean shouldRetain(Message message, long retentionMs) {
        long ageMs = Instant.now().toEpochMilli() - message.getTimestamp().toEpochMilli();
        return ageMs <= retentionMs;
    }

    @Override
    public List<Message> compact(List<Message> messages) {
        return compact(messages, 7L * 24 * 60 * 60 * 1000); // 7-day default
    }
}
```

**Behavior:**
- Compares message timestamp against `(now - retentionMs)` cutoff
- Messages older than the retention window are expired during compaction
- Default compaction uses 7-day window (matching Kafka's `retention.ms`)
- Overloaded `compact(messages, retentionMs)` for custom windows

### 6.6 LogCompactionStrategy

```java
public class LogCompactionStrategy implements StorageStrategy {
    @Override
    public boolean shouldRetain(Message message, long retentionMs) {
        return true;  // compaction is not time-based
    }

    @Override
    public List<Message> compact(List<Message> messages) {
        // 1. Null-key messages always retained
        // 2. For keyed messages: keep only highest-offset entry per key
        Map<String, Message> latestByKey = new LinkedHashMap<>();
        for (Message msg : messages) {
            if (msg.getKey() != null) {
                Message existing = latestByKey.get(msg.getKey());
                if (existing == null || msg.getOffset() > existing.getOffset()) {
                    latestByKey.put(msg.getKey(), msg);
                }
            }
        }
        // combine null-key + latest-per-key
        return ...;
    }
}
```

**Behavior:**
- `shouldRetain()` always returns true -- compaction is not time-based
- During `compact()`, only the latest (highest offset) message per key survives
- Null-key messages are always retained (cannot be compacted)
- Analogous to Kafka's `cleanup.policy=compact` for changelog/KTable topics

---

## 7. Engine Design

### 7.1 CommitLog -- The Core Data Structure

The CommitLog is the most important class in the system. Every partition is backed by exactly one CommitLog. It is an append-only, ordered list of messages with monotonically increasing offsets.

```
                    CommitLog (partition 0)
 +------+------+------+------+------+------+------+
 |  0   |  1   |  2   |  3   |  4   |  5   |  6   |  <-- offsets
 +------+------+------+------+------+------+------+
 | msg  | msg  | msg  | msg  | msg  | msg  | msg  |
 +------+------+------+------+------+------+------+
   ^                                          ^
   |                                          |
 earliestOffset                        latestOffset (next=7)
```

**Write path (append):**

```
1. Producer sends message to broker
2. Broker routes message to correct partition
3. CommitLog.append(message) is called
4. synchronized block entered
5. currentOffset.getAndIncrement() assigns next offset
6. message.setOffset(assignedOffset) stamps the offset
7. log.add(message) appends to the list
8. Return assignedOffset to caller
```

**Read path (read):**

```
1. Consumer requests read(fromOffset=3, maxMessages=5)
2. synchronized block entered
3. Bounds check: fromOffset < 0 or >= log.size() -> return empty
4. Calculate slice: start=3, end=min(3+5, log.size())
5. Return new ArrayList(log.subList(start, end))
```

**Concurrency model:**
- All methods that access the `log` list are `synchronized`
- `currentOffset` uses `AtomicLong` for lock-free offset generation
- The combination ensures:
  - No two messages get the same offset
  - No reader sees a partially written message
  - Reads and writes are serialized per partition (not per broker)

**Retention (truncateBefore):**
```java
public synchronized void truncateBefore(long offset) {
    log.removeIf(msg -> msg.getOffset() < offset);
}
```
- Removes all messages with offset strictly less than the given value
- Called by `RetentionService` during cleanup cycles

**Performance characteristics:**
- Append: O(1) amortized (ArrayList add)
- Read by offset: O(1) random access (ArrayList subList)
- Truncate: O(n) scan -- but runs on background thread
- Space: O(messages) -- bounded by retention policy

### 7.2 PartitionManager

Registry of all CommitLogs across all topics. The single source of truth for partition lifecycle.

```
          PartitionManager
    +----------------------------+
    | commitLogs: ConcurrentHashMap |
    +----------------------------+
    | "orders-0"    -> CommitLog |
    | "orders-1"    -> CommitLog |
    | "orders-2"    -> CommitLog |
    | "events-0"    -> CommitLog |
    | "events-1"    -> CommitLog |
    | "events-2"    -> CommitLog |
    | "events-3"    -> CommitLog |
    +----------------------------+
```

**Key design decisions:**
- `ConcurrentHashMap` for thread-safe partition lookup without external locks
- `putIfAbsent` in `createPartition()` for idempotent creation
- Composite key format: `"topicName-partitionId"` -- simple and collision-free
- `getPartitionsForTopic()` scans all entries by topic name prefix -- O(n) but acceptable for typical partition counts (< 1000)

### 7.3 ConsumerGroupCoordinator -- Rebalancing

The coordinator manages consumer groups, membership, rebalancing, and offset tracking.

**Rebalance algorithm (Range Assignment):**

```
Input: 4 partitions, 3 consumers (sorted: [A, B, C])

partitionsPerConsumer = 4 / 3 = 1
remainder            = 4 % 3 = 1

Consumer A: 1 + 1 (gets remainder) = 2 partitions -> [0, 1]
Consumer B: 1 + 0                  = 1 partition  -> [2]
Consumer C: 1 + 0                  = 1 partition  -> [3]
```

**Rebalance flow:**

```
1. Consumer joins/leaves group
2. ConsumerService calls coordinator.rebalance(groupId, partitionCount)
3. Coordinator gets sorted list of consumer IDs
4. Calculates partitionsPerConsumer and remainder
5. Assigns consecutive partition ranges to each consumer
6. Updates ConsumerGroup.assignments map
```

**Visual example -- consumer join/leave:**

```
Initial: 2 consumers, 4 partitions
  consumer-A -> [0, 1]
  consumer-B -> [2, 3]

After consumer-C joins:
  consumer-A -> [0, 1]      (first gets remainder)
  consumer-B -> [2]
  consumer-C -> [3]

After consumer-B leaves:
  consumer-A -> [0, 1]      (first gets remainder)
  consumer-C -> [2, 3]
```

**Offset management:**
- Composite key: `"groupId-topic-partition"`
- `commitOffset()` stores the offset in `ConcurrentHashMap`
- `getCommittedOffset()` returns 0 if no offset committed (start from beginning)
- `getLag()` = `latestOffset - committedOffset` per partition

### 7.4 MessageRouter

Routes messages to partitions using a three-level strategy:

```
  ProducerRecord arrives
       |
       v
  Has explicit partition?
       |
    YES |  NO
       |    |
       v    v
  Use it  Has key?
           |
        YES |  NO
           |    |
           v    v
       Hash key  Round-robin
       % count   counter++ % count
```

**Implementation:**

```java
public int routeToPartition(ProducerRecord record, int partitionCount) {
    // 1. explicit partition
    if (record.getPartition() >= 0) return record.getPartition();

    // 2. key-based consistent hashing
    if (record.getKey() != null)
        return Math.abs(record.getKey().hashCode()) % partitionCount;

    // 3. round-robin
    return Math.abs(roundRobinCounter.getAndIncrement()) % partitionCount;
}
```

### 7.5 ReplicationEngine

Simulates partition replication across brokers with ack mode handling.

```
  Producer sends message with AckMode
       |
       v
  +----------+
  | acks=0   | -> Return immediately (fire-and-forget)
  +----------+
       |
  +----------+
  | acks=1   | -> Leader acknowledges -> return success
  +----------+
       |
  +----------+
  | acks=all | -> Replicate to ALL ISR replicas
  +----------+     |
                   v
             For each ISR replica:
               simulate acknowledgment
             If ISR.size() < replicationFactor:
               log WARNING (durability at risk)
             Return success after all ISR ack
```

**ISR shrink scenario:**

```
Normal state:
  Leader: broker-1
  ISR: [broker-1, broker-2, broker-3]
  acks=all -> all 3 acknowledge -> SUCCESS

After broker-3 falls behind:
  Leader: broker-1
  ISR: [broker-1, broker-2]   (broker-3 removed from ISR)
  acks=all -> 2 acknowledge + WARNING: ISR(2) < replicationFactor(3)
```

---

## 8. Service Layer Design

### 8.1 MessageQueueService (Facade)

The `MessageQueueService` is the **Facade Pattern** (GoF) -- a single entry point that orchestrates all subsystems.

**Dependencies (6 services, all constructor-injected):**

```java
public MessageQueueService(
    TopicService topicService,
    ProducerService producerService,
    ConsumerService consumerService,
    BrokerService brokerService,
    RetentionService retentionService,
    MetricsService metricsService
)
```

**Produce flow (numbered steps):**

```
1. Controller receives POST /topics/{topic}/messages
2. Controller delegates to MessageQueueService.produce(record, ackMode)
3. MQService delegates to ProducerService.send(record, ackMode)
   3a. ProducerService looks up Topic from TopicRepository
   3b. ProducerService resolves target partition (Strategy Pattern)
   3c. ProducerService gets CommitLog from PartitionManager
   3d. ProducerService builds Message from ProducerRecord
   3e. CommitLog.append(message) assigns offset
   3f. ReplicationEngine.replicate(message, partition, ackMode)
   3g. Return assigned offset
4. MQService records produce metrics via MetricsService
5. Return offset to Controller
```

**Consume flow (numbered steps):**

```
1. Controller receives GET /topics/{topic}/messages
2. Controller delegates to MessageQueueService.consume(groupId, topic, partition, max)
3. MQService delegates to ConsumerService.poll(groupId, topic, partition, max)
   3a. ConsumerService gets committedOffset from ConsumerGroupCoordinator
   3b. ConsumerService gets CommitLog from PartitionManager
   3c. CommitLog.read(committedOffset, maxMessages) returns messages
   3d. Each Message is converted to immutable ConsumerRecord
   3e. Return list of ConsumerRecords
4. MQService records consume metrics for each record
5. Return ConsumerRecords to Controller
```

### 8.2 TopicService

Topic lifecycle management.

**Create topic flow:**

```
1. Validate inputs (name not blank, partitions >= 1, replicationFactor >= 1)
2. Check for duplicate name via TopicRepository.existsByName()
3. Create Topic model and persist via TopicRepository.save()
4. For i = 0 to partitionCount-1:
     PartitionManager.createPartition(name, i)
     -> creates one CommitLog per partition
5. Return the created Topic
```

**Delete topic flow:**

```
1. Look up Topic from TopicRepository (throws if not found)
2. For i = 0 to partitionCount-1:
     PartitionManager.deletePartition(name, i)
3. TopicRepository.deleteByName(name)
```

### 8.3 ProducerService

Message production -- routes records to partitions and appends to CommitLog.

**send() flow:**

```
1. Look up Topic from TopicRepository (throws if not found)
2. Resolve target partition:
   a. If record.partition != null -> validate range, use explicit
   b. Else -> delegate to PartitioningStrategy.assignPartition(key, partitionCount)
      (HashPartitioning: key.hashCode() % count; RoundRobin: counter++ % count)
3. Get CommitLog from PartitionManager (throws if not found)
4. Build Message via Builder:
   new Message.Builder(topicName, record.getValue())
       .key(record.getKey())
       .partition(targetPartition)
       .headers(record.getHeaders())
       .build()
5. CommitLog.append(message) -> returns assigned offset
6. Create Partition metadata and call ReplicationEngine.replicate()
7. Return the assigned offset
```

**sendBatch():**
- Iterates records and calls `send()` for each
- Returns list of offsets in the same order as input records

### 8.4 ConsumerService

Message consumption and consumer group management.

**poll() flow:**

```
1. Get committed offset for (groupId, topic, partition) from Coordinator
2. Get CommitLog from PartitionManager (throws if not found)
3. CommitLog.read(committedOffset, maxMessages)
4. Convert each Message to immutable ConsumerRecord:
   new ConsumerRecord(topic, partition, offset, key, value, headers, timestamp)
5. Return list of ConsumerRecords
```

**subscribe() flow:**

```
1. Create consumer group if it does not exist
2. Subscribe group to the topic
3. Create ConsumerInstance and join the group
4. Trigger rebalance to redistribute partitions
```

**unsubscribe() flow:**

```
1. Remove consumer from group via Coordinator.leaveGroup()
2. If group still has members:
   trigger rebalance to redistribute partitions
```

**getLag():**

```
lag = CommitLog.getLatestOffset() - Coordinator.getCommittedOffset(groupId, topic, partition)
```

### 8.5 BrokerService

Broker cluster management -- registration, controller election, and failure handling.

**Controller election flow:**

```
1. Clear any existing controller flag
2. Get all alive brokers (heartbeat within 30-second timeout)
3. Sort by broker ID (lexicographic)
4. Elect the broker with the LOWEST ID as controller
5. Mark the elected broker as controller and persist
6. Return the elected broker
```

**Broker failure handling flow:**

```
1. Look up the failed broker by ID
2. Record whether it was the controller
3. Clear its controller flag and persist
4. If it was the controller:
   trigger re-election via electController()
```

### 8.6 RetentionService

Message retention and cleanup -- enforces time-based retention and log compaction.

**runCleanup() flow (time-based retention):**

```
1. Get all CommitLogs for the topic
2. For each CommitLog:
   a. Get all messages from the log
   b. For each message, check StorageStrategy.shouldRetain(msg, retentionMs)
   c. Find the highest offset of expired messages -> cutoffOffset
   d. CommitLog.truncateBefore(cutoffOffset)
   e. Count removed = beforeSize - afterSize
3. Return total removed across all partitions
```

**runCompaction() flow (log compaction):**

```
1. Get all CommitLogs for the topic
2. For each CommitLog:
   a. Get all messages from the log
   b. StorageStrategy.compact(messages) -> returns deduplicated list
   c. If messages were removed:
      truncate all and log the removal count
3. Return total removed across all partitions
```

### 8.7 MetricsService

Per-topic produce/consume event tracking.

```java
public class MetricsService {
    private final Map<String, QueueMetrics> topicMetrics; // ConcurrentHashMap

    public void recordProduce(String topic, Message message) {
        topicMetrics.computeIfAbsent(topic, k -> new QueueMetrics()).recordIn(message);
    }

    public void recordConsume(String topic, Message message) {
        topicMetrics.computeIfAbsent(topic, k -> new QueueMetrics()).recordOut(message);
    }
}
```

**Dashboard output** prints a formatted table with per-topic metrics:
- Messages In / Out
- Bytes In / Out
- Consumer Lag
- Produce Rate (messages/second)

---

## 9. Concurrency Considerations

### 9.1 Thread Safety by Layer

| Component                      | Mechanism                                    | Why                                    |
|-------------------------------|----------------------------------------------|----------------------------------------|
| `CommitLog`                   | `synchronized` methods + `AtomicLong`        | Serialize reads/writes per partition   |
| `PartitionManager`           | `ConcurrentHashMap`                          | Lock-free partition lookup             |
| `ConsumerGroupCoordinator`   | `ConcurrentHashMap` for groups and offsets   | Concurrent group and offset access     |
| `InMemory*Repository`        | `ConcurrentHashMap`                          | Thread-safe CRUD without ext. locks    |
| `MessageRouter`              | `AtomicInteger` for round-robin counter      | Lock-free counter increment            |
| `RoundRobinPartitioningStrategy` | `AtomicInteger`                          | Lock-free partition cycling            |
| `ExactlyOnceDeliveryStrategy` | `ConcurrentHashMap.newKeySet()`             | Thread-safe dedup set                  |
| `AtLeastOnceDeliveryStrategy` | `ThreadLocalRandom`                         | Per-thread random (no contention)      |
| `MetricsService`             | `ConcurrentHashMap` + `computeIfAbsent`      | Atomic metric initialization           |
| `QueueMetrics`               | Non-synchronized (single-writer assumed)     | Counters updated by one service thread |

### 9.2 Synchronization Boundaries

```
  Producer Thread 1 ----+
                         |
  Producer Thread 2 ----+----> CommitLog.append() [synchronized]
                         |         |
  Producer Thread 3 ----+         v
                              AtomicLong.getAndIncrement() [lock-free]
                                   |
                                   v
                              log.add(message)
                              [happens inside synchronized block]

  Consumer Thread 1 ----+
                         |
  Consumer Thread 2 ----+----> CommitLog.read() [synchronized]
                         |
                         +----> ConsumerGroupCoordinator
                                   .getCommittedOffset() [ConcurrentHashMap.getOrDefault]
```

### 9.3 Key Concurrency Insight

Each CommitLog is synchronized independently. This means:
- Writes to partition 0 do NOT block writes to partition 1
- The unit of serialization is the partition, not the broker
- This is why partitions are the unit of parallelism in Kafka

```
  Topic "orders" with 3 partitions:

  CommitLog(orders-0): synchronized independently
  CommitLog(orders-1): synchronized independently   <-- no contention between partitions
  CommitLog(orders-2): synchronized independently
```

### 9.4 Potential Improvements for Production

| Current Design                           | Production Improvement                           |
|------------------------------------------|--------------------------------------------------|
| `synchronized` on CommitLog              | `ReadWriteLock` (concurrent reads, exclusive writes) |
| In-memory `ArrayList` storage            | Memory-mapped files (`MappedByteBuffer`)         |
| Single `QueueMetrics` without sync       | `LongAdder` for high-contention counters         |
| `ConcurrentHashMap` for offsets          | Kafka's `__consumer_offsets` internal topic       |
| Rebalance blocks caller thread           | Async rebalance with generation IDs              |

---

## 10. SOLID Principles Mapping

### S -- Single Responsibility Principle

| Class                        | Single Responsibility                               |
|------------------------------|-----------------------------------------------------|
| `CommitLog`                  | Append-only log storage for one partition            |
| `PartitionManager`          | Registry of partition commit logs                    |
| `ConsumerGroupCoordinator`  | Group lifecycle, rebalancing, offset tracking        |
| `MessageRouter`             | Determine target partition for a message             |
| `ReplicationEngine`         | ISR replication logic                                |
| `TopicService`              | Topic CRUD                                           |
| `ProducerService`           | Message production pipeline                          |
| `ConsumerService`           | Message consumption and group management             |
| `BrokerService`             | Broker cluster management and controller election    |
| `RetentionService`          | Retention cleanup and log compaction                 |
| `MetricsService`            | Throughput and lag tracking                           |

### O -- Open-Closed Principle

New algorithms can be added without modifying existing code:

```
New partitioning strategy:
  1. Create class implementing PartitioningStrategy
  2. Set via AppConfig.setPartitioningStrategy()
  3. Zero changes to ProducerService, MessageRouter, or any other class

New delivery guarantee:
  1. Create class implementing DeliveryStrategy
  2. Set via AppConfig.setDeliveryStrategy()
  3. Zero changes to ConsumerService

New storage/compaction policy:
  1. Create class implementing StorageStrategy
  2. Set via AppConfig.setStorageStrategy()
  3. Zero changes to RetentionService
```

### L -- Liskov Substitution Principle

All repository implementations are interchangeable:

```
TopicRepository repo = new InMemoryTopicRepository();
// Could be replaced with:
TopicRepository repo = new PostgresTopicRepository();
TopicRepository repo = new ZooKeeperTopicRepository();
// No code changes in TopicService needed
```

All strategy implementations are interchangeable:

```
PartitioningStrategy strategy = new HashPartitioningStrategy();
// Could be replaced with:
PartitioningStrategy strategy = new RoundRobinPartitioningStrategy();
PartitioningStrategy strategy = new ConsistentHashPartitioningStrategy();
// No code changes in ProducerService needed
```

### I -- Interface Segregation Principle

- `PartitioningStrategy` has 2 methods (assignPartition, getStrategyName)
- `DeliveryStrategy` has 3 methods (deliver, getGuarantee, getStrategyName)
- `StorageStrategy` has 3 methods (shouldRetain, compact, getStrategyName)
- Each repository interface exposes only the operations its clients need
- `BrokerRepository` adds `findController()` and `findAlive()` which are broker-specific

### D -- Dependency Inversion Principle

All services depend on abstractions, not concretions:

```java
// ProducerService depends on interface, not InMemoryTopicRepository
public ProducerService(
    PartitionManager partitionManager,
    PartitioningStrategy partitioningStrategy,    // interface
    ReplicationEngine replicationEngine,
    TopicRepository topicRepo                     // interface
)

// ConsumerService depends on interface
public ConsumerService(
    PartitionManager partitionManager,
    ConsumerGroupCoordinator coordinator,
    DeliveryStrategy deliveryStrategy             // interface
)

// RetentionService depends on interface
public RetentionService(
    PartitionManager partitionManager,
    StorageStrategy storageStrategy               // interface
)
```

`AppConfig` is the only class that knows about concrete implementations.

---

## 11. Sample Workflows

### 11.1 End-to-End Produce Workflow

```
Producer Client
    |
    v
[1] MessageQueueController.produce(record, ackMode)
    |
    v
[2] MessageQueueService.produce(record, ackMode)         -- Facade
    |
    +---> [3] ProducerService.send(record, ackMode)
    |         |
    |         +---> [4] TopicRepository.findByName(topicName)
    |         |         -> Topic{partitions=3, replication=2}
    |         |
    |         +---> [5] resolvePartition(record, partitionCount=3)
    |         |         -> HashPartitioningStrategy.assignPartition("order-123", 3)
    |         |         -> Math.abs("order-123".hashCode()) % 3 = 1
    |         |
    |         +---> [6] PartitionManager.getPartition("orders", 1)
    |         |         -> CommitLog{topic=orders, partition=1}
    |         |
    |         +---> [7] Message.Builder("orders", payload).key("order-123").build()
    |         |
    |         +---> [8] CommitLog.append(message)
    |         |         -> synchronized: offset=5 assigned, message appended
    |         |
    |         +---> [9] ReplicationEngine.replicate(msg, partition, AckMode.LEADER)
    |         |         -> Leader broker-0 acknowledged, offset=5
    |         |
    |         +---> return offset=5
    |
    +---> [10] MetricsService.recordProduce("orders", message)
    |           -> QueueMetrics.recordIn(message)
    |           -> messagesIn++, bytesIn += size, lag recalculated
    |
    v
return offset=5 to client
```

### 11.2 End-to-End Consume Workflow

```
Consumer Client
    |
    v
[1] MessageQueueController.consume(groupId, topic, partition, maxMessages)
    |
    v
[2] MessageQueueService.consume("order-group", "orders", 0, 10)   -- Facade
    |
    +---> [3] ConsumerService.poll("order-group", "orders", 0, 10)
    |         |
    |         +---> [4] ConsumerGroupCoordinator
    |         |         .getCommittedOffset("order-group", "orders", 0)
    |         |         -> returns 3 (last committed offset)
    |         |
    |         +---> [5] PartitionManager.getPartition("orders", 0)
    |         |         -> CommitLog{topic=orders, partition=0, size=8}
    |         |
    |         +---> [6] CommitLog.read(fromOffset=3, maxMessages=10)
    |         |         -> synchronized: returns messages[3..7] (5 messages)
    |         |
    |         +---> [7] Convert each Message to immutable ConsumerRecord
    |         |         -> new ConsumerRecord(topic, partition, offset, key, value, headers, ts)
    |         |
    |         +---> return List<ConsumerRecord> (5 records)
    |
    +---> [8] For each ConsumerRecord:
    |           MetricsService.recordConsume("orders", message)
    |           -> QueueMetrics.recordOut(message)
    |
    v
return 5 ConsumerRecords to client
    |
    v
[9] Client processes records
    |
    v
[10] MessageQueueController.commit("order-group", "orders", 0, offset=8)
     -> ConsumerGroupCoordinator.commitOffset("order-group", "orders", 0, 8)
```

### 11.3 Consumer Group Rebalance Workflow

```
[1] ConsumerService.subscribe("analytics-group", "consumer-A", "events", 4)
    |
    +---> [2] Coordinator.createGroup("analytics-group")
    |         -> ConsumerGroup{groupId=analytics-group}
    |
    +---> [3] group.subscribe("events")
    |         -> subscribedTopics = {"events"}
    |
    +---> [4] ConsumerInstance("consumer-A", "analytics-group", "localhost")
    |
    +---> [5] Coordinator.joinGroup("analytics-group", consumer-A)
    |         -> group.addMember(consumer-A)
    |
    +---> [6] Coordinator.rebalance("analytics-group", partitionCount=4)
              |
              +---> consumerIds = ["consumer-A"] (sorted)
              +---> partitionsPerConsumer = 4/1 = 4, remainder = 0
              +---> consumer-A -> [0, 1, 2, 3]

[7] ConsumerService.subscribe("analytics-group", "consumer-B", "events", 4)
    |
    +---> [8] Coordinator.joinGroup("analytics-group", consumer-B)
    |
    +---> [9] Coordinator.rebalance("analytics-group", partitionCount=4)
              |
              +---> consumerIds = ["consumer-A", "consumer-B"] (sorted)
              +---> partitionsPerConsumer = 4/2 = 2, remainder = 0
              +---> consumer-A -> [0, 1]
              +---> consumer-B -> [2, 3]

[10] ConsumerService.unsubscribe("analytics-group", "consumer-B")
     |
     +---> [11] Coordinator.leaveGroup("analytics-group", "consumer-B")
     |
     +---> [12] Coordinator.rebalance("analytics-group", partitionCount=2)
               |
               +---> consumerIds = ["consumer-A"] (sorted)
               +---> consumer-A -> [0, 1]
```

### 11.4 Broker Failure and Controller Re-election

```
Initial state:
  broker-1: controller=YES, alive, leading [orders-0, events-0]
  broker-2: controller=NO,  alive, leading [orders-1, events-1]
  broker-3: controller=NO,  alive, leading [orders-2, events-2]

[1] broker-1 fails (heartbeat stops)
    |
    v
[2] BrokerService.handleBrokerFailure("broker-1")
    |
    +---> [3] broker-1.setController(false)
    +---> [4] wasController = true
    +---> [5] Trigger re-election: electController()
              |
              +---> [6] Clear existing controller (already cleared)
              +---> [7] Get alive brokers: [broker-2, broker-3]
              +---> [8] Sort by ID: [broker-2, broker-3]
              +---> [9] Elect lowest ID: broker-2
              +---> [10] broker-2.setController(true)

After re-election:
  broker-1: controller=NO,  DEAD
  broker-2: controller=YES, alive    <-- new controller
  broker-3: controller=NO,  alive
```

### 11.5 Log Compaction Workflow

```
Before compaction (partition 0):
  Offset | Key      | Value
  -------+----------+------------------
  0      | user-1   | {"name":"Karan","v":1}
  1      | user-2   | {"name":"Alex","v":1}
  2      | user-1   | {"name":"Karan","v":2}     <-- supersedes offset 0
  3      | user-1   | {"name":"Karan","v":3}     <-- supersedes offset 2
  4      | user-2   | {"name":"Alex","v":2}      <-- supersedes offset 1

[1] RetentionService.runCompaction("user-profiles")
    |
    +---> [2] Get CommitLog for partition 0
    +---> [3] Get all 5 messages
    +---> [4] LogCompactionStrategy.compact(messages)
              |
              +---> [5] Null-key messages: (none)
              +---> [6] Latest per key:
              |         user-1 -> offset 3 (highest)
              |         user-2 -> offset 4 (highest)
              +---> [7] Return [offset-3, offset-4]
    |
    +---> [8] removed = 5 - 2 = 3 messages

After compaction:
  Offset | Key      | Value
  -------+----------+------------------
  3      | user-1   | {"name":"Karan","v":3}
  4      | user-2   | {"name":"Alex","v":2}
```

---

## 12. Design Patterns Used

| # | Pattern    | GoF Category | Key Class(es)                                          | Purpose                                        |
|---|------------|--------------|--------------------------------------------------------|------------------------------------------------|
| 1 | Strategy   | Behavioral   | `PartitioningStrategy`, `DeliveryStrategy`, `StorageStrategy` | Swap partitioning/delivery/storage algorithms  |
| 2 | Builder    | Creational   | `Message.Builder`                                      | Fluent construction of complex Message objects |
| 3 | Factory    | Creational   | `AppConfig`                                            | Centralized dependency wiring and lazy init    |
| 4 | Repository | Architectural| `TopicRepository`, `BrokerRepository`, etc.            | Decouple storage from business logic           |
| 5 | Facade     | Structural   | `MessageQueueService`                                  | Unified API for 6 subsystems                   |
| 6 | Observer   | Behavioral   | `ConsumerGroupCoordinator` rebalancing                 | React to membership changes                    |
| 7 | State      | Behavioral   | `Offset` lifecycle, `BrokerNode` controller flag       | Track consumer progress and broker roles       |
| 8 | Singleton  | Creational   | `AppConfig` lazy initialization                        | Single composition root for the system         |

---

## 13. Extensibility Points

### 13.1 Adding a New Partitioning Strategy

```
1. Create: ConsistentHashPartitioningStrategy implements PartitioningStrategy
2. Implement: assignPartition(key, partitionCount) using consistent hash ring
3. Register: AppConfig.setPartitioningStrategy(new ConsistentHashPartitioningStrategy())
4. Zero changes to: ProducerService, MessageRouter, TopicService, or any other class
```

### 13.2 Adding a New Delivery Strategy

```
1. Create: AtMostOnceDeliveryStrategy implements DeliveryStrategy
2. Implement: deliver() commits offset before processing (fire-and-forget)
3. Register: AppConfig.setDeliveryStrategy(new AtMostOnceDeliveryStrategy())
4. Zero changes to: ConsumerService
```

### 13.3 Adding a New Storage Strategy

```
1. Create: SizeBasedRetentionStrategy implements StorageStrategy
2. Implement: shouldRetain() checks cumulative bytes; compact() trims by size
3. Register: AppConfig.setStorageStrategy(new SizeBasedRetentionStrategy())
4. Zero changes to: RetentionService
```

### 13.4 Adding a New Repository Implementation

```
1. Create: PostgresTopicRepository implements TopicRepository
2. Implement: all CRUD methods using JDBC/JPA
3. Modify: AppConfig to return PostgresTopicRepository in getTopicRepository()
4. Zero changes to: TopicService, ProducerService, or any service
```

### 13.5 Adding a New Exception Type

```
1. Create: QuotaExceededException extends MessageQueueException
2. Add fields: topicName, quotaLimit, currentUsage
3. Throw from: ProducerService.send() when rate/size quota exceeded
4. Catch in: MessageQueueController for appropriate error response
```

### 13.6 Swapping Strategies at Runtime

```java
AppConfig config = new AppConfig();

// Start with hash partitioning (default)
config.getProducerService().send(record, AckMode.LEADER);

// Switch to round-robin partitioning at runtime
config.setPartitioningStrategy(new RoundRobinPartitioningStrategy());
// AppConfig automatically clears: producerService, messageQueueService, controller
// Next call lazily rebuilds the graph with the new strategy

config.getProducerService().send(record, AckMode.LEADER);
// Now uses round-robin instead of hash partitioning
```

**AppConfig strategy setter clears dependents:**
```
setPartitioningStrategy() -> clears: producerService, messageQueueService, controller, display
setDeliveryStrategy()     -> clears: consumerService, messageQueueService, controller, display
setStorageStrategy()      -> clears: retentionService, messageQueueService, controller, display
```

This ensures the object graph is rebuilt lazily with the new strategy on the next access.

### 13.7 Adding New Metrics

```
1. Add fields to QueueMetrics (e.g., p99Latency, errorCount)
2. Add recording methods (e.g., recordError())
3. Update MetricsService to call new recording methods
4. Update MessageQueueStatsDisplay.printDashboard() to show new metrics
```

### 13.8 Adding Batch Consumption

```
1. Add ConsumerService.pollBatch(groupId, topic, partitions[], maxMessages)
2. Iterate partitions and collect ConsumerRecords from each
3. Add MessageQueueService.consumeBatch() as Facade method
4. Add MessageQueueController.consumeBatch() endpoint
```

---

## Appendix A: Exception Hierarchy

```
RuntimeException
  |
  +-- MessageQueueException (base)
        |
        +-- TopicNotFoundException
        |     - topicName: String
        |     - thrown by: ProducerService.send(), ConsumerService.poll()
        |
        +-- PartitionNotFoundException
        |     - topicName: String
        |     - partitionId: int
        |     - thrown by: PartitionManager lookups
        |
        +-- ConsumerGroupException
              - groupId: String
              - thrown by: ConsumerGroupCoordinator validation
```

---

## Appendix B: Configuration Constants

| Constant                        | Value          | Location           | Kafka Equivalent        |
|---------------------------------|----------------|--------------------|-------------------------|
| Default retention (ms)          | 604,800,000    | `Topic`            | `retention.ms`          |
| Heartbeat timeout               | 30 seconds     | `BrokerService`    | `broker.heartbeat.interval.ms` |
| Default replication factor      | 3              | `AppConfig`        | `default.replication.factor` |
| Default partition count         | 3              | `MessageRouter`    | `num.partitions`        |
| At-least-once max retries       | 3              | `AtLeastOnce...`   | `retries`               |
| At-least-once failure rate      | 5%             | `AtLeastOnce...`   | N/A (simulation)        |
| Default time retention          | 7 days         | `TimeBased...`     | `retention.ms`          |

---

## Appendix C: File Count Summary

| Package       | Files | Interfaces | Implementations | Enums |
|---------------|-------|------------|-----------------|-------|
| model         | 15    | 0          | 13              | 2     |
| engine        | 5     | 0          | 5               | 0     |
| repository    | 8     | 4          | 4               | 0     |
| service       | 7     | 0          | 7               | 0     |
| strategy      | 9     | 3          | 6               | 0     |
| controller    | 1     | 0          | 1               | 0     |
| config        | 1     | 0          | 1               | 0     |
| display       | 1     | 0          | 1               | 0     |
| exception     | 4     | 0          | 4               | 0     |
| **Total**     | **51**| **7**      | **42**          | **2** |

---

## Appendix D: Kafka Concept Mapping

This table maps each class in the codebase to its real-world Kafka equivalent.

| This Codebase                   | Kafka Equivalent                         | Notes                                          |
|---------------------------------|------------------------------------------|-------------------------------------------------|
| `CommitLog`                     | Log Segment (`LogSegment`)               | Kafka uses memory-mapped files; we use ArrayList |
| `PartitionManager`             | `LogManager`                             | Manages all log directories per broker          |
| `ConsumerGroupCoordinator`     | `GroupCoordinator`                       | Kafka uses heartbeat protocol + generation IDs  |
| `MessageRouter`                | `DefaultPartitioner`                     | Kafka uses murmur2 hash; we use hashCode()      |
| `ReplicationEngine`            | `ReplicaManager`                         | Kafka uses ISR + HW/LEO watermarks              |
| `TopicRepository`              | ZooKeeper `/brokers/topics` znode        | KRaft replaces ZK with internal Raft log        |
| `BrokerRepository`             | ZooKeeper `/brokers/ids` znode           | KRaft replaces ZK with internal Raft log        |
| `OffsetRepository`             | `__consumer_offsets` internal topic      | Kafka stores offsets as compacted topic messages |
| `ConsumerGroupRepository`      | ZooKeeper `/consumers` znode             | KRaft replaces ZK with internal Raft log        |
| `MessageQueueService`          | `KafkaProducer` + `KafkaConsumer`        | Our Facade unifies both producer and consumer   |
| `MessageQueueController`       | Kafka REST Proxy (Confluent)             | HTTP API over Kafka protocol                    |
| `TopicService`                 | `AdminClient.createTopics()`             | Kafka's admin API for topic management          |
| `ProducerService`              | `KafkaProducer.send()`                   | Includes partitioner, serializer, accumulator   |
| `ConsumerService`              | `KafkaConsumer.poll()` + `.commitSync()` | Includes offset management and group protocol   |
| `BrokerService`                | Controller broker (KRaft)                | Handles leader election and partition assignment |
| `RetentionService`             | `LogCleaner` thread                      | Background thread for retention + compaction    |
| `MetricsService`               | JMX MBeans / Prometheus metrics          | Kafka exposes metrics via JMX                   |
| `Message`                      | `RecordBatch` entry                      | Kafka uses a binary format with varint encoding |
| `Topic`                        | Topic metadata in controller              | Includes partition count, replication factor    |
| `Partition`                    | `Partition` (leader + ISR tracking)      | Kafka tracks HW, LEO, leader epoch              |
| `BrokerNode`                   | `BrokerInfo`                             | Kafka brokers register with the controller      |
| `ConsumerGroup`                | Consumer group metadata                  | Stored in `__consumer_offsets` topic            |
| `ConsumerInstance`             | `MemberDescription`                      | Includes assignment, heartbeat, session timeout |
| `ConsumerRecord`               | `ConsumerRecord<K,V>`                    | Same concept; Kafka adds deserializer types     |
| `ProducerRecord`               | `ProducerRecord<K,V>`                    | Same concept; Kafka adds serializer types       |
| `Offset`                       | `OffsetAndMetadata`                      | Kafka includes optional metadata string         |
| `MessageBatch`                 | `RecordBatch`                            | Kafka batches for network + disk efficiency     |
| `QueueMetrics`                 | `KafkaMetric`                            | Kafka uses windowed rate metrics                |
| `RetentionPolicy`             | `TopicConfig` (retention.ms, bytes, etc.) | Kafka topic-level configs                       |
| `AckMode`                      | `ProducerConfig.ACKS_CONFIG`             | Values: "0", "1", "all"                        |
| `DeliveryGuarantee`           | Delivery semantics (documentation)        | Not a Kafka class; a design property            |
| `PartitionAssignment`         | `TopicPartition` + consumer assignment    | Kafka uses `ConsumerPartitionAssignor`          |
| `AppConfig`                    | Spring Boot auto-configuration            | Kafka clients use `Properties` for config       |

---

## Appendix E: Data Flow Diagrams

### E.1 Complete Write Path

```
  Producer Client
       |
       | ProducerRecord{topic="orders", key="order-123", value="{...}"}
       v
  +----+----+
  | Controller |  [REST Layer]
  +----+----+
       |
       v
  +----+----+
  | MQ Facade |  [Facade Pattern]
  +----+----+
       |
       v
  +----+----+
  | Producer |   [Service Layer]
  | Service  |
  +----+----+
       |
       +----> TopicRepository.findByName("orders")
       |        -> Topic{name=orders, partitions=3, replication=2}
       |
       +----> PartitioningStrategy.assignPartition("order-123", 3)
       |        -> HashPartitioning: abs("order-123".hashCode()) % 3 = 1
       |
       +----> PartitionManager.getPartition("orders", 1)
       |        -> CommitLog{topic=orders, partition=1}
       |
       +----> Message.Builder("orders", "{...}")
       |        .key("order-123").partition(1).build()
       |
       +----> CommitLog.append(message)
       |        synchronized {
       |          offset = currentOffset.getAndIncrement()  // 5
       |          message.setOffset(5)
       |          log.add(message)
       |        }
       |        return 5
       |
       +----> ReplicationEngine.replicate(msg, partition, AckMode.LEADER)
       |        switch(ackMode) {
       |          LEADER -> leader ack, return true
       |        }
       |
       +----> return offset=5
       |
       v
  +----+----+
  | MQ Facade |
  +----+----+
       |
       +----> MetricsService.recordProduce("orders", message)
       |        QueueMetrics.recordIn(msg)
       |          messagesIn: 5 -> 6
       |          bytesIn: 120 -> 145
       |          lag: 3 -> 4
       |
       v
  return offset=5 to client
```

### E.2 Complete Read Path

```
  Consumer Client
       |
       | consume(groupId="order-group", topic="orders", partition=0, max=10)
       v
  +----+----+
  | Controller |  [REST Layer]
  +----+----+
       |
       v
  +----+----+
  | MQ Facade |  [Facade Pattern]
  +----+----+
       |
       v
  +----+----+
  | Consumer |   [Service Layer]
  | Service  |
  +----+----+
       |
       +----> ConsumerGroupCoordinator.getCommittedOffset("order-group", "orders", 0)
       |        key = "order-group-orders-0"
       |        committedOffsets.getOrDefault(key, 0L) -> 3
       |
       +----> PartitionManager.getPartition("orders", 0)
       |        -> CommitLog{topic=orders, partition=0, size=8}
       |
       +----> CommitLog.read(fromOffset=3, maxMessages=10)
       |        synchronized {
       |          start=3, end=min(3+10, 8)=8
       |          return log.subList(3, 8)  // 5 messages
       |        }
       |
       +----> For each Message in [3..7]:
       |        new ConsumerRecord(topic, partition, offset, key, value, headers, ts)
       |        // immutable snapshot
       |
       +----> return List<ConsumerRecord> (5 records)
       |
       v
  +----+----+
  | MQ Facade |
  +----+----+
       |
       +----> For each ConsumerRecord:
       |        MetricsService.recordConsume("orders", message)
       |          QueueMetrics.recordOut(msg)
       |            messagesOut: 2 -> 3 (per record)
       |            lag: 4 -> 3 (per record)
       |
       v
  return 5 ConsumerRecords to client
       |
       v
  Client processes records
       |
       v
  Client calls commit("order-group", "orders", 0, offset=8)
       |
       v
  ConsumerGroupCoordinator.commitOffset("order-group", "orders", 0, 8)
    committedOffsets.put("order-group-orders-0", 8)
```

### E.3 Retention Cleanup Flow

```
  Scheduled Cleanup Trigger
       |
       v
  RetentionService.runCleanup("orders", retentionMs=604800000)
       |
       +----> PartitionManager.getPartitionsForTopic("orders")
       |        -> [CommitLog(0), CommitLog(1), CommitLog(2)]
       |
       +----> For each CommitLog:
       |        |
       |        +----> commitLog.getMessages()
       |        |        -> [msg@0, msg@1, msg@2, msg@3, msg@4]
       |        |
       |        +----> For each message:
       |        |        StorageStrategy.shouldRetain(msg, 604800000)
       |        |          TimeBasedRetention:
       |        |            age = now - msg.timestamp
       |        |            retain = (age <= 604800000ms)
       |        |          msg@0: age=605000000ms > retention -> EXPIRED
       |        |          msg@1: age=604900000ms > retention -> EXPIRED
       |        |          msg@2: age=100000ms    < retention -> RETAINED
       |        |          msg@3: age=50000ms     < retention -> RETAINED
       |        |          msg@4: age=10000ms     < retention -> RETAINED
       |        |
       |        +----> cutoffOffset = max expired offset + 1 = 2
       |        |
       |        +----> commitLog.truncateBefore(2)
       |                 log.removeIf(msg -> msg.getOffset() < 2)
       |                 // removes msg@0 and msg@1
       |
       +----> return totalRemoved = 2
```

---

## Appendix F: Interview Quick Reference

### Key Numbers to Remember

| Metric                              | Value                      |
|--------------------------------------|---------------------------|
| Total Java files                     | 51                        |
| Model classes                        | 15 (13 classes + 2 enums) |
| Engine classes                       | 5                         |
| Repository interfaces/impls          | 4 + 4 = 8                |
| Service classes                      | 7                         |
| Strategy interfaces/impls            | 3 + 6 = 9                |
| Design patterns used                 | 8                         |
| Strategy families                    | 3 (partitioning, delivery, storage) |
| Controller dependencies (with Facade)| 2 (MQService + MetricsService) |
| Controller dependencies (without)   | 6 (all services directly) |
| Default retention                    | 7 days (604,800,000 ms)   |
| Default replication factor           | 3                         |
| AckMode options                      | 3 (NONE=0, LEADER=1, ALL=-1) |
| Delivery guarantees                  | 3 (at-most, at-least, exactly-once) |

### Top 5 Talking Points

1. **CommitLog is the core** -- Append-only, O(1) append, O(1) read by offset, synchronized per partition
2. **Partitions are the unit of parallelism** -- Not topics, not brokers; each partition has its own lock
3. **Consumer groups with rebalancing** -- Range assignment redistributes partitions on join/leave
4. **Three Strategy families** -- Partitioning, delivery, storage; all swappable via AppConfig
5. **Facade simplifies API** -- Controller has 2 dependencies instead of 6; metrics tracked centrally

### Common Follow-Up Questions

| Question | Answer Pointer |
|----------|----------------|
| "Why not use a database for the commit log?" | Append-only semantics are faster than B-tree updates; sequential I/O on disk |
| "How do you handle consumer lag?" | `lag = latestOffset - committedOffset`; monitored via MetricsService |
| "What if a consumer crashes mid-processing?" | At-least-once: messages re-read from committed offset; consumer must be idempotent |
| "How does exactly-once work?" | Idempotent producer (sequence numbers) + transactional consumer (atomic commit) |
| "What happens during a network partition?" | ISR shrinks; acks=all blocks if ISR < min.insync.replicas; unclean leader election risk |
| "How do you scale the system?" | Add partitions for throughput; add consumers (up to partition count) for consumption |
| "Why Range assignment over Sticky?" | Range is simpler and deterministic; Sticky minimizes partition movement but more complex |

---

## Appendix G: Complexity Analysis

| Operation                        | Time Complexity | Space Complexity | Notes                     |
|---------------------------------|-----------------|------------------|---------------------------|
| `CommitLog.append()`            | O(1) amortized  | O(1)             | ArrayList.add()           |
| `CommitLog.read(offset, n)`     | O(n)            | O(n)             | subList + copy            |
| `CommitLog.truncateBefore()`    | O(m)            | O(1)             | m = messages to remove    |
| `PartitionManager.getPartition()` | O(1)         | O(1)             | ConcurrentHashMap.get()   |
| `PartitionManager.getPartitionsForTopic()` | O(p) | O(p)           | p = total partitions      |
| `HashPartitioning.assignPartition()` | O(1)      | O(1)             | hashCode() + mod          |
| `RoundRobin.assignPartition()`  | O(1)            | O(1)             | AtomicInteger increment   |
| `Coordinator.rebalance()`       | O(c * p/c)      | O(p)             | c=consumers, p=partitions |
| `Coordinator.commitOffset()`    | O(1)            | O(1)             | ConcurrentHashMap.put()   |
| `Coordinator.getCommittedOffset()` | O(1)         | O(1)             | ConcurrentHashMap.get()   |
| `LogCompaction.compact()`       | O(m)            | O(k)             | m=messages, k=unique keys |
| `TimeBasedRetention.compact()`  | O(m)            | O(r)             | m=messages, r=retained    |
| `BrokerService.electController()` | O(b log b)   | O(b)             | b=brokers (sort + filter) |
| `TopicRepository.findByName()`  | O(1)            | O(1)             | ConcurrentHashMap.get()   |
| `TopicService.createTopic()`    | O(p)            | O(p)             | p=partition count         |

---

## Appendix H: Thread Safety Verification Checklist

| Component | Thread-Safe? | Mechanism | Risk if Violated |
|-----------|-------------|-----------|------------------|
| `CommitLog.append()` | Yes | `synchronized` | Duplicate offsets; lost messages |
| `CommitLog.read()` | Yes | `synchronized` | Partial reads; IndexOutOfBounds |
| `CommitLog.truncateBefore()` | Yes | `synchronized` | Concurrent modification |
| `PartitionManager` | Yes | `ConcurrentHashMap` | Lost partitions |
| `ConsumerGroupCoordinator.groups` | Yes | `ConcurrentHashMap` | Lost groups |
| `ConsumerGroupCoordinator.offsets` | Yes | `ConcurrentHashMap` | Lost offset commits |
| `InMemoryTopicRepository` | Yes | `ConcurrentHashMap` | Lost topics |
| `InMemoryBrokerRepository` | Yes | `ConcurrentHashMap` | Lost brokers |
| `InMemoryOffsetRepository` | Yes | `ConcurrentHashMap` | Lost offsets |
| `InMemoryConsumerGroupRepository` | Yes | `ConcurrentHashMap` | Lost groups |
| `MessageRouter.roundRobinCounter` | Yes | `AtomicInteger` | Duplicate partition assignment |
| `RoundRobinPartitioningStrategy` | Yes | `AtomicInteger` | Duplicate partition assignment |
| `ExactlyOnceDeliveryStrategy.deliveredIds` | Yes | `ConcurrentHashMap.newKeySet()` | Duplicate delivery |
| `AtLeastOnceDeliveryStrategy` | Yes | `ThreadLocalRandom` (per-thread) | N/A |
| `MetricsService.topicMetrics` | Yes | `ConcurrentHashMap` | Lost metrics |
| `QueueMetrics` counters | No | No synchronization | Stale counters under contention |
| `AppConfig` lazy fields | No | Single-threaded assumption | Duplicate object creation |
| `ConsumerGroup.members` | No | `HashMap` (not concurrent) | ConcurrentModificationException |
| `ConsumerGroup.assignments` | No | `HashMap` (not concurrent) | ConcurrentModificationException |

**Key finding:** `ConsumerGroup` internal maps (`members`, `assignments`) use `HashMap` -- not thread-safe. This is acceptable because all modifications go through the `ConsumerGroupCoordinator`, which serializes access at the service layer. In production, these would need `ConcurrentHashMap` or external synchronization.

---

## Appendix I: Controller and Display Layer

### I.1 MessageQueueController

The controller is a thin REST-like layer that delegates every operation to the `MessageQueueService` facade.

```java
public class MessageQueueController {
    private final MessageQueueService mqService;   // Facade
    private final MetricsService metricsService;   // Dashboard access

    // Topic management
    public Topic createTopic(String topicName, int partitions, int replicationFactor) {
        return mqService.createTopic(topicName, partitions, replicationFactor);
    }

    // Produce
    public long produce(ProducerRecord record, AckMode ackMode) {
        return mqService.produce(record, ackMode);
    }

    // Consume
    public List<ConsumerRecord> consume(String groupId, String topic, int partition, int max) {
        return mqService.consume(groupId, topic, partition, max);
    }

    // Commit offset
    public void commit(String groupId, String topicName, int partition, long offset) {
        mqService.commit(groupId, topicName, partition, offset);
    }

    // Subscribe consumer to topic
    public void subscribe(String groupId, String consumerId, String topic, int partitionCount) {
        mqService.subscribe(groupId, consumerId, topic, partitionCount);
    }

    // Metrics dashboard
    public void getDashboard() {
        metricsService.printDashboard();
    }
}
```

**REST endpoint mapping (conceptual):**

| Method | Endpoint                                 | Controller Method |
|--------|------------------------------------------|-------------------|
| POST   | `/topics`                                | `createTopic()`   |
| POST   | `/topics/{topic}/messages`               | `produce()`       |
| GET    | `/topics/{topic}/messages`               | `consume()`       |
| POST   | `/topics/{topic}/commit`                 | `commit()`        |
| POST   | `/consumer-groups/subscribe`             | `subscribe()`     |
| GET    | `/dashboard`                             | `getDashboard()`  |

### I.2 MessageQueueStatsDisplay

The display helper prints formatted console tables for system state inspection.

**Printed sections:**

| Section           | Columns                                               | Data Source                |
|-------------------|-------------------------------------------------------|----------------------------|
| Topics            | Name, Partitions, Replication, Retention              | `TopicService.getAllTopics()` |
| Partition Details | Partition ID, Messages, Earliest Offset, Latest Offset | `PartitionManager.getPartitionsForTopic()` |
| Consumer Groups   | Group ID, Members, Assigned Partitions, Lag           | `ConsumerGroupCoordinator.getAllGroups()` |
| Broker Cluster    | Broker ID, Host, Controller, Partitions Led           | `BrokerService.getAllBrokers()` |
| Message Log       | Offset, Key, Value, Timestamp                         | `CommitLog.read()` |
| Summary Stats     | Topics, Partitions, Messages, Groups, Brokers         | All services aggregated |

**Dependencies (8 constructor parameters):**

```java
public MessageQueueStatsDisplay(
    MessageQueueService mqService,
    TopicService topicService,
    ProducerService producerService,
    ConsumerService consumerService,
    BrokerService brokerService,
    MetricsService metricsService,
    PartitionManager partitionManager,
    ConsumerGroupCoordinator coordinator
)
```

**Lag computation:**
```java
private long computeGroupLag(ConsumerGroup group) {
    long totalLag = 0;
    for (List<PartitionAssignment> assignments : group.getAssignments().values()) {
        for (PartitionAssignment assignment : assignments) {
            var commitLog = partitionManager.getPartition(
                assignment.getTopicName(), assignment.getPartitionId());
            if (commitLog.isPresent()) {
                totalLag += commitLog.get().getLatestOffset();
            }
        }
    }
    return totalLag;
}
```

---

## Appendix J: Main Application Demo Structure

The `DistributedMessageQueueApp` runs 12 sequential demos that exercise every component.

| Demo | Name                          | Components Exercised                                |
|------|-------------------------------|-----------------------------------------------------|
| 1    | Produce and Consume           | ProducerService, ConsumerService, CommitLog         |
| 2    | Key-Based Partitioning        | HashPartitioningStrategy, PartitionManager          |
| 3    | Consumer Group Rebalancing    | ConsumerGroupCoordinator, ConsumerService           |
| 4    | Offset Management             | Offset commit/retrieve, consumer lag                |
| 5    | Ack Modes                     | ReplicationEngine with NONE, LEADER, ALL            |
| 6    | At-Least-Once Delivery        | AtLeastOnceDeliveryStrategy                         |
| 7    | Exactly-Once Delivery         | ExactlyOnceDeliveryStrategy, strategy swap          |
| 8    | Log Compaction                | LogCompactionStrategy, RetentionService             |
| 9    | Time-Based Retention          | TimeBasedRetentionStrategy, RetentionService        |
| 10   | Broker Cluster & Election     | BrokerService, controller election, failure         |
| 11   | Replication (ISR & Ack Modes) | ReplicationEngine, Partition ISR management         |
| 12   | Full Pipeline Overview        | MessageQueueStatsDisplay, MetricsService            |

**Startup sequence:**
```
1. Create AppConfig (composition root)
2. Register 3 broker nodes:
     broker-1 @ kafka-1.cluster:9092
     broker-2 @ kafka-2.cluster:9092
     broker-3 @ kafka-3.cluster:9092
3. Elect controller (broker with lowest ID = broker-1)
4. Run demo1 through demo12 sequentially
5. Print design summary
```

**Topics created across demos:**

| Topic Name       | Partitions | Replication | Created In |
|------------------|-----------|-------------|------------|
| orders           | 3         | 2           | Demo 1     |
| user-events      | 4         | 2           | Demo 2     |
| events           | 4         | 2           | Demo 3     |
| payments         | 2         | 2           | Demo 7     |
| user-profiles    | 1         | 1           | Demo 8     |
| replicated-topic | 2         | 3           | Demo 11    |

**Strategy swaps during execution:**

```
Initial state:
  Partitioning: HashPartitioningStrategy (default)
  Delivery:     AtLeastOnceDeliveryStrategy (default)
  Storage:      TimeBasedRetentionStrategy (default)

Demo 7: config.setDeliveryStrategy(new ExactlyOnceDeliveryStrategy())
  -> ConsumerService rebuilt with ExactlyOnce
  -> MessageQueueService rebuilt
  -> Controller rebuilt

Demo 8: config.setStorageStrategy(new LogCompactionStrategy())
  -> RetentionService rebuilt with LogCompaction
  -> MessageQueueService rebuilt
  -> Controller rebuilt
```

---

## Appendix K: API Contract Summary

### Produce API

```
Input:
  ProducerRecord {
    topic: String     (required)
    key: String       (optional -- null triggers round-robin)
    value: String     (required)
    partition: Integer (optional -- null triggers strategy-based assignment)
    headers: Map<String, String> (optional)
  }
  AckMode: NONE | LEADER | ALL

Output:
  long offset       (assigned by CommitLog)

Errors:
  IllegalStateException: topic not found
  IllegalStateException: partition not found
  IllegalArgumentException: partition out of range
```

### Consume API

```
Input:
  groupId: String       (consumer group identifier)
  topic: String         (topic to read from)
  partition: int        (partition index)
  maxMessages: int      (maximum records to return)

Output:
  List<ConsumerRecord> {
    topic: String
    partition: int
    offset: long
    key: String
    value: String
    headers: Map<String, String>  (immutable)
    timestamp: Instant
  }

Errors:
  IllegalStateException: partition not found
```

### Commit API

```
Input:
  groupId: String       (consumer group identifier)
  topicName: String     (topic name)
  partition: int        (partition index)
  offset: long          (offset to commit -- typically lastProcessedOffset + 1)

Output:
  void

Side Effects:
  ConsumerGroupCoordinator stores committed offset
  Next poll() starts from this offset
```

### Subscribe API

```
Input:
  groupId: String       (consumer group identifier)
  consumerId: String    (consumer instance identifier)
  topic: String         (topic to subscribe to)
  partitionCount: int   (total partitions for rebalance calculation)

Output:
  void

Side Effects:
  1. Consumer group created if not exists
  2. Group subscribed to topic
  3. Consumer instance joined to group
  4. Rebalance triggered -- partitions redistributed
```

### Create Topic API

```
Input:
  name: String              (unique topic name, not blank)
  partitions: int           (must be >= 1)
  replicationFactor: int    (must be >= 1)

Output:
  Topic {
    name: String
    partitionCount: int
    replicationFactor: int
    retentionMs: long       (default: 604,800,000 = 7 days)
    createdAt: Instant
    config: Map<String, String>
  }

Side Effects:
  1. Topic persisted in TopicRepository
  2. N CommitLogs created in PartitionManager (one per partition)

Errors:
  IllegalArgumentException: blank name, partitions < 1, replication < 1
  IllegalStateException: topic already exists
```

---

## Appendix L: Design Decisions and Rationale

| Decision | Rationale | Alternative Considered |
|----------|-----------|------------------------|
| `ArrayList` for CommitLog storage | O(1) random access by offset; simple; sufficient for in-memory demo | `LinkedList` (O(n) access), `MappedByteBuffer` (production) |
| `synchronized` on CommitLog methods | Simple; correct; sufficient for demo throughput | `ReadWriteLock` (concurrent reads), `StampedLock` (optimistic reads) |
| `AtomicLong` for offset generation | Lock-free; monotonically increasing; thread-safe | `synchronized` counter, database sequence |
| `ConcurrentHashMap` for repositories | Thread-safe without explicit locks; good read performance | `Collections.synchronizedMap` (coarser locking) |
| Range assignment for rebalancing | Simple; deterministic; matches Kafka's default | Sticky assignment (less partition movement), Round-robin assignment |
| Composite string keys ("group-topic-partition") | Simple; human-readable; debuggable | Dedicated key objects (type-safe but more classes) |
| `Optional` returns from repositories | Forces callers to handle missing data explicitly | Return null (NPE risk), throw exception (no graceful handling) |
| Static factory methods on `RetentionPolicy` | Self-documenting; named construction; no builder needed for 3 fields | Constructor overloading, Builder pattern |
| Private constructor on `Message` | Forces use of Builder; prevents incomplete construction | Public constructor with validation |
| RuntimeException hierarchy | Unchecked; no forced catches; clean service code | Checked exceptions (forces try-catch everywhere) |
| Lazy initialization in AppConfig | Pay only for what you use; simple null-check pattern | Eager init (simpler but wasteful), Spring DI (framework dependency) |
| Strategy setter cascade clears | Ensures graph consistency after swap; lazy rebuild | Manual rebuild required by caller (error-prone) |
