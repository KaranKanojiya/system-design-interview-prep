# CAP Theorem & Tradeoffs: Distributed Message Queue (Project 20)

> CP for committed messages, AP for lag metrics, ordering guarantees,
> delivery semantics costs, log compaction vs. deletion, push vs. pull.

---

## Table of Contents

1. [CAP Theorem in Message Queues](#1-cap-theorem-in-message-queues)
2. [CP Mode: Committed Messages](#2-cp-mode-committed-messages)
3. [AP Mode: Consumer Lag and Metadata](#3-ap-mode-consumer-lag-and-metadata)
4. [Ordering Guarantees](#4-ordering-guarantees)
5. [Delivery Semantics: Cost Analysis](#5-delivery-semantics-cost-analysis)
6. [At-Least-Once vs. Exactly-Once](#6-at-least-once-vs-exactly-once)
7. [Log Compaction vs. Deletion](#7-log-compaction-vs-deletion)
8. [Push vs. Pull Consumption Model](#8-push-vs-pull-consumption-model)
9. [Ack Modes: Durability vs. Latency](#9-ack-modes-durability-vs-latency)
10. [Replication Tradeoffs](#10-replication-tradeoffs)
11. [Partition Count Tradeoffs](#11-partition-count-tradeoffs)
12. [Consistency Boundaries](#12-consistency-boundaries)
13. [Simulation-to-Production Tradeoff Mapping](#13-simulation-to-production-tradeoff-mapping)
14. [Interview Deep Dive](#14-interview-deep-dive)

---

## 1. CAP Theorem in Message Queues

### CAP Recap

The CAP theorem states that a distributed system can provide at most two of three guarantees:

```
                    Consistency (C)
                        /\
                       /  \
                      /    \
                     / CP   \
                    /________\
                   /\        /\
                  /  \  CA  /  \
                 / AP \    /    \
                /______\  /______\
         Availability (A)    Partition
                            Tolerance (P)
```

In the presence of network partitions (which are unavoidable in distributed systems),
you must choose between Consistency and Availability.

### How Kafka Applies CAP

Kafka does NOT make a single CAP choice for the entire system. Instead, it applies
**different CAP positions for different data paths**:

```
  ┌──────────────────────────────────────────────────────────────┐
  │              Kafka's CAP Positions                           │
  │                                                              │
  │  ┌──────────────────────────────────────┐                   │
  │  │ COMMITTED MESSAGES                    │                   │
  │  │ CAP Position: CP (Consistency)        │                   │
  │  │                                        │                   │
  │  │ acks=all + min.insync.replicas=2      │                   │
  │  │ → Rejects writes if ISR < 2           │                   │
  │  │ → Sacrifices availability for          │                   │
  │  │   consistency (no data loss)           │                   │
  │  └──────────────────────────────────────┘                   │
  │                                                              │
  │  ┌──────────────────────────────────────┐                   │
  │  │ CONSUMER LAG METRICS                  │                   │
  │  │ CAP Position: AP (Availability)       │                   │
  │  │                                        │                   │
  │  │ Lag values are eventually consistent  │                   │
  │  │ → Always available for reading        │                   │
  │  │ → May be slightly stale               │                   │
  │  └──────────────────────────────────────┘                   │
  │                                                              │
  │  ┌──────────────────────────────────────┐                   │
  │  │ METADATA (KRaft)                      │                   │
  │  │ CAP Position: CP (Consistency)        │                   │
  │  │                                        │                   │
  │  │ Raft consensus for cluster metadata   │                   │
  │  │ → Leader must have quorum to operate  │                   │
  │  │ → Metadata always consistent          │                   │
  │  └──────────────────────────────────────┘                   │
  │                                                              │
  │  ┌──────────────────────────────────────┐                   │
  │  │ CONSUMER OFFSETS                      │                   │
  │  │ CAP Position: CP (Consistency)        │                   │
  │  │                                        │                   │
  │  │ __consumer_offsets is a replicated    │                   │
  │  │ topic with acks=all                   │                   │
  │  │ → Offsets survive broker failures     │                   │
  │  └──────────────────────────────────────┘                   │
  └──────────────────────────────────────────────────────────────┘
```

---

## 2. CP Mode: Committed Messages

### The Configuration That Enforces CP

```
  Producer config:
    acks = all                 ← all ISR replicas must acknowledge
    retries = MAX_INT          ← retry indefinitely on transient errors
    enable.idempotence = true  ← deduplicate retried messages

  Broker config:
    min.insync.replicas = 2    ← at least 2 replicas must be in-sync
    replication.factor = 3     ← 3 copies of every partition
    unclean.leader.election.enable = false  ← never elect out-of-sync replica
```

### How CP Works in Practice

```
  Normal operation (3 replicas, ISR=3):

  Producer          Leader (B0)       Follower (B1)    Follower (B2)
     │                  │                  │                │
     │── Produce ──────▶│                  │                │
     │                  │── Replicate ────▶│                │
     │                  │── Replicate ────────────────────▶│
     │                  │                  │                │
     │                  │◀── Ack ─────────│                │
     │                  │◀── Ack ──────────────────────────│
     │                  │                  │                │
     │◀── Ack (3/3) ──│                  │                │
     │                  │                  │                │
     │  Message is COMMITTED (durable on 3 replicas)       │
```

### What Happens During a Partition

```
  Scenario: Broker B2 becomes unreachable (network partition)

  ISR shrinks: [B0, B1, B2] → [B0, B1]

  Case 1: min.insync.replicas = 2 (CP preserved)
  ┌──────────────────────────────────────────────────────────┐
  │ ISR = [B0, B1] → 2 replicas, meets min.insync = 2      │
  │ → Producer write SUCCEEDS (2/2 ISR ack)                 │
  │ → No data loss, reduced redundancy                      │
  └──────────────────────────────────────────────────────────┘

  Case 2: Another broker fails (B1 also goes down)
  ┌──────────────────────────────────────────────────────────┐
  │ ISR = [B0] → 1 replica, BELOW min.insync = 2           │
  │ → Producer write REJECTED (NotEnoughReplicasException)  │
  │ → AVAILABILITY SACRIFICED for consistency                │
  │ → No data loss — but writes are blocked                 │
  └──────────────────────────────────────────────────────────┘
```

### Our Simulation's CP Behavior

```java
// ReplicationEngine.java — acks=ALL mode
private boolean replicateToAllIsr(Message message, Partition partition) {
    List<String> isr = partition.getInSyncReplicaIds();

    // Warn if ISR is below replication factor (production would block)
    if (isr.size() < replicationFactor) {
        System.out.println("[REPLICATION] WARNING: ISR size (" + isr.size()
                + ") < replicationFactor (" + replicationFactor + ")");
    }

    // Simulate acknowledgment from each ISR replica
    for (String replicaId : isr) {
        System.out.println("[REPLICATION] Replica '" + replicaId + "' acknowledged");
    }

    return true;  // In production, would fail if ISR < min.insync.replicas
}
```

The simulation logs the warning but does not block writes when ISR is too small.
In production Kafka, this would throw `NotEnoughReplicasException` — enforcing CP.

### CP Cost Analysis

| Metric | acks=0 | acks=1 | acks=all (CP) |
|---|---|---|---|
| **Latency (p50)** | <1ms | 2-3ms | 5-10ms |
| **Latency (p99)** | <2ms | 5-8ms | 15-30ms |
| **Throughput** | Highest | High | ~20-30% lower |
| **Data loss risk** | High | Medium | None (while ISR >= min.insync) |
| **Availability** | Always available | Always available | Blocked if ISR < min.insync |
| **Use case** | Metrics, logs | Most applications | Financial, critical data |

---

## 3. AP Mode: Consumer Lag and Metadata

### Why Consumer Lag Is AP

Consumer lag (how far behind a consumer is from the latest message) is an
eventually consistent metric:

```
  ┌──────────────────────────────────────────────────────────┐
  │              Consumer Lag: Eventually Consistent          │
  │                                                          │
  │  High Watermark (HW): 1000  (latest committed offset)   │
  │  Consumer committed offset: 950                          │
  │  Lag (stale): 50                                         │
  │                                                          │
  │  But in reality:                                         │
  │  - Consumer may have processed up to 970 but not         │
  │    committed yet (auto.commit.interval.ms = 5000)        │
  │  - True lag might be 30, not 50                          │
  │  - HW itself may be slightly behind LEO                  │
  │                                                          │
  │  Lag is always AVAILABLE (you can always read it)        │
  │  Lag is not always CONSISTENT (may be stale)             │
  │  → This is an AP tradeoff                                │
  └──────────────────────────────────────────────────────────┘
```

### Our Simulation's Lag Calculation

```java
// ConsumerGroupCoordinator.java
public long getLag(String groupId, String topic, int partition, long latestOffset) {
    long committed = getCommittedOffset(groupId, topic, partition);
    return latestOffset - committed;
}

// This is eventually consistent because:
// 1. latestOffset comes from CommitLog.getLatestOffset() — real-time
// 2. committed offset is updated on commitOffset() — periodic
// 3. Gap between real consumption position and committed position = staleness
```

### Why Metadata Is AP (Client-Side)

```
  Producer metadata cache:

  t=0s    Producer caches: P0-leader = broker-0
  t=5s    Controller reassigns: P0-leader = broker-1
  t=10s   Producer still thinks: P0-leader = broker-0 (stale!)
  t=10.1s Producer sends to broker-0 → NOT_LEADER_FOR_PARTITION
  t=10.2s Producer refreshes metadata → learns broker-1 is leader
  t=10.3s Producer sends to broker-1 → success

  The metadata is:
  - Always available (cached locally)
  - Not always consistent (up to metadata.max.age.ms stale)
  - Self-healing (error triggers refresh)
```

### Why This AP Tradeoff Is Acceptable

```
  Lag metrics:
    Who cares if lag is 50 instead of 30?
    → Monitoring systems sample every 30-60 seconds anyway
    → Alert thresholds are coarse (e.g., alert if lag > 10,000)
    → Eventual accuracy is sufficient

  Client metadata:
    What if the producer sends to the wrong broker?
    → The broker returns an error with the correct leader
    → Producer retries to the correct broker
    → No data loss, just a retry (tens of milliseconds)
```

---

## 4. Ordering Guarantees

### Per-Partition Ordering (Kafka's Guarantee)

```
  ┌──────────────────────────────────────────────────────────────┐
  │              Kafka Ordering Guarantee                        │
  │                                                              │
  │  GUARANTEED: Messages within a partition are strictly ordered│
  │                                                              │
  │  Partition 0: [A1] [A2] [A3] [A4] [A5]                     │
  │               ←── Always read in this order                  │
  │                                                              │
  │  NOT GUARANTEED: Messages across partitions have no order    │
  │                                                              │
  │  Partition 0: [A1] [A2] [A3]                                │
  │  Partition 1: [B1] [B2] [B3]                                │
  │                                                              │
  │  Consumer might see: B1, A1, A2, B2, B3, A3                │
  │  Or: A1, B1, B2, A2, A3, B3                                │
  │  Or any other interleaving                                   │
  └──────────────────────────────────────────────────────────────┘
```

### How Ordering Is Achieved

```
  Key insight: Same key → same partition → ordered

  Producer sends:
    Message(key="user-123", value="OrderCreated")  → partition 2
    Message(key="user-123", value="OrderPaid")     → partition 2 (same key!)
    Message(key="user-123", value="OrderShipped")  → partition 2 (same key!)

  Consumer reads partition 2:
    1. OrderCreated    ← guaranteed first
    2. OrderPaid       ← guaranteed second
    3. OrderShipped    ← guaranteed third
```

Our `HashPartitioningStrategy.java` ensures this:

```java
public int assignPartition(String key, int partitionCount) {
    if (key == null) {
        return 0;  // null key → default partition
    }
    return Math.abs(key.hashCode()) % partitionCount;
}
// Same key always produces the same partition index
```

### Ordering Tradeoffs

| More Partitions | Fewer Partitions |
|---|---|
| Higher parallelism | Lower parallelism |
| More consumers can work simultaneously | Fewer consumers needed |
| No cross-partition ordering | Stronger ordering (fewer keys per partition) |
| Better throughput | Lower throughput |
| Higher resource usage | Lower resource usage |

### When Global Ordering Is Needed

```
  Option 1: Single partition (simple, limited throughput)
    Topic "bank-ledger": 1 partition
    → All transactions strictly ordered
    → Max throughput: ~10-50 MB/s
    → Single consumer only

  Option 2: Sequence numbers in application layer
    Each message carries a sequence number
    Consumer sorts by sequence before processing
    → Any number of partitions
    → Application complexity increases
    → Must handle gaps and reordering

  Option 3: Event sourcing with deterministic replay
    Each event carries a logical timestamp (vector clock / Lamport)
    Consumers process in logical order
    → Complex but scalable
    → Used in financial systems, CQRS architectures
```

### max.in.flight.requests.per.connection and Ordering

```
  Problem: With max.in.flight > 1, retries can reorder messages

  max.in.flight = 5 (default):
  ┌──────────────────────────────────────────────────────────┐
  │  Batch 1 → sent, waiting for ack                        │
  │  Batch 2 → sent, waiting for ack                        │
  │  Batch 3 → sent, waiting for ack                        │
  │                                                          │
  │  Batch 1 fails → retry                                  │
  │  Batch 2 succeeds → committed at offset N               │
  │  Batch 1 retry succeeds → committed at offset N+1       │
  │                                                          │
  │  Result: Batch 2 is BEFORE Batch 1 in the log!          │
  │  Ordering violated!                                      │
  └──────────────────────────────────────────────────────────┘

  Solution 1: max.in.flight = 1 (strict ordering, lower throughput)
  
  Solution 2: enable.idempotence = true (Kafka >= 0.11)
    Producer assigns sequence numbers
    Broker detects out-of-order sequences and rejects
    → Ordering preserved with up to 5 in-flight requests
    → No throughput penalty
```

---

## 5. Delivery Semantics: Cost Analysis

### The Three Delivery Guarantees

```
  ┌──────────────────────────────────────────────────────────────┐
  │                                                              │
  │  AT MOST ONCE:                                               │
  │  ┌──────┐     ┌───────┐     ┌──────────┐                   │
  │  │Broker│────▶│Commit │────▶│ Process  │                   │
  │  │      │     │offset │     │ message  │                   │
  │  └──────┘     └───────┘     └──────────┘                   │
  │               (step 1)       (step 2)                       │
  │                                                              │
  │  If crash after step 1, before step 2:                       │
  │    → Offset committed but message never processed            │
  │    → MESSAGE LOST                                            │
  │                                                              │
  │  AT LEAST ONCE:                                              │
  │  ┌──────┐     ┌──────────┐     ┌───────┐                   │
  │  │Broker│────▶│ Process  │────▶│Commit │                   │
  │  │      │     │ message  │     │offset │                   │
  │  └──────┘     └──────────┘     └───────┘                   │
  │               (step 1)          (step 2)                    │
  │                                                              │
  │  If crash after step 1, before step 2:                       │
  │    → Message processed but offset not committed              │
  │    → Message RE-DELIVERED on restart                         │
  │    → MESSAGE DUPLICATED                                      │
  │                                                              │
  │  EXACTLY ONCE:                                               │
  │  ┌──────┐     ┌────────────────────────────────────┐        │
  │  │Broker│────▶│ TRANSACTION:                        │        │
  │  │      │     │   process(message)                  │        │
  │  └──────┘     │   commitOffset(message.offset + 1)  │        │
  │               │   produce(output)                   │        │
  │               │ COMMIT                               │        │
  │               └────────────────────────────────────┘        │
  │                                                              │
  │  All-or-nothing: process + commit + output are atomic        │
  │  → No loss, no duplication                                   │
  └──────────────────────────────────────────────────────────────┘
```

### Our Simulation's Delivery Semantics

```java
// DeliveryGuarantee.java — the three modes
AT_MOST_ONCE("Messages delivered zero or one time — no duplicates, possible loss"),
AT_LEAST_ONCE("Messages delivered one or more times — no loss, possible duplicates"),
EXACTLY_ONCE("Messages delivered exactly one time — no loss, no duplicates");
```

```java
// ExactlyOnceDeliveryStrategy.java — simulates idempotent delivery
private final Set<String> deliveredIds = ConcurrentHashMap.newKeySet();

public boolean deliver(Message message, String consumerId) {
    if (deliveredIds.contains(message.getId())) {
        return true;  // idempotent — already delivered, skip
    }
    deliveredIds.add(message.getId());
    return true;
}
```

```java
// AtLeastOnceDeliveryStrategy.java — always delivers, may duplicate
public boolean deliver(Message message, String consumerId) {
    // Always deliver — consumer is responsible for deduplication
    return true;
}
```

---

## 6. At-Least-Once vs. Exactly-Once

### Cost Breakdown

```
  ┌──────────────────────────────────────────────────────────────┐
  │        At-Least-Once vs. Exactly-Once Cost                  │
  │                                                              │
  │  Dimension          │ At-Least-Once    │ Exactly-Once        │
  │  ───────────────────┼──────────────────┼─────────────────── │
  │  Throughput          │ Baseline         │ -30% to -50%       │
  │  Latency (p99)       │ 5-15ms          │ 20-50ms            │
  │  Broker CPU          │ Baseline         │ +20% (TX tracking) │
  │  Disk I/O            │ Baseline         │ +15% (TX markers)  │
  │  Client complexity   │ Simple           │ TX API + init      │
  │  Consumer side       │ Handle dupes     │ read_committed     │
  │  Memory overhead     │ None             │ PID/seq tracking   │
  │  Operational risk    │ Low              │ Medium (TX timeouts)│
  └──────────────────────────────────────────────────────────────┘
```

### When Each Is Appropriate

```
  AT-LEAST-ONCE (90% of use cases):
  ┌──────────────────────────────────────────────────────────┐
  │ Use when:                                                │
  │   - Consumer logic is idempotent (can safely re-process) │
  │   - Using database upserts (INSERT ON CONFLICT UPDATE)   │
  │   - Aggregations where duplicate doesn't matter          │
  │   - Log aggregation, metrics collection                  │
  │   - Event notifications (duplicate notification is OK)   │
  │                                                          │
  │ Pattern: consumer maintains its own deduplication         │
  │   - Database unique constraint                           │
  │   - Redis SET check                                      │
  │   - Bloom filter for approximate dedup                   │
  └──────────────────────────────────────────────────────────┘

  EXACTLY-ONCE (10% of use cases):
  ┌──────────────────────────────────────────────────────────┐
  │ Use when:                                                │
  │   - Financial transactions (double-charge is unacceptable)│
  │   - Kafka-to-Kafka stream processing (Kafka Streams)     │
  │   - Inventory management (double-decrement is wrong)     │
  │   - Billing systems                                      │
  │   - Compliance/audit (exact record required)             │
  │                                                          │
  │ Requirement: Kafka Transactions (or app-level 2PC)       │
  │   producer.initTransactions()                            │
  │   producer.beginTransaction()                            │
  │   producer.send(records)                                 │
  │   producer.sendOffsetsToTransaction(offsets, group)      │
  │   producer.commitTransaction()                           │
  └──────────────────────────────────────────────────────────┘
```

### The "Effectively Exactly-Once" Pattern

```
  Instead of Kafka transactions, many systems achieve exactly-once
  by making the consumer idempotent:

  Consumer reads from Kafka:
    message = {key: "order-123", value: "created", offset: 42}

  Consumer writes to database:
    INSERT INTO orders (order_id, status, kafka_offset)
    VALUES ('order-123', 'created', 42)
    ON CONFLICT (order_id) DO NOTHING;
    ← idempotent! Duplicate Kafka delivery has no effect.

  This is "effectively exactly-once" because:
  - Kafka provides at-least-once delivery
  - Database provides deduplication
  - Combined effect = exactly-once processing
  - No Kafka transaction overhead
```

---

## 7. Log Compaction vs. Deletion

### Deletion (Time/Size-Based Retention)

```
  cleanup.policy = delete

  Timeline:
  ┌──────────────────────────────────────────────────────────┐
  │ Day 1: [msg0][msg1][msg2]...[msg999]  (Segment 1)      │
  │ Day 2: [msg1000]...[msg1999]          (Segment 2)      │
  │ Day 3: [msg2000]...[msg2999]          (Segment 3)      │
  │ ...                                                      │
  │ Day 8: retention.ms = 7 days                            │
  │         → Segment 1 DELETED entirely                    │
  │         → msg0 through msg999 are GONE forever          │
  │                                                          │
  │ Characteristics:                                         │
  │ - Simple: oldest segments deleted when retention exceeded│
  │ - Lossy: old messages are permanently deleted            │
  │ - Space-bounded: storage grows up to retention limit     │
  │ - No key awareness: all messages treated equally         │
  └──────────────────────────────────────────────────────────┘
```

### Log Compaction (Key-Based Retention)

```
  cleanup.policy = compact

  Before compaction:
  ┌──────┬──────┬───────────┐
  │Offset│ Key  │  Value    │
  ├──────┼──────┼───────────┤
  │  0   │ K1   │  "v1"     │  ← superseded by offset 3
  │  1   │ K2   │  "v1"     │  ← superseded by offset 5
  │  2   │ K3   │  "v1"     │  ← latest for K3 (kept)
  │  3   │ K1   │  "v2"     │  ← latest for K1 (kept)
  │  4   │ K4   │  "v1"     │  ← latest for K4 (kept)
  │  5   │ K2   │  "v2"     │  ← latest for K2 (kept)
  │  6   │ null │  "event"  │  ← null key always kept
  │  7   │ K5   │  null     │  ← tombstone (K5 deleted)
  └──────┴──────┴───────────┘

  After compaction:
  ┌──────┬──────┬───────────┐
  │  2   │ K3   │  "v1"     │
  │  3   │ K1   │  "v2"     │
  │  4   │ K4   │  "v1"     │
  │  5   │ K2   │  "v2"     │
  │  6   │ null │  "event"  │
  │  7   │ K5   │  null     │  ← tombstone kept briefly, then removed
  └──────┴──────┴───────────┘
```

### Our Simulation's Log Compaction

```java
// LogCompactionStrategy.java
public List<Message> compact(List<Message> messages) {
    List<Message> nullKeyMessages = new ArrayList<>();
    Map<String, Message> latestByKey = new LinkedHashMap<>();

    for (Message message : messages) {
        if (message.getKey() == null) {
            nullKeyMessages.add(message);     // null keys always retained
        } else {
            Message existing = latestByKey.get(message.getKey());
            if (existing == null || message.getOffset() > existing.getOffset()) {
                latestByKey.put(message.getKey(), message);  // keep latest offset per key
            }
        }
    }

    List<Message> compacted = new ArrayList<>();
    compacted.addAll(nullKeyMessages);
    compacted.addAll(latestByKey.values());
    return compacted;
}
```

### Compaction vs. Deletion Tradeoffs

| Dimension | Deletion | Compaction |
|---|---|---|
| **Space reclamation** | Predictable (time/size bounded) | Key-dependent (may never shrink) |
| **Data loss** | Yes (old messages deleted) | No (latest value per key always kept) |
| **Use case** | Event logs, metrics, activity | Changelog, state snapshots, KTable |
| **CPU overhead** | Minimal (delete whole segments) | Significant (scan + rewrite segments) |
| **Disk I/O** | Low (segment delete is rename) | High (read + write compacted segments) |
| **Tombstones** | N/A | Special handling (null value = delete key) |
| **Consumer safety** | Must consume before retention | Latest value always available |
| **Kafka internal use** | User topics | `__consumer_offsets`, `__transaction_state` |

### Compaction + Deletion Combined

```
  cleanup.policy = compact,delete

  Behavior:
  1. Log compaction runs first (keep latest per key)
  2. Then deletion runs on compacted segments
  3. Result: compacted snapshot with time-bounded retention

  Use case: Keep recent changelog with bounded storage
    - E.g., "latest state for each user, but only last 30 days"
```

### Tombstones

```
  Tombstone: a message with a non-null key and a null value.
  It signals "delete this key from the compacted log."

  Producer sends: key="user-123", value=null  (tombstone)

  Compaction behavior:
  1. First pass: tombstone retained, all prior messages for
     key "user-123" removed
  2. After delete.retention.ms (default 24 hours): tombstone
     itself is removed
  3. Result: key "user-123" completely removed from log

  Why delay tombstone removal?
    → Consumers that are behind need to see the tombstone
      to know the key was deleted
    → If removed immediately, lagging consumers would never
      know the deletion happened
```

---

## 8. Push vs. Pull Consumption Model

### Pull Model (Kafka)

```
  ┌──────────┐                     ┌──────────┐
  │ Consumer │──── poll() ────────▶│  Broker  │
  │          │◀─── records ────────│          │
  │          │                     │          │
  │ (process records)              │          │
  │          │                     │          │
  │          │──── poll() ────────▶│          │
  │          │◀─── records ────────│          │
  └──────────┘                     └──────────┘

  Consumer controls:
    - When to fetch (poll interval)
    - How much to fetch (max.poll.records, fetch.min.bytes)
    - Processing pace (back-pressure is natural)
```

### Push Model (RabbitMQ, SQS with long-polling)

```
  ┌──────────┐                     ┌──────────┐
  │ Consumer │                     │  Broker  │
  │          │◀─── deliver ────────│          │
  │          │                     │          │
  │ (process)│                     │          │
  │          │──── ack ───────────▶│          │
  │          │                     │          │
  │          │◀─── deliver ────────│          │
  └──────────┘                     └──────────┘

  Broker controls:
    - When to deliver (immediate)
    - Rate limiting via prefetch count
    - Consumer signals readiness via ack
```

### Push vs. Pull Tradeoffs

```
  ┌──────────────────────────────────────────────────────────────┐
  │                    Push vs. Pull                             │
  │                                                              │
  │  Dimension            │ Pull (Kafka)     │ Push (RabbitMQ)  │
  │  ─────────────────────┼──────────────────┼──────────────────│
  │  Back-pressure         │ Natural (don't   │ Needs prefetch   │
  │                        │ poll = no data)  │ count control    │
  │                        │                  │                  │
  │  Latency               │ Higher (poll     │ Lower (immediate │
  │                        │ interval)        │ delivery)        │
  │                        │                  │                  │
  │  Batching              │ Natural (fetch   │ Per-message      │
  │                        │ many at once)    │ (no natural      │
  │                        │                  │  batching)       │
  │                        │                  │                  │
  │  Consumer pace         │ Consumer-driven  │ Broker-driven    │
  │                        │ (process at own  │ (must keep up    │
  │                        │  speed)          │  with delivery)  │
  │                        │                  │                  │
  │  Idle overhead         │ Wasted polls     │ None (broker     │
  │                        │ (mitigated by    │ pushes only when │
  │                        │  long-polling)   │ data available)  │
  │                        │                  │                  │
  │  Multi-consumer        │ Consumer groups  │ Competing        │
  │                        │ (1 consumer per  │ consumers (any   │
  │                        │  partition)      │ consumer can get │
  │                        │                  │  any message)    │
  │                        │                  │                  │
  │  Replay                │ Yes (seek offset)│ No (consumed =   │
  │                        │                  │  deleted)        │
  └──────────────────────────────────────────────────────────────┘
```

### Why Kafka Chose Pull

```
  1. BATCHING:
     Pull model naturally supports batching. Consumer fetches
     a batch of messages in one network call.
     fetch.min.bytes + fetch.max.wait.ms control batch fill.

  2. BACK-PRESSURE:
     If consumer is slow, it simply polls less frequently.
     No need for complex flow control protocol.
     Broker never overwhelms a slow consumer.

  3. CONSUMER PACE:
     Different consumers can process at different speeds.
     Real-time consumer: polls every 100ms.
     Batch consumer: polls every 60 seconds.
     Same broker serves both without special handling.

  4. REPLAY:
     Consumer controls its position (offset).
     Can seek backward to replay historical data.
     Not possible in push model (broker already delivered).
```

### Why RabbitMQ Chose Push

```
  1. LOW LATENCY:
     Messages delivered immediately when available.
     No poll interval delay.
     Critical for real-time applications (chat, gaming).

  2. SIMPLICITY:
     Consumer just handles incoming messages.
     No need to manage fetch loops, backoff, etc.

  3. WORK DISTRIBUTION:
     Broker knows which consumers are idle.
     Can distribute work intelligently with prefetch.
     Avoids head-of-line blocking.
```

---

## 9. Ack Modes: Durability vs. Latency

### The Three Ack Modes

Our `AckMode.java` defines the three modes:

```java
NONE(0, "No acknowledgement — fire and forget (acks=0)"),
LEADER(1, "Leader acknowledgement only (acks=1)"),
ALL(-1, "All in-sync replicas acknowledge (acks=all)");
```

### Visual Comparison

```
  acks=0 (NONE):
  Producer ──▶ Broker     (no response waited)
               │
               └──▶ (may or may not write to disk)
  
  Latency: <1ms
  Risk: message may be lost entirely

  ────────────────────────────────────────────────

  acks=1 (LEADER):
  Producer ──▶ Leader ──▶ writes to local log
                   │
                   └──▶ ack back to producer
                   │
                   └──▶ async replicate to followers
  
  Latency: 2-5ms
  Risk: message lost if leader fails before replication

  ────────────────────────────────────────────────

  acks=all (ALL):
  Producer ──▶ Leader ──▶ writes to local log
                   │
                   ├──▶ replicate to follower 1 ──▶ ack
                   ├──▶ replicate to follower 2 ──▶ ack
                   │
                   └──▶ all ISR acked ──▶ ack to producer
  
  Latency: 5-15ms
  Risk: none (unless entire ISR fails simultaneously)
```

### Our Simulation's Ack Handling

```java
// ReplicationEngine.java
public boolean replicate(Message message, Partition partition, AckMode ackMode) {
    switch (ackMode) {
        case NONE -> {
            // fire-and-forget — return immediately
            return true;
        }
        case LEADER -> {
            // leader acknowledges only
            return true;
        }
        case ALL -> {
            // all ISR replicas must acknowledge
            return replicateToAllIsr(message, partition);
        }
    }
}
```

### Ack Mode Selection Guide

| Use Case | Recommended Ack | Why |
|---|---|---|
| Metrics/logs | NONE or LEADER | Loss acceptable, latency matters |
| User actions | LEADER | Good balance of durability and speed |
| Financial data | ALL | No data loss acceptable |
| Audit trail | ALL | Compliance requires durability |
| IoT sensor data | NONE or LEADER | High volume, individual loss OK |
| Order events | ALL | Business-critical, exactly-once |

---

## 10. Replication Tradeoffs

### Replication Factor Choice

```
  RF=1 (no replication):
  ┌────────┐
  │Broker 0│  ← single copy, any broker failure = data loss
  │ P0     │
  └────────┘

  RF=2 (one backup):
  ┌────────┐  ┌────────┐
  │Broker 0│  │Broker 1│
  │ P0 (L) │  │ P0 (F) │  ← survives 1 broker failure
  └────────┘  └────────┘

  RF=3 (standard production):
  ┌────────┐  ┌────────┐  ┌────────┐
  │Broker 0│  │Broker 1│  │Broker 2│
  │ P0 (L) │  │ P0 (F) │  │ P0 (F) │  ← survives 2 broker failures
  └────────┘  └────────┘  └────────┘     (with min.insync.replicas=2,
                                          survives 1 and still writes)
```

### Replication Factor Tradeoffs

| RF | Durability | Write Latency | Disk Usage | Availability |
|---|---|---|---|---|
| 1 | None | Lowest | 1x | No fault tolerance |
| 2 | Survives 1 failure | Medium | 2x | Limited (min.isr=1 only) |
| 3 | Survives 2 failures | Higher | 3x | Standard (min.isr=2) |
| 5 | Survives 4 failures | Highest | 5x | Extreme (min.isr=3) |

### ISR and min.insync.replicas Interaction

```
  RF=3, min.insync.replicas=2

  Healthy:  ISR=[B0, B1, B2]  → writes allowed (3 >= 2)
  1 down:   ISR=[B0, B1]      → writes allowed (2 >= 2)
  2 down:   ISR=[B0]          → writes REJECTED (1 < 2)
                                  NotEnoughReplicasException

  RF=3, min.insync.replicas=1

  Healthy:  ISR=[B0, B1, B2]  → writes allowed (3 >= 1)
  1 down:   ISR=[B0, B1]      → writes allowed (2 >= 1)
  2 down:   ISR=[B0]          → writes allowed (1 >= 1)
                                 BUT only 1 copy — data at risk!
```

### unclean.leader.election.enable

```
  When set to FALSE (recommended for CP):
  ┌──────────────────────────────────────────────────────────┐
  │ If all ISR replicas are down, NO leader is elected.     │
  │ Partition becomes UNAVAILABLE until ISR replica returns. │
  │ → No data loss (CP behavior)                            │
  │ → Partition is offline until recovery                    │
  └──────────────────────────────────────────────────────────┘

  When set to TRUE (for availability):
  ┌──────────────────────────────────────────────────────────┐
  │ If all ISR replicas are down, an out-of-sync replica    │
  │ may be elected leader.                                  │
  │ → DATA LOSS: messages not yet replicated are lost       │
  │ → Partition stays available                             │
  │ → AP behavior at the cost of consistency                │
  └──────────────────────────────────────────────────────────┘
```

---

## 11. Partition Count Tradeoffs

### More Partitions

```
  Pros:
  + Higher parallelism (more consumers can work simultaneously)
  + Higher aggregate throughput
  + Finer-grained load distribution

  Cons:
  - More file handles per broker
  - Higher memory usage (per-partition buffers, indexes)
  - Longer recovery time after broker failure
  - More metadata in KRaft/ZooKeeper
  - Consumer rebalance takes longer
  - End-to-end latency may increase (more overhead per partition)
```

### Partition Count Guidelines

```
  Rule of thumb:
    Partitions = MAX(throughput_needed / throughput_per_consumer,
                     throughput_needed / throughput_per_producer,
                     desired_consumer_count)

  Example:
    Target throughput: 100 MB/s
    Per-consumer throughput: 20 MB/s
    Desired consumers: 10

    Partitions = MAX(100/20, 100/50, 10) = MAX(5, 2, 10) = 10

  Additional guidelines:
    - Start with target consumer count
    - Can only ADD partitions, never remove
    - More partitions = more overhead per broker
    - Recommended: 2,000-4,000 partitions per broker
    - KRaft supports millions per cluster
```

### The "Can't Reduce Partitions" Problem

```
  ┌──────────────────────────────────────────────────────────┐
  │ Kafka partitions can ONLY be increased, never decreased  │
  │                                                          │
  │ Why: removing a partition would delete its data          │
  │ and break consumers reading from that partition.         │
  │                                                          │
  │ Start conservative:                                      │
  │   Phase 1: 6 partitions (3 consumers)                   │
  │   Phase 2: 12 partitions (6 consumers)                  │
  │   Phase 3: 24 partitions (12 consumers)                 │
  │                                                          │
  │ Warning: Increasing partitions breaks key-based ordering │
  │   Before: hash("user-123") % 6 = 3                     │
  │   After:  hash("user-123") % 12 = 9  ← DIFFERENT!      │
  │   → Messages for same key now in different partition    │
  │   → Key-based ordering broken during transition         │
  └──────────────────────────────────────────────────────────┘
```

---

## 12. Consistency Boundaries

### Where Consistency Applies

```
  ┌──────────────────────────────────────────────────────────────┐
  │              Consistency Boundaries in Kafka                 │
  │                                                              │
  │  STRONG CONSISTENCY (linearizable):                          │
  │    Within a single partition:                                │
  │    - Offset ordering is strict                               │
  │    - Reads after writes (to committed offset) always reflect │
  │      the write                                               │
  │    - ISR replication ensures committed = durable             │
  │                                                              │
  │  EVENTUAL CONSISTENCY:                                       │
  │    Across partitions:                                        │
  │    - No ordering between partitions                          │
  │    - Consumer group may process P0 ahead of P1               │
  │                                                              │
  │    Consumer offsets:                                          │
  │    - auto.commit.interval.ms introduces delay                │
  │    - Committed offset may lag behind actual processing       │
  │                                                              │
  │    Metadata:                                                 │
  │    - Client caches stale for up to metadata.max.age.ms      │
  │    - Error-driven refresh is fast but not instant             │
  │                                                              │
  │    Consumer lag:                                              │
  │    - Always available, but may be stale                      │
  │    - Accuracy depends on commit frequency                    │
  └──────────────────────────────────────────────────────────────┘
```

### Read-Your-Writes Guarantee

```
  Kafka does NOT guarantee read-your-writes across partitions.

  Scenario:
  1. Producer writes to partition 0 (offset 100)
  2. Producer writes to partition 1 (offset 50)
  3. Consumer reads partition 1 (sees offset 50)
  4. Consumer reads partition 0 (may see offset 99 — hasn't received 100 yet!)

  Within a single partition:
  1. Producer writes to partition 0 (offset 100, acks=all)
  2. Consumer reads partition 0 (will see offset 100 eventually)
  3. BUT: consumer may need to poll multiple times
     because HW (high watermark) may not have advanced yet
```

### High Watermark

```
  ┌──────────────────────────────────────────────────────────┐
  │                    High Watermark (HW)                   │
  │                                                          │
  │  Leader log:  [0][1][2][3][4][5][6][7][8]               │
  │                                       ^       ^          │
  │                                       HW      LEO        │
  │                                       │       │          │
  │  HW = 7: last offset replicated to ALL ISR              │
  │  LEO = 9: last offset written to leader                 │
  │                                                          │
  │  Consumers can only read up to HW (offset 7)            │
  │  Offsets 7-8 are written but not yet replicated          │
  │  → Protects consumers from reading uncommitted data     │
  │                                                          │
  │  If leader fails before replication:                     │
  │    Offsets 7-8 are LOST (never committed)               │
  │    New leader starts from HW                             │
  │    → Consistency preserved                               │
  └──────────────────────────────────────────────────────────┘
```

---

## 13. Simulation-to-Production Tradeoff Mapping

### How Our Simulation Models Each Tradeoff

| Tradeoff | Simulation Implementation | Production Reality |
|---|---|---|
| **CP vs. AP** | `ReplicationEngine` warns but doesn't block | Broker rejects writes when ISR < min.insync |
| **Ordering** | `HashPartitioningStrategy` ensures per-key ordering | Same algorithm (murmur2 in production) |
| **At-least-once** | `AtLeastOnceDeliveryStrategy` always delivers | Consumer offset commit after processing |
| **Exactly-once** | `ExactlyOnceDeliveryStrategy` dedup set | PID+sequence + transactions |
| **Compaction** | `LogCompactionStrategy.compact()` | Background log cleaner threads |
| **Deletion** | `CommitLog.truncateBefore(offset)` | Segment-level deletion by age/size |
| **Ack modes** | `AckMode` enum (NONE, LEADER, ALL) | Producer `acks` config property |
| **Replication** | `Partition.inSyncReplicaIds` tracking | ISR management in controller |
| **Consumer lag** | `ConsumerGroupCoordinator.getLag()` | Consumer group lag via admin API |
| **Push vs. Pull** | `ConsumerService.consume()` (pull from CommitLog) | KafkaConsumer.poll() (pull from broker) |

### Tradeoffs We Simplified

```
  1. No network partitions
     Simulation: all in one JVM, no network
     Production: network failures trigger ISR shrink, leader election

  2. No disk failures
     Simulation: in-memory storage
     Production: disk corruption handled by checksums, replication

  3. No consumer failures
     Simulation: consumers never crash
     Production: heartbeat timeout → rebalance → partition reassignment

  4. No broker failures
     Simulation: single broker, always available
     Production: broker failure → leader election → ISR adjustment

  5. No transaction coordination
     Simulation: dedup set for exactly-once
     Production: 2-phase commit with transaction coordinator
```

---

## 14. Interview Deep Dive

### "Explain the CAP tradeoffs in a distributed message queue"

**Structure your answer:**

```
  "Kafka makes different CAP choices for different data paths.

  For committed messages, Kafka is CP:
  With acks=all and min.insync.replicas=2, the broker will reject
  writes if fewer than 2 replicas are in-sync. This sacrifices
  availability for consistency — no data is lost, but the partition
  becomes read-only.

  In our simulation, ReplicationEngine.java models this with the
  acks=ALL mode. It checks ISR size against the replication factor
  and logs a warning if ISR is too small. In production, this would
  throw NotEnoughReplicasException.

  For consumer lag metrics, Kafka is AP:
  Lag is always available (you can always query it) but may be stale
  because the committed offset lags behind the actual processing
  position. This is acceptable because monitoring systems sample
  infrequently anyway.

  The high watermark mechanism ensures consumers only see committed
  messages, providing a consistency boundary within each partition."
```

### "What are the tradeoffs between at-least-once and exactly-once?"

**Structure your answer:**

```
  "At-least-once is the default and sufficient for 90% of use cases.
  The consumer commits its offset AFTER processing, so if it crashes
  mid-processing, the message is redelivered. This is safe as long
  as the consumer is idempotent — for example, using database upserts
  or a deduplication cache.

  Exactly-once in Kafka requires the transactional API, which adds
  30-50% latency overhead and reduces throughput by a similar amount.
  It uses a two-phase commit protocol where message production and
  offset commits are atomically batched.

  Our simulation models both: AtLeastOnceDeliveryStrategy always
  delivers (consumer handles dupes), and ExactlyOnceDeliveryStrategy
  uses a ConcurrentHashMap-based dedup set to simulate idempotent
  consumption."
```

### "Why does Kafka use a pull model instead of push?"

**Key points:**

```
  1. Natural back-pressure:
     If the consumer is slow, it simply polls less frequently.
     The broker never overwhelms a slow consumer.

  2. Batching efficiency:
     Consumer fetches large batches (fetch.min.bytes + fetch.max.wait.ms),
     reducing per-message network overhead.

  3. Consumer-controlled pace:
     Real-time consumers poll every 100ms.
     Batch consumers poll every 60 seconds.
     Same broker, same partition, no special configuration.

  4. Replay capability:
     Consumer controls its offset. Can seek backward to replay.
     Not possible in a push model where the broker decides what to send.

  The downside is latency: pull adds poll interval delay. Kafka mitigates
  this with fetch.max.wait.ms (long polling) to avoid busy-waiting.
```

### "How does log compaction differ from deletion?"

**Key points referencing simulation:**

```
  "Log compaction keeps the LATEST value for each key, while deletion
  removes entire segments by age or size.

  Our LogCompactionStrategy.java implements this by iterating messages,
  grouping by key, and keeping only the highest-offset entry per key.
  Null-key messages are always retained because they can't be compacted.

  In production Kafka, this runs as a background thread that reads
  old segments, rewrites them without duplicates, and swaps the files.
  It's more expensive (CPU and disk I/O) than deletion, but it provides
  a 'latest state per key' guarantee that's essential for changelog
  topics like __consumer_offsets.

  The choice depends on the use case: deletion for event logs
  (where old events have no value), compaction for state snapshots
  (where the latest value per key is always needed)."
```

### "What happens when a broker fails with acks=all?"

**Walk through the scenario:**

```
  Setup: RF=3, min.insync.replicas=2, acks=all

  Step 1: Broker 2 fails
    ISR: [B0, B1, B2] → [B0, B1]
    Impact: writes still succeed (ISR=2 >= min.insync=2)
    Latency: unchanged (still waiting for 2 acks)

  Step 2: Broker 1 also fails
    ISR: [B0, B1] → [B0]
    Impact: writes REJECTED (ISR=1 < min.insync=2)
    The partition becomes read-only
    Consumers can still read committed data

  Step 3: Broker 1 recovers
    ISR: [B0] → [B0, B1] (after B1 catches up)
    Impact: writes resume

  This is the CP tradeoff: we chose consistency (no data loss)
  at the cost of availability (blocked writes).
  With unclean.leader.election.enable=true (AP), we could have
  elected B0 as solo leader, but risked losing un-replicated data.
```

---

*This document covers the CAP theorem and key tradeoffs in distributed message queues.*
*Every tradeoff is mapped to simulation code and production Kafka behavior.*
