# Technologies Deep Dive: Distributed Message Queue (Project 20)

> Comprehensive analysis of messaging technologies, protocols, serialization formats,
> and stream processing frameworks — mapped to our simulation code.

---

## Table of Contents

1. [Apache Kafka](#1-apache-kafka)
2. [Apache Kafka Internals: Log Storage](#2-apache-kafka-internals-log-storage)
3. [ZooKeeper to KRaft Migration](#3-zookeeper-to-kraft-migration)
4. [Kafka Consumer Groups](#4-kafka-consumer-groups)
5. [Kafka Exactly-Once Semantics (EOS)](#5-kafka-exactly-once-semantics-eos)
6. [RabbitMQ](#6-rabbitmq)
7. [AMQP Protocol Deep Dive](#7-amqp-protocol-deep-dive)
8. [RabbitMQ Exchange Types](#8-rabbitmq-exchange-types)
9. [Apache Pulsar](#9-apache-pulsar)
10. [Pulsar Tiered Storage](#10-pulsar-tiered-storage)
11. [Pulsar Multi-Tenancy](#11-pulsar-multi-tenancy)
12. [AWS SQS](#12-aws-sqs)
13. [AWS SNS](#13-aws-sns)
14. [AWS Kinesis Data Streams](#14-aws-kinesis-data-streams)
15. [Amazon MSK (Managed Streaming for Apache Kafka)](#15-amazon-msk)
16. [Redis Streams](#16-redis-streams)
17. [ZeroMQ](#17-zeromq)
18. [Protocol Buffers (Protobuf)](#18-protocol-buffers-protobuf)
19. [Apache Avro](#19-apache-avro)
20. [Schema Registry](#20-schema-registry)
21. [Kafka Connect](#21-kafka-connect)
22. [Kafka Streams](#22-kafka-streams)
23. [Simulation-to-Production Mapping](#23-simulation-to-production-mapping)
24. [Technology Selection Matrix](#24-technology-selection-matrix)
25. [Interview Quick Reference](#25-interview-quick-reference)

---

## 1. Apache Kafka

### What It Is

Apache Kafka is a distributed event streaming platform originally developed at LinkedIn in 2011.
It is designed for high-throughput, fault-tolerant, publish-subscribe messaging with durable storage.
Kafka treats every topic as a partitioned, replicated commit log.

### Core Architecture

```
                        ┌──────────────────────────────┐
                        │         Kafka Cluster         │
                        │                              │
  ┌──────────┐          │  ┌────────┐  ┌────────┐     │          ┌──────────┐
  │ Producer │───────▶  │  │Broker 0│  │Broker 1│     │  ◀───────│ Consumer │
  │  (API)   │          │  │        │  │        │     │          │  Group   │
  └──────────┘          │  │ P0(L)  │  │ P0(F)  │     │          └──────────┘
                        │  │ P1(F)  │  │ P1(L)  │     │
  ┌──────────┐          │  │ P2(L)  │  │ P2(F)  │     │          ┌──────────┐
  │ Producer │───────▶  │  └────────┘  └────────┘     │  ◀───────│ Consumer │
  │  (API)   │          │                              │          │  Group   │
  └──────────┘          │  ┌────────┐                  │          └──────────┘
                        │  │Broker 2│                  │
                        │  │ P3(L)  │                  │
                        │  └────────┘                  │
                        └──────────────────────────────┘
                                      │
                        ┌─────────────┴──────────────┐
                        │  KRaft Controller Quorum   │
                        │  (metadata management)     │
                        └────────────────────────────┘
```

### Key Properties

| Property | Value |
|---|---|
| **Throughput** | Millions of messages/sec per cluster |
| **Latency** | Sub-10ms p99 (with acks=1) |
| **Retention** | Configurable (time, size, or compaction) |
| **Ordering** | Per-partition only |
| **Delivery** | At-least-once by default; exactly-once with EOS |
| **Storage** | Append-only commit log on disk |
| **Protocol** | Custom binary protocol over TCP |
| **Partitioning** | Hash-based (default), round-robin, or custom |

### How Our Simulation Maps to Kafka

| Kafka Concept | Simulation Class | Notes |
|---|---|---|
| Commit log | `CommitLog.java` | Append-only list with monotonic offsets |
| Partition | `Partition.java` | Tracks leader/ISR broker assignments |
| Producer | `ProducerService.java` | Routes via partitioning strategy, appends to log |
| Consumer group | `ConsumerGroupCoordinator.java` | Range assignment, offset tracking |
| Replication | `ReplicationEngine.java` | Simulates acks=0/1/all against ISR |
| Ack modes | `AckMode.java` | NONE(0), LEADER(1), ALL(-1) |
| Retention | `RetentionPolicy.java` | DELETE, COMPACT, COMPACT_DELETE |
| Partitioning | `HashPartitioningStrategy.java` | `abs(key.hashCode()) % partitionCount` |

### Why Kafka Dominates

1. **Sequential I/O** - Kafka writes sequentially to disk, which is faster than random I/O to SSD
2. **Page cache reliance** - Reads serve from OS page cache, not JVM heap
3. **Zero-copy** - Uses `sendfile()` to transfer data from disk to network without user-space copies
4. **Batching** - Producers batch messages; brokers write batches to disk atomically
5. **Compacted topics** - Serve as a durable key-value store (changelog pattern)

---

## 2. Apache Kafka Internals: Log Storage

### Segment Architecture

Kafka does not store each topic-partition as a single file. Instead, each partition is broken
into **segments** — a sequence of files on disk:

```
/data/kafka-logs/
  └── orders-0/                     ← topic "orders", partition 0
      ├── 00000000000000000000.log  ← segment file (messages)
      ├── 00000000000000000000.index  ← offset-to-position index
      ├── 00000000000000000000.timeindex  ← timestamp-to-offset index
      ├── 00000000000052428800.log  ← next segment (starts at offset 52428800)
      ├── 00000000000052428800.index
      ├── 00000000000052428800.timeindex
      └── leader-epoch-checkpoint
```

### Segment File Format

Each `.log` file contains a sequence of **record batches**:

```
┌──────────────────────────────────────────────────────────┐
│                    Record Batch                          │
├──────────────┬───────────────────────────────────────────┤
│ Base Offset  │ 8 bytes — first offset in this batch     │
│ Batch Length │ 4 bytes — size of batch in bytes          │
│ Partition    │ 4 bytes — leader epoch                    │
│ Leader Epoch │                                           │
│ Magic Byte   │ 1 byte — format version (currently 2)    │
│ CRC          │ 4 bytes — checksum of batch contents      │
│ Attributes   │ 2 bytes — compression, timestamp type     │
│ Last Offset  │ 4 bytes — delta from base offset          │
│ First TS     │ 8 bytes — timestamp of first record       │
│ Max TS       │ 8 bytes — maximum timestamp in batch      │
│ Producer ID  │ 8 bytes — for idempotent/transactional    │
│ Producer Ep  │ 2 bytes — producer epoch                  │
│ Base Seq     │ 4 bytes — base sequence number            │
│ Record Count │ 4 bytes — number of records               │
├──────────────┴───────────────────────────────────────────┤
│ Record 0 | Record 1 | ... | Record N                    │
└──────────────────────────────────────────────────────────┘
```

### Index Files

The `.index` file maps offsets to physical file positions:

```
Offset   →   Physical Position (bytes)
0        →   0
100      →   16384
200      →   32768
300      →   49152
```

When a consumer requests offset 150:
1. Binary search the `.index` to find the largest offset <= 150 (which is 100 at position 16384)
2. Scan forward from position 16384 until offset 150 is found
3. This limits the linear scan to at most one index interval (default 4KB)

### Simulation Mapping

Our `CommitLog.java` simplifies this:

```java
// Production Kafka: segment files + index files + binary search
// Our simulation: ArrayList<Message> with offset = list index
public synchronized long append(Message message) {
    long assignedOffset = currentOffset.getAndIncrement();
    message.setOffset(assignedOffset);
    log.add(message);
    return assignedOffset;
}
```

The key insight is identical: **offsets are monotonically increasing, assigned at write time,
and define strict ordering within a partition**.

### Log Retention

Kafka provides two retention strategies, both implemented in our simulation:

```
Time-based retention (cleanup.policy=delete):
  retention.ms=604800000  (7 days default)
  → Segments older than retention.ms are deleted

Size-based retention (cleanup.policy=delete):
  retention.bytes=1073741824  (1 GB)
  → Oldest segments deleted when partition exceeds limit

Log compaction (cleanup.policy=compact):
  → Background thread scans log, keeps only latest value per key
  → Perfect for changelog/snapshot topics
```

Our `RetentionPolicy.java` captures all three:

```java
public static RetentionPolicy timeBased(long ms) { ... }
public static RetentionPolicy sizeBased(long bytes) { ... }
public static RetentionPolicy compact() { ... }
```

---

## 3. ZooKeeper to KRaft Migration

### Why ZooKeeper Was Used

Originally, Kafka relied on Apache ZooKeeper for:
1. **Broker registration** - Ephemeral znodes for broker liveness
2. **Controller election** - One broker elected as controller via ZK
3. **Topic metadata** - Partition assignments, ISR lists
4. **Consumer offsets** - (Pre-0.9, moved to `__consumer_offsets` topic)
5. **ACLs and quotas** - Security configuration storage

### Problems with ZooKeeper

```
┌──────────────────────────────────────────────────────────┐
│                   ZooKeeper Problems                     │
├──────────────────────────────────────────────────────────┤
│                                                          │
│  1. Operational overhead — separate cluster to manage    │
│  2. Scalability ceiling — ZK watches don't scale past    │
│     ~200K partitions per cluster                         │
│  3. Controller bottleneck — single controller must       │
│     propagate all metadata changes to all brokers        │
│  4. Slow failover — controller election + full metadata  │
│     reload from ZK takes 10-30 seconds                   │
│  5. Inconsistent state — split-brain between Kafka       │
│     controller's in-memory state and ZK's state          │
│                                                          │
└──────────────────────────────────────────────────────────┘
```

### KRaft: Kafka's Built-In Consensus

KRaft (Kafka Raft) replaces ZooKeeper with a built-in Raft-based consensus protocol:

```
                   KRaft Architecture
                   
  ┌────────────────────────────────────────┐
  │          Controller Quorum             │
  │                                        │
  │  ┌──────────┐  ┌──────────┐  ┌──────────┐
  │  │Controller│  │Controller│  │Controller│
  │  │  Node 0  │  │  Node 1  │  │  Node 2  │
  │  │ (Active) │  │ (Standby)│  │ (Standby)│
  │  └────┬─────┘  └────┬─────┘  └────┬─────┘
  │       │              │              │     │
  │  ┌────┴──────────────┴──────────────┴──┐  │
  │  │    __cluster_metadata topic         │  │
  │  │    (Raft-replicated log)            │  │
  │  └─────────────────────────────────────┘  │
  └────────────────────────────────────────┘
               │
               │ Metadata updates
               ▼
  ┌────────┐  ┌────────┐  ┌────────┐
  │Broker 0│  │Broker 1│  │Broker 2│
  │  (data)│  │  (data)│  │  (data)│
  └────────┘  └────────┘  └────────┘
```

### KRaft Improvements

| Dimension | ZooKeeper | KRaft |
|---|---|---|
| **Partition limit** | ~200K per cluster | Millions per cluster |
| **Controller failover** | 10-30 seconds | <5 seconds |
| **Metadata propagation** | Controller pushes to brokers | Brokers pull from log |
| **Operational complexity** | Two clusters (Kafka + ZK) | Single cluster |
| **Consistency model** | Eventual (ZK watches) | Log-based (always consistent) |
| **Deployment** | 3+ ZK nodes + N brokers | N brokers (some are controllers) |

### Migration Path

```
Phase 1: Dual-write mode
  KRaft controllers + ZooKeeper both active
  Kafka writes metadata to both systems
  
Phase 2: KRaft primary
  Remove ZooKeeper dependency from data path
  ZK still running for rollback safety
  
Phase 3: ZooKeeper removal
  Stop ZK ensemble
  All metadata via KRaft __cluster_metadata topic
```

### KRaft Timeline

- **Kafka 2.8 (2021)** - KRaft early access (development only)
- **Kafka 3.3 (2022)** - KRaft production-ready (KIP-833)
- **Kafka 3.5 (2023)** - ZK-to-KRaft migration tooling
- **Kafka 4.0 (2024)** - ZooKeeper removed entirely

---

## 4. Kafka Consumer Groups

### What They Solve

Consumer groups provide **parallel consumption** with automatic partition assignment and
failover. Each partition is consumed by exactly one consumer within a group.

### Assignment Strategies

```
                    6 Partitions, 3 Consumers
                    
  Range Assignment (default):
  ┌─────┐ ┌─────┐ ┌─────┐ ┌─────┐ ┌─────┐ ┌─────┐
  │ P0  │ │ P1  │ │ P2  │ │ P3  │ │ P4  │ │ P5  │
  └──┬──┘ └──┬──┘ └──┬──┘ └──┬──┘ └──┬──┘ └──┬──┘
     │       │       │       │       │       │
     └───┬───┘       └───┬───┘       └───┬───┘
         │               │               │
      ┌──▼──┐         ┌──▼──┐         ┌──▼──┐
      │ C0  │         │ C1  │         │ C2  │
      │P0,P1│         │P2,P3│         │P4,P5│
      └─────┘         └─────┘         └─────┘
      
  Round-Robin Assignment:
  ┌─────┐ ┌─────┐ ┌─────┐ ┌─────┐ ┌─────┐ ┌─────┐
  │ P0  │ │ P1  │ │ P2  │ │ P3  │ │ P4  │ │ P5  │
  └──┬──┘ └──┬──┘ └──┬──┘ └──┬──┘ └──┬──┘ └──┬──┘
     │       │       │       │       │       │
     │       │       └───┐   │       │   ┌───┘
     │       └───┐       │   └───┐   │   │
     ▼           ▼       ▼       ▼   ▼   ▼
  ┌─────┐     ┌─────┐     ┌─────┐
  │ C0  │     │ C1  │     │ C2  │
  │P0,P3│     │P1,P4│     │P2,P5│
  └─────┘     └─────┘     └─────┘
  
  Sticky Assignment:
  Same as round-robin, but minimizes partition movement
  during rebalances — consumers keep existing assignments
  when possible.
```

### Our Simulation's Range Assignment

```java
// ConsumerGroupCoordinator.java — rebalance()
int partitionsPerConsumer = partitionCount / consumerCount;
int remainder = partitionCount % consumerCount;

for (int i = 0; i < consumerCount; i++) {
    String consumerId = consumerIds.get(i);
    int count = partitionsPerConsumer + (i < remainder ? 1 : 0);
    // assign 'count' consecutive partitions starting at partitionIndex
}
```

### Rebalance Protocol

```
  Consumer A          Group Coordinator          Consumer B
      │                      │                       │
      │── JoinGroup ────────▶│                       │
      │                      │◀────── JoinGroup ─────│
      │                      │                       │
      │                      │ (Wait for all         │
      │                      │  members or timeout)  │
      │                      │                       │
      │◀─ JoinResponse ─────│─── JoinResponse ─────▶│
      │   (leader=A)         │   (follower=B)        │
      │                      │                       │
      │── SyncGroup ────────▶│                       │
      │   (assignments)      │◀────── SyncGroup ─────│
      │                      │                       │
      │◀─ SyncResponse ─────│─── SyncResponse ─────▶│
      │   (your partitions)  │   (your partitions)   │
      │                      │                       │
      │── Heartbeat ────────▶│                       │
      │   (every 3s)         │                       │
```

### Cooperative Incremental Rebalancing (Kafka 2.4+)

Traditional "eager" rebalancing revokes all partitions from all consumers during rebalance.
Cooperative incremental rebalancing only revokes partitions that need to move:

```
Eager rebalance (stop-the-world):
  1. All consumers release ALL partitions
  2. All consumers rejoin
  3. New assignments distributed
  4. Consumption resumes
  → Total downtime: seconds to minutes

Cooperative incremental rebalance:
  1. Coordinator identifies which partitions need to move
  2. Only those partitions are revoked from their current owner
  3. Remaining partitions continue processing
  4. Moved partitions reassigned in second rebalance round
  → Partial downtime: milliseconds per moved partition
```

---

## 5. Kafka Exactly-Once Semantics (EOS)

### The Three Guarantees

```
┌────────────────────────────────────────────────────────────┐
│                  Delivery Guarantees                       │
├──────────────────┬─────────────────────────────────────────┤
│                  │                                         │
│  AT_MOST_ONCE    │  Consumer commits BEFORE processing     │
│                  │  → Message may be lost (crash after     │
│                  │    commit, before processing)           │
│                  │  → Never duplicated                     │
│                  │                                         │
│  AT_LEAST_ONCE   │  Consumer commits AFTER processing      │
│                  │  → Message never lost                   │
│                  │  → May be duplicated (crash after       │
│                  │    processing, before commit)           │
│                  │                                         │
│  EXACTLY_ONCE    │  Atomic: process + commit in one TX     │
│                  │  → Message never lost                   │
│                  │  → Never duplicated                     │
│                  │  → Requires idempotent producer +       │
│                  │    transactional consumer               │
│                  │                                         │
└──────────────────┴─────────────────────────────────────────┘
```

### How Kafka EOS Works

```
              Idempotent Producer (enable.idempotence=true)
              
  Producer                      Broker
     │                            │
     │── Produce(PID=5, seq=0) ──▶│ ← first attempt
     │                            │ (stores PID:seq mapping)
     │    (network timeout)       │
     │                            │
     │── Produce(PID=5, seq=0) ──▶│ ← retry (same PID+seq)
     │                            │ (detects duplicate, returns
     │◀── Ack(offset=42) ────────│  existing offset)
     │                            │
     │── Produce(PID=5, seq=1) ──▶│ ← next message
     │◀── Ack(offset=43) ────────│
     │                            │
     │── Produce(PID=5, seq=3) ──▶│ ← out-of-order! seq=2 missing
     │◀── OutOfOrderSequence ────│  (fatal error — producer must
     │                            │   reinitialize)

              Transactional Producer + Consumer

  Producer                      Broker                    Consumer
     │                            │                          │
     │── InitTransactions() ─────▶│                          │
     │── BeginTransaction() ─────▶│                          │
     │── Produce(msg1, TX) ──────▶│                          │
     │── Produce(msg2, TX) ──────▶│                          │
     │── SendOffsetsToTxn() ─────▶│ (consumer offsets        │
     │                            │  in same TX)             │
     │── CommitTransaction() ────▶│                          │
     │                            │ ┌─────────────────────┐  │
     │                            │ │ TX committed:       │  │
     │                            │ │  - msg1 visible     │──▶ (reads msg1, msg2)
     │                            │ │  - msg2 visible     │  │
     │                            │ │  - offsets committed│  │
     │                            │ └─────────────────────┘  │
```

### Our Simulation's EOS Approach

Our `ExactlyOnceDeliveryStrategy.java` simulates the **consumer-side idempotency** pattern:

```java
// Consumer-side deduplication via delivered message ID tracking
private final Set<String> deliveredIds = ConcurrentHashMap.newKeySet();

public boolean deliver(Message message, String consumerId) {
    String messageId = message.getId();
    if (deliveredIds.contains(messageId)) {
        // duplicate detected — skip (idempotent)
        return true;
    }
    deliveredIds.add(messageId);
    return true;
}
```

This captures the core idea: **idempotent processing makes at-least-once equivalent to
exactly-once from the application's perspective**.

### EOS Performance Cost

| Setting | Throughput Impact | Latency Impact | When to Use |
|---|---|---|---|
| acks=1, no idem | Baseline | Baseline | Logs, metrics |
| acks=all, no idem | ~10-20% reduction | +5-10ms | Important data |
| acks=all + idempotent | ~15-25% reduction | +5-10ms | Critical data |
| Transactional | ~30-50% reduction | +20-50ms | Financial, exactly-once |

---

## 6. RabbitMQ

### What It Is

RabbitMQ is an open-source message broker implementing the AMQP (Advanced Message Queuing
Protocol). Unlike Kafka's log-based model, RabbitMQ uses a **queue-based** model with
sophisticated routing through exchanges.

### Architecture

```
  ┌──────────┐     ┌──────────────────────────────────────┐     ┌──────────┐
  │ Producer │────▶│            RabbitMQ Broker            │────▶│ Consumer │
  │          │     │                                      │     │          │
  └──────────┘     │  ┌──────────┐    ┌──────────────┐   │     └──────────┘
                   │  │ Exchange │───▶│    Queue      │   │
  ┌──────────┐     │  │  (route) │    │  (buffer)    │   │     ┌──────────┐
  │ Producer │────▶│  └──────────┘    └──────────────┘   │────▶│ Consumer │
  │          │     │       │          ┌──────────────┐   │     │          │
  └──────────┘     │       └─────────▶│    Queue      │   │     └──────────┘
                   │                  │  (buffer)    │   │
                   │                  └──────────────┘   │
                   └──────────────────────────────────────┘
```

### Kafka vs. RabbitMQ Comparison

| Dimension | Kafka | RabbitMQ |
|---|---|---|
| **Model** | Log (append-only) | Queue (consume-and-delete) |
| **Ordering** | Per-partition | Per-queue (FIFO) |
| **Replay** | Yes (seek to offset) | No (message deleted after ack) |
| **Routing** | Topic-based | Exchange-based (direct, topic, fanout, headers) |
| **Protocol** | Custom binary | AMQP 0-9-1 |
| **Push/Pull** | Pull (consumer polls) | Push (broker delivers) |
| **Throughput** | Millions msg/sec | Tens of thousands msg/sec |
| **Latency** | Higher (batching) | Lower (per-message) |
| **Use case** | Event streaming, log aggregation | Task queues, RPC, workflow |
| **Consumer scaling** | Partition-bound | Queue-bound (competing consumers) |
| **Persistence** | Always (log) | Optional (per-queue/message) |

### When to Choose RabbitMQ Over Kafka

1. **Complex routing** - Need header-based or pattern-based message routing
2. **Task queues** - Work distribution with acknowledgment and retry
3. **Low latency** - Per-message delivery without batching overhead
4. **Request-reply (RPC)** - Built-in correlation ID and reply-to support
5. **Priority queues** - RabbitMQ supports message priority levels
6. **Small-scale** - Fewer than 10K msg/sec, simpler operations

---

## 7. AMQP Protocol Deep Dive

### AMQP 0-9-1 Model

```
  ┌─────────────────────────────────────────────────────────┐
  │                   AMQP Connection                       │
  │                                                         │
  │  ┌───────────────────────────────────────────────────┐  │
  │  │                  Channel 1                        │  │
  │  │  publish() → Exchange → Binding → Queue → consume │  │
  │  └───────────────────────────────────────────────────┘  │
  │                                                         │
  │  ┌───────────────────────────────────────────────────┐  │
  │  │                  Channel 2                        │  │
  │  │  publish() → Exchange → Binding → Queue → consume │  │
  │  └───────────────────────────────────────────────────┘  │
  │                                                         │
  │  (Channels multiplex over a single TCP connection)      │
  └─────────────────────────────────────────────────────────┘
```

### AMQP Frame Format

```
┌────────┬────────┬──────────┬─────────┬──────────┐
│  Type  │Channel │  Size    │ Payload │ Frame-End│
│ 1 byte │2 bytes │ 4 bytes  │ N bytes │  0xCE    │
└────────┴────────┴──────────┴─────────┴──────────┘

Frame types:
  1 = Method frame   (e.g., Basic.Publish, Basic.Consume)
  2 = Header frame   (content header with properties)
  3 = Body frame     (actual message payload)
  4 = Heartbeat      (keep connection alive)
```

### Message Lifecycle in AMQP

```
1. Producer publishes to an exchange with a routing key
2. Exchange evaluates bindings (routing rules)
3. Message routed to zero or more queues
4. Queue stores message until a consumer is ready
5. Broker pushes message to consumer (Basic.Deliver)
6. Consumer processes and sends ack (Basic.Ack)
7. Broker removes message from queue upon ack
```

### AMQP vs. Kafka Protocol

| Feature | AMQP (RabbitMQ) | Kafka Protocol |
|---|---|---|
| **Connection model** | Multiplexed channels | One connection per broker |
| **Flow control** | Credit-based (prefetch) | Consumer-driven polling |
| **Acknowledgment** | Per-message or batch | Offset commit |
| **Message format** | Properties + body | Record batch (compressed) |
| **Routing** | Exchange + binding key | Topic + partition |
| **Heartbeat** | Frame-level | Broker-level session timeout |

---

## 8. RabbitMQ Exchange Types

### Exchange Taxonomy

```
  ┌─────────────────────────────────────────────────────────────┐
  │                     Exchange Types                          │
  │                                                             │
  │  1. Direct Exchange                                         │
  │     routing_key == binding_key  →  route to that queue      │
  │                                                             │
  │  2. Fanout Exchange                                         │
  │     Ignore routing key → broadcast to ALL bound queues      │
  │                                                             │
  │  3. Topic Exchange                                          │
  │     Pattern matching: "order.*.created" matches             │
  │     "order.us.created" but not "order.us.shipped"          │
  │     (* = one word, # = zero or more words)                  │
  │                                                             │
  │  4. Headers Exchange                                        │
  │     Match on message headers (key-value pairs)              │
  │     x-match: "all" (AND) or "any" (OR)                     │
  │                                                             │
  │  5. Default Exchange (nameless)                             │
  │     routing_key = queue name → direct delivery              │
  │                                                             │
  └─────────────────────────────────────────────────────────────┘
```

### Direct Exchange Example

```
  Producer                  Direct Exchange             Queues
     │                           │
     │── routing_key="error" ───▶│
     │                           │──▶ [error_queue]     (binding_key="error")
     │                           │
     │── routing_key="info" ────▶│
     │                           │──▶ [info_queue]      (binding_key="info")
     │                           │
     │── routing_key="debug" ───▶│
     │                           │──▶ (dropped — no binding)
```

### Topic Exchange Example

```
  Producer                  Topic Exchange              Queues
     │                           │
     │── "order.us.created" ────▶│
     │                           │──▶ [us_orders]       (binding="order.us.*")
     │                           │──▶ [all_orders]      (binding="order.#")
     │                           │
     │── "order.eu.shipped" ────▶│
     │                           │──▶ [all_orders]      (binding="order.#")
     │                           │──▶ [eu_shipping]     (binding="order.eu.shipped")
```

### Fanout Exchange Use Cases

```
  Event: "user.signup"

  Fanout Exchange ─────┬──▶ [email_queue]        → Send welcome email
                       ├──▶ [analytics_queue]    → Track signup event
                       ├──▶ [notification_queue] → Push notification
                       └──▶ [crm_queue]          → Create CRM record
```

---

## 9. Apache Pulsar

### What It Is

Apache Pulsar is a cloud-native distributed messaging and streaming platform originally
developed at Yahoo in 2013. Its key differentiator is the **separation of compute (serving)
and storage**.

### Architecture

```
  ┌───────────────────────────────────────────────────────────┐
  │                    Pulsar Cluster                         │
  │                                                           │
  │  ┌─────────────────────────────────────────────────────┐  │
  │  │              Serving Layer (Brokers)                 │  │
  │  │                                                     │  │
  │  │  ┌────────┐  ┌────────┐  ┌────────┐               │  │
  │  │  │Broker 0│  │Broker 1│  │Broker 2│  (stateless)   │  │
  │  │  └───┬────┘  └───┬────┘  └───┬────┘               │  │
  │  │      │            │            │                    │  │
  │  └──────┼────────────┼────────────┼────────────────────┘  │
  │         │            │            │                       │
  │  ┌──────┼────────────┼────────────┼────────────────────┐  │
  │  │      ▼            ▼            ▼                    │  │
  │  │           Storage Layer (BookKeeper)                 │  │
  │  │                                                     │  │
  │  │  ┌────────┐  ┌────────┐  ┌────────┐  ┌────────┐   │  │
  │  │  │Bookie 0│  │Bookie 1│  │Bookie 2│  │Bookie 3│   │  │
  │  │  └────────┘  └────────┘  └────────┘  └────────┘   │  │
  │  │                                                     │  │
  │  └─────────────────────────────────────────────────────┘  │
  │                                                           │
  │  ┌─────────────────────────────────────────────────────┐  │
  │  │              Metadata (ZooKeeper)                    │  │
  │  └─────────────────────────────────────────────────────┘  │
  └───────────────────────────────────────────────────────────┘
```

### Pulsar vs. Kafka

| Dimension | Apache Pulsar | Apache Kafka |
|---|---|---|
| **Compute/storage** | Separated (broker + bookie) | Co-located (broker = storage) |
| **Scaling** | Scale independently | Scale together |
| **Multi-tenancy** | Built-in (tenant/namespace) | Requires separate clusters |
| **Geo-replication** | Built-in, active-active | MirrorMaker (active-passive) |
| **Tiered storage** | Native (offload to S3/GCS) | Third-party (Confluent Tiered) |
| **Subscription modes** | Exclusive, shared, failover, key_shared | Consumer groups only |
| **Message ack** | Individual message ack | Offset-based (batch ack) |
| **Dead letter** | Built-in | Manual implementation |
| **Schema** | Built-in schema registry | External (Confluent) |
| **Adoption** | Growing | Dominant |

---

## 10. Pulsar Tiered Storage

### How It Works

```
  ┌────────────────────────────────────────────────────────────┐
  │                 Tiered Storage Architecture                │
  │                                                            │
  │  Tier 1: BookKeeper (Hot)                                  │
  │  ┌──────────────────────────────────────────────────┐     │
  │  │ Active ledgers — recent data                     │     │
  │  │ Low latency reads/writes                         │     │
  │  │ SSD-backed, replicated across bookies            │     │
  │  └──────────────────┬───────────────────────────────┘     │
  │                     │                                      │
  │                     │ Offload (age/size threshold)         │
  │                     ▼                                      │
  │  Tier 2: Object Storage (Cold)                             │
  │  ┌──────────────────────────────────────────────────┐     │
  │  │ Sealed ledgers — historical data                 │     │
  │  │ S3 / GCS / Azure Blob / HDFS                    │     │
  │  │ ~10x cheaper per GB than SSDs                    │     │
  │  │ Higher latency but acceptable for catch-up reads │     │
  │  └──────────────────────────────────────────────────┘     │
  │                                                            │
  └────────────────────────────────────────────────────────────┘
```

### Cost Impact

```
  Scenario: 10 TB of message data, 30-day retention

  Without tiered storage (all on SSD):
    10 TB * $0.10/GB/month = $1,024/month

  With tiered storage (1 TB hot, 9 TB cold on S3):
    1 TB SSD  * $0.10/GB/month = $102.40/month
    9 TB S3   * $0.023/GB/month = $211.97/month
    Total: $314.37/month (69% savings)
```

---

## 11. Pulsar Multi-Tenancy

### Namespace Hierarchy

```
  Pulsar Instance
  └── Tenant: "autodesk"
      ├── Namespace: "autodesk/cad-events"
      │   ├── Topic: persistent://autodesk/cad-events/file-saves
      │   ├── Topic: persistent://autodesk/cad-events/model-updates
      │   └── Policies: retention=7d, rate-limit=10K/s
      │
      └── Namespace: "autodesk/billing"
          ├── Topic: persistent://autodesk/billing/invoices
          ├── Topic: persistent://autodesk/billing/payments
          └── Policies: retention=365d, rate-limit=1K/s, encryption=on
```

### Isolation Guarantees

| Isolation Level | Mechanism |
|---|---|
| **Rate limiting** | Per-namespace publish/consume rate limits |
| **Storage quotas** | Per-namespace storage byte limits |
| **Resource isolation** | Broker-level CPU/memory limits per namespace |
| **Authentication** | Per-tenant auth tokens (JWT or mTLS) |
| **Authorization** | Per-namespace ACLs (produce, consume, admin) |
| **Backlog quotas** | Per-namespace consumer backlog limits |

---

## 12. AWS SQS

### What It Is

Amazon Simple Queue Service (SQS) is a fully managed message queuing service.
It provides two queue types: Standard and FIFO.

### Standard vs. FIFO

```
  Standard Queue:                    FIFO Queue:
  ┌──────────────────────┐          ┌──────────────────────┐
  │ Nearly unlimited TPS │          │ 3,000 msg/s (batch)  │
  │ At-least-once        │          │ Exactly-once          │
  │ Best-effort ordering │          │ Strict FIFO ordering  │
  │ $0.40 per million    │          │ $0.50 per million     │
  │                      │          │ Message group IDs     │
  │  Use: decouple       │          │                      │
  │  microservices       │          │  Use: ordered         │
  │                      │          │  transactions         │
  └──────────────────────┘          └──────────────────────┘
```

### SQS Visibility Timeout

```
  Producer ──▶ SQS Queue ──▶ Consumer A

  t=0s    Message arrives in queue
  t=1s    Consumer A receives message
          → Message becomes INVISIBLE for 30s (default)
  t=5s    Consumer B polls → does NOT see the message
  t=10s   Consumer A finishes processing → sends DeleteMessage
          → Message permanently removed

  If Consumer A crashes:
  t=31s   Visibility timeout expires
          → Message becomes VISIBLE again
  t=32s   Consumer B receives the message → processes it
```

### SQS Dead Letter Queue

```
  Main Queue                     Dead Letter Queue
  ┌──────────────────────┐      ┌──────────────────────┐
  │ Message "order-123"  │      │                      │
  │ Receive count: 0     │      │                      │
  └──────────┬───────────┘      │                      │
             │                   │                      │
  Attempt 1: Consumer fails     │                      │
  Attempt 2: Consumer fails     │                      │
  Attempt 3: Consumer fails     │                      │
  (maxReceiveCount=3 reached)   │                      │
             │                   │                      │
             └──────────────────▶│ Message "order-123"  │
                                 │ Receive count: 3     │
                                 │ → Manual inspection  │
                                 └──────────────────────┘
```

---

## 13. AWS SNS

### What It Is

Amazon Simple Notification Service (SNS) is a fully managed pub/sub messaging service.
Unlike SQS, SNS pushes messages to subscribers immediately.

### Fan-Out Pattern: SNS + SQS

```
  Producer ──▶ SNS Topic "order-events"
                      │
               ┌──────┼──────┐──────────────────┐
               ▼      ▼      ▼                   ▼
           SQS Queue  SQS    SQS              Lambda
           "email"    "inv"  "analytics"      Function
               │      │      │                   │
               ▼      ▼      ▼                   ▼
           Email    Inventory  Analytics      Real-time
           Service  Service    Pipeline       Alert
```

### SNS Message Filtering

```json
{
  "store": ["us-east"],
  "event": ["order_placed"],
  "price": [{"numeric": [">=", 100]}]
}
```

Only messages matching the filter policy are delivered to that subscriber.
This eliminates the need for consumers to filter irrelevant messages.

### SNS vs. SQS

| Feature | SNS | SQS |
|---|---|---|
| **Model** | Pub/Sub (push) | Queue (pull) |
| **Consumers** | Many subscribers | One consumer per message |
| **Persistence** | No (delivery only) | Yes (retained until consumed) |
| **Ordering** | FIFO topics available | Standard or FIFO |
| **Filtering** | Attribute-based policies | No built-in filtering |
| **Protocols** | HTTP, email, SMS, SQS, Lambda | HTTP (polling) |

---

## 14. AWS Kinesis Data Streams

### What It Is

Amazon Kinesis Data Streams is a real-time data streaming service. It uses a **shard-based**
model similar to Kafka's partitions.

### Architecture

```
  ┌──────────────────────────────────────────────────────────┐
  │                 Kinesis Data Stream                       │
  │                                                          │
  │  ┌────────┐  ┌────────┐  ┌────────┐  ┌────────┐        │
  │  │Shard 0 │  │Shard 1 │  │Shard 2 │  │Shard 3 │        │
  │  │ 1MB/s  │  │ 1MB/s  │  │ 1MB/s  │  │ 1MB/s  │        │
  │  │write   │  │write   │  │write   │  │write   │        │
  │  │ 2MB/s  │  │ 2MB/s  │  │ 2MB/s  │  │ 2MB/s  │        │
  │  │read    │  │read    │  │read    │  │read    │        │
  │  └────────┘  └────────┘  └────────┘  └────────┘        │
  │                                                          │
  │  Retention: 24 hours (default) → up to 365 days          │
  │  Capacity: 1 MB/s or 1000 records/s per shard (write)   │
  │            2 MB/s per shard (read)                       │
  └──────────────────────────────────────────────────────────┘
```

### Kinesis vs. Kafka

| Dimension | Kinesis | Kafka |
|---|---|---|
| **Managed** | Fully managed | Self-managed (or MSK) |
| **Partition unit** | Shard (fixed capacity) | Partition (flexible) |
| **Throughput per shard** | 1 MB/s write, 2 MB/s read | No per-partition limit |
| **Retention** | 24h to 365 days | Unlimited (configurable) |
| **Scaling** | Shard splitting/merging | Add partitions (no removal) |
| **Consumer model** | KCL (enhanced fan-out) | Consumer groups |
| **Pricing** | Per shard-hour + PUT payload | Per broker instance |
| **Replay** | Yes (within retention) | Yes (within retention) |
| **Compaction** | No | Yes (log compaction) |

---

## 15. Amazon MSK

### What It Is

Amazon MSK (Managed Streaming for Apache Kafka) is a fully managed Kafka service
that handles broker provisioning, patching, and cluster management.

### MSK Modes

```
  ┌─────────────────────────────────────────────────────────┐
  │                    MSK Provisioned                      │
  │                                                         │
  │  You choose:                                            │
  │   - Broker instance type (kafka.m5.large, etc.)        │
  │   - Number of brokers per AZ                           │
  │   - Storage volume size                                │
  │                                                         │
  │  Good for: predictable workloads, cost optimization     │
  └─────────────────────────────────────────────────────────┘

  ┌─────────────────────────────────────────────────────────┐
  │                    MSK Serverless                       │
  │                                                         │
  │  You specify:                                           │
  │   - Topics and partitions                              │
  │   - AWS auto-scales compute and storage                │
  │                                                         │
  │  Good for: variable workloads, quick start              │
  │  Cost: pay per data throughput ($0.10/GB in/out)        │
  └─────────────────────────────────────────────────────────┘
```

### MSK vs. Self-Managed Kafka

| Responsibility | MSK | Self-Managed |
|---|---|---|
| Broker provisioning | AWS | You |
| OS patching | AWS | You |
| Kafka version upgrades | AWS (rolling) | You |
| Monitoring | CloudWatch + Prometheus | You |
| Storage scaling | Automatic | Manual |
| Networking | VPC integration | Your VPC |
| ZK/KRaft | Managed | You |
| Custom plugins | Yes (MSK Connect) | Yes |
| Cost | Higher per broker | Lower, more ops work |

---

## 16. Redis Streams

### What It Is

Redis Streams (introduced in Redis 5.0) is a log-like data structure built into Redis.
It provides consumer groups similar to Kafka but with Redis's in-memory performance.

### Data Model

```
  Stream key: "orders"
  
  Entry ID         Fields
  ─────────────────────────────────
  1692345678901-0  {user: "alice", item: "widget", qty: "3"}
  1692345678902-0  {user: "bob",   item: "gadget", qty: "1"}
  1692345678903-0  {user: "alice", item: "thing",  qty: "5"}
  
  Entry ID format: <millisecondsTimestamp>-<sequenceNumber>
```

### Redis Streams Consumer Groups

```
  XGROUP CREATE orders analytics-group 0

  Consumer A: XREADGROUP GROUP analytics-group consumer-a COUNT 10 BLOCK 2000 STREAMS orders >
  Consumer B: XREADGROUP GROUP analytics-group consumer-b COUNT 10 BLOCK 2000 STREAMS orders >

  ┌─────────────────────────────────────────────┐
  │ Stream: "orders"                            │
  │ ┌─────┐ ┌─────┐ ┌─────┐ ┌─────┐ ┌─────┐  │
  │ │  0  │ │  1  │ │  2  │ │  3  │ │  4  │  │
  │ └──┬──┘ └──┬──┘ └──┬──┘ └──┬──┘ └──┬──┘  │
  │    │       │       │       │       │      │
  │    ▼       ▼       ▼       ▼       ▼      │
  │   C-A     C-B     C-A     C-B     C-A     │
  │                                            │
  │ Consumer Group: "analytics-group"          │
  │ last_delivered_id: 4                       │
  └─────────────────────────────────────────────┘
```

### Redis Streams vs. Kafka

| Feature | Redis Streams | Kafka |
|---|---|---|
| **Storage** | In-memory (+ RDB/AOF) | On-disk (page cache) |
| **Throughput** | ~100K msg/s per node | Millions msg/s per cluster |
| **Retention** | Memory-bounded (MAXLEN/MINID) | Disk-bounded (time/size) |
| **Persistence** | Optional (RDB snapshots, AOF) | Always (commit log) |
| **Consumer groups** | Yes (XREADGROUP) | Yes |
| **Replication** | Redis replication (async) | ISR-based (sync configurable) |
| **Use case** | Low-latency, small-scale streaming | Large-scale event streaming |

---

## 17. ZeroMQ

### What It Is

ZeroMQ (also written as 0MQ or ZMQ) is a high-performance asynchronous messaging library.
It is NOT a broker — it is a library that applications link against directly.

### Patterns

```
  1. REQ-REP (Request-Reply)
     Client ──REQ──▶ ◀──REP── Server
     Synchronous request-reply pattern.

  2. PUB-SUB (Publish-Subscribe)
     Publisher ──PUB──▶ ◀──SUB── Subscriber 1
                       ◀──SUB── Subscriber 2
     Topic-filtered broadcast.

  3. PUSH-PULL (Pipeline)
     Ventilator ──PUSH──▶ Worker 1 ──PUSH──▶ Sink
                         ▶ Worker 2 ──PUSH──▶
                         ▶ Worker 3 ──PUSH──▶
     Parallel task distribution.

  4. DEALER-ROUTER (Async Request-Reply)
     Client ──DEALER──▶ ◀──ROUTER── Server
     Non-blocking, multi-part messages.
```

### ZeroMQ vs. Kafka/RabbitMQ

| Feature | ZeroMQ | Kafka / RabbitMQ |
|---|---|---|
| **Type** | Library | Server (broker) |
| **Broker** | None (peer-to-peer) | Central broker |
| **Persistence** | None | Yes |
| **Ops overhead** | Zero (embedded) | Cluster management |
| **Latency** | Microseconds | Milliseconds |
| **Throughput** | Millions msg/s | Millions (Kafka) / thousands (RMQ) |
| **Reliability** | Application-managed | Broker-managed |
| **Use case** | Inter-process, low-latency | Durable messaging |

---

## 18. Protocol Buffers (Protobuf)

### What It Is

Protocol Buffers is Google's language-neutral, platform-neutral mechanism for
serializing structured data. It is smaller and faster than JSON.

### Schema Definition

```protobuf
// message_queue.proto

syntax = "proto3";

package messagequeue;

message ProducerRecord {
  string topic = 1;
  string key = 2;
  bytes value = 3;
  map<string, string> headers = 4;
  int32 partition = 5;
  int64 timestamp = 6;
}

message ConsumerRecord {
  string topic = 1;
  int32 partition = 2;
  int64 offset = 3;
  string key = 4;
  bytes value = 5;
  map<string, string> headers = 6;
  int64 timestamp = 7;
}

message CommitRequest {
  string group_id = 1;
  string topic = 2;
  int32 partition = 3;
  int64 offset = 4;
}
```

### Wire Format

```
Field 1 (string, topic):
  Tag: 0x0A (field=1, wire type=2 length-delimited)
  Length: 0x06
  Value: "orders"

Field 2 (string, key):
  Tag: 0x12 (field=2, wire type=2)
  Length: 0x08
  Value: "user-123"

Total bytes for this record: ~30 bytes
Same data in JSON: ~120 bytes (4x larger)
```

### Protobuf vs. JSON Performance

| Metric | Protobuf | JSON |
|---|---|---|
| **Serialization** | ~10x faster | Baseline |
| **Deserialization** | ~10x faster | Baseline |
| **Wire size** | ~3-5x smaller | Baseline |
| **Schema** | Required (.proto file) | Optional |
| **Human readable** | No (binary) | Yes |
| **Schema evolution** | Field numbers (safe) | Breaking changes possible |

---

## 19. Apache Avro

### What It Is

Apache Avro is a data serialization system that stores schema alongside data
or via a schema registry. It is the default serialization for Kafka ecosystems.

### Schema Definition

```json
{
  "type": "record",
  "name": "ProducerRecord",
  "namespace": "com.systemdesign.messagequeue",
  "fields": [
    {"name": "topic", "type": "string"},
    {"name": "key", "type": ["null", "string"], "default": null},
    {"name": "value", "type": "bytes"},
    {"name": "headers", "type": {"type": "map", "values": "string"}, "default": {}},
    {"name": "partition", "type": "int", "default": -1},
    {"name": "timestamp", "type": "long", "logicalType": "timestamp-millis"}
  ]
}
```

### Avro vs. Protobuf

| Dimension | Avro | Protobuf |
|---|---|---|
| **Schema format** | JSON | .proto (IDL) |
| **Schema in data** | Embedded or registry | External .proto file |
| **Encoding** | No field tags (schema required) | Field tags in wire format |
| **Code generation** | Optional (GenericRecord) | Required |
| **Dynamic typing** | Yes (GenericRecord) | No |
| **Kafka ecosystem** | Default (Confluent) | Supported |
| **File format** | .avro container files | No container |
| **Schema evolution** | Full/transitive compat | Forward/backward compat |
| **Use case** | Big data (Hadoop, Kafka) | gRPC, microservices |

### Schema Evolution Rules

```
  Backward compatible (new reader, old writer):
    ✅ Add a field with default value
    ✅ Remove a field that had a default
    ❌ Remove a field without a default
    ❌ Change field type

  Forward compatible (old reader, new writer):
    ✅ Remove a field (old reader ignores unknown)
    ✅ Add a field with default
    ❌ Change field type

  Full compatible (both directions):
    ✅ Add a field with default AND keep old fields
```

---

## 20. Schema Registry

### What It Is

Schema Registry (Confluent) is a centralized repository for schemas (Avro, Protobuf,
JSON Schema). It assigns a unique ID to each schema version and validates compatibility.

### Architecture

```
  Producer                Schema Registry               Consumer
     │                         │                           │
     │── Register schema ─────▶│                           │
     │◀── Schema ID: 42 ──────│                           │
     │                         │                           │
     │── Produce(schemaId=42, │                           │
     │   data=<bytes>)        │                           │
     │          │              │                           │
     │          ▼              │                           │
     │     Kafka Broker        │                           │
     │          │              │                           │
     │          └──────────────┼──────────────────────────▶│
     │                         │                           │
     │                         │◀── GET /schemas/ids/42 ──│
     │                         │──▶ Schema definition ────▶│
     │                         │                           │
     │                         │    (consumer caches       │
     │                         │     schema locally)       │
```

### Wire Format with Schema Registry

```
┌───────┬──────────┬────────────────────────┐
│ Magic │Schema ID │    Avro/Protobuf Data   │
│ 0x00  │ 4 bytes  │    N bytes              │
│       │(big-end) │                         │
└───────┴──────────┴────────────────────────┘

Total overhead: 5 bytes per message
```

### Compatibility Modes

| Mode | Description | Use Case |
|---|---|---|
| **BACKWARD** | New schema can read old data | Consumer upgrades first |
| **FORWARD** | Old schema can read new data | Producer upgrades first |
| **FULL** | Both backward and forward | Any upgrade order |
| **BACKWARD_TRANSITIVE** | All previous versions compat | Strict backward |
| **FORWARD_TRANSITIVE** | All previous versions compat | Strict forward |
| **FULL_TRANSITIVE** | All previous versions compat | Strictest |
| **NONE** | No compatibility check | Development only |

---

## 21. Kafka Connect

### What It Is

Kafka Connect is a framework for streaming data between Kafka and external systems
(databases, file systems, search indices, cloud services) without writing custom code.

### Architecture

```
  ┌─────────────────────────────────────────────────────────┐
  │                    Kafka Connect Cluster                 │
  │                                                         │
  │  ┌───────────┐  ┌───────────┐  ┌───────────┐          │
  │  │ Worker 0  │  │ Worker 1  │  │ Worker 2  │          │
  │  │           │  │           │  │           │          │
  │  │ ┌───────┐ │  │ ┌───────┐ │  │ ┌───────┐ │          │
  │  │ │Task 0 │ │  │ │Task 2 │ │  │ │Task 4 │ │          │
  │  │ │Task 1 │ │  │ │Task 3 │ │  │ │Task 5 │ │          │
  │  │ └───────┘ │  │ └───────┘ │  │ └───────┘ │          │
  │  └───────────┘  └───────────┘  └───────────┘          │
  │                                                         │
  │  Connectors:                                            │
  │   Source (DB → Kafka):  JDBC, Debezium, S3, etc.       │
  │   Sink   (Kafka → DB):  JDBC, Elasticsearch, S3, etc. │
  └─────────────────────────────────────────────────────────┘
```

### Source Connector Example (CDC with Debezium)

```
  PostgreSQL ──(WAL)──▶ Debezium Connector ──▶ Kafka Topic
                                                    │
                        ┌───────────────────────────┘
                        │
               ┌────────┼────────┐
               ▼        ▼        ▼
          Elasticsearch  S3     Another DB
          (search)    (archive)  (replica)
```

### Key Connector Categories

| Category | Connectors | Direction |
|---|---|---|
| **Databases** | JDBC, Debezium, MongoDB | Source + Sink |
| **Cloud storage** | S3, GCS, Azure Blob | Source + Sink |
| **Search** | Elasticsearch, Solr | Sink |
| **Data warehouse** | Snowflake, BigQuery, Redshift | Sink |
| **Messaging** | JMS, RabbitMQ, SQS | Source + Sink |
| **File systems** | HDFS, local files, FTP | Source + Sink |

---

## 22. Kafka Streams

### What It Is

Kafka Streams is a client library for building real-time streaming applications.
Unlike Spark Streaming or Flink, it runs as a library inside your application — no
separate cluster required.

### Topology

```
  Input Topic         Kafka Streams Application         Output Topic
  "raw-orders"                                          "enriched-orders"
       │                                                     ▲
       ▼                                                     │
  ┌─────────┐    ┌──────────┐    ┌─────────┐    ┌──────────┐
  │ Source  │───▶│  Filter  │───▶│  Map    │───▶│  Sink    │
  │ (read)  │    │(amt>100) │    │(enrich) │    │ (write)  │
  └─────────┘    └──────────┘    └─────────┘    └──────────┘
                                      │
                                      ▼
                                ┌───────────┐
                                │  KTable   │
                                │ (state    │
                                │  store)   │
                                │ RocksDB   │
                                └───────────┘
```

### KStream vs. KTable

```
  KStream (event stream — insert-only):
    Key    Value       Interpretation
    k1     v1          Event: k1 set to v1
    k2     v2          Event: k2 set to v2
    k1     v3          Event: k1 set to v3  (both v1 and v3 exist)

  KTable (changelog — upsert):
    Key    Value       Interpretation
    k1     v1          State: k1 = v1
    k2     v2          State: k2 = v2
    k1     v3          State: k1 = v3  (v1 is replaced by v3)
    k2     null        State: k2 deleted (tombstone)
```

### Windowed Operations

```
  Tumbling Window (fixed, non-overlapping):
  ──┤ Window 1 ├──┤ Window 2 ├──┤ Window 3 ├──
    [0s    5s]    [5s   10s]    [10s  15s]
    
  Hopping Window (fixed, overlapping):
  ──┤ Window 1      ├──
    [0s         5s]
       ──┤ Window 2      ├──
         [2s         7s]
            ──┤ Window 3      ├──
              [4s         9s]

  Session Window (activity-based, gap-defined):
  ──┤ Session 1 ├─────gap─────┤ Session 2    ├──
    [event event]             [event event event]
    
  Sliding Window (event-driven, for joins):
  Every event triggers a window of [event_time - window, event_time + window]
```

---

## 23. Simulation-to-Production Mapping

### Complete Class-to-Technology Mapping

| Simulation Class | Production Equivalent | Key Difference |
|---|---|---|
| `CommitLog.java` | Kafka segment files (.log + .index) | ArrayList vs. disk segments with mmap |
| `Partition.java` | Kafka partition metadata | In-memory vs. ZK/KRaft stored |
| `Message.java` (Builder) | Kafka RecordBatch | POJO vs. binary wire format |
| `ReplicationEngine.java` | Kafka ISR replication | Simulated acks vs. actual network I/O |
| `ConsumerGroupCoordinator.java` | Kafka GroupCoordinator | In-process vs. broker-side coordinator |
| `HashPartitioningStrategy.java` | Kafka DefaultPartitioner | Same algorithm (murmur2 in production) |
| `RoundRobinPartitioningStrategy.java` | Kafka RoundRobinPartitioner | Same concept, different counter scope |
| `ExactlyOnceDeliveryStrategy.java` | Kafka Transactional API | Dedup set vs. PID+sequence+TX |
| `AtLeastOnceDeliveryStrategy.java` | Kafka auto.offset.reset=earliest + retry | Retry loop vs. consumer config |
| `LogCompactionStrategy.java` | Kafka Log Cleaner thread | LinkedHashMap vs. segment scanning |
| `TimeBasedRetentionStrategy.java` | Kafka LogManager retention | Timestamp check vs. segment mtime |
| `RetentionPolicy.java` | Kafka topic configs | Enum-based vs. per-topic properties |
| `AckMode.java` | Kafka acks config | Enum vs. producer config string |
| `TopicService.java` | Kafka AdminClient | In-memory vs. ZK/KRaft metadata |
| `ProducerService.java` | KafkaProducer | Synchronous vs. async + batching |
| `ConsumerService.java` | KafkaConsumer | Pull from in-memory vs. network fetch |
| `BrokerService.java` | Kafka Broker process | Single-node simulation vs. distributed |
| `MessageRouter.java` | Kafka internal request handler | Direct call vs. network RPC |

### What We Intentionally Simplified

```
  ┌─────────────────────────────────────────────────────────────┐
  │              Simplifications in Our Simulation              │
  ├─────────────────────────────────────────────────────────────┤
  │                                                             │
  │  1. Single-process      Real Kafka: multi-broker cluster    │
  │  2. In-memory storage   Real Kafka: disk + page cache       │
  │  3. No network I/O      Real Kafka: TCP between all nodes   │
  │  4. No compression      Real Kafka: snappy/lz4/zstd        │
  │  5. No batching         Real Kafka: linger.ms + batch.size  │
  │  6. No zero-copy        Real Kafka: sendfile() syscall      │
  │  7. Sync replication    Real Kafka: async with watermarks   │
  │  8. No leader election  Real Kafka: controller-managed      │
  │  9. No auth/SSL         Real Kafka: SASL + TLS              │
  │  10. No quotas          Real Kafka: per-client rate limits   │
  │                                                             │
  └─────────────────────────────────────────────────────────────┘
```

---

## 24. Technology Selection Matrix

### Decision Framework

| Requirement | Kafka | RabbitMQ | Pulsar | SQS | Kinesis | Redis Streams |
|---|---|---|---|---|---|---|
| High throughput (>100K/s) | A | C | A | B | B | B |
| Low latency (<1ms) | C | B | C | C | C | A |
| Message replay | A | F | A | F | A | B |
| Complex routing | C | A | B | F | F | F |
| Managed (no ops) | B (MSK) | B (CloudAMQP) | B (StreamNative) | A | A | B (ElastiCache) |
| Multi-tenancy | C | B | A | A | B | C |
| Exactly-once | A | B | A | A (FIFO) | C | C |
| Log compaction | A | F | A | F | F | F |
| Geo-replication | B | B | A | A | C | C |
| Cost efficiency | B | B | B | A | C | B |

**Legend:** A = Excellent, B = Good, C = Adequate, F = Not supported

### When to Use What

```
  Event streaming / log aggregation
    → Kafka or Pulsar

  Task queue / work distribution
    → RabbitMQ or SQS

  Real-time analytics pipeline
    → Kafka + Kafka Streams / Kinesis + Lambda

  Serverless event processing
    → SQS + Lambda / SNS + Lambda

  IoT / sensor data ingestion
    → Kinesis or Kafka

  Microservice decoupling (simple)
    → SQS or RabbitMQ

  Cache invalidation / notifications
    → Redis Streams or Redis Pub/Sub

  Inter-process communication (same host)
    → ZeroMQ
```

---

## 25. Interview Quick Reference

### "Design a Distributed Message Queue" — Technology Mentions

**Opening statement:**
"I would model this after Apache Kafka's architecture — a distributed commit log with
partitioned topics, consumer groups, and configurable replication."

**Key talking points by topic:**

```
  Storage:
    "Append-only commit log, segmented files, offset-based indexing.
     Sequential I/O on disk outperforms random I/O on SSDs.
     OS page cache eliminates the need for application-level caching."

  Partitioning:
    "Hash-based partitioning by message key ensures per-key ordering.
     Partition count is the unit of parallelism — more partitions means
     more consumers can process in parallel."

  Replication:
    "ISR (In-Sync Replicas) model with configurable ack modes.
     acks=all + min.insync.replicas=2 prevents data loss on leader failure.
     KRaft (replacing ZooKeeper) manages controller election and metadata."

  Consumer Groups:
    "Each partition is consumed by exactly one consumer in a group.
     Range or round-robin assignment. Cooperative rebalancing minimizes
     stop-the-world pauses when consumers join or leave."

  Delivery Semantics:
    "At-least-once by default (commit after processing).
     Exactly-once via idempotent producer (PID + sequence numbers) and
     transactional API (atomic produce + offset commit)."

  Serialization:
    "Avro with Schema Registry for schema evolution.
     5-byte overhead per message (magic byte + schema ID).
     Backward/forward compatibility checked at registration time."

  Retention:
    "Time-based (default 7 days) or size-based retention.
     Log compaction for changelog topics — keeps latest value per key.
     Tiered storage (Pulsar, Confluent) offloads cold data to S3."
```

**Comparison signals (show breadth):**
- "If the use case requires complex routing rather than streaming, I would consider
  RabbitMQ with its exchange-based model."
- "For a fully managed solution on AWS with no operational overhead, SQS provides
  at-least-once delivery with visibility timeouts."
- "If we need multi-tenancy with strong isolation, Apache Pulsar's tenant/namespace
  model provides built-in resource quotas."

---

*This document covers the technology landscape for distributed message queues.*
*Each technology is mapped back to our simulation code in Project 20.*
