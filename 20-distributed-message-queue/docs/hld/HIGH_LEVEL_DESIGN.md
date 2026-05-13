# Distributed Message Queue -- High-Level Design

## Interview Guide

**Target Duration**: 30-45 minutes
**Difficulty**: Staff Engineer / L6+
**Format**: Structured walkthrough, whiteboard-friendly

---

## Table of Contents

1. [Problem Statement](#1-problem-statement)
2. [Scope](#2-scope)
3. [Requirements](#3-requirements)
4. [API Design](#4-api-design)
5. [Data Model](#5-data-model)
6. [High-Level Architecture](#6-high-level-architecture)
7. [Commit Log Deep Dive](#7-commit-log-deep-dive)
8. [Partitioning Strategies](#8-partitioning-strategies)
9. [Consumer Group Coordination & Rebalancing](#9-consumer-group-coordination--rebalancing)
10. [Offset Management](#10-offset-management)
11. [Ack Modes & Delivery Guarantees](#11-ack-modes--delivery-guarantees)
12. [Replication (ISR & Leader Election)](#12-replication-isr--leader-election)
13. [Log Compaction vs Retention](#13-log-compaction-vs-retention)
14. [Scaling Strategy](#14-scaling-strategy)
15. [Database Choices](#15-database-choices)
16. [CAP Analysis](#16-cap-analysis)
17. [Cloud Mapping](#17-cloud-mapping)
18. [Failure Scenarios](#18-failure-scenarios)
19. [Interview Walkthrough Script](#19-interview-walkthrough-script)

---

## 1. Problem Statement

Design a **Distributed Message Queue** (like Apache Kafka, RabbitMQ, Amazon SQS, or Apache Pulsar) that provides durable, ordered, high-throughput message delivery between producers and consumers in a distributed system.

### Why This Is Hard

1. **Scale**: A large platform generates millions of messages per second across thousands of topics. LinkedIn processes 7T+ messages/day through Kafka. The system must append messages at O(1) and deliver them with single-digit millisecond latency.
2. **Ordering**: Global ordering across all messages is a bottleneck (single writer). The system must provide per-partition ordering while allowing parallel consumption across partitions. The challenge is choosing the right partition key so that events that need to be ordered (e.g., all events for a single user) land in the same partition.
3. **Durability**: Messages cannot be lost, especially for financial transactions. Replication across multiple brokers with configurable acknowledgment modes (acks=0/1/all) provides durability guarantees at the cost of latency.
4. **Delivery Guarantees**: Different workloads require different guarantees. Metrics collection can tolerate loss (at-most-once). Order processing requires no loss (at-least-once). Payment processing requires no loss AND no duplicates (exactly-once). Supporting all three in a single system is architecturally complex.
5. **Consumer Coordination**: Multiple consumer instances must coordinate to ensure each partition is processed by exactly one consumer within a group. When consumers join or leave, partitions must be rebalanced without losing messages or processing them twice.
6. **Retention**: The system must support both time-based retention (delete messages older than 7 days) and log compaction (keep only the latest value per key). These are fundamentally different strategies with different storage, CPU, and semantic tradeoffs.

### Real-World Scale

| Metric | Mid-Size Platform | Large Platform (LinkedIn/Uber) |
|--------|-------------------|-------------------------------|
| Messages/second | 100K | 10M+ |
| Messages/day | 10B | 7T+ (LinkedIn) |
| Topics | 100-500 | 100K+ |
| Partitions | 10K | Millions |
| Brokers | 10-50 | 4000+ |
| Consumer groups | 50-200 | 10K+ |
| Storage per broker | 1-10 TB | 100+ TB |
| Replication factor | 3 | 3 |
| Retention period | 7 days | 7 days - unlimited |
| P99 produce latency | <10ms | <5ms |
| Throughput per broker | 100 MB/s | 600+ MB/s |

---

## 2. Scope

### In Scope

| Feature | Details |
|---------|---------|
| Commit Log | Append-only per-partition log with monotonic offsets, O(1) append, O(1) read by offset |
| Topic Management | Create/delete topics with configurable partition count and replication factor |
| Partitioning | Key-based hash partitioning (per-key ordering) and round-robin (max throughput) |
| Consumer Groups | Load-balanced consumption with range rebalancing on member join/leave |
| Offset Management | Per-group per-partition offset tracking, commit/lag calculation |
| Ack Modes | acks=0 (fire-and-forget), acks=1 (leader only), acks=all (all ISR) |
| Delivery Guarantees | At-least-once (default), exactly-once (idempotent consumer dedup) |
| Replication | ISR-based replication with leader election and min.insync.replicas |
| Log Compaction | Keep only the latest value per key (for changelogs, CDC, materialized views) |
| Time-Based Retention | Delete messages older than configurable window (default 7 days) |
| Broker Cluster | Multi-broker cluster with controller election (lowest ID wins) |
| Metrics & Monitoring | Per-topic throughput, byte rates, consumer lag tracking |

### Out of Scope

| Feature | Reason |
|---------|--------|
| Kafka Streams / KSQL | Stream processing framework -- separate system built on top of the queue |
| Schema Registry | Avro/Protobuf schema evolution -- orthogonal concern (Confluent Schema Registry) |
| Multi-datacenter replication | Cross-DC replication (MirrorMaker/Cluster Linking) adds complexity |
| Exactly-once transactions | Kafka's transactional API (begin/commit/abort across topics) is an advanced feature |
| Rack-aware replica placement | Datacenter topology awareness for replica spreading |
| Quota management | Per-client produce/consume rate limits |
| ACL / SASL authentication | Security layer -- orthogonal concern |

---

## 3. Requirements

### Functional Requirements

| # | Requirement | Details |
|---|------------|---------|
| FR-1 | Create topic | Create a named topic with N partitions and replication factor R |
| FR-2 | Produce message | Send a message to a topic with optional key (for partitioning) and ack mode |
| FR-3 | Consume messages | Poll messages from a partition starting from the consumer group's committed offset |
| FR-4 | Commit offset | Persist the consumer's progress for a (group, topic, partition) tuple |
| FR-5 | Subscribe to topic | Join a consumer group and receive partition assignments via rebalancing |
| FR-6 | Unsubscribe | Leave a consumer group, triggering rebalance for remaining members |
| FR-7 | Get consumer lag | Calculate how far behind a consumer group is on a specific partition |
| FR-8 | Run retention | Delete messages older than the configured retention window |
| FR-9 | Run compaction | Keep only the latest message per key, removing older versions |
| FR-10 | Cluster management | Register/deregister brokers, elect controller, handle broker failures |

### Non-Functional Requirements

| # | Requirement | Target |
|---|------------|--------|
| NFR-1 | Throughput | 1M+ messages/sec per cluster (horizontally scalable) |
| NFR-2 | Latency | P99 produce latency <10ms (acks=1), <50ms (acks=all) |
| NFR-3 | Durability | Zero message loss with acks=all and replication factor >= 3 |
| NFR-4 | Availability | 99.99% uptime -- survive single broker failure without data loss |
| NFR-5 | Ordering | Strict ordering within a partition (monotonic offset), no global ordering |
| NFR-6 | Scalability | Linear scaling by adding partitions (throughput) and brokers (storage/compute) |
| NFR-7 | Retention | Configurable per-topic: time-based (ms), size-based (bytes), or compaction |
| NFR-8 | Consumer lag | Real-time lag monitoring with <1 second granularity |

---

## 4. API Design

### 4.1 Produce Message

```
POST /topics/{topicName}/messages
```

**Request:**
```json
{
  "key": "order-123",
  "value": "{\"item\":\"laptop\",\"qty\":1}",
  "headers": {
    "source": "web",
    "correlation-id": "abc-123"
  },
  "partition": null,
  "ackMode": "LEADER"
}
```

**Response:**
```json
{
  "topic": "orders",
  "partition": 2,
  "offset": 42,
  "timestamp": "2025-01-15T10:30:00Z"
}
```

**Semantics:**
- If `key` is set and `partition` is null: partition = `hash(key) % partitionCount`
- If `key` is null and `partition` is null: round-robin assignment
- If `partition` is set: use explicit partition (must be in range)
- `ackMode`: `NONE` (acks=0), `LEADER` (acks=1), `ALL` (acks=all)

**Flow (numbered):**
1. Controller receives ProducerRecord
2. Look up Topic metadata from TopicRepository (get partition count)
3. Resolve target partition via PartitioningStrategy (explicit > key hash > round-robin)
4. Build Message from ProducerRecord (assign UUID, timestamp)
5. Append to CommitLog for the target partition (assigns monotonic offset)
6. Replicate to ISR replicas based on AckMode
7. Record produce metrics via MetricsService
8. Return offset to producer

### 4.2 Consume Messages

```
GET /topics/{topicName}/messages?groupId={groupId}&partition={partition}&maxMessages={max}
```

**Response:**
```json
{
  "records": [
    {
      "topic": "orders",
      "partition": 2,
      "offset": 42,
      "key": "order-123",
      "value": "{\"item\":\"laptop\",\"qty\":1}",
      "headers": {"source": "web"},
      "timestamp": "2025-01-15T10:30:00Z"
    },
    {
      "topic": "orders",
      "partition": 2,
      "offset": 43,
      "key": "order-456",
      "value": "{\"item\":\"phone\",\"qty\":2}",
      "headers": {},
      "timestamp": "2025-01-15T10:30:01Z"
    }
  ]
}
```

**Semantics:**
- Reads from the consumer group's committed offset for the given partition
- Returns up to `maxMessages` records
- Does NOT advance the committed offset (explicit commit required)
- Returns empty list if no new messages available

**Flow (numbered):**
1. Get committed offset for (groupId, topic, partition) from ConsumerGroupCoordinator
2. Look up CommitLog for the partition from PartitionManager
3. Read messages from committedOffset, up to maxMessages
4. Convert each Message to immutable ConsumerRecord
5. Record consume metrics via MetricsService
6. Return ConsumerRecords to consumer (offset is NOT advanced)

### 4.3 Commit Offset

```
POST /topics/{topicName}/commit
```

**Request:**
```json
{
  "groupId": "order-processors",
  "partition": 2,
  "offset": 44
}
```

**Semantics:**
- Persists the consumer's progress: "I have processed all messages with offset < 44"
- Next poll will start from offset 44
- At-least-once: commit AFTER processing (may re-read on crash)
- At-most-once: commit BEFORE processing (may skip on crash)

**Flow (numbered):**
1. ConsumerGroupCoordinator stores offset in committedOffsets map
2. Key = "groupId-topic-partition", value = offset
3. In production: written to `__consumer_offsets` internal compacted topic
4. Return success to consumer

### 4.4 Subscribe to Topic

```
POST /consumer-groups/subscribe
```

**Request:**
```json
{
  "groupId": "order-processors",
  "consumerId": "consumer-A",
  "topic": "orders",
  "partitionCount": 6
}
```

**Semantics:**
- Creates consumer group if it does not exist
- Adds consumer to the group's member list
- Triggers rebalance to redistribute partitions across all members
- Returns the consumer's assigned partitions

**Flow (numbered):**
1. Create ConsumerGroup if not exists (via ConsumerGroupCoordinator)
2. Subscribe group to topic (add to subscribedTopics set)
3. Create ConsumerInstance (consumerId, groupId, "localhost")
4. Join group (add member, initialize empty assignment)
5. Trigger rebalance: sort consumers, divide partitions evenly (range assignment)
6. Log partition assignments for each consumer
7. Return success with assigned partitions

### 4.5 Create Topic

```
POST /topics
```

**Request:**
```json
{
  "name": "orders",
  "partitions": 6,
  "replicationFactor": 3
}
```

**Flow (numbered):**
1. Validate name uniqueness and partition count >= 1
2. Create Topic model (name, partitionCount, replicationFactor, retentionMs=7d)
3. Persist via TopicRepository
4. Create N CommitLog instances via PartitionManager (one per partition)
5. Return created Topic

### 4.6 Get System Overview

```
GET /dashboard
```

**Response:**
```json
{
  "brokers": {"alive": 3, "total": 3},
  "topics": [
    {
      "name": "orders",
      "partitions": 6,
      "replicationFactor": 3,
      "metrics": {
        "messagesIn": 15000,
        "messagesOut": 14500,
        "lag": 500,
        "produceRate": 2500.0
      }
    }
  ]
}
```

---

## 5. Data Model

### 5.1 Message

The fundamental unit of data in the message queue. Created by producers, stored in commit logs, consumed by consumer groups.

```
Message
├── id: String (UUID)               -- unique message identifier
├── key: String (nullable)           -- partition key (null = round-robin)
├── value: String                    -- payload (the actual data)
├── headers: Map<String, String>     -- user-defined metadata
├── topic: String                    -- destination topic name
├── partition: int                   -- assigned partition (set by partitioner)
├── offset: long                     -- log offset (set by CommitLog on append)
├── timestamp: Instant               -- creation time
└── producerId: String               -- producing client identifier
```

**Key design decisions:**
- `offset` and `partition` start at -1 and are set broker-side (after routing and append)
- `key` is nullable: null key triggers round-robin partitioning (no ordering guarantee)
- `headers` carry metadata without polluting the value (correlation IDs, trace IDs, content types)
- Builder pattern enforces required fields (topic, value) and defaults optional ones

### 5.2 Topic

A named channel that producers write to and consumers read from. Split into partitions for parallelism.

```
Topic
├── name: String                     -- unique topic identifier
├── partitionCount: int              -- number of partitions (set at creation, hard to change)
├── replicationFactor: int           -- number of replicas per partition
├── retentionMs: long                -- how long to keep messages (default 7 days = 604,800,000 ms)
├── createdAt: Instant               -- creation timestamp
└── config: Map<String, String>      -- topic-level configuration overrides
```

**Key design decisions:**
- Partition count determines maximum parallelism (max consumers in a group = partition count)
- Replication factor determines durability (RF=3 survives 2 broker failures)
- Retention is per-topic, checked by a background cleaner thread

### 5.3 Partition

The unit of parallelism and ordering. Each partition is an independent commit log with its own leader and ISR.

```
Partition
├── topicName: String                -- owning topic
├── partitionId: int                 -- index within the topic (0-based)
├── leaderId: String                 -- broker ID of the current leader
├── replicaIds: List<String>         -- all replica broker IDs (includes leader)
├── inSyncReplicaIds: List<String>   -- ISR: replicas caught up with the leader
├── isLeader(brokerId): boolean      -- check if a broker is the leader
├── isInSync(brokerId): boolean      -- check if a broker is in the ISR
├── addToIsr(brokerId): void         -- add a broker to ISR (caught up)
├── removeFromIsr(brokerId): void    -- remove from ISR (fell behind or died)
└── getPartitionKey(): String        -- "topicName-partitionId" composite key
```

**Key design decisions:**
- Each partition has exactly ONE leader (all reads and writes go through the leader)
- ISR tracks which replicas are "caught up" -- acks=all waits for all ISR replicas
- ISR is dynamic: replicas fall out when they lag too far (>10 seconds by default in Kafka)

### 5.4 ConsumerGroup

Coordinates a set of consumers for load-balanced consumption. Each partition is assigned to exactly one consumer within the group.

```
ConsumerGroup
├── groupId: String                                  -- unique group identifier
├── subscribedTopics: Set<String>                    -- topics this group reads from
├── members: Map<String, ConsumerInstance>            -- consumerId -> instance
├── assignments: Map<String, List<PartitionAssignment>>  -- consumerId -> assigned partitions
├── createdAt: Instant                               -- creation timestamp
├── addMember(instance): void                        -- join group (triggers rebalance)
├── removeMember(consumerId): void                   -- leave group (triggers rebalance)
├── setAssignment(consumerId, partitions): void       -- set partition assignments
└── getMemberCount(): int                            -- number of active consumers
```

**Key design decisions:**
- One partition is assigned to exactly ONE consumer in the group (Kafka-style)
- More consumers than partitions = some consumers sit idle
- Rebalance is triggered on any membership change (join/leave/crash)

### 5.5 Offset

Tracks the committed offset for a (consumer group, topic, partition) tuple.

```
Offset
├── groupId: String                  -- consumer group
├── topicName: String                -- topic
├── partitionId: int                 -- partition index
├── committedOffset: long            -- last committed offset (-1 = none committed)
├── lastCommitTime: Instant          -- timestamp of the last commit
└── commit(newOffset): void          -- advance the committed offset
```

**Key design decisions:**
- Composite key: (groupId, topicName, partitionId) uniquely identifies an offset
- `committedOffset = -1` means "start from beginning" (new consumer group)
- In production: Kafka stores offsets in `__consumer_offsets` internal compacted topic

### 5.6 BrokerNode

A broker node in the distributed cluster. One broker is elected as the controller.

```
BrokerNode
├── brokerId: String                 -- unique broker identifier
├── host: String                     -- hostname or IP address
├── port: int                        -- listening port (default 9092)
├── isController: boolean            -- true if this broker is the cluster controller
├── partitionLeadership: Set<String> -- set of "topic-partition" keys this broker leads
├── lastHeartbeat: Instant           -- last heartbeat from this broker
├── isAlive(timeout): boolean        -- heartbeat within timeout?
├── addLeadership(topicPartition)    -- record partition leadership
└── removeLeadership(topicPartition) -- give up partition leadership
```

### 5.7 Entity Relationship

```
                    ┌─────────────┐
                    │  BrokerNode │
                    │  (cluster)  │
                    └──────┬──────┘
                           │ leads
                           ▼
┌─────────┐       ┌───────────────┐       ┌──────────┐
│  Topic  │──1:N──│  Partition    │──1:1──│ CommitLog │
│         │       │  (ISR, leader)│       │ (append-  │
│         │       └───────┬───────┘       │  only)    │
└─────────┘               │               └──────────┘
                           │ assigned to
                           ▼
                  ┌─────────────────┐
                  │ ConsumerGroup   │
                  │ (members,       │──1:N── ConsumerInstance
                  │  assignments)   │
                  └────────┬────────┘
                           │ tracks
                           ▼
                      ┌─────────┐
                      │ Offset  │
                      │ (group, │
                      │  topic, │
                      │  part.) │
                      └─────────┘
```

---

## 6. High-Level Architecture

### 6.1 System Architecture Diagram

```
                            ┌─────────────────────────────────────┐
                            │          PRODUCER CLIENTS           │
                            │  (send ProducerRecords with key,    │
                            │   value, optional partition, ackMode)│
                            └─────────────┬───────────────────────┘
                                          │
                                          ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│                        MESSAGE QUEUE SERVICE (FACADE)                        │
│                                                                              │
│  ┌──────────────────────┐  ┌───────────────────────┐  ┌──────────────────┐  │
│  │    TopicService      │  │   ProducerService     │  │  ConsumerService │  │
│  │  - createTopic()     │  │  - send(record, ack)  │  │  - poll()        │  │
│  │  - deleteTopic()     │  │  - sendBatch()        │  │  - commit()      │  │
│  │  - getTopic()        │  │  - resolvePartition() │  │  - subscribe()   │  │
│  │  - getAllTopics()     │  │                       │  │  - unsubscribe() │  │
│  └──────────┬───────────┘  └───────────┬───────────┘  └────────┬─────────┘  │
│             │                          │                       │             │
│  ┌──────────┴───────────┐  ┌───────────┴───────────┐  ┌────────┴─────────┐  │
│  │   BrokerService      │  │  RetentionService     │  │  MetricsService  │  │
│  │  - registerBroker()  │  │  - runCleanup()       │  │  - recordProduce │  │
│  │  - electController() │  │  - runCompaction()    │  │  - recordConsume │  │
│  │  - handleFailure()   │  │  - getStorageStats()  │  │  - printDashboard│  │
│  └──────────────────────┘  └───────────────────────┘  └──────────────────┘  │
└──────────────────────────────────┬───────────────────────────────────────────┘
                                   │
                                   ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│                              ENGINES LAYER                                   │
│                                                                              │
│  ┌────────────────────┐  ┌──────────────────────┐  ┌──────────────────────┐ │
│  │  PartitionManager  │  │ ConsumerGroupCoord.  │  │  ReplicationEngine   │ │
│  │  - createPartition │  │ - createGroup()      │  │  - replicate()       │ │
│  │  - getPartition()  │  │ - joinGroup()        │  │  - acks=0/1/all      │ │
│  │  - getPartitions   │  │ - leaveGroup()       │  │  - ISR validation    │ │
│  │    ForTopic()      │  │ - rebalance()        │  │                      │ │
│  │                    │  │ - commitOffset()     │  │  ┌────────────────┐  │ │
│  │  ┌──────────────┐  │  │ - getLag()           │  │  │ MessageRouter  │  │ │
│  │  │  CommitLog   │  │  └──────────────────────┘  │  │ - routeTo      │  │ │
│  │  │  (per part.) │  │                            │  │   Partition()   │  │ │
│  │  │  - append()  │  │                            │  └────────────────┘  │ │
│  │  │  - read()    │  │                            │                      │ │
│  │  │  - truncate()│  │                            │                      │ │
│  │  └──────────────┘  │                            │                      │ │
│  └────────────────────┘                            └──────────────────────┘ │
└──────────────────────────────────────────────────────────────────────────────┘
                                   │
                                   ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│                           STRATEGIES LAYER                                   │
│                                                                              │
│  ┌────────────────────────┐  ┌──────────────────────┐  ┌──────────────────┐ │
│  │  PartitioningStrategy  │  │  DeliveryStrategy    │  │  StorageStrategy │ │
│  │  ┌──────────────────┐  │  │  ┌────────────────┐  │  │  ┌────────────┐ │ │
│  │  │ HashPartitioning │  │  │  │ AtLeastOnce    │  │  │  │ TimeBased  │ │ │
│  │  │ key.hashCode() % │  │  │  │ retry 3x, 5%  │  │  │  │ Retention  │ │ │
│  │  │ partitionCount   │  │  │  │ failure sim    │  │  │  │ now-ts>ms  │ │ │
│  │  └──────────────────┘  │  │  └────────────────┘  │  │  └────────────┘ │ │
│  │  ┌──────────────────┐  │  │  ┌────────────────┐  │  │  ┌────────────┐ │ │
│  │  │ RoundRobin       │  │  │  │ ExactlyOnce    │  │  │  │ LogCompact │ │ │
│  │  │ AtomicInteger    │  │  │  │ dedup by       │  │  │  │ latest per │ │ │
│  │  │ counter % count  │  │  │  │ message ID set │  │  │  │ key, null  │ │ │
│  │  └──────────────────┘  │  │  └────────────────┘  │  │  │ keys kept  │ │ │
│  └────────────────────────┘  └──────────────────────┘  │  └────────────┘ │ │
│                                                         └──────────────────┘ │
└──────────────────────────────────────────────────────────────────────────────┘
                                   │
                                   ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│                          REPOSITORIES LAYER                                  │
│                                                                              │
│  ┌────────────────────┐  ┌──────────────────────┐  ┌──────────────────────┐ │
│  │ InMemoryTopicRepo  │  │ InMemoryConsumerGrp  │  │  InMemoryBrokerRepo │ │
│  │ ConcurrentHashMap  │  │ ConcurrentHashMap    │  │  ConcurrentHashMap  │ │
│  │ <name, Topic>      │  │ <groupId, Group>     │  │  <brokerId, Broker> │ │
│  └────────────────────┘  └──────────────────────┘  └──────────────────────┘ │
│                                                                              │
│  ┌────────────────────┐                                                      │
│  │ InMemoryOffsetRepo │  All repositories implement interfaces for           │
│  │ ConcurrentHashMap  │  swappable persistence (Redis, PostgreSQL, ZK)       │
│  │ <key, Offset>      │                                                      │
│  └────────────────────┘                                                      │
└──────────────────────────────────────────────────────────────────────────────┘
                                   │
                                   ▼
                            ┌─────────────────────────────────────┐
                            │          CONSUMER CLIENTS           │
                            │  (poll ConsumerRecords, commit      │
                            │   offsets, join/leave groups)       │
                            └─────────────────────────────────────┘
```

### 6.2 Wiring Graph (AppConfig Composition Root)

```
AppConfig (Factory + Singleton)
    │
    ├── Repositories (4 InMemory implementations)
    │     ├── InMemoryTopicRepository
    │     ├── InMemoryConsumerGroupRepository
    │     ├── InMemoryBrokerRepository
    │     └── InMemoryOffsetRepository
    │
    ├── Engines (4 core engines)
    │     ├── PartitionManager (manages CommitLog instances)
    │     ├── ConsumerGroupCoordinator (group lifecycle, offsets)
    │     ├── MessageRouter (partition routing)
    │     └── ReplicationEngine (ISR replication, ack modes)
    │
    ├── Strategies (3 swappable via setters)
    │     ├── PartitioningStrategy: HashPartitioning (default) | RoundRobin
    │     ├── DeliveryStrategy: AtLeastOnce (default) | ExactlyOnce
    │     └── StorageStrategy: TimeBasedRetention (default) | LogCompaction
    │
    ├── Services (7 services)
    │     ├── TopicService (topicRepo, partitionManager)
    │     ├── ProducerService (partitionManager, partitioningStrategy, replicationEngine, topicRepo)
    │     ├── ConsumerService (partitionManager, coordinator, deliveryStrategy)
    │     ├── BrokerService (brokerRepo)
    │     ├── RetentionService (partitionManager, storageStrategy)
    │     ├── MetricsService (standalone)
    │     └── MessageQueueService (FACADE: all 6 services above)
    │
    └── Presentation (2 output classes)
          ├── MessageQueueController (REST-like facade)
          └── MessageQueueStatsDisplay (formatted console output)
```

### 6.3 Message Flow -- Produce (End-to-End)

```
Producer                                       Broker Cluster
  │                                                │
  │ 1. send(ProducerRecord("orders", "order-123",  │
  │        "{\"item\":\"laptop\"}", LEADER))        │
  │─────────────────────────────────────────────────>│
  │                                                │
  │         2. TopicRepo.findByName("orders")      │
  │            → Topic{partitions=3, replication=3} │
  │                                                │
  │         3. PartitioningStrategy                │
  │            .assignPartition("order-123", 3)    │
  │            → Math.abs("order-123".hashCode()   │
  │               % 3) = partition 2               │
  │                                                │
  │         4. Message.Builder("orders", payload)  │
  │            .key("order-123")                   │
  │            .partition(2)                        │
  │            .build()                            │
  │                                                │
  │         5. CommitLog("orders", 2)              │
  │            .append(message)                    │
  │            → offset = currentOffset            │
  │              .getAndIncrement() = 42           │
  │                                                │
  │         6. ReplicationEngine                   │
  │            .replicate(msg, partition, LEADER)   │
  │            → Leader ack only                   │
  │                                                │
  │         7. MetricsService                      │
  │            .recordProduce("orders", msg)        │
  │                                                │
  │<─────────────────────────────────────────────────│
  │ 8. return offset=42                            │
  │                                                │
```

### 6.4 Message Flow -- Consume (End-to-End)

```
Consumer                                       Broker Cluster
  │                                                │
  │ 1. poll("order-group", "orders",               │
  │        partition=2, maxMessages=10)             │
  │─────────────────────────────────────────────────>│
  │                                                │
  │         2. ConsumerGroupCoordinator            │
  │            .getCommittedOffset(                 │
  │              "order-group", "orders", 2)        │
  │            → committedOffset = 40              │
  │                                                │
  │         3. PartitionManager                    │
  │            .getPartition("orders", 2)           │
  │            → CommitLog                         │
  │                                                │
  │         4. CommitLog.read(40, 10)              │
  │            → [msg@40, msg@41, msg@42]          │
  │                                                │
  │         5. Convert Message → ConsumerRecord    │
  │            (immutable snapshot)                 │
  │                                                │
  │         6. MetricsService                      │
  │            .recordConsume("orders", msg)        │
  │                                                │
  │<─────────────────────────────────────────────────│
  │ 7. return [CR@40, CR@41, CR@42]                │
  │                                                │
  │ 8. Process messages...                         │
  │                                                │
  │ 9. commit("order-group", "orders", 2, 43)      │
  │─────────────────────────────────────────────────>│
  │                                                │
  │         10. ConsumerGroupCoordinator           │
  │             .commitOffset(                      │
  │               "order-group", "orders", 2, 43)   │
  │             → committedOffsets["order-group     │
  │               -orders-2"] = 43                 │
  │                                                │
  │<─────────────────────────────────────────────────│
  │ 11. return success                             │
  │                                                │
```

---

## 7. Commit Log Deep Dive

### 7.1 What is a Commit Log?

The commit log is the **single most important data structure** in the entire system. It is an append-only, ordered sequence of messages for a single partition. Every message is assigned a monotonically increasing offset at write time. The log is NOT deleted on consumption -- this is the fundamental difference between a commit log (Kafka) and a traditional queue (RabbitMQ).

```
CommitLog for "orders-2":

  Offset:  0       1       2       3       4       5       6
         ┌───────┬───────┬───────┬───────┬───────┬───────┬───────┐
         │ msg-A │ msg-B │ msg-C │ msg-D │ msg-E │ msg-F │ msg-G │
         └───────┴───────┴───────┴───────┴───────┴───────┴───────┘
                                  ▲                       ▲
                                  │                       │
                          Consumer Group A          Latest Offset
                          committed at 3            (next write = 7)
                          (lag = 7 - 3 = 4)
```

### 7.2 Implementation Details

```java
public class CommitLog {
    private final List<Message> log;           // ordered list (append-only)
    private final AtomicLong currentOffset;    // next offset to assign (starts at 0)

    // Write path: O(1) amortized
    public synchronized long append(Message message) {
        long assignedOffset = currentOffset.getAndIncrement();
        message.setOffset(assignedOffset);
        log.add(message);
        return assignedOffset;
    }

    // Read path: O(1) to start, O(k) to read k messages
    public synchronized List<Message> read(long fromOffset, int maxMessages) {
        if (fromOffset < 0 || fromOffset >= log.size()) return emptyList();
        int end = Math.min((int) fromOffset + maxMessages, log.size());
        return new ArrayList<>(log.subList((int) fromOffset, end));
    }
}
```

### 7.3 Why Append-Only?

| Property | Append-Only Log | Traditional Queue (RabbitMQ) |
|----------|-----------------|------------------------------|
| Write complexity | O(1) append at tail | O(1) enqueue |
| Read complexity | O(1) by offset | O(1) dequeue |
| Deletion on consume | NO (retained until retention) | YES (deleted on ack) |
| Multi-consumer | YES (each group has its own offset) | NO (message gone after first consumer) |
| Replay | YES (seek to any past offset) | NO (message deleted) |
| Consumer independence | YES (groups read independently) | Requires fanout exchange |
| Disk I/O pattern | Sequential (optimal for HDD/SSD) | Random (deletes cause fragmentation) |

### 7.4 Production: Kafka's On-Disk Log

In production Kafka, each partition is stored as a sequence of **segment files**:

```
/data/orders-2/
  ├── 00000000000000000000.log      # segment 0: offsets 0-999
  ├── 00000000000000000000.index    # sparse offset index
  ├── 00000000000000000000.timeindex # time-based index
  ├── 00000000000000001000.log      # segment 1: offsets 1000-1999
  ├── 00000000000000001000.index
  └── 00000000000000001000.timeindex
```

**Key optimizations:**
- **Sequential disk writes**: append-only = sequential I/O. Sequential HDD: 600 MB/s. Random HDD: 100 KB/s. Sequential is 6000x faster.
- **Zero-copy transfers**: `sendfile()` syscall moves data directly from page cache to network socket, bypassing user-space. Saves 2 memory copies and 2 context switches per read.
- **Page cache**: Kafka relies on the OS page cache rather than managing its own heap cache. This means the JVM garbage collector does not need to manage message data.
- **Batching**: Messages are batched on both the producer (linger.ms, batch.size) and broker sides. A single I/O operation handles thousands of messages.
- **Compression**: Batches are compressed end-to-end (producer compresses, broker stores compressed, consumer decompresses). Supports gzip, snappy, lz4, zstd.

### 7.5 Performance Characteristics

| Operation | Complexity | Notes |
|-----------|-----------|-------|
| Append | O(1) amortized | Single atomic increment + list add |
| Read by offset | O(1) | Direct index into array (in-memory) or binary search of sparse index (on-disk) |
| Get latest offset | O(1) | Read AtomicLong |
| Get earliest offset | O(1) | First element of the log (or first surviving segment after cleanup) |
| Truncate before offset | O(n) | removeIf on the list (in production: delete entire segments) |
| Size | O(1) | List.size() |

---

## 8. Partitioning Strategies

### 8.1 Why Partition?

Partitioning is how the system achieves parallelism without sacrificing ordering guarantees. A topic with 1 partition has a single writer and reader -- it is ordered but limited to one consumer. A topic with N partitions can have up to N consumers reading in parallel, each processing a subset of the data.

```
Topic "orders" with 4 partitions:

  Partition 0: [order-100, order-104, order-108, ...]  ← Consumer A
  Partition 1: [order-101, order-105, order-109, ...]  ← Consumer B
  Partition 2: [order-102, order-106, order-110, ...]  ← Consumer C
  Partition 3: [order-103, order-107, order-111, ...]  ← Consumer D

  4x parallelism, each partition independently ordered
```

### 8.2 Hash Partitioning (Default)

```java
public class HashPartitioningStrategy implements PartitioningStrategy {
    public int assignPartition(String key, int partitionCount) {
        if (key == null) return 0;  // fallback for null key
        return Math.abs(key.hashCode()) % partitionCount;
    }
}
```

**Properties:**
- Same key ALWAYS maps to the same partition (deterministic)
- Guarantees per-key ordering: all events for `user-123` are in the same partition, processed in order
- Potential hot partition if one key produces disproportionate traffic (viral user, popular product)

**Use cases:**
- User activity events keyed by `userId` (per-user ordering for recommendations)
- Order events keyed by `orderId` (per-order lifecycle ordering)
- Payment events keyed by `paymentIntentId` (per-payment flow ordering)

### 8.3 Round-Robin Partitioning

```java
public class RoundRobinPartitioningStrategy implements PartitioningStrategy {
    private final AtomicInteger counter = new AtomicInteger(0);

    public int assignPartition(String key, int partitionCount) {
        return Math.abs(counter.getAndIncrement() % partitionCount);
    }
}
```

**Properties:**
- Even distribution across all partitions
- Maximum throughput (all partitions equally loaded)
- NO ordering guarantee (even if key is provided, it is ignored)

**Use cases:**
- Metrics collection (ordering does not matter, throughput is priority)
- Log aggregation (no per-entity ordering needed)
- Load testing (maximize producer throughput)

### 8.4 Partition Routing Cascade (MessageRouter)

The `MessageRouter` implements a three-level cascade:

```
1. Explicit partition (producer specifies partition directly)
   ↓ (if not set)
2. Key-based hash (key.hashCode() % partitionCount)
   ↓ (if key is null)
3. Round-robin (AtomicInteger counter % partitionCount)
```

This mirrors Kafka's `DefaultPartitioner` behavior. In production, Kafka uses Murmur2 hash for better distribution than Java's `hashCode()`.

### 8.5 Partition Count Trade-Offs

| Factor | Fewer Partitions | More Partitions |
|--------|-----------------|-----------------|
| Max parallelism | Lower (1 consumer per partition) | Higher |
| End-to-end latency | Lower (fewer leaders) | Higher (more leaders to manage) |
| Rebalance time | Faster (fewer to redistribute) | Slower |
| Controller failover | Faster (fewer leader elections) | Slower |
| Memory (broker) | Lower (1 offset per partition) | Higher |
| File handles | Fewer (1 segment set per partition) | More |
| Ordering guarantee | Stronger (fewer partitions = more data per partition) | Weaker (data spread across partitions) |

**Rule of thumb:** Start with `max(t/p, t/c)` where `t` = target throughput, `p` = throughput per partition (~10 MB/s), `c` = throughput per consumer. Err on the side of more partitions (you cannot easily reduce partition count).

---

## 9. Consumer Group Coordination & Rebalancing

### 9.1 Consumer Group Model

A consumer group is a set of consumer instances that cooperatively consume from one or more topics. The key invariant: **each partition is assigned to exactly one consumer within the group**.

```
Consumer Group "order-processors" consuming from "orders" (6 partitions):

  State 1: 2 consumers
  ┌────────────────┐  ┌────────────────┐
  │  consumer-A    │  │  consumer-B    │
  │  partitions:   │  │  partitions:   │
  │  [0, 1, 2]     │  │  [3, 4, 5]     │
  └────────────────┘  └────────────────┘

  State 2: 3 consumers (consumer-C joins → rebalance)
  ┌────────────────┐  ┌────────────────┐  ┌────────────────┐
  │  consumer-A    │  │  consumer-B    │  │  consumer-C    │
  │  partitions:   │  │  partitions:   │  │  partitions:   │
  │  [0, 1]        │  │  [2, 3]        │  │  [4, 5]        │
  └────────────────┘  └────────────────┘  └────────────────┘

  State 3: consumer-B crashes → rebalance
  ┌────────────────┐  ┌────────────────┐
  │  consumer-A    │  │  consumer-C    │
  │  partitions:   │  │  partitions:   │
  │  [0, 1, 2]     │  │  [3, 4, 5]     │
  └────────────────┘  └────────────────┘
```

### 9.2 Range Assignment Algorithm

The implementation uses **range assignment** (Kafka's default `RangeAssignor`):

```java
public void rebalance(String groupId, int partitionCount) {
    // 1. Sort consumer IDs for deterministic assignment
    List<String> consumerIds = new ArrayList<>(group.getMembers().keySet());
    consumerIds.sort(String::compareTo);

    int consumerCount = consumerIds.size();
    int partitionsPerConsumer = partitionCount / consumerCount;
    int remainder = partitionCount % consumerCount;

    // 2. Assign partitions: first 'remainder' consumers get one extra
    int partitionIndex = 0;
    for (int i = 0; i < consumerCount; i++) {
        int count = partitionsPerConsumer + (i < remainder ? 1 : 0);
        List<PartitionAssignment> assignments = new ArrayList<>();
        for (int j = 0; j < count; j++) {
            assignments.add(new PartitionAssignment(topic, partitionIndex++, consumerId));
        }
        group.setAssignment(consumerId, assignments);
    }
}
```

**Example:** 7 partitions, 3 consumers:
- `partitionsPerConsumer = 7 / 3 = 2`
- `remainder = 7 % 3 = 1`
- consumer-A: partitions [0, 1, 2] (2 + 1 extra)
- consumer-B: partitions [3, 4] (2)
- consumer-C: partitions [5, 6] (2)

### 9.3 Rebalancing Strategies Comparison

| Strategy | Description | Partition Movement | Use Case |
|----------|------------|-------------------|----------|
| **Range** (this impl) | Divide partitions per topic, distribute evenly | High (all partitions reassigned) | Simple, predictable |
| **RoundRobin** | Lay out all partitions across all topics, assign sequentially | Medium (more even distribution across topics) | Multi-topic subscriptions |
| **Sticky** | Minimize partition movement from previous assignment | Low (only move what is necessary) | Minimize state rebuild |
| **CooperativeSticky** | Incremental rebalance (only revoke partitions that move) | Minimal (no stop-the-world) | Production standard |

### 9.4 Rebalance Protocol (Kafka)

In production Kafka, rebalancing follows this protocol:

```
Consumer Coordinator                    Group Coordinator (Broker)
     │                                         │
     │ 1. JoinGroup(groupId, memberId,          │
     │    subscriptions, assignor)              │
     │─────────────────────────────────────────>│
     │                                         │
     │         2. Wait for all members          │
     │         3. Select leader (first joiner)  │
     │         4. Send JoinGroupResponse        │
     │            (with member list to leader,   │
     │             empty to followers)           │
     │<─────────────────────────────────────────│
     │                                         │
     │ 5. Leader runs assignor,                 │
     │    computes partition assignments        │
     │                                         │
     │ 6. SyncGroup(assignments)                │
     │─────────────────────────────────────────>│
     │                                         │
     │         7. Distribute assignments        │
     │            to all members                │
     │<─────────────────────────────────────────│
     │                                         │
     │ 8. Resume consuming from                 │
     │    assigned partitions                   │
```

### 9.5 Why Consumers > Partitions = Idle Consumers

```
6 partitions, 8 consumers:

  consumer-A: [0]
  consumer-B: [1]
  consumer-C: [2]
  consumer-D: [3]
  consumer-E: [4]
  consumer-F: [5]
  consumer-G: []  ← IDLE (no partitions to assign)
  consumer-H: []  ← IDLE

  Key insight: max parallelism = partition count.
  To increase parallelism, increase partition count (cannot decrease later).
```

---

## 10. Offset Management

### 10.1 What is an Offset?

An offset is a 64-bit integer that uniquely identifies a message's position within a partition's commit log. It is assigned at write time and increases monotonically.

```
                 Committed Offset    Latest Offset
                 (consumer progress) (high watermark)
                        │                  │
                        ▼                  ▼
  Offset:  0  1  2  3  4  5  6  7  8  9  10
         ┌──┬──┬──┬──┬──┬──┬──┬──┬──┬──┬──┐
         │  │  │  │  │  │  │  │  │  │  │  │
         └──┴──┴──┴──┴──┴──┴──┴──┴──┴──┴──┘
         ├──────────────┤├─────────────────┤
            Processed        Lag = 6
          (will not be     (unprocessed
           re-read)         messages)
```

### 10.2 Offset Types

| Offset Type | Description | In This Implementation |
|-------------|------------|----------------------|
| **Current Offset** | The next offset the consumer will read (from poll) | Derived from committedOffset |
| **Committed Offset** | The last offset the consumer has confirmed processing | `committedOffsets.get("group-topic-partition")` |
| **Latest Offset** (High Watermark) | The next offset that will be assigned to a new message | `CommitLog.currentOffset.get()` |
| **Earliest Offset** | The lowest available offset (may be > 0 after retention) | `CommitLog.getEarliestOffset()` |
| **Log Start Offset** | The offset of the first surviving message after cleanup | Same as earliest offset |

### 10.3 Consumer Lag

```
Lag = Latest Offset - Committed Offset

                                    Lag
                               ◄──────────►
  Offset:  0  1  2  3  4  5  6  7  8  9  10
         ┌──┬──┬──┬──┬──┬──┬──┬──┬──┬──┬──┐
         │  │  │  │  │  │  │  │  │  │  │  │
         └──┴──┴──┴──┴──┴──┴──┴──┴──┴──┴──┘
                              ▲              ▲
                              │              │
                      Committed = 6    Latest = 11
                                    Lag = 11 - 6 = 5
```

**Lag monitoring is the #1 operational metric for message queues:**
- Lag = 0: consumer is caught up (healthy)
- Lag > 0, stable: consumer processing at same rate as producer (healthy)
- Lag growing: consumer throughput < producer throughput (unhealthy -- scale up consumers)
- Lag shrinking: consumer catching up (recovering)

### 10.4 Offset Commit Semantics

```
┌──────────────────────┐
│  AT-LEAST-ONCE       │  1. Poll messages
│  (Default, Safest)   │  2. Process messages
│                      │  3. Commit offset
│  Crash between 2-3:  │  → Messages re-delivered on restart
│  Result: DUPLICATES  │  → Consumer must be idempotent
└──────────────────────┘

┌──────────────────────┐
│  AT-MOST-ONCE        │  1. Poll messages
│  (Data loss risk)    │  2. Commit offset
│                      │  3. Process messages
│  Crash between 2-3:  │  → Messages SKIPPED (lost)
│  Result: DATA LOSS   │  → Acceptable for metrics/logs
└──────────────────────┘

┌──────────────────────┐
│  EXACTLY-ONCE        │  1. Poll messages
│  (Most complex)      │  2. Process + commit in single transaction
│                      │  3. Dedup by message ID
│  Crash during 2:     │  → Transaction rolls back, re-process
│  Result: NO LOSS,    │  → Requires transactional API or
│  NO DUPLICATES       │    idempotent consumer (dedup set)
└──────────────────────┘
```

### 10.5 Production: __consumer_offsets

In Kafka, committed offsets are stored in an internal compacted topic called `__consumer_offsets`:

```
Topic: __consumer_offsets (50 partitions, compacted)

  Key: "order-group|orders|2"     → Value: {offset: 43, timestamp: 2025-01-15T10:30:00Z}
  Key: "order-group|orders|0"     → Value: {offset: 127, timestamp: 2025-01-15T10:30:01Z}
  Key: "analytics-group|orders|2" → Value: {offset: 38, timestamp: 2025-01-15T10:29:55Z}

  Compaction ensures only the LATEST offset per key is retained.
  This topic is replicated (RF=3) for durability.
```

---

## 11. Ack Modes & Delivery Guarantees

### 11.1 Ack Modes

Ack modes control the trade-off between durability and latency on the producer side.

```
┌────────────────────────────────────────────────────────────────────────┐
│                        ACK MODE COMPARISON                            │
├──────────┬────────────────────────┬──────────┬────────────────────────┤
│ Ack Mode │ Behavior               │ Latency  │ Durability             │
├──────────┼────────────────────────┼──────────┼────────────────────────┤
│ acks=0   │ Fire-and-forget.       │ ~0.5ms   │ LOWEST. Message may   │
│ (NONE)   │ Don't wait for any     │          │ be lost if leader     │
│          │ acknowledgment.        │          │ crashes before flush. │
│          │ Producer does not know │          │ No retry possible.    │
│          │ if message was received.│          │                       │
├──────────┼────────────────────────┼──────────┼────────────────────────┤
│ acks=1   │ Leader acknowledges.   │ ~2-5ms   │ MEDIUM. Message is    │
│ (LEADER) │ Message written to     │          │ durable on leader.    │
│          │ leader's local log.    │          │ May be lost if leader │
│          │ Replicas replicate     │          │ crashes before replica│
│          │ asynchronously.        │          │ tion completes.       │
├──────────┼────────────────────────┼──────────┼────────────────────────┤
│ acks=all │ All ISR replicas ack.  │ ~5-20ms  │ HIGHEST. Message is   │
│ (ALL)    │ Leader waits for every │ (cross-  │ durable on ALL ISR    │
│          │ ISR replica to confirm.│ rack:    │ replicas. Zero loss   │
│          │ Producer retries if    │ ~50ms)   │ if min.insync.replicas│
│          │ ISR < min.insync.      │          │ is met. Rejects write │
│          │ replicas.              │          │ if ISR too small.     │
└──────────┴────────────────────────┴──────────┴────────────────────────┘
```

### 11.2 Implementation

```java
public class ReplicationEngine {
    public boolean replicate(Message message, Partition partition, AckMode ackMode) {
        switch (ackMode) {
            case NONE -> {
                // Fire-and-forget: return immediately, no acknowledgment
                return true;
            }
            case LEADER -> {
                // Leader acknowledges: message is in leader's local log
                System.out.println("Leader '" + partition.getLeaderId() + "' acknowledged");
                return true;
            }
            case ALL -> {
                // All ISR replicas must acknowledge
                List<String> isr = partition.getInSyncReplicaIds();
                if (isr.size() < replicationFactor) {
                    System.out.println("WARNING: ISR size < replicationFactor");
                }
                for (String replicaId : isr) {
                    System.out.println("Replica '" + replicaId + "' acknowledged");
                }
                return true;
            }
        }
    }
}
```

### 11.3 Delivery Guarantees

| Guarantee | How It Works | Implementation | Use Case |
|-----------|-------------|----------------|----------|
| **At-Most-Once** | Commit offset BEFORE processing. If crash during processing, messages are skipped. | Consumer calls `commit()` immediately after `poll()`, before processing. | Metrics, logs, non-critical telemetry. Data loss is acceptable. |
| **At-Least-Once** | Commit offset AFTER processing. If crash during processing, messages are re-delivered. | Consumer processes all messages, then calls `commit()`. `AtLeastOnceDeliveryStrategy` retries delivery up to 3 times. | Default for most workloads. Consumer must handle duplicates (idempotent). |
| **Exactly-Once** | At-least-once + deduplication. Consumer tracks processed message IDs and skips duplicates. | `ExactlyOnceDeliveryStrategy` maintains a `Set<String>` of delivered message IDs. Duplicate = skip silently. | Payment processing, financial reconciliation. Cannot tolerate loss OR duplicates. |

### 11.4 At-Least-Once Implementation

```java
public class AtLeastOnceDeliveryStrategy implements DeliveryStrategy {
    private static final int MAX_RETRIES = 3;
    private static final double FAILURE_RATE = 0.05;  // 5% simulated failure

    public boolean deliver(Message message, String consumerId) {
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            boolean acked = ThreadLocalRandom.current().nextDouble() >= FAILURE_RATE;
            if (acked) {
                // Delivery succeeded on this attempt
                return true;
            }
            // Retry: message will be redelivered → consumer may see duplicates
        }
        return false;  // All attempts exhausted
    }
}
```

### 11.5 Exactly-Once Implementation (Idempotent Consumer)

```java
public class ExactlyOnceDeliveryStrategy implements DeliveryStrategy {
    private final Set<String> deliveredIds = ConcurrentHashMap.newKeySet();

    public boolean deliver(Message message, String consumerId) {
        String messageId = message.getId();

        if (deliveredIds.contains(messageId)) {
            // DUPLICATE: silently skip (idempotent)
            return true;
        }

        deliveredIds.add(messageId);
        // First delivery: process the message
        return true;
    }
}
```

### 11.6 Production: Kafka Exactly-Once Semantics (EOS)

Kafka's EOS is implemented at two levels:

**1. Idempotent Producer (broker-side dedup):**
```
Producer assigns: (producerId=5, sequenceNumber=42)
Broker checks: have I seen (producerId=5, seq=42) before?
  - No → accept and write
  - Yes → reject as duplicate (OutOfOrderSequenceException)

This prevents duplicates from producer retries.
```

**2. Transactions (atomic read-process-write):**
```
1. producer.beginTransaction()
2. consumer.poll() → messages
3. Process messages
4. producer.send(outputTopic, results)
5. producer.sendOffsetsToTransaction(offsets, groupId)
6. producer.commitTransaction()

All of steps 4-5 are atomic: either ALL succeed or NONE do.
If crash during commit → transaction is aborted on timeout → no partial writes.
```

---

## 12. Replication (ISR & Leader Election)

### 12.1 ISR (In-Sync Replicas)

ISR is the set of replicas that are "caught up" with the leader. A replica is in the ISR if it has replicated all messages within `replica.lag.time.max.ms` (default 10 seconds).

```
Partition: orders-2 (replication factor = 3)

  Broker-1 (LEADER):  [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10]
  Broker-2 (ISR):     [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10]  ← caught up
  Broker-3 (ISR):     [0, 1, 2, 3, 4, 5, 6, 7, 8]          ← 2 behind, but within 10s

  ISR = [Broker-1, Broker-2, Broker-3]

  --- After Broker-3 falls 10+ seconds behind ---

  Broker-1 (LEADER):  [0, 1, ..., 20]
  Broker-2 (ISR):     [0, 1, ..., 20]  ← caught up
  Broker-3 (OUT):     [0, 1, ..., 8]   ← too far behind (>10s)

  ISR = [Broker-1, Broker-2]  (Broker-3 removed from ISR)
```

### 12.2 Leader Election

When a leader broker fails, the controller must elect a new leader from the ISR:

```
Before failure:
  Partition orders-2: Leader=Broker-1, ISR=[Broker-1, Broker-2, Broker-3]

Broker-1 fails:
  1. Controller detects failure (heartbeat timeout)
  2. Controller selects new leader from ISR: Broker-2 (first in ISR after leader)
  3. Controller updates metadata: Leader=Broker-2, ISR=[Broker-2, Broker-3]
  4. Producers/consumers update their metadata cache
  5. Traffic redirected to Broker-2

After recovery:
  Broker-1 comes back online:
  1. Broker-1 fetches log from current leader (Broker-2) to catch up
  2. Once caught up, Broker-1 is added back to ISR
  3. ISR = [Broker-2, Broker-3, Broker-1]
  4. Optional: preferred leader election moves leadership back to Broker-1
```

### 12.3 Controller Election

The controller is the broker responsible for partition leader election, topic creation, and replica management.

```java
public class BrokerService {
    public Optional<BrokerNode> electController() {
        // 1. Clear existing controller flag
        brokerRepo.findController().ifPresent(c -> c.setController(false));

        // 2. Get all alive brokers
        List<BrokerNode> aliveBrokers = brokerRepo.findAlive(HEARTBEAT_TIMEOUT);

        // 3. Elect the broker with the lowest ID (deterministic)
        BrokerNode elected = aliveBrokers.stream()
                .min(Comparator.comparing(BrokerNode::getBrokerId))
                .orElseThrow();

        // 4. Mark as controller
        elected.setController(true);
        return Optional.of(elected);
    }
}
```

### 12.4 min.insync.replicas

`min.insync.replicas` (MIR) is the minimum number of ISR replicas that must acknowledge a write for acks=all to succeed.

```
Configuration: acks=all, min.insync.replicas=2, replication.factor=3

Scenario 1: All 3 replicas alive
  ISR = [B1, B2, B3] → ISR size (3) >= MIR (2) → WRITE SUCCEEDS

Scenario 2: One replica down
  ISR = [B1, B2] → ISR size (2) >= MIR (2) → WRITE SUCCEEDS

Scenario 3: Two replicas down
  ISR = [B1] → ISR size (1) < MIR (2) → WRITE REJECTED
  NotEnoughReplicasException → producer retries or fails

Key insight: acks=all + min.insync.replicas=2 guarantees zero data loss
if at least 2 brokers are alive. This is the recommended configuration
for critical data.
```

### 12.5 Unclean Leader Election

What happens if ALL ISR replicas are down, but out-of-sync replicas are available?

```
ISR = [] (all ISR replicas dead)
Out-of-sync replicas: [Broker-4] (has data up to offset 900, leader was at offset 1000)

Option 1: unclean.leader.election.enable=false (DEFAULT since Kafka 1.0)
  → Partition is UNAVAILABLE until an ISR replica recovers
  → Zero data loss, but partition is offline
  → CP behavior

Option 2: unclean.leader.election.enable=true
  → Broker-4 becomes leader, offsets 901-1000 are LOST
  → Partition is available immediately
  → AP behavior (availability over consistency)

Staff-level insight: This is the CAP theorem in action.
Choose CP for financial data, AP for metrics/logs.
```

---

## 13. Log Compaction vs Retention

### 13.1 Time-Based Retention

Time-based retention deletes messages older than the configured window. This is the default cleanup policy.

```
retention.ms = 604800000 (7 days)

Day 1                Day 7              Day 8
│                    │                  │
▼                    ▼                  ▼
┌─────────────────────────────────────────────────┐
│ [old msgs] │ [5-day msgs] │ [2-day msgs] │ [new] │
└─────────────────────────────────────────────────┘
  ▲                                          ▲
  │                                          │
  Expired: deleted                     Retained
  by background cleaner
```

**Implementation:**

```java
public class TimeBasedRetentionStrategy implements StorageStrategy {
    public boolean shouldRetain(Message message, long retentionMs) {
        long messageAgeMs = Instant.now().toEpochMilli()
                          - message.getTimestamp().toEpochMilli();
        return messageAgeMs <= retentionMs;
    }
}
```

**Production considerations:**
- Retention is per-partition, checked by a background log cleaner thread
- Kafka deletes entire log segments (not individual messages) -- segments are time-bucketed
- `retention.bytes` provides a secondary size-based cap (delete oldest segments when partition exceeds limit)
- Default: 7 days (`log.retention.hours=168`), can be set to -1 for infinite retention

### 13.2 Log Compaction

Log compaction keeps only the **latest value for each key**. Older values for the same key are discarded.

```
Before compaction (cleanup.policy=compact):

  Offset: 0     1     2     3     4     5     6     7
  Key:    A     B     A     C     B     A     D     C
  Value:  v1    v1    v2    v1    v2    v3    v1    v2

After compaction:

  Offset: 5     4     6     7
  Key:    A     B     D     C
  Value:  v3    v2    v1    v2

  Only the LATEST value for each key is retained.
  Offsets are preserved (not renumbered).
```

**Implementation:**

```java
public class LogCompactionStrategy implements StorageStrategy {
    public List<Message> compact(List<Message> messages) {
        // 1. Collect null-key messages (always retained)
        List<Message> nullKeyMessages = new ArrayList<>();
        // 2. Keep latest (highest offset) per key
        Map<String, Message> latestByKey = new LinkedHashMap<>();

        for (Message message : messages) {
            if (message.getKey() == null) {
                nullKeyMessages.add(message);
            } else {
                Message existing = latestByKey.get(message.getKey());
                if (existing == null || message.getOffset() > existing.getOffset()) {
                    latestByKey.put(message.getKey(), message);
                }
            }
        }

        List<Message> compacted = new ArrayList<>();
        compacted.addAll(nullKeyMessages);
        compacted.addAll(latestByKey.values());
        return compacted;
    }
}
```

### 13.3 Tombstones (Key Deletion)

A message with a null value is a **tombstone** -- it marks a key for deletion during compaction.

```
Offset: 0     1     2     3
Key:    A     B     A     A
Value:  v1    v1    v2    null  ← tombstone

After compaction:
  Key A is DELETED (tombstone consumed)
  Key B: v1 (retained)

Tombstones are retained for a configurable period (delete.retention.ms, default 24h)
to ensure downstream consumers see the deletion before the tombstone is removed.
```

### 13.4 Compaction vs Retention Comparison

| Property | Time-Based Retention | Log Compaction |
|----------|---------------------|----------------|
| cleanup.policy | `delete` | `compact` |
| What is deleted | Messages older than retention.ms | Older values for the same key |
| Storage growth | Bounded by retention window | Bounded by unique key count |
| Ordering preserved | Yes (within retention window) | Yes (offsets preserved, not renumbered) |
| Null-key messages | Deleted when expired | Always retained |
| Key deletion | Not applicable | Tombstone (null value) |
| Use case | Event streams, logs, metrics | Changelogs, CDC, materialized views, __consumer_offsets |
| CPU overhead | Low (delete entire segments) | High (scan dirty segments, rebuild) |
| Can combine? | Yes: `cleanup.policy=compact,delete` (compact first, then delete old segments) |

### 13.5 Use Cases for Log Compaction

| Use Case | Topic | Key | Why Compaction? |
|----------|-------|-----|-----------------|
| KTable (Kafka Streams) | `user-preferences` | userId | Latest preference per user is all that matters |
| CDC (Change Data Capture) | `orders-changelog` | orderId | Latest order state per order ID |
| Materialized views | `product-inventory` | productId | Latest stock count per product |
| Consumer offsets | `__consumer_offsets` | groupId-topic-partition | Latest committed offset per consumer group |
| Configuration | `config-updates` | configKey | Latest config value per key |

---

## 14. Scaling Strategy

### 14.1 Horizontal Scaling Dimensions

```
                          ┌───────────────────────────────────────┐
                          │         SCALING DIMENSIONS             │
                          └───────────────────────────────────────┘

  ┌─────────────────────┐  ┌─────────────────────┐  ┌────────────────────────┐
  │  ADD PARTITIONS     │  │  ADD BROKERS         │  │  ADD CONSUMERS         │
  │  (throughput)       │  │  (storage/compute)   │  │  (consumption rate)    │
  │                     │  │                      │  │                        │
  │  More partitions    │  │  More brokers =      │  │  More consumers =      │
  │  = more parallel    │  │  more disk, CPU,     │  │  more partitions       │
  │  producers and      │  │  network capacity.   │  │  processed in          │
  │  consumers.         │  │  Partitions           │  │  parallel.             │
  │                     │  │  rebalanced across   │  │                        │
  │  Topic: 6 → 12      │  │  brokers.            │  │  Max consumers =       │
  │  partitions =       │  │                      │  │  partition count.       │
  │  2x throughput      │  │  3 → 6 brokers =     │  │                        │
  │                     │  │  2x storage + 2x     │  │  3 → 6 consumers =     │
  │  CAVEAT: cannot     │  │  network bandwidth   │  │  2x consumption rate   │
  │  easily reduce      │  │                      │  │  (if partitions >= 6)   │
  │  partition count    │  │  Partition reassign-  │  │                        │
  │  after creation.    │  │  ment via controller  │  │  Consumer group        │
  │                     │  │  (automated).         │  │  auto-rebalances.      │
  └─────────────────────┘  └─────────────────────┘  └────────────────────────┘
```

### 14.2 Scaling Triggers

| Metric | Threshold | Action |
|--------|-----------|--------|
| Consumer lag growing | Lag > 10K messages for > 5 min | Add consumers (up to partition count) |
| Consumer lag at max consumers | All partitions assigned, lag still growing | Add partitions (requires producer restart for new partition routing) |
| Broker disk utilization | > 70% used | Add brokers, rebalance partitions |
| Broker CPU utilization | > 80% sustained | Add brokers, rebalance partitions |
| Broker network saturation | > 80% of NIC capacity | Add brokers |
| Produce latency P99 | > 50ms (acks=all) | Add brokers, check ISR health |
| Controller failover time | > 30 seconds | Migrate to KRaft (Raft consensus) |

### 14.3 Partition Rebalancing Across Brokers

When brokers are added or removed, partitions must be redistributed:

```
Before (3 brokers, 12 partitions):
  Broker-1: [orders-0, orders-1, orders-2, orders-3]      (4 partitions)
  Broker-2: [orders-4, orders-5, orders-6, orders-7]      (4 partitions)
  Broker-3: [orders-8, orders-9, orders-10, orders-11]    (4 partitions)

After adding Broker-4 (4 brokers, 12 partitions):
  Broker-1: [orders-0, orders-1, orders-2]                (3 partitions)
  Broker-2: [orders-3, orders-4, orders-5]                (3 partitions)
  Broker-3: [orders-6, orders-7, orders-8]                (3 partitions)
  Broker-4: [orders-9, orders-10, orders-11]              (3 partitions)

  3 partitions moved to Broker-4 to even out the load.
  In Kafka: use kafka-reassign-partitions.sh or Cruise Control for automated balancing.
```

### 14.4 Throughput Estimation

**Back-of-envelope calculation for a LinkedIn-scale deployment:**

```
Messages/day: 7 trillion = 7 * 10^12
Messages/second: 7T / 86400 = ~81 million msg/sec

Average message size: 500 bytes
Throughput: 81M * 500B = ~40 GB/s

Replication factor: 3
Total write throughput: 40 GB/s * 3 = ~120 GB/s

Single broker throughput: ~600 MB/s (sequential I/O)
Brokers needed for throughput: 120 GB/s / 600 MB/s = ~200 brokers

Retention: 7 days
Daily storage: 40 GB/s * 86400 = ~3.4 PB/day (pre-replication)
Total storage (7 days, RF=3): 3.4 PB * 7 * 3 = ~71 PB

LinkedIn runs 4000+ brokers across multiple clusters to handle this scale.
```

---

## 15. Database Choices

### 15.1 Storage Layer Comparison

| Component | This Implementation | Production Options | Recommended |
|-----------|-------------------|-------------------|-------------|
| **Commit Log** | `ArrayList<Message>` (in-memory) | Local disk (segment files) | **Local disk** -- Kafka's core. Sequential I/O. Page cache for reads. Zero-copy for consumer fetches. No external database needed. |
| **Topic Metadata** | `InMemoryTopicRepository` (ConcurrentHashMap) | ZooKeeper, KRaft, etcd | **KRaft** (Kafka 3.3+). Self-managed Raft consensus quorum. Eliminates ZK dependency. Supports millions of partitions. |
| **Consumer Offsets** | `ConcurrentHashMap<String, Long>` | `__consumer_offsets` topic, Redis, PostgreSQL | **`__consumer_offsets` topic** (Kafka's internal compacted topic). Self-bootstrapping. No external dependency. Replicated for durability. |
| **Broker Metadata** | `InMemoryBrokerRepository` (ConcurrentHashMap) | ZooKeeper (ephemeral znodes), KRaft | **KRaft**. Broker liveness via Raft heartbeats. Controller election via Raft leader election. |
| **Consumer Group State** | `ConcurrentHashMap<String, ConsumerGroup>` | `__consumer_offsets` topic, ZooKeeper | **`__consumer_offsets` topic** (same topic stores both offsets and group metadata). |
| **Metrics** | `ConcurrentHashMap<String, QueueMetrics>` (in-memory) | Prometheus, InfluxDB, Datadog | **Prometheus** + JMX exporter. Pull-based scraping. Grafana dashboards. AlertManager for lag alerts. |

### 15.2 Why Kafka Uses No External Database

Kafka is unique among distributed systems in that it uses **no external database**. The commit log itself IS the database:

```
┌─────────────────────────────────────────────────────────┐
│  Traditional System                                      │
│  App → Message Queue → Database                          │
│       (transient)      (persistent)                      │
│                                                          │
│  Kafka                                                   │
│  App → Kafka Topic (IS the persistent storage)           │
│       (the log IS the database of record)                │
│                                                          │
│  Topic data:     stored in segment files on disk         │
│  Offsets:        stored in __consumer_offsets topic       │
│  Metadata:       stored in KRaft quorum (or ZK)          │
│  No external DB, no Redis, no PostgreSQL needed.         │
└─────────────────────────────────────────────────────────┘
```

### 15.3 When to Add External Storage

| Scenario | External Storage | Reason |
|----------|-----------------|--------|
| Exactly-once consumer dedup | Redis / PostgreSQL | Store processed message IDs for dedup. Redis TTL for automatic cleanup. PostgreSQL unique constraint for durable dedup. |
| Full-text search on messages | Elasticsearch | Kafka messages consumed by an indexing pipeline, searchable via ES queries. |
| Long-term archival | S3 / GCS / HDFS | Tiered storage: hot data in Kafka (7 days), warm/cold in object storage. Kafka 3.6+ supports native tiered storage. |
| Materialized views | RocksDB (Kafka Streams) | KTable state stores backed by RocksDB. Compacted topic as changelog backup. |
| Monitoring dashboards | Prometheus + Grafana | JMX metrics scraped by Prometheus. Grafana for visualization. AlertManager for alerts. |

---

## 16. CAP Analysis

### 16.1 CAP Classification

```
                         Consistency
                            /\
                           /  \
                          /    \
                         /      \
                        /  CP    \
                       /  (writes \
                      /   acks=all)\
                     /              \
                    /                \
                   /     Kafka       \
                  /    (configurable) \
                 /                    \
                /         AP           \
               /     (consumption)      \
              /____________________________\
         Availability              Partition Tolerance
```

### 16.2 Kafka's Configurable CAP Position

| Operation | Default | CAP Position | Configuration |
|-----------|---------|-------------|---------------|
| **Produce (acks=0)** | Not default | **AP** -- no durability guarantee, maximum availability. Fire-and-forget. | `acks=0` |
| **Produce (acks=1)** | Default | **AP** leaning -- durable on leader, but leader failure before replication = data loss. | `acks=1` |
| **Produce (acks=all)** | Recommended for critical data | **CP** -- rejects writes if ISR < min.insync.replicas. Prefers consistency over availability. | `acks=all, min.insync.replicas=2` |
| **Consume** | Always | **AP** -- consumers tolerate stale offsets. Eventually catch up. Reading stale data is acceptable (consumer reads up to committed offset). | N/A |
| **Metadata** | Always | **CP** -- partition leader metadata must be consistent. Stale metadata → produce to wrong broker → error → metadata refresh. | N/A |
| **Offset commit** | Default | **AP** -- offset commits are async by default. Consumer may re-read messages on crash (at-least-once). | `enable.auto.commit=true` |

### 16.3 CAP Trade-Offs in Practice

```
Financial Data Pipeline (CP):
  acks=all
  min.insync.replicas=2
  replication.factor=3
  unclean.leader.election.enable=false
  → Zero data loss. Partition unavailable if <2 replicas alive.

Metrics Collection Pipeline (AP):
  acks=0  (or acks=1)
  replication.factor=2
  unclean.leader.election.enable=true
  → Maximum throughput and availability. Acceptable data loss.

User Activity Tracking (balanced):
  acks=1
  replication.factor=3
  min.insync.replicas=1
  unclean.leader.election.enable=false
  → Good throughput, low latency, data loss only on leader failure
    before replication (rare).
```

---

## 17. Cloud Mapping

### 17.1 AWS

| Component | AWS Service | Notes |
|-----------|------------|-------|
| **Managed Kafka** | Amazon MSK (Managed Streaming for Kafka) | Fully managed Kafka. Handles brokers, ZooKeeper (or KRaft), patching, scaling. Supports tiered storage to S3. |
| **Serverless queue** | Amazon SQS | Traditional queue (not commit log). Point-to-point. Auto-scales. No partitioning. 256KB message limit. |
| **Pub/Sub** | Amazon SNS | Fanout to multiple SQS queues, Lambda, HTTP endpoints. Topic-based pub/sub. |
| **Serverless Kafka** | Amazon MSK Serverless | Kafka API without managing brokers. Pay-per-use. Auto-scales partitions. |
| **Event bus** | Amazon EventBridge | Event routing with schema registry, filtering rules, and 100+ AWS service integrations. |
| **Kinesis** | Amazon Kinesis Data Streams | Real-time streaming. Similar to Kafka but AWS-native. 1 MB/sec per shard. Retention up to 365 days. |

**MSK architecture:**
```
                    ┌─────────────────────────────────┐
                    │          Amazon MSK              │
                    │  ┌─────────┐  ┌─────────┐      │
   Producers ──────>│  │ Broker 1│  │ Broker 2│ ...  │──────> Consumers
                    │  └─────────┘  └─────────┘      │
                    │        ┌─────────────┐         │
                    │        │   KRaft /    │         │
                    │        │  ZooKeeper   │         │
                    │        └─────────────┘         │
                    │                                 │
                    │  Tiered Storage ──> S3           │
                    │  Monitoring ──> CloudWatch       │
                    │  Encryption ──> KMS               │
                    │  Networking ──> VPC, PrivateLink  │
                    └─────────────────────────────────┘
```

### 17.2 Azure

| Component | Azure Service | Notes |
|-----------|-------------|-------|
| **Managed Kafka** | Azure Event Hubs (Kafka endpoint) | Event Hubs supports the Kafka protocol. Switch from self-managed Kafka by changing the bootstrap server endpoint. |
| **Serverless queue** | Azure Service Bus | Enterprise message broker with queues and pub/sub topics. Supports sessions (ordered processing), dead-letter queues, and transactions. |
| **Event streaming** | Azure Event Hubs | Native event streaming. Capture to Azure Blob/ADLS. Auto-inflate (auto-scale throughput units). |
| **Event routing** | Azure Event Grid | Event-driven architecture. Push-based delivery to Azure Functions, Logic Apps, webhooks. |

**Event Hubs architecture:**
```
                    ┌──────────────────────────────────┐
                    │         Azure Event Hubs          │
                    │  ┌──────────────────────────┐    │
   Kafka Producer ──>│  │ Kafka Protocol Endpoint  │    │
   (no code change) │  │  ┌──────┐  ┌──────┐     │    │
                    │  │  │Part 0│  │Part 1│ ... │    │──> Kafka Consumer
                    │  │  └──────┘  └──────┘     │    │   (no code change)
                    │  └──────────────────────────┘    │
                    │                                   │
                    │  Capture ──> Blob Storage / ADLS  │
                    │  Monitoring ──> Azure Monitor      │
                    │  Schema ──> Schema Registry        │
                    └──────────────────────────────────┘

  Throughput Units: 1 TU = 1 MB/s ingress, 2 MB/s egress
  Up to 40 TUs (auto-inflate) or dedicated clusters
```

### 17.3 GCP

| Component | GCP Service | Notes |
|-----------|------------|-------|
| **Managed Kafka** | Confluent Cloud on GCP (or self-managed on GKE) | No native managed Kafka. Use Confluent Cloud or deploy on GKE with operators. |
| **Pub/Sub** | Google Cloud Pub/Sub | Fully managed, serverless pub/sub. No partition management needed. Exactly-once delivery. Auto-scales. Global replication. |
| **Event streaming** | Google Cloud Dataflow | Apache Beam runner. Stream and batch processing. Integrates with Pub/Sub and BigQuery. |

**Cloud Pub/Sub architecture:**
```
                    ┌───────────────────────────────────┐
                    │       Google Cloud Pub/Sub         │
                    │                                    │
   Publishers ─────>│  Topic (no partitions to manage)  │
                    │       │                            │
                    │  ┌────▼─────┐  ┌──────────┐      │
                    │  │ Sub A    │  │ Sub B     │      │──> Subscribers
                    │  │ (push)   │  │ (pull)    │      │
                    │  └──────────┘  └──────────┘      │
                    │                                    │
                    │  Auto-scales: no partition mgmt    │
                    │  Exactly-once: ack + dedup          │
                    │  Retention: 7 days (configurable)   │
                    │  Dead letter: built-in               │
                    │  Ordering: per-key (ordering key)    │
                    └───────────────────────────────────┘
```

### 17.4 Cloud Comparison Matrix

| Feature | AWS MSK | Azure Event Hubs | GCP Pub/Sub | Self-Managed Kafka |
|---------|---------|-------------------|-------------|-------------------|
| Protocol | Kafka | Kafka-compatible | gRPC/REST | Kafka |
| Partitioning | Manual | Manual (1-32/namespace) | Automatic | Manual |
| Max throughput | 600 MB/s per broker | 20 MB/s per TU (auto-inflate) | Unlimited (auto-scales) | Hardware-bound |
| Retention | 7 days - unlimited | 7 days (90 days max) | 7 days (31 days max) | Unlimited |
| Exactly-once | Kafka EOS | Kafka EOS | Built-in (ack + dedup) | Kafka EOS |
| Operational burden | Low (managed) | Low (managed) | Lowest (serverless) | Highest |
| Cost model | Per-broker-hour + storage | Per-TU-hour + ingress | Per-message + storage | Hardware + ops team |
| Kafka API compatibility | 100% | 95% (most features) | 0% (different API) | 100% |

---

## 18. Failure Scenarios

### 18.1 Producer Failures

| Scenario | Impact | Mitigation |
|----------|--------|------------|
| **Producer crashes before send** | Message never sent. No data in any topic. | Upstream retry (caller retries the operation). |
| **Producer crashes after send, before ack** | Message MAY be in the log (acks=0: unknown, acks=1: in leader, acks=all: in all ISR). | Idempotent producer: producer retries with same (producerId, sequenceNumber). Broker deduplicates. |
| **Network partition between producer and broker** | Producer cannot reach leader. Sends fail with TimeoutException. | Producer buffers in-memory, retries up to `retries` (default: 2147483647). Backoff via `retry.backoff.ms`. |
| **Producer sends to wrong partition** | Message lands in unexpected partition. Consumer group processes it, but ordering may be violated for the intended key. | Metadata refresh: producer fetches updated topic metadata from any broker. |
| **Producer buffer full** | `BufferExhaustedException`. Producer's in-memory buffer (default 32 MB) is full. | Back-pressure: caller must slow down or increase `buffer.memory`. Monitor `record-queue-time-avg`. |

### 18.2 Broker Failures

| Scenario | Impact | Mitigation |
|----------|--------|------------|
| **Leader broker crashes** | Partition is unavailable until new leader elected (typically <1 second). In-flight acks=1 messages may be lost if not yet replicated. | Controller elects new leader from ISR. `acks=all` prevents data loss. `unclean.leader.election.enable=false` prevents data loss at the cost of availability. |
| **Follower broker crashes** | No immediate impact on producers/consumers (leader is still alive). ISR shrinks. | When ISR < min.insync.replicas, acks=all writes are rejected (NotEnoughReplicasException). Follower catches up when it recovers. |
| **Controller broker crashes** | No new topics can be created. No partition leader elections until new controller is elected. Existing topics continue serving. | Remaining brokers detect via heartbeat timeout. New controller elected from alive brokers (lowest ID wins). KRaft: Raft leader election is automatic. |
| **All brokers in ISR crash** | Partition is completely unavailable. Data may be lost if `unclean.leader.election.enable=true`. | If `unclean.leader.election.enable=false`: partition stays offline until an ISR replica recovers (CP behavior). If true: out-of-sync replica becomes leader (AP, data loss). |
| **Disk failure on single broker** | Partitions led by that broker are unavailable. Replicas on other brokers take over leadership. | RAID configuration. Log directory: Kafka supports multiple `log.dirs` on different disks. Controller reassigns leadership. |
| **Network partition (split brain)** | Some brokers cannot reach others. Clients may see different views of the cluster. | ZooKeeper/KRaft quorum prevents split-brain controller election. Producers route to the partition that has the majority of the quorum. ISR membership requires connectivity to the leader. |

### 18.3 Consumer Failures

| Scenario | Impact | Mitigation |
|----------|--------|------------|
| **Consumer crashes before commit** | Messages already processed are re-delivered on restart (at-least-once semantics). | Consumer must be idempotent. Dedup by message ID or database unique constraint. |
| **Consumer crashes after commit, before processing** | Messages are marked as processed but were not actually processed (at-most-once). | Use at-least-once: process THEN commit. Accept duplicates over data loss. |
| **Consumer hangs (heartbeat timeout)** | Group coordinator marks consumer as dead. Triggers rebalance. Partitions reassigned to surviving consumers. | `session.timeout.ms` (default 45s) and `heartbeat.interval.ms` (default 3s). Processing-heavy consumers should increase `max.poll.interval.ms`. |
| **Slow consumer (processing takes too long)** | `max.poll.interval.ms` (default 5 min) exceeded. Consumer is evicted from group. Rebalance triggered. | Increase `max.poll.interval.ms`. Reduce `max.poll.records`. Use async processing with manual offset management. |
| **Consumer group rebalance storm** | Frequent rebalances due to consumers repeatedly joining/leaving. Each rebalance pauses all consumers. | Use CooperativeSticky assignor (incremental rebalance). Increase `session.timeout.ms`. Set `group.instance.id` for static membership. |

### 18.4 Data Loss Scenarios

```
Scenario: Leader fails before replication (acks=1)

  Timeline:
    T1: Producer sends message M to Leader (Broker-1)
    T2: Leader writes M to local log, returns ack to producer
    T3: Leader crashes BEFORE Follower (Broker-2) replicates M
    T4: Controller elects Broker-2 as new leader
    T5: Message M is LOST -- it was only on Broker-1's disk

  Prevention:
    acks=all → Leader waits for ALL ISR replicas to acknowledge
    min.insync.replicas=2 → Ensures at least 2 copies before ack
    replication.factor=3 → 3 copies of every message

  Result with acks=all + min.insync.replicas=2:
    T2: Leader writes M, waits for Broker-2 AND Broker-3 to ack
    T3: Broker-1 crashes → M is on Broker-2 AND Broker-3
    T4: Broker-2 elected leader → M is NOT lost
```

### 18.5 Cascading Failure Prevention

```
Backpressure Chain:
  If consumer lag grows → consumers fall further behind
  → producers fill up broker disk → brokers reject writes
  → producers buffer fills up → producer crashes

Prevention:
  1. Monitor consumer lag (primary alert)
  2. Auto-scale consumers (add instances up to partition count)
  3. If consumers at max, add partitions (requires producer restart)
  4. Broker disk alerts at 70% → add brokers, rebalance partitions
  5. Producer-side back-pressure: reduce batch.size, increase linger.ms
  6. Quota management: per-client produce/consume rate limits
```

---

## 19. Interview Walkthrough Script

### 19.1 Opening (2 minutes)

"I'll design a distributed message queue like Kafka. The core abstraction is an **append-only commit log** -- a per-partition sequential data structure where producers append messages and consumers read by offset. Unlike a traditional queue, messages are NOT deleted on consumption, enabling replay and multi-consumer reading.

The system splits topics into **partitions** for parallelism, uses **consumer groups** with range rebalancing for load-balanced consumption, and provides configurable **ack modes** (acks=0/1/all) to trade off between latency and durability. Let me start with the API..."

### 19.2 API Design (3 minutes)

"Four core APIs:
1. **Produce**: `POST /topics/{topic}/messages` with key, value, ackMode. Key determines partition via `hash(key) % partitionCount`. Null key = round-robin.
2. **Consume**: `GET /topics/{topic}/messages?groupId=X&partition=Y&max=100`. Reads from the group's committed offset. Does NOT advance the offset.
3. **Commit**: `POST /topics/{topic}/commit` with groupId, partition, offset. Persists consumer progress. At-least-once: commit AFTER processing.
4. **Subscribe**: `POST /consumer-groups/subscribe`. Joins a consumer group and triggers partition rebalance."

### 19.3 Data Model (3 minutes)

"Six core entities:
- **Message**: id (UUID), key, value, headers, topic, partition, offset, timestamp. Builder pattern for construction. Offset assigned by CommitLog at append time.
- **Topic**: name, partitionCount, replicationFactor, retentionMs. Partition count = max parallelism.
- **Partition**: topicName, partitionId, leaderId, replicaIds, inSyncReplicaIds (ISR).
- **ConsumerGroup**: groupId, members, assignments (consumerId -> partitions). One partition per consumer.
- **Offset**: (groupId, topic, partition) -> committedOffset. Tracks consumer progress.
- **BrokerNode**: brokerId, host, port, isController, partitionLeadership."

### 19.4 Architecture Deep Dive (10 minutes)

"The architecture has five layers:
1. **Repositories**: InMemory implementations of TopicRepository, ConsumerGroupRepository, BrokerRepository, OffsetRepository. Swappable for persistent stores.
2. **Engines**: PartitionManager (CommitLog instances), ConsumerGroupCoordinator (groups, rebalancing, offsets), ReplicationEngine (ISR, ack modes), MessageRouter (partition routing cascade).
3. **Strategies**: Three Strategy pattern families -- PartitioningStrategy (Hash/RoundRobin), DeliveryStrategy (AtLeastOnce/ExactlyOnce), StorageStrategy (TimeBasedRetention/LogCompaction).
4. **Services**: TopicService, ProducerService, ConsumerService, BrokerService, RetentionService, MetricsService.
5. **Facade**: MessageQueueService orchestrates all services behind a unified API.

The produce flow: resolve partition (hash key % count) -> build Message -> append to CommitLog (O(1), assigns offset) -> replicate to ISR based on ack mode -> return offset."

### 19.5 Key Design Decisions (5 minutes)

"Five decisions I want to highlight:
1. **Commit log over traditional queue**: Messages persist after consumption. Enables replay, multi-consumer, and independent consumer group progress.
2. **Ack modes for configurable durability**: acks=all + min.insync.replicas=2 for zero data loss. acks=0 for metrics where throughput > durability.
3. **Consumer group rebalancing**: Range assignment for simplicity. CooperativeSticky in production to avoid stop-the-world rebalances.
4. **Log compaction + time-based retention via Strategy pattern**: Compaction for changelogs (KTable, CDC). Time-based for event streams. Swappable at runtime.
5. **KRaft over ZooKeeper**: Self-managed metadata quorum. No external dependency. Supports millions of partitions."

### 19.6 Scaling & Follow-Up Questions (5 minutes)

"Scaling dimensions: add partitions (throughput), add brokers (storage/compute), add consumers (consumption rate, up to partition count).

Common follow-up questions:
- **How do you handle hot partitions?** Salt the key or use a custom partitioner that sub-partitions hot keys across multiple partitions.
- **How does exactly-once work?** Idempotent producer (producer ID + sequence number for broker-side dedup) + transactions (atomic read-process-write).
- **What happens when a leader fails?** Controller elects new leader from ISR. acks=all ensures no data loss. unclean.leader.election=false prevents data loss at the cost of availability.
- **How do you monitor the system?** Consumer lag is the #1 metric. Also: produce rate, consume rate, ISR count, controller election count, broker disk usage."

### 19.7 Closing (1 minute)

"To summarize: an append-only commit log partitioned by key hash, with consumer groups for load-balanced consumption, configurable ack modes for durability-latency tradeoff, and ISR replication for fault tolerance. The design uses 8 GoF patterns (Strategy x3, Builder, Factory, Repository x4, Facade, Observer, State, Singleton) and maps directly to AWS MSK, Azure Event Hubs, or GCP Pub/Sub."

---

## Appendix A: GoF Design Patterns Summary

| # | Pattern | Class | Purpose |
|---|---------|-------|---------|
| 1 | Strategy | `PartitioningStrategy` (Hash, RoundRobin) | Swap partition assignment algorithm |
| 2 | Strategy | `DeliveryStrategy` (AtLeastOnce, ExactlyOnce) | Swap delivery guarantee semantics |
| 3 | Strategy | `StorageStrategy` (TimeBased, LogCompaction) | Swap retention/compaction algorithm |
| 4 | Builder | `Message.Builder` | Complex object construction (9 fields) |
| 5 | Factory | `AppConfig` (Composition Root) | Lazy creation and wiring of 30+ objects |
| 6 | Repository | Topic, ConsumerGroup, Broker, Offset (4x) | Abstract data access, swappable persistence |
| 7 | Facade | `MessageQueueService` | Unified API orchestrating 6 services |
| 8 | Observer | Consumer group rebalancing | React to membership changes |
| 9 | State | Offset lifecycle | Uncommitted -> committed -> stale transitions |
| 10 | Singleton | `AppConfig` lazy init | Single instance, cached getters |

---

## Appendix B: Kafka Configuration Cheat Sheet

| Configuration | Default | Description | Staff-Level Notes |
|--------------|---------|-------------|-------------------|
| `acks` | 1 | Producer acknowledgment mode | 0=fire-and-forget, 1=leader, all=ISR |
| `min.insync.replicas` | 1 | Min ISR for acks=all | Set to 2 for zero data loss with RF=3 |
| `replication.factor` | 1 | Replicas per partition | 3 for production |
| `retention.ms` | 604800000 (7d) | Time-based retention | -1 for unlimited |
| `retention.bytes` | -1 (unlimited) | Size-based retention per partition | Set to cap storage |
| `cleanup.policy` | delete | Retention strategy | compact, delete, compact+delete |
| `num.partitions` | 1 | Default partitions for new topics | Set based on throughput needs |
| `max.poll.records` | 500 | Max records per poll | Tune for consumer processing time |
| `max.poll.interval.ms` | 300000 (5m) | Max time between polls | Increase for slow processing |
| `session.timeout.ms` | 45000 (45s) | Consumer heartbeat timeout | Lower = faster failure detection |
| `heartbeat.interval.ms` | 3000 (3s) | Heartbeat frequency | Must be < session.timeout.ms / 3 |
| `enable.auto.commit` | true | Auto-commit offsets | false for at-least-once |
| `auto.offset.reset` | latest | Where to start if no offset | earliest = from beginning |
| `unclean.leader.election.enable` | false | Allow out-of-sync leader | true = AP (data loss), false = CP |
| `linger.ms` | 0 | Producer batch wait time | 5-100ms for batching throughput |
| `batch.size` | 16384 (16KB) | Producer batch size | 100KB-1MB for high throughput |
| `compression.type` | none | Message compression | lz4 or zstd recommended |
| `replica.lag.time.max.ms` | 10000 (10s) | Max lag before ISR removal | Tune based on network latency |
| `log.segment.bytes` | 1073741824 (1GB) | Segment file size | Smaller = faster retention cleanup |

---

## Appendix C: Comparison with Other Message Queues

| Feature | Kafka | RabbitMQ | Amazon SQS | Apache Pulsar |
|---------|-------|----------|------------|---------------|
| Core abstraction | Commit log | Queue | Queue | Commit log |
| Message deletion | Retained (offset-based) | Deleted on ack | Deleted on ack | Retained (cursor-based) |
| Ordering | Per-partition | Per-queue | Best-effort (FIFO optional) | Per-partition |
| Consumer model | Pull (consumer polls) | Push (broker delivers) | Pull | Pull + Push |
| Throughput | Millions/sec | 10K-50K/sec | 3K/sec (standard), 70K/sec (FIFO) | Millions/sec |
| Replay | Yes (seek to offset) | No (deleted on ack) | No (deleted on ack) | Yes (seek to cursor) |
| Multi-consumer | Yes (consumer groups) | No (requires exchange fanout) | No (single consumer per message) | Yes (subscriptions) |
| Storage | Disk (sequential I/O) | Memory + disk | Managed (S3-backed) | BookKeeper (tiered) |
| Exactly-once | Yes (EOS, transactions) | No (at-most-once or at-least-once) | Yes (dedup window) | Yes (dedup) |
| Managed cloud | AWS MSK, Confluent | CloudAMQP, AWS MQ | Native AWS | StreamNative |
| Best for | Event streaming, log aggregation, high throughput | Task queues, RPC, low latency | Decoupling microservices, serverless | Multi-tenancy, geo-replication |

---

## Appendix D: File Inventory

```
src/main/java/com/systemdesign/messagequeue/
├── DistributedMessageQueueApp.java          -- Main class, 12 demos
├── config/
│   └── AppConfig.java                       -- Factory/Composition Root (30+ objects)
├── controller/
│   └── MessageQueueController.java          -- REST-like facade
├── display/
│   └── MessageQueueStatsDisplay.java        -- Formatted console output
├── engine/
│   ├── CommitLog.java                       -- Append-only log (core data structure)
│   ├── ConsumerGroupCoordinator.java        -- Group lifecycle, rebalancing, offsets
│   ├── MessageRouter.java                   -- Partition routing cascade
│   ├── PartitionManager.java               -- Partition registry (ConcurrentHashMap)
│   └── ReplicationEngine.java              -- ISR replication, ack modes
├── exception/
│   ├── ConsumerGroupException.java
│   ├── MessageQueueException.java
│   ├── PartitionNotFoundException.java
│   └── TopicNotFoundException.java
├── model/
│   ├── AckMode.java                        -- Enum: NONE(0), LEADER(1), ALL(-1)
│   ├── BrokerNode.java                     -- Cluster node with heartbeat, leadership
│   ├── ConsumerGroup.java                  -- Group with members, assignments
│   ├── ConsumerInstance.java               -- Single consumer with heartbeat
│   ├── ConsumerRecord.java                 -- Immutable consumer-side message
│   ├── DeliveryGuarantee.java              -- Enum: AT_MOST_ONCE, AT_LEAST_ONCE, EXACTLY_ONCE
│   ├── Message.java                        -- Core entity with Builder pattern
│   ├── MessageBatch.java                   -- Batch for I/O optimization
│   ├── Offset.java                         -- Consumer progress tracker
│   ├── Partition.java                      -- ISR, leader, replicas
│   ├── PartitionAssignment.java            -- Rebalance output
│   ├── ProducerRecord.java                 -- Producer-side message
│   ├── QueueMetrics.java                   -- Per-topic throughput counters
│   ├── RetentionPolicy.java               -- Retention config with cleanup policy
│   └── Topic.java                          -- Topic definition
├── repository/
│   ├── BrokerRepository.java               -- Interface
│   ├── ConsumerGroupRepository.java        -- Interface
│   ├── InMemoryBrokerRepository.java       -- ConcurrentHashMap impl
│   ├── InMemoryConsumerGroupRepository.java
│   ├── InMemoryOffsetRepository.java
│   ├── InMemoryTopicRepository.java
│   ├── OffsetRepository.java               -- Interface
│   └── TopicRepository.java               -- Interface
├── service/
│   ├── BrokerService.java                  -- Cluster management, controller election
│   ├── ConsumerService.java                -- Poll, commit, subscribe, lag
│   ├── MessageQueueService.java            -- FACADE: unified API
│   ├── MetricsService.java                 -- Throughput tracking, dashboard
│   ├── ProducerService.java                -- Send, batch, partition resolution
│   ├── RetentionService.java               -- Cleanup, compaction, storage stats
│   └── TopicService.java                   -- Topic lifecycle
└── strategy/
    ├── delivery/
    │   ├── AtLeastOnceDeliveryStrategy.java -- Retry 3x, 5% failure sim
    │   ├── DeliveryStrategy.java           -- Strategy interface
    │   └── ExactlyOnceDeliveryStrategy.java -- Dedup by message ID set
    ├── partitioning/
    │   ├── HashPartitioningStrategy.java   -- key.hashCode() % count
    │   ├── PartitioningStrategy.java       -- Strategy interface
    │   └── RoundRobinPartitioningStrategy.java -- AtomicInteger counter
    └── storage/
        ├── LogCompactionStrategy.java      -- Latest per key, null-key preserved
        ├── StorageStrategy.java            -- Strategy interface
        └── TimeBasedRetentionStrategy.java -- now - timestamp > retentionMs
```

**Total: 52 Java files, 12 demos, 8 design patterns, 3 strategy families**
